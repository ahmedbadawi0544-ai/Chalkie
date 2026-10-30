package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportType

class NeighborEntry(
    val nodeId: UInt,
    var name: String = "",
    val links: MutableMap<TransportType, LinkInfo> = mutableMapOf(),
    var battery: Int = 0,
    var charging: Boolean = false,
    var onLan: Boolean = false,
    var lanIp: ByteArray? = null,
    var lanPort: Int = 0,
    var ssidHash: Int? = null,
    var roles: Set<Role> = emptySet(),
    var hubId: UInt = 0u,
    var hubEpoch: Int = 0,
    var bridgeIds: List<UInt> = emptyList(),
    var reportedNeighbors: List<ReportedNeighbor> = emptyList(),
    var hubScore: Float = 0f,
    var bridgeScore: Float = 0f,
    var lastHelloMs: Long = 0,
    var hopCount: Int = 0,
    var heardHello: Boolean = false,
) {
    val isDirect: Boolean get() = links.isNotEmpty()
    val bestLink: LinkInfo? get() = LinkCost.best(links.values)

    fun snapshot(): NeighborSnapshot = NeighborSnapshot(
        nodeId, name, links.toMap(), battery, charging, onLan, roles, hubId, hubEpoch,
        reportedNeighbors, lastHelloMs, hopCount, hubScore, heardHello,
        bridgeScore, lanIp?.copyOf(), lanPort, ssidHash,
    )
}

/** Immutable copy for UI and other threads. */
data class NeighborSnapshot(
    val nodeId: UInt,
    val name: String,
    val links: Map<TransportType, LinkInfo>,
    val battery: Int,
    val charging: Boolean,
    val onLan: Boolean,
    val roles: Set<Role>,
    val hubId: UInt,
    val hubEpoch: Int,
    val reportedNeighbors: List<ReportedNeighbor>,
    val lastHelloMs: Long,
    val hopCount: Int,
    val hubScore: Float = 0f,
    /** False while we only know the node from a link, before its first HELLO. */
    val heardHello: Boolean = false,
    val bridgeScore: Float = 0f,
    val lanIp: ByteArray? = null,
    val lanPort: Int = 0,
    val ssidHash: Int? = null,
) {
    val isDirect: Boolean get() = links.isNotEmpty()
}

/** Direct neighbors (have links) and remote nodes (known via relayed HELLOs). SPEC §6. */
class NeighborTable(private val clock: Clock) {
    private val entries = HashMap<UInt, NeighborEntry>()

    @Synchronized
    fun onLinkUp(info: LinkInfo) {
        val e = entries.getOrPut(info.neighborId) { NeighborEntry(info.neighborId, lastHelloMs = clock.nowMs()) }
        e.links[info.type] = info
    }

    @Synchronized
    fun onLinkStats(info: LinkInfo) {
        entries[info.neighborId]?.links?.let { if (info.type in it) it[info.type] = info }
    }

    @Synchronized
    fun onLinkDown(type: TransportType, neighborId: UInt) {
        entries[neighborId]?.links?.remove(type)
    }

    @Synchronized
    fun onHello(srcId: UInt, hello: HelloPayload, hopCount: Int) {
        val e = entries.getOrPut(srcId) { NeighborEntry(srcId) }
        e.name = hello.name
        e.battery = hello.battery
        e.charging = hello.charging
        e.onLan = hello.onLan
        e.lanIp = hello.lanIp
        e.lanPort = hello.lanPort
        e.ssidHash = hello.ssidHash
        e.roles = hello.roles
        e.hubId = hello.hubId
        e.hubEpoch = hello.hubEpoch
        e.bridgeIds = hello.bridgeIds
        e.reportedNeighbors = hello.neighbors
        e.hubScore = hello.hubScore
        e.bridgeScore = hello.bridgeScore
        e.lastHelloMs = clock.nowMs()
        e.hopCount = hopCount
        e.heardHello = true
    }

    /** Removes nodes silent for NODE_TIMEOUT_MS. Returns removed ids. */
    @Synchronized
    fun expire(): List<UInt> {
        val now = clock.nowMs()
        val dead = entries.values.filter { now - it.lastHelloMs > ChalkieConfig.NODE_TIMEOUT_MS && !it.isDirect }
            .map { it.nodeId }
        dead.forEach { entries.remove(it) }
        return dead
    }

    @Synchronized
    fun get(id: UInt): NeighborSnapshot? = entries[id]?.snapshot()

    @Synchronized
    fun all(): List<NeighborSnapshot> = entries.values.map { it.snapshot() }

    @Synchronized
    fun directIds(type: TransportType): List<UInt> =
        entries.values.filter { type in it.links }.map { it.nodeId }

    /** What we report in our own HELLO: one entry per direct link. */
    @Synchronized
    fun reportable(): List<ReportedNeighbor> =
        entries.values.flatMap { e -> e.links.values.map { ReportedNeighbor(e.nodeId, it.type, it.rssi) } }
}
