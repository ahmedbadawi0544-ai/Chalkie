package com.chalkie.app.sim

import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
import com.chalkie.app.core.Priority
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.election.StarControl
import com.chalkie.app.election.StarRole
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.LocalStatus
import com.chalkie.app.transport.LanStatus
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Parameters of one simulated link. */
data class LinkParams(val loss: Double = 0.0, val latencyMs: Long = 10, val rssi: Int? = -60)

/**
 * In-memory network with virtual time. Every node gets one [FakeTransport] per type;
 * [connect]/[disconnect]/[kill] change the topology while a test runs.
 *
 * Wi-Fi Direct is driven by the nodes' STAR roles, like Nearby: a leaf connects to its
 * hub WD_CONNECT_MS after both roles match, if the two are in Wi-Fi range. A leaf that was
 * pre-discovering that node as its standby connects in WD_RECONNECT_MS instead.
 */
class SimNetwork(
    private val scope: CoroutineScope,
    val clock: Clock,
    private val random: Random = Random(42),
) {
    private data class Edge(val a: UInt, val b: UInt, val type: TransportType)

    private val edges = HashMap<Edge, LinkParams>()
    private val transports = HashMap<Pair<UInt, TransportType>, FakeTransport>()
    val nodes = LinkedHashMap<UInt, ChalkieNode>()
    val statuses = HashMap<UInt, LocalStatus>()

    private val wdRoles = HashMap<UInt, StarRole>()
    private val wdPending = HashSet<Pair<UInt, UInt>>()
    /** leaf → the standby it was pre-discovering. */
    private val prediscovered = HashMap<UInt, UInt>()
    private val dead = HashSet<UInt>()

    /** Pairs that cannot reach each other over Wi-Fi Direct (default: everyone can). */
    val wdBlocked = HashSet<Pair<UInt, UInt>>()

    fun addNode(
        id: UInt,
        secret: ByteArray,
        types: Set<TransportType> = setOf(TransportType.BLE),
        status: LocalStatus = LocalStatus("node-$id", 80, false),
    ): ChalkieNode {
        statuses[id] = status
        val node = ChalkieNode(
            nodeId = id,
            keys = GroupKeys(secret),
            clock = clock,
            scope = scope,
            status = { statuses.getValue(id) },
            sessionId = random.nextInt().toUInt(),
            random = Random(id.toInt()),
        )
        for (t in types) {
            val ft = if (t == TransportType.LAN) FakeLanTransport(id, this) else FakeTransport(t, id, this)
            transports[id to t] = ft
            node.addTransport(ft)
        }
        nodes[id] = node
        return node
    }

    fun transport(id: UInt, type: TransportType): FakeTransport = transports.getValue(id to type)

    fun connect(a: UInt, b: UInt, type: TransportType = TransportType.BLE, params: LinkParams = LinkParams()) {
        edges[edge(a, b, type)] = params
        transport(a, type).linkUp(b, params)
        transport(b, type).linkUp(a, params)
    }

    /** Changes a live link's quality; both ends report it as LinkStats (like a real transport). */
    fun updateLink(a: UInt, b: UInt, type: TransportType, params: LinkParams) {
        val e = edge(a, b, type)
        if (e !in edges) return
        edges[e] = params
        transport(a, type).linkStats(b, params)
        transport(b, type).linkStats(a, params)
    }

    fun disconnect(a: UInt, b: UInt, type: TransportType = TransportType.BLE) {
        if (edges.remove(edge(a, b, type)) == null) return
        transports[a to type]?.linkDown(b)
        transports[b to type]?.linkDown(a)
    }

    /** Node disappears: all its links drop, its transports go silent. */
    fun kill(id: UInt) {
        dead += id
        edges.keys.filter { it.a == id || it.b == id }.forEach { disconnect(it.a, it.b, it.type) }
        transports.filterKeys { it.first == id }.values.forEach { it.dead = true }
    }

    fun totalSent(type: TransportType, packetType: PacketType): Long =
        transports.filterKeys { it.second == type }.values.sumOf { it.sentByType[packetType] ?: 0L }

    // ---- Wi-Fi Direct (Nearby-like) ----

    internal fun onWdRole(id: UInt, role: StarRole) {
        (wdRoles[id] as? StarRole.Leaf)?.standbyId?.let { prediscovered[id] = it }
        wdRoles[id] = role
        reconcileWd()
    }

    /** Re-checks the WD range rules; call after changing [wdBlocked]. */
    fun reconcileWd() {
        // Drop links whose roles no longer match.
        edges.keys.filter { it.type == TransportType.WD && !starMatches(it.a, it.b) && !starMatches(it.b, it.a) }
            .forEach { disconnect(it.a, it.b, TransportType.WD) }
        // Leaves connect to their hub after the discovery/connection delay.
        for ((leaf, role) in wdRoles.toMap()) {
            if (role !is StarRole.Leaf) continue
            val pair = leaf to role.hubId
            if (!starMatches(leaf, role.hubId) || edges.containsKey(edge(leaf, role.hubId, TransportType.WD)) || pair in wdPending) continue
            wdPending += pair
            val wait = if (prediscovered[leaf] == role.hubId) WD_RECONNECT_MS else WD_CONNECT_MS
            scope.launch {
                delay(wait)
                wdPending -= pair
                if (starMatches(leaf, role.hubId) && !edges.containsKey(edge(leaf, role.hubId, TransportType.WD))) {
                    connect(leaf, role.hubId, TransportType.WD, LinkParams(latencyMs = 5, rssi = null))
                }
            }
        }
    }

    /** True if [leaf] wants [hub] and [hub] is currently advertising that epoch, in range. */
    private fun starMatches(leaf: UInt, hub: UInt): Boolean {
        val l = wdRoles[leaf] as? StarRole.Leaf ?: return false
        val h = wdRoles[hub] as? StarRole.Hub ?: return false
        return l.hubId == hub && l.epoch == h.epoch && leaf !in dead && hub !in dead &&
            (leaf to hub) !in wdBlocked && (hub to leaf) !in wdBlocked
    }

    internal fun deliver(from: UInt, to: UInt, type: TransportType, bytes: ByteArray): Boolean {
        val p = edges[edge(from, to, type)] ?: return false
        if (random.nextDouble() < p.loss) return true   // sent, lost on air
        scope.launch {
            delay(p.latencyMs)
            val dst = transports[to to type] ?: return@launch
            if (dst.dead || edges[edge(from, to, type)] == null) return@launch
            dst.emit(TransportEvent.PacketIn(type, from, bytes))
        }
        return true
    }

    private fun edge(a: UInt, b: UInt, t: TransportType) = if (a < b) Edge(a, b, t) else Edge(b, a, t)

    companion object {
        /** Nearby discovery + connection setup time. */
        const val WD_CONNECT_MS = 1500L
        /** Connection to an advert found while pre-discovering (no discovery phase left). */
        const val WD_RECONNECT_MS = 500L
    }
}

open class FakeTransport(
    override val type: TransportType,
    private val ownerId: UInt,
    private val net: SimNetwork,
) : Transport, StarControl {
    private val flow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 65_536)
    override val events: Flow<TransportEvent> = flow
    private val links = LinkedHashMap<UInt, LinkInfo>()
    var dead = false
    var sent = 0L
        private set
    val sentByType = HashMap<PacketType, Long>()

    override suspend fun start() = Unit
    override suspend fun stop() { dead = true }

    override fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean {
        if (dead || neighborId !in links) return false
        sent++
        PacketType.fromCode(bytes[3].toInt() and 0xFF)?.let { sentByType[it] = (sentByType[it] ?: 0L) + 1 }
        return net.deliver(ownerId, neighborId, type, bytes)
    }

    override fun links(): List<LinkInfo> = links.values.toList()

    override fun setStarRole(role: StarRole) {
        if (type == TransportType.WD && !dead) net.onWdRole(ownerId, role)
    }

    internal fun linkUp(peer: UInt, p: LinkParams) {
        val info = LinkInfo(peer, type, if (type == TransportType.BLE) p.rssi else null, p.loss.toFloat(), null, net.clock.nowMs())
        links[peer] = info
        emit(TransportEvent.LinkUp(info))
    }

    internal fun linkStats(peer: UInt, p: LinkParams) {
        val info = LinkInfo(peer, type, if (type == TransportType.BLE) p.rssi else null, p.loss.toFloat(), null, net.clock.nowMs())
        if (peer !in links) return
        links[peer] = info
        emit(TransportEvent.LinkStats(info))
    }

    internal fun linkDown(peer: UInt) {
        if (links.remove(peer) != null) emit(TransportEvent.LinkDown(type, peer))
    }

    internal fun emit(e: TransportEvent) {
        check(flow.tryEmit(e)) { "sim event buffer overflow" }
    }
}

/** Building Wi-Fi: "on LAN" while it has at least one authenticated LAN peer. */
class FakeLanTransport(ownerId: UInt, net: SimNetwork) : FakeTransport(TransportType.LAN, ownerId, net), LanStatus {
    override val lanActive: Boolean get() = links().isNotEmpty()
    override val lanIp: ByteArray? = byteArrayOf(10, 0, 0, ownerId.toByte())
    override val lanPort: Int = 47000
    override val ssidHash: Int? = 0x5EED
}
