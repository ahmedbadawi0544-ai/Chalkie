package com.chalkie.app.sim

import com.chalkie.app.audio.TalkPriority
import com.chalkie.app.core.Clock
import com.chalkie.app.core.Priority
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.node.StopReason
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUnsignedTypes::class)
class MeshSimTest {
    private val secret = ByteArray(32) { 42 }
    private val frame = ByteArray(160) { 0x7F }

    private class Rec(val events: MutableList<NodeEvent> = mutableListOf()) {
        fun voiceFrom(src: UInt) = events.filterIsInstance<NodeEvent.VoiceIn>().filter { it.srcId == src }
    }

    private fun TestScope.setup(vararg ids: UInt): Pair<SimNetwork, Map<UInt, Rec>> {
        val net = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val recs = ids.associateWith { Rec() }
        for (id in ids) {
            val node = net.addNode(id, secret)
            backgroundScope.launch { node.events.collect { recs.getValue(id).events += it } }
        }
        runCurrent()
        backgroundScope.launch { net.nodes.values.forEach { it.start() } }
        runCurrent()
        return net to recs
    }

    /** Talks for [packets] VOICE packets (40 ms each). */
    private fun TestScope.talk(node: ChalkieNode, packets: Int) {
        assertTrue(node.pttDown(), "floor should be granted")
        repeat(packets) {
            node.sendVoice(listOf(frame, frame))
            advanceTimeBy(40)
        }
        node.pttUp()
        advanceTimeBy(200)
    }

    @Test
    fun lineTopologyTalksBothWaysThroughMiddle() = runTest {
        val (net, recs) = setup(1u, 2u, 3u)
        net.connect(1u, 2u)
        net.connect(2u, 3u)
        advanceTimeBy(5000)

        // A and C learned about each other only through B's relays.
        val a = net.nodes.getValue(1u)
        val c = net.nodes.getValue(3u)
        val cAtA = assertNotNull(a.neighbors.get(3u), "A knows C via relayed HELLO")
        assertFalse(cAtA.isDirect)
        assertEquals(1, cAtA.hopCount)

        talk(a, 25)
        assertEquals(25, recs.getValue(3u).voiceFrom(1u).size, "C hears all of A")
        assertEquals((0 until 25).map { it * 2 }, recs.getValue(3u).voiceFrom(1u).map { it.payload.frameSeq })
        assertEquals(25, recs.getValue(2u).voiceFrom(1u).size, "B hears A too")
        assertTrue(recs.getValue(3u).events.any { it is NodeEvent.TalkEnded && it.srcId == 1u })

        advanceTimeBy(1000)
        talk(c, 25)
        assertEquals(25, recs.getValue(1u).voiceFrom(3u).size, "A hears all of C")
        assertEquals(0, recs.getValue(1u).voiceFrom(1u).size, "own packets never delivered back")
    }

    @Test
    fun meshDeliversExactlyOnceDespiteLoops() = runTest {
        // Full mesh of 4 + extra node: lots of redundant relays.
        val (net, recs) = setup(1u, 2u, 3u, 4u)
        for (x in 1u..4u) for (y in (x + 1u)..4u) net.connect(x, y)
        advanceTimeBy(5000)
        talk(net.nodes.getValue(1u), 10)
        for (id in 2u..4u) assertEquals(10, recs.getValue(id).voiceFrom(1u).size, "node $id")
        assertTrue(net.nodes.values.sumOf { it.router.stats.duplicates.get() } > 0, "redundant paths were deduped")
    }

    @Test
    fun ttlLimitsVoiceReach() = runTest {
        // Line of 6: VOICE ttl=3 reaches 4 hops (origin + relays at ttl 3,2,1 → nodes 2..5).
        val ids = (1u..6u).toList().toUIntArray()
        val (net, recs) = setup(*ids)
        for (i in 1u..5u) net.connect(i, i + 1u)
        advanceTimeBy(5000)
        talk(net.nodes.getValue(1u), 5)
        assertEquals(5, recs.getValue(5u).voiceFrom(1u).size)
        assertEquals(0, recs.getValue(6u).voiceFrom(1u).size)
    }

    @Test
    fun lossyLinkStillDeliversMost() = runTest {
        val (net, recs) = setup(1u, 2u, 3u)
        net.connect(1u, 2u, params = LinkParams(loss = 0.1))
        net.connect(2u, 3u, params = LinkParams(loss = 0.1))
        advanceTimeBy(5000)
        talk(net.nodes.getValue(1u), 100)
        val got = recs.getValue(3u).voiceFrom(1u).size
        assertTrue(got in 70..95, "≈81% expected over two 10% links, got $got")
    }

    @Test
    fun relayAroundBrokenLink() = runTest {
        // Square 1-2-4 and 1-3-4. Break 2-4 mid-talk; 3 keeps carrying.
        val (net, recs) = setup(1u, 2u, 3u, 4u)
        net.connect(1u, 2u); net.connect(2u, 4u); net.connect(1u, 3u); net.connect(3u, 4u)
        advanceTimeBy(5000)
        val a = net.nodes.getValue(1u)
        assertTrue(a.pttDown())
        repeat(20) {
            if (it == 10) net.disconnect(2u, 4u)
            a.sendVoice(listOf(frame, frame)); advanceTimeBy(40)
        }
        a.pttUp(); advanceTimeBy(200)
        assertEquals(20, recs.getValue(4u).voiceFrom(1u).size)
    }

    @Test
    fun floorCollisionExactlyOneTalkerSurvives() = runTest {
        val (net, recs) = setup(1u, 2u, 3u)
        net.connect(1u, 2u); net.connect(2u, 3u); net.connect(1u, 3u)
        advanceTimeBy(5000)
        val n1 = net.nodes.getValue(1u)
        val n2 = net.nodes.getValue(2u)
        assertTrue(n1.pttDown())
        assertTrue(n2.pttDown())          // same instant: both granted locally
        advanceTimeBy(100)                // TALK_STARTs cross
        assertEquals(1, listOf(n1, n2).count { it.isTalking }, "exactly one talker")
        assertTrue(n1.isTalking, "equal priority and timestamp → lower nodeId wins")
        assertTrue(recs.getValue(2u).events.any { it == NodeEvent.MyTalkStopped(StopReason.COLLISION_LOST) })
        assertEquals(1u, net.nodes.getValue(3u).floor.currentTalker(0))
    }

    @Test
    fun floorBusyBlocksLateTalkerButHigherPriorityPreempts() = runTest {
        val (net, recs) = setup(1u, 2u)
        net.connect(1u, 2u)
        advanceTimeBy(5000)
        val n1 = net.nodes.getValue(1u)
        val n2 = net.nodes.getValue(2u)
        assertTrue(n1.pttDown())
        n1.sendVoice(listOf(frame, frame))
        advanceTimeBy(400)
        assertFalse(n2.pttDown(), "channel busy")
        n1.sendVoice(listOf(frame, frame))
        advanceTimeBy(20)
        assertTrue(n2.pttDown(TalkPriority.SUPERVISOR), "higher priority may talk over")
        advanceTimeBy(100)
        assertFalse(n1.isTalking, "normal talker preempted by supervisor")
        assertTrue(n2.isTalking)
        assertTrue(recs.getValue(1u).events.contains(NodeEvent.MyTalkStopped(StopReason.PREEMPTED)))
    }

    @Test
    fun maxTalkTimeAutoReleases() = runTest {
        val (net, recs) = setup(1u, 2u)
        net.connect(1u, 2u)
        advanceTimeBy(1000)
        val n1 = net.nodes.getValue(1u)
        assertTrue(n1.pttDown())
        advanceTimeBy(60_100)
        assertFalse(n1.isTalking)
        assertTrue(recs.getValue(1u).events.contains(NodeEvent.MyTalkStopped(StopReason.MAX_TALK_TIME)))
        assertTrue(recs.getValue(2u).events.any { it is NodeEvent.TalkEnded })
    }

    @Test
    fun otherGroupIsInvisible() = runTest {
        val net = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val a = net.addNode(1u, secret)
        val b = net.addNode(2u, ByteArray(32) { 1 })
        backgroundScope.launch { a.start(); b.start() }
        runCurrent()
        net.connect(1u, 2u)
        advanceTimeBy(5000)
        assertNull(a.neighbors.get(2u)?.name?.takeIf { it.isNotEmpty() }, "no HELLO content from foreign group")
        assertTrue(a.router.stats.authFailed.get() > 0)
    }

    @Test
    fun remoteNodeExpiresAfterTimeout() = runTest {
        val (net, _) = setup(1u, 2u, 3u)
        net.connect(1u, 2u); net.connect(2u, 3u)
        advanceTimeBy(5000)
        val a = net.nodes.getValue(1u)
        assertNotNull(a.neighbors.get(3u))
        net.kill(3u)
        advanceTimeBy(17_000)
        assertNull(a.neighbors.get(3u), "removed after NODE_TIMEOUT_MS")
        assertNotNull(a.neighbors.get(2u))
    }

    @Test
    fun voiceCarriesVoicePriorityOnTheWire() = runTest {
        val (net, _) = setup(1u, 2u)
        net.connect(1u, 2u)
        advanceTimeBy(1000)
        val t = net.transport(1u, TransportType.BLE)
        val before = t.sent
        val n1 = net.nodes.getValue(1u)
        n1.pttDown(); n1.sendVoice(listOf(frame, frame)); n1.pttUp()
        assertEquals(before + 3, t.sent)
        assertEquals(Priority.VOICE, com.chalkie.app.core.PacketType.VOICE.priority)
    }
}
