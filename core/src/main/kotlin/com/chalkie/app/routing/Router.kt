package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.DecodedPacket
import com.chalkie.app.core.Ids
import com.chalkie.app.core.MsgIdGenerator
import com.chalkie.app.core.PacketCodec
import com.chalkie.app.core.PacketHeader
import com.chalkie.app.core.PacketType
import com.chalkie.app.core.Priority
import com.chalkie.app.crypto.CryptoBox
import com.chalkie.app.election.StarRole
import com.chalkie.app.transport.BroadcastCapable
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportType
import java.util.concurrent.atomic.AtomicLong

class RouterStats {
    val malformed = AtomicLong()
    val duplicates = AtomicLong()
    val authFailed = AtomicLong()
    val replayed = AtomicLong()
    val delivered = AtomicLong()
    val relayed = AtomicLong()
    val originated = AtomicLong()
    /** Packets handed to / received from each transport (a LAN multicast counts once). Debug UI. */
    val tx: Map<TransportType, AtomicLong> = TransportType.entries.associateWith { AtomicLong() }
    val rx: Map<TransportType, AtomicLong> = TransportType.entries.associateWith { AtomicLong() }

    internal fun sent(type: TransportType) = tx.getValue(type).incrementAndGet()
}

private val NOT_COVERED: (UInt) -> Boolean = { false }

private object NoStar : Router.StarView {
    override val role: StarRole = StarRole.Off
    override fun covers(nodeId: UInt) = false
}

/**
 * One router for every transport (SPEC §10). Pure logic: transports only move bytes.
 *
 * Complementarity: LAN and WD (STAR) carry the bulk to everyone they reach; BLE only reaches
 * the nodes they miss ("covered-set"), and carries everything when they fail. Dedup
 * guarantees single playback.
 */
class Router(
    private val myId: UInt,
    sessionId: UInt,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val dedup: DedupCache = DedupCache(clock),
) {
    /** What the router needs to know about the Wi-Fi Direct STAR and my bridge role. */
    interface StarView {
        val role: StarRole
        /** True if [nodeId] is in my star over a live WD path, so a packet sent into the star reaches it. */
        fun covers(nodeId: UInt): Boolean
        /** True while I am an elected LAN ↔ off-LAN bridge. */
        val amBridge: Boolean get() = false
    }

    var star: StarView = NoStar

    /** Unicast next hop (SPEC §10): first hop on the shortest path and the link type to reach it. */
    fun interface UnicastRoute {
        fun nextHop(dstId: UInt): Pair<UInt, TransportType>?
    }

    var unicast: UnicastRoute? = null

    fun interface Listener {
        fun onDeliver(packet: DecodedPacket, arrivedVia: TransportType, from: UInt)
    }

    val stats = RouterStats()
    var listener: Listener? = null

    private val msgIds = MsgIdGenerator(sessionId)
    private val transports = LinkedHashMap<TransportType, Transport>()
    private val latestTimestamp = HashMap<UInt, UInt>()

    @Synchronized
    fun attach(transport: Transport) {
        transports[transport.type] = transport
    }

    @Synchronized
    fun detach(type: TransportType) {
        transports.remove(type)
    }

    fun nowTimestamp(): UInt = clock.nowMs().toUInt()

    /** Sends my own packet (SPEC §10 origination). Returns its msgId. */
    fun originate(
        type: PacketType,
        channel: Int,
        payload: ByteArray,
        dstId: UInt = Ids.BROADCAST,
        timestamp: UInt = nowTimestamp(),
    ): Long {
        val msgId = msgIds.next()
        val bytes = PacketCodec.encode(crypto, type, channel, myId, dstId, msgId, timestamp, payload)
        synchronized(this) {
            dedup.add(myId, msgId)
            stats.originated.incrementAndGet()
        }
        if (dstId != Ids.BROADCAST && sendUnicast(bytes, dstId, type.priority, from = null)) return msgId
        // Cheapest transport first: LAN, WD into the star, then BLE to whoever they miss.
        val sent = HashSet<UInt>()
        val lanReached = sendLan(bytes, type.priority, arrivedVia = null, from = null, srcId = myId, sent = sent)
        val starReached = sendIntoStar(bytes, type.priority, arrivedVia = null, from = null, srcId = myId, sent = sent)
        // My HELLO goes over every transport (SPEC §7): it is also each BLE link's keepalive.
        val covered = if (type == PacketType.HELLO) NOT_COVERED else coverage(lanReached, starReached, sent)
        sendBle(bytes, type.priority, from = null, srcId = myId, covered = covered)
        return msgId
    }

    /** SPEC §10 receive pipeline. */
    fun onPacketIn(via: TransportType, from: UInt, bytes: ByteArray) {
        stats.rx.getValue(via).incrementAndGet()
        val decoded: DecodedPacket
        synchronized(this) {
            val header = PacketCodec.parseHeader(bytes) ?: run { stats.malformed.incrementAndGet(); return }
            if (header.srcId == myId || dedup.contains(header.srcId, header.msgId)) {
                stats.duplicates.incrementAndGet()
                return
            }
            val plain = crypto.open(bytes, header) ?: run { stats.authFailed.incrementAndGet(); return }
            if (isReplay(header.srcId, header.timestamp)) {
                stats.replayed.incrementAndGet()
                return
            }
            dedup.add(header.srcId, header.msgId)
            decoded = DecodedPacket(header, plain)
        }
        val h = decoded.header
        if (h.isBroadcast || h.dstId == myId) {
            stats.delivered.incrementAndGet()
            listener?.onDeliver(decoded, via, from)
        }
        if (h.dstId == myId || h.ttl == 0) return
        forward(bytes, h, via, from)
    }

    private fun forward(bytes: ByteArray, h: PacketHeader, via: TransportType, from: UInt) {
        val relay = PacketCodec.forRelay(bytes)
        if (!h.isBroadcast && sendUnicast(relay, h.dstId, h.priority, from)) {
            stats.relayed.incrementAndGet()
            return
        }
        val sent = HashSet<UInt>()
        val lanReached = sendLan(relay, h.priority, via, from, h.srcId, sent)
        val starReached = sendIntoStar(relay, h.priority, via, from, h.srcId, sent)
        sendBle(relay, h.priority, from, h.srcId, coverage(lanReached, starReached, sent))
    }

    /**
     * Sends along the shortest path. False (caller floods; only dst delivers) when there is no
     * route, the hop would bounce the packet back where it came from, or the send fails.
     */
    private fun sendUnicast(bytes: ByteArray, dstId: UInt, priority: Priority, from: UInt?): Boolean {
        val (hop, type) = unicast?.nextHop(dstId) ?: return false
        if (hop == from) return false
        val t = synchronized(this) { transports[type] } ?: return false
        if (!t.send(hop, bytes, priority)) return false
        stats.sent(type)
        return true
    }

    /** m is covered when LAN or the star already carries the packet to it, or I just sent it there. */
    private fun coverage(lanReached: Boolean, starReached: Boolean, sent: Set<UInt>): (UInt) -> Boolean {
        val lanPeers = if (lanReached) lanPeers() else emptySet()
        val view = star
        return { m -> m in sent || m in lanPeers || (starReached && view.covers(m)) }
    }

    private fun lanPeers(): Set<UInt> =
        synchronized(this) { transports[TransportType.LAN] }?.links()?.mapTo(HashSet()) { it.neighborId } ?: emptySet()

    /**
     * LAN rules. Returns true when every LAN peer has (or is now getting) the packet.
     * - The originator sends to all LAN peers (one multicast when it works).
     * - A packet that arrived via LAN already reached every LAN peer: never relay back into LAN.
     * - Exception: a BRIDGE relays into LAN what arrived via BLE or WD.
     */
    private fun sendLan(
        bytes: ByteArray, priority: Priority, arrivedVia: TransportType?, from: UInt?, srcId: UInt, sent: MutableSet<UInt>,
    ): Boolean {
        val lan = synchronized(this) { transports[TransportType.LAN] } ?: return false
        val peers = lan.links().map { it.neighborId }
        if (peers.isEmpty()) return false
        return when {
            arrivedVia == TransportType.LAN -> true
            arrivedVia != null && srcId in peers -> true          // its originator sent it to all of us
            arrivedVia == null || star.amBridge -> {
                if (lan is BroadcastCapable && lan.sendAll(bytes, priority)) {
                    stats.sent(TransportType.LAN)
                } else {
                    peers.filter { it != from && it != srcId }.forEach { if (lan.send(it, bytes, priority)) stats.sent(TransportType.LAN) }
                }
                sent += peers
                if (arrivedVia != null) stats.relayed.incrementAndGet()
                true
            }
            else -> false
        }
    }

    /**
     * WD STAR rules. Returns true when the packet has reached (or is now reaching) the whole star.
     * - Hub: forwards to every leaf except the one it came from.
     * - Leaf: sends only to the hub, and never relays a packet that arrived via WD
     *   (the hub already gave it to the whole star) or that a star member originated.
     */
    private fun sendIntoStar(
        bytes: ByteArray, priority: Priority, arrivedVia: TransportType?, from: UInt?, srcId: UInt, sent: MutableSet<UInt>,
    ): Boolean {
        val wd = synchronized(this) { transports[TransportType.WD] }
        val view = star
        var reached = arrivedVia == TransportType.WD
        when (val role = view.role) {
            is StarRole.Hub -> wd?.links()?.forEach { l ->
                val m = l.neighborId
                if (m == from || m == srcId) return@forEach
                if (wd.send(m, bytes, priority)) { sent += m; reached = true; stats.sent(TransportType.WD); countRelay(arrivedVia) }
            }
            is StarRole.Leaf -> when {
                reached -> Unit                                   // leaves never relay back into WD
                srcId != myId && view.covers(srcId) -> reached = true   // originator already sent it into the star
                wd != null && wd.send(role.hubId, bytes, priority) -> {
                    sent += role.hubId; reached = true; stats.sent(TransportType.WD); countRelay(arrivedVia)
                }
            }
            StarRole.Off -> Unit
        }
        return reached
    }

    /** BLE to every neighbor except sender/source, skipping those LAN or the star already cover. */
    private fun sendBle(bytes: ByteArray, priority: Priority, from: UInt?, srcId: UInt, covered: (UInt) -> Boolean) {
        val ble = synchronized(this) { transports[TransportType.BLE] } ?: return
        for (link in ble.links()) {
            val m = link.neighborId
            if (m == from || m == srcId || covered(m)) continue
            if (ble.send(m, bytes, priority)) {
                stats.sent(TransportType.BLE)
                if (from != null) stats.relayed.incrementAndGet()
            }
        }
    }

    private fun countRelay(arrivedVia: TransportType?) {
        if (arrivedVia != null) stats.relayed.incrementAndGet()
    }

    /** Per-source window: reject timestamps more than REPLAY_WINDOW_MS older than the newest seen. */
    private fun isReplay(srcId: UInt, ts: UInt): Boolean {
        val latest = latestTimestamp[srcId]
        if (latest == null) {
            latestTimestamp[srcId] = ts
            return false
        }
        val age = (latest - ts).toInt()   // wrap-safe signed difference
        if (age > ChalkieConfig.REPLAY_WINDOW_MS) return true
        if (age < 0) latestTimestamp[srcId] = ts
        return false
    }
}
