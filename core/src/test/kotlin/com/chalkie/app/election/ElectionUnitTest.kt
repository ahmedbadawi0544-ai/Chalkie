package com.chalkie.app.election

import com.chalkie.app.core.Clock
import com.chalkie.app.routing.HelloPayload
import com.chalkie.app.routing.NeighborTable
import com.chalkie.app.routing.Role
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElectionUnitTest {
    private var now = 0L
    private val clock = Clock { now }

    @Test
    fun scoreFormula() {
        // B=0.5, S=norm(-65)=0.5, C=1/2 → 0.4·0.5 + 0.3·0.5 + 0.3·0.5 = 0.5
        assertEquals(0.5f, ScoreCalculator.hubScore(50, false, listOf(-65), 1, 3), 1e-6f)
        assertEquals(0f, ScoreCalculator.hubScore(19, false, listOf(-40), 5, 6), "not eligible")
        assertTrue(ScoreCalculator.hubScore(5, true, emptyList(), 0, 1) > 0f, "charging is always eligible")
        assertEquals(1.0, ScoreCalculator.normRssi(-30))
        assertEquals(0.0, ScoreCalculator.normRssi(-100))
    }

    @Test
    fun claimConflictRule() {
        assertTrue(ElectionManager.beats(HubState(9u, 3, 0.1f), HubState(1u, 2, 0.9f)), "higher epoch")
        assertTrue(ElectionManager.beats(HubState(9u, 2, 0.8f), HubState(1u, 2, 0.5f)), "higher score")
        assertTrue(ElectionManager.beats(HubState(1u, 2, 0.5f), HubState(9u, 2, 0.5f)), "lower id")
        assertFalse(ElectionManager.beats(HubState(9u, 2, 0.5f), HubState(1u, 2, 0.5f)))
    }

    /** Node 2's view: node 1 is hub (score 0.5); my score swings with my battery. */
    private class Rig(val clock: () -> Long) {
        val table = NeighborTable { clock() }
        val em = ElectionManager(2u, { clock() }, table)
        init {
            table.onLinkUp(LinkInfo(1u, TransportType.BLE, -60, 0f, null, 0))
        }
        fun hubHello() {
            val h = HelloPayload("hub", 60, false, roles = setOf(Role.HUB), hubId = 1u, hubEpoch = 1, hubScore = 0.5f)
            table.onHello(1u, h, 0)
            em.onHello(1u, h)
        }
    }

    @Test
    fun hysteresisIgnoresNoisyChallenger() {
        val rig = Rig { now }
        rig.hubHello()
        assertEquals(StarRole.Leaf(1u, 1), rig.em.role)
        // My score: 0.4·B + 0.3·0.6 + 0.3·1. Battery 100 → 0.88 (> 0.5·1.25); battery 10 → ineligible.
        var claims = 0
        while (now < 60_000) {
            val strong = (now % 5500) < 5000          // strong for 5 s, then a 0.5 s dip
            rig.em.computeMyScore(if (strong) 100 else 10, false)
            if (now % 2000 == 0L) rig.hubHello()
            if (rig.em.tick() != null) claims++
            now += 250
        }
        assertEquals(0, claims, "never 10 s continuously better → no switch")
        assertEquals(StarRole.Leaf(1u, 1), rig.em.role)
    }

    @Test
    fun hysteresisSwitchesAfterHoldTime() {
        val rig = Rig { now }
        rig.hubHello()
        var claimAt: Long? = null
        while (now < 30_000 && claimAt == null) {
            rig.em.computeMyScore(100, false)
            if (now % 2000 == 0L) rig.hubHello()
            rig.em.tick()?.let { (epoch, _) -> assertEquals(2, epoch); claimAt = now }
            now += 250
        }
        val at = assertNotNull(claimAt)
        assertTrue(at in 10_000..10_500, "claimed at $at ms")
        assertEquals(StarRole.Hub(2), rig.em.role)
    }

    @Test
    fun smallImprovementNeverSwitches() {
        val rig = Rig { now }
        rig.hubHello()
        while (now < 60_000) {
            rig.em.computeMyScore(35, false)          // 0.62: better than 0.5, but below 0.5 × 1.25
            if (now % 2000 == 0L) rig.hubHello()
            assertNull(rig.em.tick())
            now += 250
        }
    }

    @Test
    fun lostHubIsReplacedAfterHubLossTime() {
        val rig = Rig { now }
        rig.hubHello()
        // Keep the link alive via a second, non-hub neighbor so a hub is still needed.
        rig.table.onLinkUp(LinkInfo(3u, TransportType.BLE, -60, 0f, null, 0))
        var claimAt: Long? = null
        while (now < 20_000 && claimAt == null) {
            rig.em.computeMyScore(100, false)
            rig.table.onHello(3u, HelloPayload("n3", 50, false, hubId = 1u, hubEpoch = 1, hubScore = 0.3f), 0)
            if (rig.em.tick() != null) claimAt = now
            now += 250
        }
        val at = assertNotNull(claimAt)
        assertTrue(at in 6000..6500, "hub silent since t=0, claim at $at")
    }

    @Test
    fun hubStepsDownWhenAHigherEpochClaimArrives() {
        val table = NeighborTable(clock)
        val em = ElectionManager(1u, clock, table)
        table.onLinkUp(LinkInfo(2u, TransportType.BLE, -60, 0f, null, 0))
        table.onHello(2u, HelloPayload("n2", 50, false, hubScore = 0.1f), 0)
        em.computeMyScore(100, true)
        now = 5000
        assertNotNull(em.tick())
        assertEquals(StarRole.Hub(1), em.role)
        em.onClaim(2u, 2, 0.1f)
        assertEquals(StarRole.Leaf(2u, 2), em.role)
    }
}
