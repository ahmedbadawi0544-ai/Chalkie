package com.chalkie.app.election

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.routing.NeighborTable
import com.chalkie.app.routing.Role
import com.chalkie.app.transport.TransportType

/**
 * Bridge election (SPEC §9): LAN nodes with a non-LAN link to an off-LAN node relay between
 * the LAN and everyone else. The top MAX_BRIDGES by bridge score serve; extra bridges are
 * harmless (dedup), so conflicts resolve simply by rank. Same hysteresis as the hub.
 */
class BridgeElection(
    private val myId: UInt,
    private val clock: Clock,
    private val neighbors: NeighborTable,
) {
    private val startedMs = clock.nowMs()

    var amBridge = false
        private set
    var publishedScore = 0f
        private set
    var maxEpochSeen = 0
        private set
    private var candidate = false
    private var challengeSinceMs: Long? = null

    @Synchronized
    fun computeMyScore(battery: Int, charging: Boolean, onLan: Boolean): Float {
        val all = neighbors.all()
        val offLan = all.filter { it.heardHello && !it.onLan }
        val reachable = offLan.filter { n -> n.links.keys.any { it != TransportType.LAN } }
        candidate = onLan && reachable.isNotEmpty() && ScoreCalculator.eligible(battery, charging)
        if (!candidate) {
            publishedScore = 0f
            return 0f
        }
        val b = if (charging) 1.0 else battery / 100.0
        val r = reachable.sumOf { n -> n.links[TransportType.BLE]?.rssi?.let(ScoreCalculator::normRssi) ?: 1.0 } /
            maxOf(1, offLan.size)
        publishedScore = (ChalkieConfig.BRIDGE_W_BATTERY * b + ChalkieConfig.BRIDGE_W_REACH * r).toFloat()
        return publishedScore
    }

    @Synchronized
    fun onClaim(epoch: Int) {
        maxEpochSeen = maxOf(maxEpochSeen, epoch)
    }

    /** Returns the epoch of a BRIDGE_CLAIM to broadcast when I just became a bridge. */
    @Synchronized
    fun tick(): Int? {
        val now = clock.nowMs()
        if (!candidate) {
            amBridge = false
            challengeSinceMs = null
            return null
        }
        val fresh = neighbors.all().filter { it.heardHello && it.onLan && now - it.lastHelloMs < ChalkieConfig.HUB_LOSS_MS }
        val others = fresh.filter { Role.BRIDGE in it.roles }.map { it.nodeId to it.bridgeScore }

        if (amBridge) {
            // Too many bridges (simultaneous claims): the lowest ranked step down.
            val ranked = (others + (myId to publishedScore)).sortedWith(RANK)
            if (ranked.indexOfFirst { it.first == myId } >= ChalkieConfig.MAX_BRIDGES) amBridge = false
            return null
        }

        if (now - startedMs < ChalkieConfig.HUB_SETTLE_MS) return null
        if (others.size < ChalkieConfig.MAX_BRIDGES) {
            val free = ChalkieConfig.MAX_BRIDGES - others.size
            val cands = fresh.filter { Role.BRIDGE !in it.roles && it.bridgeScore > 0f }
                .map { it.nodeId to it.bridgeScore } + (myId to publishedScore)
            return if (cands.sortedWith(RANK).take(free).any { it.first == myId }) claim() else null
        }

        // All seats taken: replace the weakest only if clearly better, held continuously.
        val weakest = others.sortedWith(RANK).last()
        if (publishedScore > weakest.second * ChalkieConfig.HUB_SWITCH_FACTOR) {
            val since = challengeSinceMs ?: now.also { challengeSinceMs = it }
            if (now - since >= ChalkieConfig.HUB_SWITCH_HOLD_MS) {
                challengeSinceMs = null
                return claim()
            }
        } else {
            challengeSinceMs = null
        }
        return null
    }

    private fun claim(): Int {
        amBridge = true
        maxEpochSeen++
        return maxEpochSeen
    }

    private companion object {
        /** Best first: higher score, then lower nodeId. */
        val RANK = compareByDescending<Pair<UInt, Float>> { it.second }.thenBy { it.first }
    }
}
