package com.chalkie.app.sim

import com.chalkie.app.audio.Codec
import com.chalkie.app.audio.DecoderFactory
import com.chalkie.app.audio.FrameDecoder
import com.chalkie.app.audio.Playout
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
import com.chalkie.app.election.StarRole
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.LocalStatus
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/** Wi-Fi 8–style reliability (SPEC §19) on the JVM simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilitySimTest {
    private val secret = ByteArray(32) { 42 }
    private val frame = ByteArray(160) { 0x7F }
    private val both = setOf(TransportType.BLE, TransportType.WD)

    private class Rec(val events: MutableList<Pair<Long, NodeEvent>> = mutableListOf()) {
        fun voiceFrom(src: UInt) = events.filter { (_, e) -> e is NodeEvent.VoiceIn && e.srcId == src }
        inline fun <reified T : NodeEvent> count() = events.count { it.second is T }
    }

    private class Net(val sim: SimNetwork, val recs: Map<UInt, Rec>) {
        fun node(id: UInt): ChalkieNode = sim.nodes.getValue(id)
        fun roles(): Map<UInt, StarRole> = sim.nodes.mapValues { it.value.election.role }
    }

    private fun TestScope.build(
        ids: List<UInt>,
        charging: Set<UInt> = emptySet(),
        types: Set<TransportType> = both,
        onEvent: (UInt, NodeEvent) -> Unit = { _, _ -> },
    ): Net {
        val sim = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val recs = ids.associateWith { Rec() }
        for (id in ids) {
            val node = sim.addNode(id, secret, types, LocalStatus("n$id", 60, charging = id in charging))
            backgroundScope.launch {
                node.events.collect { recs.getValue(id).events += testScheduler.currentTime to it; onEvent(id, it) }
            }
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
        val epoch = assertIs<StarRole.Hub>(roles.getValue(hub), "hub role: $roles").epoch
        members.filter { it != hub }.forEach {
            val r = assertIs<StarRole.Leaf>(roles.getValue(it), "node $it: $roles")
            assertEquals(hub to epoch, r.hubId to r.epoch, "node $it: $roles")
        }
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
    fun electionPicksStandbyAsSecondBest() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u, 3u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        for (id in ids) assertEquals(3u, net.node(id).election.standbyId, "node $id agrees on the standby")
        val leaf = assertIs<StarRole.Leaf>(net.node(2u).role.value)
        assertEquals(3u, leaf.standbyId, "leaves pre-discover the standby")
    }

    /** Requirement: hub kill with a standby → voice gap < 2 s, where only Wi-Fi Direct carries voice. */
    @Test
    fun hubKillWithStandbyKeepsVoiceGapUnder2s() = runTest {
        val ids = (1u..5u).toList()
        val net = build(ids, charging = setOf(1u, 2u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        // BLE out of range from here on: no mesh to hide the handover, the star is the only path.
        for (i in ids) for (j in ids) if (i < j) net.sim.disconnect(i, j)
        advanceTimeBy(6000)
        net.assertOneStar(hub = 1u, members = ids)
        for (id in ids) assertEquals(2u, net.node(id).election.standbyId, "node $id: standby")

        var killedAt = 0L
        talk(net.node(3u), packets = 150) { i ->
            if (i == 40) { net.sim.kill(1u); killedAt = testScheduler.currentTime }
        }
        for (id in listOf(2u, 4u, 5u)) {
            val times = listOf(killedAt) + net.recs.getValue(id).voiceFrom(3u).map { it.first }.filter { it >= killedAt }
            val gap = times.zipWithNext { a, b -> b - a }.max()
            assertTrue(gap < 2000, "node $id: voice gap $gap ms after the hub was killed")
            assertTrue(times.size - 1 >= 110 - 25, "node $id heard ${times.size - 1} of 110 packets after the kill (> 1 s lost)")
        }
        val newHubAt = net.recs.getValue(2u).events
            .firstOrNull { (t, e) -> t >= killedAt && e is NodeEvent.RoleChanged && e.role is StarRole.Hub }?.first
        assertNotNull(newHubAt, "standby took over")
        assertTrue(newHubAt - killedAt < 1000, "standby advertising ${newHubAt - killedAt} ms after the loss")
        advanceTimeBy(5000)
        net.assertOneStar(hub = 2u, members = listOf(2u, 3u, 4u, 5u))
    }

    /** Requirement: 20 % random loss on every link → no audible gaps (redundancy + duplicate path). */
    @Test
    fun twentyPercentRandomLossHasNoAudibleGaps() = runTest {
        val mark = ShortArray(ChalkieConfig.SAMPLES_PER_FRAME) { 1000 }
        val marker = object : FrameDecoder { override fun decode(frame: ByteArray) = mark }
        val playout = Playout(DecoderFactory { c -> if (c == Codec.G711_ULAW) marker else null })
        val net = build(listOf(1u, 2u), types = setOf(TransportType.LAN, TransportType.BLE)) { id, e ->
            if (id != 2u) return@build
            when (e) {
                is NodeEvent.VoiceIn -> playout.onVoice(e.srcId, e.payload)
                is NodeEvent.TalkEnded -> playout.onTalkEnd(e.srcId, e.streamId)
                else -> Unit
            }
        }
        val lossy = LinkParams(loss = 0.2)
        net.sim.connect(1u, 2u, TransportType.LAN, lossy)
        net.sim.connect(1u, 2u, TransportType.BLE, lossy)
        advanceTimeBy(5000)

        // The audio thread: one frame every 20 ms. null = nothing playing, zeros = audible gap.
        val played = mutableListOf<ShortArray?>()
        backgroundScope.launch {
            while (isActive) {
                played += playout.nextFrame()
                delay(ChalkieConfig.VOICE_FRAME_MS.toLong())
            }
        }
        val packets = 500
        talk(net.node(1u), packets)
        advanceTimeBy(1000)

        val audio = played.filterNotNull()
        val first = audio.indexOfFirst { it[0] != 0.toShort() }
        val last = audio.indexOfLast { it[0] != 0.toShort() }
        val gaps = audio.subList(first, last + 1).count { it[0] == 0.toShort() }
        val heard = net.recs.getValue(2u).voiceFrom(1u).size
        assertTrue(heard < packets, "the test must actually lose packets on both paths (heard $heard of $packets)")
        assertEquals(0, gaps, "silent frames inside the talk")
        assertEquals(packets * 2, last - first + 1, "every frame played once, in time")
    }

    @Test
    fun criticalPacketsGoOnTwoTransportsVoiceOnOne() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        val ble = { t: PacketType -> net.sim.totalSent(TransportType.BLE, t) }
        val before = PacketType.entries.associateWith(ble)
        talk(net.node(2u), packets = 25)
        assertTrue(ble(PacketType.TALK_START) > before.getValue(PacketType.TALK_START), "TALK_START also on BLE")
        assertTrue(ble(PacketType.TALK_END) > before.getValue(PacketType.TALK_END), "TALK_END also on BLE")
        assertEquals(before[PacketType.VOICE], ble(PacketType.VOICE), "healthy star: no duplicate VOICE")
        for (id in listOf(1u, 3u, 4u)) {
            assertEquals(1, net.recs.getValue(id).count<NodeEvent.TalkStarted>(), "node $id: deduped to one TALK_START")
            assertEquals(25, net.recs.getValue(id).voiceFrom(2u).size)
        }
    }

    @Test
    fun voiceIsDuplicatedOnBleOnlyWhileTheWdLinkDegrades() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids, charging = setOf(1u))
        net.bleMesh(ids)
        advanceTimeBy(10_000)
        net.assertOneStar(hub = 1u, members = ids)
        val bleVoice = { net.sim.totalSent(TransportType.BLE, PacketType.VOICE) }

        net.sim.updateLink(2u, 1u, TransportType.WD, LinkParams(loss = 0.2, latencyMs = 5, rssi = null))
        runCurrent()
        var before = bleVoice()
        talk(net.node(2u), packets = 25)
        assertTrue(bleVoice() - before >= 25, "degrading WD link: BLE carries a copy (${bleVoice() - before})")
        for (id in listOf(1u, 3u, 4u)) assertEquals(25, net.recs.getValue(id).voiceFrom(2u).size, "node $id, deduped")

        net.sim.updateLink(2u, 1u, TransportType.WD, LinkParams(latencyMs = 5, rssi = null))
        runCurrent()
        before = bleVoice()
        talk(net.node(2u), packets = 25)
        assertEquals(0, bleVoice() - before, "recovered: no more duplicates")
    }

    @Test
    fun fadingBleLinkMovesUnicastToTheNextLinkBeforeItDrops() = runTest {
        val net = build(listOf(1u, 2u), types = setOf(TransportType.LAN, TransportType.BLE))
        net.sim.connect(1u, 2u, TransportType.BLE, LinkParams(rssi = -50))
        net.sim.connect(1u, 2u, TransportType.LAN, LinkParams(loss = 0.0))
        advanceTimeBy(5000)
        val n1 = net.node(1u)
        // LAN is cheapest; make it degrade (loss) while BLE fades (-4 dB per second).
        net.sim.updateLink(1u, 2u, TransportType.LAN, LinkParams(loss = 0.25))
        runCurrent()
        assertTrue(n1.health.isDegrading(2u, TransportType.LAN))
        assertEquals(TransportType.BLE, n1.router.unicast!!.nextHop(2u)!!.second, "healthy BLE preferred over degrading LAN")
        var rssi = -50
        repeat(5) {
            rssi -= 4
            advanceTimeBy(1000)
            net.sim.updateLink(1u, 2u, TransportType.BLE, LinkParams(rssi = rssi))
            runCurrent()
        }
        assertTrue(n1.health.slope(2u, TransportType.BLE) < ChalkieConfig.LINK_DEGRADE_SLOPE_DB, "slope ${n1.health.slope(2u, TransportType.BLE)}")
        assertTrue(n1.health.isDegrading(2u, TransportType.BLE))
    }

    @Test
    fun helloIntervalIsFiveSecondsDuringATalk() = runTest {
        val net = build(listOf(1u, 2u), types = setOf(TransportType.BLE))
        net.sim.connect(1u, 2u)
        advanceTimeBy(5000)
        val hellos = { net.sim.transport(2u, TransportType.BLE).sentByType[PacketType.HELLO] ?: 0L }
        var before = hellos()
        advanceTimeBy(20_000)
        val idle = hellos() - before
        before = hellos()
        talk(net.node(1u), packets = 500)                  // 20 s, node 2 listens
        val talking = hellos() - before
        assertTrue(idle in 9..11, "idle: $idle HELLOs in 20 s")
        assertTrue(talking in 3..5, "talking: $talking HELLOs in 20 s")
        assertTrue(net.node(2u).talkActive.value.not(), "talk mode ends with the talk")
    }

    @Test
    fun privateCallIsAcknowledged() = runTest {
        val net = build(listOf(1u, 2u, 3u), types = setOf(TransportType.BLE))
        net.sim.connect(1u, 2u)
        net.sim.connect(2u, 3u)
        advanceTimeBy(5000)
        assertTrue(net.node(1u).pttDown(target = 3u))
        advanceTimeBy(200)
        val ack = net.recs.getValue(1u).events.map { it.second }.filterIsInstance<NodeEvent.TalkAcked>().single()
        assertEquals(3u, ack.srcId)
        net.node(1u).pttUp()
    }
}
