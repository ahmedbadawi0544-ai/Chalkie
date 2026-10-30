package com.chalkie.app.election

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.routing.HelloPayload
import com.chalkie.app.routing.NeighborTable
import com.chalkie.app.routing.Role
import com.chalkie.app.transport.TransportType

/**
 * Hub election (SPEC §8, §19.1). Every node runs the same function on nearly the same table,
 * so they agree without voting; claims with epochs settle the rest.
 *
 * Besides the hub, every node picks a **standby** (the best candidate after the hub). When the
 * hub is lost, everyone switches to the standby at once, with the epoch they all derive
 * (`max(seen, hubEpoch) + 1`): the standby claims and advertises immediately, the leaves go
 * straight to it. Leaves hold that switch provisionally until the standby confirms.
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

    /** Next hub if the current one is lost (may be me). Null when there is no other candidate. */
    var standbyId: UInt? = null
        private set

    /** The score my last HELLO carried; used for myself so every node compares the same numbers. */
    var publishedScore = 0f
        private set
    private var eligibleSelf = false

    /** Phase 3 sets this; LAN nodes take no part in hub election. */
    @Volatile var onLan = false

    /** False when this phone has no Wi-Fi Direct (e.g. no Play Services): never a hub candidate. */
    @Volatile var canBeHub = true

    /** True while any talk is active: HELLOs are sparser, so HELLO-silence loss waits longer. */
    @Volatile var talkActive = false

    private var challenger: UInt? = null
    private var challengerSinceMs = 0L

    /** The hub I had a WD link to; losing every link to it means it is gone (fast loss). */
    private var hubLinkedOverWd: UInt? = null

    /** A hub I declared lost; not a candidate again until it is heard from anew. */
    private var lostHubId: UInt? = null

    /** Set while I switched to the standby but it has not confirmed yet. */
    private class Pending(val lost: HubState, val sinceMs: Long)
    private var pending: Pending? = null

    val role: StarRole
        @Synchronized get() = current.let {
            when {
                it == null -> StarRole.Off
                it.hubId == myId -> StarRole.Hub(it.epoch, standbyId)
                else -> StarRole.Leaf(it.hubId, it.epoch, standbyId)
            }
        }

    private val lossMs: Long get() = if (talkActive) ChalkieConfig.HUB_LOSS_TALK_MS else ChalkieConfig.HUB_LOSS_MS

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
        if (srcId == lostHubId) lostHubId = null
        consider(HubState(srcId, epoch, score))
    }

    @Synchronized
    fun onHello(srcId: UInt, hello: HelloPayload) {
        maxEpochSeen = maxOf(maxEpochSeen, hello.hubEpoch)
        val claimsHub = Role.HUB in hello.roles && hello.hubId == srcId
        val p = pending
        if (p != null && srcId == p.lost.hubId) {
            // The hub I gave up on is alive and still hub at that epoch: go back to it.
            if (claimsHub && hello.hubEpoch == p.lost.epoch && current?.let { it.epoch <= p.lost.epoch + 1 } == true) {
                current = p.lost
                pending = null
                lostHubId = null
                return
            }
        }
        if (srcId == lostHubId) lostHubId = null
        val cur = current
        if (cur != null && cur.hubId == srcId) {
            when {
                claimsHub && hello.hubEpoch == cur.epoch -> current = cur.copy(score = hello.hubScore)
                // The standby hasn't noticed the loss yet; wait for it (or the pending timeout).
                !claimsHub && pending != null -> Unit
                !claimsHub -> current = null      // it stepped down
            }
        }
        if (claimsHub) consider(HubState(srcId, hello.hubEpoch, hello.hubScore))
    }

    /** Re-evaluates the table. Returns a claim (epoch, score) to broadcast if I just became hub. */
    @Synchronized
    fun tick(): Pair<Int, Float>? {
        val now = clock.nowMs()
        val loss = lossMs
        val fresh = neighbors.all().filter { it.heardHello && now - it.lastHelloMs < loss }
        val offLan = fresh.filter { !it.onLan }

        // A hub is needed only when at least 2 nodes (me + one) are off a usable LAN.
        if (onLan || offLan.isEmpty()) {
            current = null
            standbyId = null
            pending = null
            challenger = null
            return null
        }

        val candidates = buildList {
            offLan.filter { it.nodeId != lostHubId && ScoreCalculator.eligible(it.battery, it.charging) && it.hubScore > 0f }
                .forEach { add(it.nodeId to it.hubScore) }
            if (eligibleSelf && publishedScore > 0f) add(myId to publishedScore)
        }

        pending?.let { if (now - it.sinceMs > ChalkieConfig.STANDBY_CONFIRM_MS) { current = null; pending = null } }

        current?.let { cur ->
            if (cur.hubId == myId) {
                if (!eligibleSelf) current = null
            } else if (pending == null) {
                val h = neighbors.get(cur.hubId)
                val wdNow = h?.links?.containsKey(TransportType.WD) == true
                // Fast loss: I had a WD link to the hub and now have no link to it at all.
                val linkLost = hubLinkedOverWd == cur.hubId && h?.isDirect != true
                val silent = h == null || now - h.lastHelloMs > loss
                val ineligible = h != null && h.heardHello && !ScoreCalculator.eligible(h.battery, h.charging)
                hubLinkedOverWd = cur.hubId.takeIf { wdNow } ?: hubLinkedOverWd?.takeIf { it == cur.hubId && h?.isDirect == true }
                if (linkLost || silent || ineligible) {
                    lostHubId = cur.hubId
                    hubLinkedOverWd = null
                    return succeed(cur, candidates, now)
                }
            }
        }

        val winner = candidates.maxWithOrNull(WINNER_ORDER)
        val cur = current
        if (cur == null) {
            standbyId = null
            challenger = null
            if (winner == null) return null
            val settled = now - startedMs >= ChalkieConfig.HUB_SETTLE_MS
            return if (winner.first == myId && settled) claim() else null
        }

        standbyId = candidates.filter { it.first != cur.hubId }.maxWithOrNull(WINNER_ORDER)?.first
        if (pending != null || winner == null) return null

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

    /**
     * The hub [lost] is gone. Everyone moves to the standby at the epoch they all derive; the
     * standby claims now. Without a usable standby, fall back to a fresh election.
     */
    private fun succeed(lost: HubState, candidates: List<Pair<UInt, Float>>, now: Long): Pair<Int, Float>? {
        val sb = standbyId?.takeIf { id -> id != lost.hubId && candidates.any { it.first == id } }
        standbyId = null
        challenger = null
        if (sb == null) {
            current = null
            return null
        }
        val epoch = maxOf(maxEpochSeen, lost.epoch) + 1
        if (sb == myId) {
            maxEpochSeen = epoch
            current = HubState(myId, epoch, publishedScore)
            return epoch to publishedScore
        }
        current = HubState(sb, epoch, candidates.first { it.first == sb }.second)
        pending = Pending(lost, now)
        return null
    }

    private fun claim(): Pair<Int, Float> {
        val epoch = maxOf(maxEpochSeen, current?.epoch ?: 0) + 1
        maxEpochSeen = epoch
        current = HubState(myId, epoch, publishedScore)
        pending = null
        return epoch to publishedScore
    }

    private fun consider(c: HubState) {
        val cur = current
        when {
            cur == null -> if (c.hubId != myId) current = c   // never adopt stale news about myself
            c.hubId == cur.hubId -> if (c.epoch >= cur.epoch) {
                current = c
                pending = null                                  // the standby confirmed
            }
            beats(c, cur) -> { current = c; pending = null }   // if I was hub, I step down here
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
