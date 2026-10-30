package com.chalkie.app.transport.wd

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.Ids
import com.chalkie.app.core.Priority
import com.chalkie.app.core.SystemClock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.crypto.LinkHandshake
import com.chalkie.app.election.StarControl
import com.chalkie.app.election.StarRole
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.LinkSendQueue
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct STAR over Nearby Connections P2P_STAR (SPEC §11.2).
 * The node's election decides the role; this class only follows it:
 * HUB advertises and accepts leaves, LEAF discovers and connects to exactly the elected hub.
 * endpointInfo = groupTag(4) ‖ nodeId(4) ‖ hubEpoch(4). One packet per BYTES payload.
 */
@SuppressLint("MissingPermission")   // ChalkieService only starts after permissions are granted.
class WdTransport(
    context: Context,
    private val myId: UInt,
    private val keys: GroupKeys,
    private val clock: Clock = SystemClock,
) : Transport, StarControl {
    override val type = TransportType.WD

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private var scope: CoroutineScope? = null
    @Volatile private var started = false
    @Volatile private var role: StarRole = StarRole.Off
    private var advertising = false
    private var discovering = false

    /** An endpoint between connection and the end of the §5 handshake. */
    private class Endpoint(val id: String, val expectedId: UInt, val hs: LinkHandshake) {
        var peerId: UInt? = null
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    /** Hub adverts seen while discovering: nodeId -> (endpointId, epoch). Lets a leaf jump to the standby at once. */
    private val adverts = ConcurrentHashMap<UInt, Pair<String, Int>>()
    private val links = ConcurrentHashMap<UInt, Link>()

    /** Live state for the UI. */
    @Volatile var state: String = "off"
        private set

    // ---- Transport ----

    override suspend fun start() {
        if (started) return
        started = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { maintenanceLoop() }
        applyRole(role)
    }

    override suspend fun stop() {
        if (!started) return
        applyRole(StarRole.Off)
        started = false
        scope?.cancel()
        scope = null
        state = "off"
    }

    override fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean =
        links[neighborId]?.enqueue(bytes, priority) ?: false

    override fun links(): List<LinkInfo> = links.values.map { it.info() }

    override fun setStarRole(role: StarRole) {
        this.role = role
        if (started) applyRole(role)
    }

    // ---- role → Nearby ----

    @Synchronized
    private fun applyRole(r: StarRole) {
        when (r) {
            StarRole.Off -> {
                stopAdvertising(); stopDiscovery()
                adverts.clear()
                client.stopAllEndpoints()
                dropAll()
                state = "off"
            }
            is StarRole.Hub -> {
                stopDiscovery()
                adverts.clear()
                // Leaves of an older star (or my old hub) go; a hub keeps nothing but its leaves.
                if (!advertising) {
                    client.stopAllEndpoints()
                    dropAll()
                    startAdvertising(r.epoch)
                }
                state = "hub"
            }
            is StarRole.Leaf -> {
                stopAdvertising()
                // Keep the link only if it is to the elected hub.
                val keep = links[r.hubId]
                links.values.filter { it !== keep }.forEach { client.disconnectFromEndpoint(it.endpointId); onGone(it.endpointId) }
                endpoints.values.filter { it.expectedId != r.hubId }.forEach { client.disconnectFromEndpoint(it.id); onGone(it.id) }
                if (keep == null) {
                    // Pre-discovered (e.g. the standby that just took over): connect without waiting.
                    adverts[r.hubId]?.takeIf { it.second == r.epoch }?.let { (endpointId, _) -> requestHub(endpointId, r) }
                    startDiscovery()
                } else if (r.standbyId != null && r.standbyId != myId) {
                    startDiscovery()                       // keep watching for the standby's advert (SPEC §19.1)
                } else {
                    stopDiscovery()
                }
                state = "leaf"
            }
        }
    }

    private fun startAdvertising(epoch: Int) {
        client.startAdvertising(
            endpointInfo(epoch), ChalkieConfig.WD_SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener {
            Log.i(TAG, "advertising as hub, epoch $epoch")
        }.addOnFailureListener {
            Log.w(TAG, "advertising failed: ${it.message}")
            advertising = false
            state = "hub (advertise failed)"
        }
        advertising = true
    }

    private fun stopAdvertising() {
        if (advertising) client.stopAdvertising()
        advertising = false
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        client.startDiscovery(
            ChalkieConfig.WD_SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnFailureListener {
            Log.w(TAG, "discovery failed: ${it.message}")
            discovering = false
        }
    }

    private fun stopDiscovery() {
        if (discovering) client.stopDiscovery()
        discovering = false
    }

    private fun endpointInfo(epoch: Int): ByteArray =
        ByteBuffer.allocate(12).put(keys.groupTag).putInt(myId.toInt()).putInt(epoch).array()

    private data class PeerInfo(val nodeId: UInt, val epoch: Int)

    private fun parseInfo(info: ByteArray?): PeerInfo? {
        if (info == null || info.size < 12 || !info.copyOfRange(0, 4).contentEquals(keys.groupTag)) return null
        val b = ByteBuffer.wrap(info)
        return PeerInfo(b.getInt(4).toUInt(), b.getInt(8))
    }

    // ---- Nearby callbacks ----

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val r = role as? StarRole.Leaf ?: return
            val peer = parseInfo(info.endpointInfo) ?: return
            adverts[peer.nodeId] = endpointId to peer.epoch
            if (peer.nodeId != r.hubId || peer.epoch != r.epoch) return
            requestHub(endpointId, r)
        }

        override fun onEndpointLost(endpointId: String) {
            adverts.entries.removeIf { it.value.first == endpointId }
        }
    }

    private fun requestHub(endpointId: String, r: StarRole.Leaf) {
        if (links.containsKey(r.hubId) || endpoints.values.any { it.expectedId == r.hubId }) return
        Log.i(TAG, "found hub ${Ids.hex(r.hubId)}, connecting")
        client.requestConnection(endpointInfo(r.epoch), endpointId, lifecycle)
            .addOnFailureListener { Log.w(TAG, "requestConnection failed: ${it.message}") }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = parseInfo(info.endpointInfo)
            val ok = peer != null && when (val r = role) {
                is StarRole.Hub -> true
                is StarRole.Leaf -> peer.nodeId == r.hubId
                StarRole.Off -> false
            }
            if (!ok || peer == null) {
                client.rejectConnection(endpointId)
                return
            }
            endpoints[endpointId] = Endpoint(endpointId, peer.nodeId, LinkHandshake(keys.authKey, myId))
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ep = endpoints[endpointId] ?: return
            if (!result.status.isSuccess) {
                endpoints.remove(endpointId)
                return
            }
            sendRaw(endpointId, ep.hs.start())
            scope?.launch {
                delay(ChalkieConfig.HANDSHAKE_TIMEOUT_MS)
                if (ep.peerId == null && endpoints[endpointId] === ep) {
                    Log.w(TAG, "handshake timeout ${Ids.hex(ep.expectedId)}")
                    client.disconnectFromEndpoint(endpointId)
                    onGone(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) = onGone(endpointId)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val ep = endpoints[endpointId] ?: return
            val peer = ep.peerId
            if (peer == null) {
                handshake(ep, bytes)
            } else {
                links[peer]?.lastRxMs = clock.nowMs()
                _events.tryEmit(TransportEvent.PacketIn(TransportType.WD, peer, bytes))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            val peer = endpoints[endpointId]?.peerId ?: return
            links[peer]?.onDone(update.payloadId)
        }
    }

    private fun handshake(ep: Endpoint, msg: ByteArray) {
        when (val step = ep.hs.onMessage(msg)) {
            is LinkHandshake.Step.Send -> sendRaw(ep.id, step.bytes)
            is LinkHandshake.Step.Done -> {
                step.bytes?.let { sendRaw(ep.id, it) }
                if (step.peerId != ep.expectedId || links.containsKey(step.peerId)) {
                    client.disconnectFromEndpoint(ep.id); onGone(ep.id); return
                }
                ep.peerId = step.peerId
                val link = Link(step.peerId, ep.id)
                links[step.peerId] = link
                // Nearby works best without discovery running; keep it only to watch for a standby.
                (role as? StarRole.Leaf)?.let { if (it.standbyId == null || it.standbyId == myId) stopDiscovery() }
                _events.tryEmit(TransportEvent.LinkUp(link.info()))
                Log.i(TAG, "link up ${Ids.hex(step.peerId)} as ${role::class.simpleName}")
            }
            LinkHandshake.Step.Fail -> {
                Log.w(TAG, "handshake failed with ${Ids.hex(ep.expectedId)}")
                client.disconnectFromEndpoint(ep.id); onGone(ep.id)
            }
            LinkHandshake.Step.Wait -> Unit
        }
    }

    private fun sendRaw(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    /** Endpoint closed: report the link down, and a leaf goes looking for its hub again. */
    private fun onGone(endpointId: String) {
        val ep = endpoints.remove(endpointId) ?: return
        val peer = ep.peerId ?: return
        links.remove(peer)?.let {
            _events.tryEmit(TransportEvent.LinkDown(TransportType.WD, peer))
            Log.i(TAG, "link down ${Ids.hex(peer)}")
        }
        if (started && role is StarRole.Leaf) startDiscovery()
    }

    private fun dropAll() {
        endpoints.keys.toList().forEach(::onGone)
    }

    private suspend fun maintenanceLoop() {
        var tick = 0
        while (started && scope?.isActive == true) {
            delay(1000)
            tick++
            val now = clock.nowMs()
            // HELLO every 2 s is the keepalive.
            links.values.filter { now - it.lastRxMs > ChalkieConfig.WD_LINK_TIMEOUT_MS }.forEach {
                Log.w(TAG, "link timeout ${Ids.hex(it.peerId)}")
                client.disconnectFromEndpoint(it.endpointId)
                onGone(it.endpointId)
            }
            if (tick % 2 == 0) links.values.forEach { _events.tryEmit(TransportEvent.LinkStats(it.info())) }
            // A leaf that lost discovery (failure, radio hiccup) keeps trying.
            val r = role
            if (r is StarRole.Leaf && !links.containsKey(r.hubId) && endpoints.isEmpty() &&
                tick % (ChalkieConfig.WD_CONNECT_RETRY_MS / 1000).toInt() == 0) {
                stopDiscovery(); startDiscovery()
            }
        }
    }

    /** One authenticated WD link, with a priority queue and a small in-flight window. */
    private inner class Link(val peerId: UInt, val endpointId: String) {
        private val queue = LinkSendQueue()
        private val inFlight = HashSet<Long>()
        @Volatile var lastRxMs = clock.nowMs()

        fun info() = LinkInfo(peerId, TransportType.WD, null, queue.lossRate(clock.nowMs()), null, lastRxMs)

        fun enqueue(bytes: ByteArray, priority: Priority): Boolean {
            if (!queue.offer(bytes, priority, clock.nowMs())) return false
            pump()
            return true
        }

        @Synchronized
        fun pump() {
            while (inFlight.size < ChalkieConfig.WD_MAX_IN_FLIGHT) {
                val bytes = queue.poll(clock.nowMs()) ?: return
                val p = Payload.fromBytes(bytes)
                inFlight += p.id
                client.sendPayload(endpointId, p).addOnFailureListener { onDone(p.id) }
            }
        }

        @Synchronized
        fun onDone(payloadId: Long) {
            if (inFlight.remove(payloadId)) pump()
        }
    }

    private companion object {
        const val TAG = "WdTransport"
    }
}
