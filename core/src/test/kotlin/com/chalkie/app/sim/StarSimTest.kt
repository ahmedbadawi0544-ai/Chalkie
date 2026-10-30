package com.chalkie.app.sim

import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
import com.chalkie.app.election.StarRole
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.LocalStatus
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 2: hub election + Wi-Fi Direct STAR routing, on the JVM simulator (SPEC §15). */
@OptIn(ExperimentalCoroutinesApi::class)
class StarSimTest {
    private val secret = ByteArray(32) { 42 }
    private val frame = ByteArray(160) { 0x7F }
    private val both = setOf(TransportType.BLE, TransportType.WD)

    private class Rec(val events: MutableList<Pair<Long, NodeEvent>> = mutableListOf()) {
        fun voiceFrom(src: UInt) = events.map { it.second }.filterIsInstance<NodeEvent.VoiceIn>().filter { it.srcId == src }
    }

    private class Net(val sim: SimNetwork, val recs: Map<UInt, Rec>) {
        fun node(id: UInt): ChalkieNode = sim.nodes.getValue(id)
        fun roles(): Map<UInt, StarRole> = sim.nodes.mapValues { it.value.election.role }
    }

    /** [charging] nodes score highest; [bleOnly] nodes have no Wi-Fi Direct. */
    private fun TestScope.build(ids: List<UInt>, charging: Set<UInt> = emptySet(), bleOnly: Set<UInt> = emptySet()): Net {
        val sim = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val recs = ids.associateWith { Rec() }
        for (id in ids) {
            val node = sim.addNode(
                id, secret,
                types = if (id in bleOnly) setOf(TransportType.BLE) else both,
                status = LocalStatus("n$id", 60, charging = id in charging),
            )
            backgroundScope.launch { node.events.collect { recs.getValue(id).events += testScheduler.currentTime to it } }
        }
        runCurrent()
        backgroundScope.launch { sim.nodes.values.forEach { it.start() } }
        runCurrent()
        return Net(sim, recs)
    }

    private fun Net.bleMesh(ids: List<UInt>) {
        for (i in ids.indices) for (j in i + 1 until ids.size) sim.connect(ids[i], ids[j])
    }

    private fun Net.assertOneStar(hub: UInt, members: List<UInt>) {
        val roles = roles().filterKeys { it in members }
        assertIs<StarRole.Hub>(roles.getValue(hub), "hub role: $roles")
        val epoch = (roles.getValue(hub) as StarRole.Hub).epoch
        members.filter { it != hub }.forEach {
            assertEquals(StarRole.Leaf(hub, epoch), roles.getValue(it), "node $it: $roles")
        }
        assertEquals(members.size - 1, sim.transport(hub, TransportType.WD).links().size, "every leaf has a WD link to the hub")
    }

    private fun TestScope.talk(node: ChalkieNode, packets: Int, each: (Int) -> Unit = {}) {
        assertTrue(node.pttDown(), "floor should be granted")
        repeat(packets) {
            each(it)
            node.sendVoice(listOf(frame, frame))
            advanceTimeBy(40)
        }
        node.pttUp()
        advanceTimeBy(300)
    }

    @Test
    fun sameTablesSameWinner() = runTest {
        val ids = (1u..5u).toList()
        val net = build(ids, charging = setOf(3u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 3u, members = ids)
        assertEquals(1, (net.roles().getValue(3u) as StarRole.Hub).epoch, "exactly one claim, no fights")
    }

    @Test
    fun tieBreaksToLowestNodeId() = runTest {
        val ids = listOf(7u, 4u, 9u)
        val net = build(ids)                       // identical scores
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 4u, members = ids)
    }

    @Test
    fun noHubForASingleNode() = runTest {
        val net = build(listOf(1u))
        advanceTimeBy(10_000)
        assertEquals(StarRole.Off, net.node(1u).election.role)
    }

    @Test
    fun nodeWithoutWifiDirectIsNeverHub() = runTest {
        val ids = listOf(1u, 2u, 3u)
        val net = build(ids, charging = setOf(1u), bleOnly = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 2u, members = listOf(2u, 3u))
    }

    @Test
    fun splitBrainResolvesByEpochScoreId() = runTest {
        val a = listOf(1u, 2u, 3u)
        val b = listOf(4u, 5u, 6u)
        val net = build(a + b, charging = setOf(2u, 5u))
        // Two rooms, no radio contact at all.
        for (x in a) for (y in b) net.sim.wdBlocked += x to y
        net.bleMesh(a); net.bleMesh(b)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 2u, members = a)
        net.assertOneStar(hub = 5u, members = b)

        // The rooms meet: one BLE bridge, Wi-Fi now in range for everyone.
        net.sim.wdBlocked.clear()
        net.sim.connect(3u, 4u)
        net.sim.reconcileWd()
        advanceTimeBy(15_000)

        val hubs = net.roles().filterValues { it is StarRole.Hub }.keys
        assertEquals(1, hubs.size, "one hub after merge: ${net.roles()}")
        net.assertOneStar(hub = hubs.single(), members = a + b)
    }

    @Test
    fun failoverKeepsVoiceAndElectsNewHubFast() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)

        var killedAt = 0L
        talk(net.node(2u), packets = 300) { i ->
            if (i == 50) { net.sim.kill(1u); killedAt = testScheduler.currentTime }
        }
        for (id in listOf(3u, 4u)) {
            assertEquals(300, net.recs.getValue(id).voiceFrom(2u).size, "node $id heard every packet through the handover")
        }
        val newHubAt = ids.filter { it != 1u }.flatMap { id ->
            net.recs.getValue(id).events.filter { (t, e) -> t > killedAt && e is NodeEvent.RoleChanged && e.role is StarRole.Hub }
        }.minOfOrNull { it.first }
        assertNotNull(newHubAt, "a new hub was claimed")
        assertTrue(newHubAt - killedAt < 7000, "new hub after ${newHubAt - killedAt} ms")

        advanceTimeBy(5000)
        net.assertOneStar(hub = 2u, members = listOf(2u, 3u, 4u))   // equal scores → lowest id
    }

    @Test
    fun coveredSetFullStarSendsNoBleVoice() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)

        val before = net.sim.totalSent(TransportType.BLE, PacketType.VOICE)
        talk(net.node(2u), packets = 25)
        assertEquals(0, net.sim.totalSent(TransportType.BLE, PacketType.VOICE) - before, "star covers everyone")
        for (id in listOf(1u, 3u, 4u)) assertEquals(25, net.recs.getValue(id).voiceFrom(2u).size)
    }

    @Test
    fun helloKeepsEveryBleLinkAliveInsideTheStar() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        val before = ids.associateWith { net.sim.transport(it, TransportType.BLE).sentByType[PacketType.HELLO] ?: 0L }
        advanceTimeBy(6000)                    // BLE_LINK_TIMEOUT_MS: every link must hear a HELLO in this window
        for (id in ids) {
            val sent = (net.sim.transport(id, TransportType.BLE).sentByType[PacketType.HELLO] ?: 0L) - before.getValue(id)
            assertTrue(sent >= 3 * 2, "node $id sent only $sent BLE HELLOs to its 3 neighbors in 6 s")
        }
    }

    @Test
    fun coveredSetBleTailGetsExactlyTheNeededRelays() = runTest {
        val star = (1u..4u).toList()
        val net = build(star + 5u, charging = setOf(1u), bleOnly = setOf(5u))
        net.bleMesh(star)
        net.sim.connect(3u, 5u)                    // node 5 hangs off leaf 3 by BLE only
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = star)

        val before = net.sim.totalSent(TransportType.BLE, PacketType.VOICE)
        talk(net.node(2u), packets = 25)
        assertEquals(25, net.sim.totalSent(TransportType.BLE, PacketType.VOICE) - before, "one BLE relay per packet: 3 → 5")
        assertEquals(25, net.recs.getValue(5u).voiceFrom(2u).size)

        // And the tail can talk back into the star.
        talk(net.node(5u), packets = 10)
        for (id in star.filter { it != 5u }) assertEquals(10, net.recs.getValue(id).voiceFrom(5u).size, "node $id")
    }

    @Test
    fun hubLosingBatteryHandsOver() = runTest {
        val ids = (1u..3u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        net.sim.statuses[1u] = LocalStatus("n1", 15, charging = false)   // unplugged at 15 %
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 2u, members = ids)
    }
}
