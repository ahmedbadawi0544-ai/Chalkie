package com.chalkie.app.transport.lan

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.crypto.LinkHandshake
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/** SPEC §11.1 usability check. */
enum class LanState {
    OFF, PROBING, ACTIVE_MULTICAST, ACTIVE_UNICAST, STANDBY, ISOLATED;

    val active: Boolean get() = this == ACTIVE_MULTICAST || this == ACTIVE_UNICAST
}

/** The platform's UDP sockets. All sends go out from the data socket. */
interface UdpIo {
    fun sendTo(addr: InetSocketAddress, bytes: ByteArray)
    fun sendMulticast(bytes: ByteArray)
}

/** A teammate that, according to its HELLO (relayed over BLE/WD), is on the same network. */
data class LanPeerHint(val nodeId: UInt, val addr: InetSocketAddress)

/**
 * Platform-independent LAN transport logic: framing, discovery beacons, the §5 handshake per
 * peer over UDP, link liveness and the probe state machine. The Android LanTransport only owns
 * the sockets and feeds datagrams in; tests drive it with a fake network.
 *
 * Datagram = kind(1) ‖ body:
 * - BEACON    groupTag(4) ‖ nodeId(4)        multicast while probing, unicast to hinted peers
 * - HANDSHAKE LinkHandshake message           lower nodeId initiates, restarted on loss
 * - PACKET    Chalkie packet                  only accepted from authenticated addresses
 */
class LanCore(
    private val myId: UInt,
    private val keys: GroupKeys,
    private val clock: Clock,
    private val io: UdpIo,
    private val emit: (TransportEvent) -> Unit,
) {
    /** Same-network teammates learned from HELLOs; used when multicast is blocked. */
    @Volatile var hints: () -> List<LanPeerHint> = { emptyList() }

    var state = LanState.OFF
        private set
    var localAddr: InetSocketAddress? = null
        private set

    private var stateSinceMs = 0L
    private var lastBeaconMs = Long.MIN_VALUE / 2
    private var lastLinkMs = 0L
    private var multicastSeen = false

    private class Session(val hs: LinkHandshake, val startedMs: Long, val expectedId: UInt?) {
        var gotNonce = false
    }

    private class Link(val peerId: UInt, val addr: InetSocketAddress, var lastRxMs: Long)

    private val sessions = HashMap<InetSocketAddress, Session>()
    private val links = HashMap<UInt, Link>()
    private val byAddr = HashMap<InetSocketAddress, UInt>()
    private val blacklist = HashMap<InetSocketAddress, Long>()

    // ---- network lifecycle ----

    @Synchronized
    fun networkUp(addr: InetSocketAddress) {
        if (addr == localAddr && state != LanState.OFF) return
        dropAll()
        localAddr = addr
        enter(LanState.PROBING)
    }

    @Synchronized
    fun networkDown() {
        dropAll()
        localAddr = null
        enter(LanState.OFF)
    }

    /** Roam / IP change: start over. */
    @Synchronized
    fun networkChanged() {
        val addr = localAddr ?: return
        localAddr = null
        networkUp(addr)
    }

    // ---- I/O ----

    @Synchronized
    fun send(peerId: UInt, packet: ByteArray): Boolean {
        val l = links[peerId] ?: return false
        io.sendTo(l.addr, frame(KIND_PACKET, packet))
        return true
    }

    /** One multicast send reaches every LAN peer, when multicast works here. */
    @Synchronized
    fun sendAll(packet: ByteArray): Boolean {
        if (state != LanState.ACTIVE_MULTICAST || links.isEmpty()) return false
        io.sendMulticast(frame(KIND_PACKET, packet))
        return true
    }

    @Synchronized
    fun links(): List<LinkInfo> = links.values.map { LinkInfo(it.peerId, TransportType.LAN, null, 0f, null, it.lastRxMs) }

    @Synchronized
    fun onDatagram(from: InetSocketAddress, bytes: ByteArray, viaMulticast: Boolean) {
        if (state == LanState.OFF || from == localAddr || bytes.isEmpty()) return
        val body = bytes.copyOfRange(1, bytes.size)
        when (bytes[0]) {
            KIND_BEACON -> onBeacon(from, body, viaMulticast)
            KIND_HANDSHAKE -> onHandshake(from, body)
            KIND_PACKET -> {
                val id = byAddr[from] ?: return          // unauthenticated sender
                val l = links[id] ?: return
                l.lastRxMs = clock.nowMs()
                emit(TransportEvent.PacketIn(TransportType.LAN, id, body))
            }
        }
    }

    // ---- state machine ----

    @Synchronized
    fun tick() {
        if (state == LanState.OFF) return
        val now = clock.nowMs()
        links.values.filter { now - it.lastRxMs > ChalkieConfig.LAN_LINK_TIMEOUT_MS }.forEach(::drop)
        sessions.entries.removeAll { now - it.value.startedMs > ChalkieConfig.LAN_HANDSHAKE_RETRY_MS }
        blacklist.entries.removeAll { now >= it.value }

        val every = if (state == LanState.PROBING) ChalkieConfig.LAN_PROBE_BEACON_MS else ChalkieConfig.LAN_ACTIVE_BEACON_MS
        if (now - lastBeaconMs >= every) {
            lastBeaconMs = now
            beacon()
        }

        val inState = now - stateSinceMs
        when (state) {
            LanState.PROBING -> if (inState >= ChalkieConfig.LAN_PROBE_MS) {
                // Teammates on this network exist but nothing got through: client isolation.
                enter(if (otherHints().isNotEmpty()) LanState.ISOLATED else LanState.STANDBY)
            }
            LanState.STANDBY -> if (inState >= ChalkieConfig.LAN_REPROBE_MS || otherHints().isNotEmpty()) enter(LanState.PROBING)
            LanState.ISOLATED -> if (inState >= ChalkieConfig.LAN_REPROBE_ISOLATED_MS) enter(LanState.PROBING)
            LanState.ACTIVE_MULTICAST, LanState.ACTIVE_UNICAST ->
                if (links.isEmpty() && now - lastLinkMs >= ChalkieConfig.LAN_REPROBE_MS) enter(LanState.PROBING)
            LanState.OFF -> Unit
        }
    }

    private fun enter(s: LanState) {
        state = s
        stateSinceMs = clock.nowMs()
        if (s == LanState.PROBING) {
            multicastSeen = false
            lastBeaconMs = Long.MIN_VALUE / 2     // beacon on the next tick
        }
    }

    private fun otherHints() = hints().filter { it.nodeId != myId && it.addr != localAddr }

    // ---- discovery ----

    private fun beaconFrame() = frame(KIND_BEACON, ByteBuffer.allocate(8).put(keys.groupTag).putInt(myId.toInt()).array())

    private fun beacon() {
        val b = beaconFrame()
        io.sendMulticast(b)
        otherHints().filter { links[it.nodeId]?.addr != it.addr }.forEach { io.sendTo(it.addr, b) }
    }

    private fun onBeacon(from: InetSocketAddress, body: ByteArray, viaMulticast: Boolean) {
        if (body.size < 8 || !body.copyOfRange(0, 4).contentEquals(keys.groupTag)) return
        val peerId = ByteBuffer.wrap(body).getInt(4).toUInt()
        if (peerId == myId) return
        if (viaMulticast) {
            multicastSeen = true
            if (state == LanState.ACTIVE_UNICAST) enter(LanState.ACTIVE_MULTICAST)
        }
        if (links[peerId]?.addr == from || from in sessions || isBlacklisted(from)) return
        if (myId < peerId) startHandshake(from, peerId)
        else io.sendTo(from, beaconFrame())         // let the lower id initiate
    }

    // ---- handshake (SPEC §5) over datagrams ----

    private fun startHandshake(addr: InetSocketAddress, expectedId: UInt) {
        val s = Session(LinkHandshake(keys.authKey, myId), clock.nowMs(), expectedId)
        sessions[addr] = s
        io.sendTo(addr, frame(KIND_HANDSHAKE, s.hs.start()))
    }

    private fun onHandshake(from: InetSocketAddress, msg: ByteArray) {
        if (msg.isEmpty() || isBlacklisted(from)) return
        var s = sessions[from]
        if (msg[0] == LinkHandshake.TAG_NONCE) {
            if (s == null || s.gotNonce) {
                // Responder side, or the peer restarted after a lost datagram.
                s = Session(LinkHandshake(keys.authKey, myId), clock.nowMs(), s?.expectedId)
                sessions[from] = s
                io.sendTo(from, frame(KIND_HANDSHAKE, s.hs.start()))
            }
            s.gotNonce = true
        }
        if (s == null) return
        when (val step = s.hs.onMessage(msg)) {
            is LinkHandshake.Step.Send -> io.sendTo(from, frame(KIND_HANDSHAKE, step.bytes))
            is LinkHandshake.Step.Done -> {
                step.bytes?.let { io.sendTo(from, frame(KIND_HANDSHAKE, it)) }
                sessions.remove(from)
                if (s.expectedId != null && s.expectedId != step.peerId) fail(from) else linkUp(step.peerId, from)
            }
            LinkHandshake.Step.Fail -> fail(from)
            LinkHandshake.Step.Wait -> Unit
        }
    }

    private fun fail(addr: InetSocketAddress) {
        sessions.remove(addr)
        blacklist[addr] = clock.nowMs() + ChalkieConfig.HANDSHAKE_BLACKLIST_MS
    }

    private fun isBlacklisted(addr: InetSocketAddress) = (blacklist[addr] ?: 0L) > clock.nowMs()

    private fun linkUp(peerId: UInt, addr: InetSocketAddress) {
        links[peerId]?.let(::drop)                   // same node, new address (IP change)
        val now = clock.nowMs()
        val l = Link(peerId, addr, now)
        links[peerId] = l
        byAddr[addr] = peerId
        lastLinkMs = now
        if (!state.active) enter(if (multicastSeen) LanState.ACTIVE_MULTICAST else LanState.ACTIVE_UNICAST)
        emit(TransportEvent.LinkUp(LinkInfo(peerId, TransportType.LAN, null, 0f, null, now)))
    }

    private fun drop(l: Link) {
        if (links.remove(l.peerId) == null) return
        byAddr.remove(l.addr)
        lastLinkMs = clock.nowMs()
        emit(TransportEvent.LinkDown(TransportType.LAN, l.peerId))
    }

    private fun dropAll() {
        links.values.toList().forEach(::drop)
        sessions.clear()
    }

    private fun frame(kind: Byte, body: ByteArray) = byteArrayOf(kind) + body

    companion object {
        const val KIND_BEACON: Byte = 0x01
        const val KIND_HANDSHAKE: Byte = 0x02
        const val KIND_PACKET: Byte = 0x03
    }
}
