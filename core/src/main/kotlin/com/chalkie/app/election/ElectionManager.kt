package com.chalkie.app.election

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.routing.HelloPayload
import com.chalkie.app.routing.NeighborTable
import com.chalkie.app.routing.Role
import com.chalkie.app.transport.TransportType

/**
 * Hub election (SPEC §8). Every node runs the same function on nearly the same table,
 * so they agree without voting; claims with epochs settle the rest.
 *
 * Pure logic: the node calls [computeMyScore] when it builds a HELLO, feeds in HELLOs and
 * HUB_CLAIMs, and calls [tick] every ELECTION_TICK_MS. [role] is the result.
 */
class ElectionManager(
    private val myId: UInt,
    private val clock: Clock,
    private val neighbors: NeighborTable,
) {
    private val startedMs = clock.nowMs()

    var current: HubState? = null
        private set
    var maxEpochSeen = 0
        private set

    /** The score my last HELLO carried; used for myself so every node compares the same numbers. */
    var publishedScore = 0f
        private set
    private var eligibleSelf = false

    /** Phase 3 sets this; LAN nodes take no part in hub election. */
    @Volatile var onLan = false

    /** False when this phone has no Wi-Fi Direct (e.g. no Play Services): never a hub candidate. */
    @Volatile var canBeHub = true

    private var challenger: UInt? = null
    private var challengerSinceMs = 0L

    val role: StarRole
        @Synchronized get() = current.let {
            when {
                it == null -> StarRole.Off
                it.hubId == myId -> StarRole.Hub(it.epoch)
                else -> StarRole.Leaf(it.hubId, it.epoch)
            }
        }

    @Synchronized
    fun computeMyScore(battery: Int, charging: Boolean): Float {
        val all = neighbors.all()
        val bleRssis = all.mapNotNull { it.links[TransportType.BLE]?.rssi }
        val direct = all.count { n -> n.links.keys.any { it != TransportType.WD } }
        eligibleSelf = canBeHub && ScoreCalculator.eligible(battery, charging)
        publishedScore = if (!canBeHub) 0f else ScoreCalculator.hubScore(battery, charging, bleRssis, direct, all.size + 1)
        // As hub, compare rival claims against the same score everyone else sees in my HELLO.
        current?.let { if (it.hubId == myId) current = it.copy(score = publishedScore) }
        return publishedScore
    }

    @Synchronized
    fun onClaim(srcId: UInt, epoch: Int, score: Float) {
        maxEpochSeen = maxOf(maxEpochSeen, epoch)
        consider(HubState(srcId, epoch, score))
    }

    @Synchronized
    fun onHello(srcId: UInt, hello: HelloPayload) {
        maxEpochSeen = maxOf(maxEpochSeen, hello.hubEpoch)
        val claimsHub = Role.HUB in hello.roles && hello.hubId == srcId
        val cur = current
        if (cur != null && cur.hubId == srcId) {
            when {
                claimsHub && hello.hubEpoch == cur.epoch -> current = cur.copy(score = hello.hubScore)
                !claimsHub -> current = null      // it stepped down
            }
        }
        if (claimsHub) consider(HubState(srcId, hello.hubEpoch, hello.hubScore))
    }

    /** Re-evaluates the table. Returns a claim (epoch, score) to broadcast if I just became hub. */
    @Synchronized
    fun tick(): Pair<Int, Float>? {
        val now = clock.nowMs()
        val fresh = neighbors.all().filter { it.heardHello && now - it.lastHelloMs < ChalkieConfig.HUB_LOSS_MS }
        val offLan = fresh.filter { !it.onLan }

        // A hub is needed only when at least 2 nodes (me + one) are off a usable LAN.
        if (onLan || offLan.isEmpty()) {
            current = null
            challenger = null
            return null
        }

        current?.let { cur ->
            if (cur.hubId == myId) {
                if (!eligibleSelf) current = null
            } else {
                val h = neighbors.get(cur.hubId)
                val lost = h == null || now - h.lastHelloMs > ChalkieConfig.HUB_LOSS_MS
                val ineligible = h != null && h.heardHello && !ScoreCalculator.eligible(h.battery, h.charging)
                if (lost || ineligible) current = null
            }
        }

        val candidates = buildList {
            offLan.filter { ScoreCalculator.eligible(it.battery, it.charging) && it.hubScore > 0f }
                .forEach { add(it.nodeId to it.hubScore) }
            if (eligibleSelf && publishedScore > 0f) add(myId to publishedScore)
        }
        val winner = candidates.maxWithOrNull(WINNER_ORDER) ?: return null

        val cur = current
        if (cur == null) {
            challenger = null
            val settled = now - startedMs >= ChalkieConfig.HUB_SETTLE_MS
            return if (winner.first == myId && settled) claim() else null
        }

        // Hysteresis: switch only for a clearly better candidate, held continuously.
        val curScore = if (cur.hubId == myId) publishedScore else neighbors.get(cur.hubId)?.hubScore ?: cur.score
        if (winner.first != cur.hubId && winner.second > curScore * ChalkieConfig.HUB_SWITCH_FACTOR) {
            if (challenger != winner.first) {
                challenger = winner.first
                challengerSinceMs = now
            } else if (now - challengerSinceMs >= ChalkieConfig.HUB_SWITCH_HOLD_MS && winner.first == myId) {
                challenger = null
                return claim()
            }
        } else {
            challenger = null
        }
        return null
    }

    private fun claim(): Pair<Int, Float> {
        val epoch = maxOf(maxEpochSeen, current?.epoch ?: 0) + 1
        maxEpochSeen = epoch
        current = HubState(myId, epoch, publishedScore)
        return epoch to publishedScore
    }

    private fun consider(c: HubState) {
        val cur = current
        when {
            cur == null -> if (c.hubId != myId) current = c   // never adopt stale news about myself
            c.hubId == cur.hubId -> if (c.epoch >= cur.epoch) current = c
            beats(c, cur) -> current = c                       // if I was hub, I step down here
        }
    }

    companion object {
        /** Max score wins; on a tie the lowest nodeId wins. */
        private val WINNER_ORDER = compareBy<Pair<UInt, Float>> { it.second }.thenByDescending { it.first }

        /** Claim conflict rule: higher epoch, then higher score, then lower nodeId. */
        fun beats(a: HubState, b: HubState): Boolean = when {
            a.epoch != b.epoch -> a.epoch > b.epoch
            a.score != b.score -> a.score > b.score
            else -> a.hubId < b.hubId
        }
    }
}
