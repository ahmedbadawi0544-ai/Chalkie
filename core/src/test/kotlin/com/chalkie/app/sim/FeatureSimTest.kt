package com.chalkie.app.sim

import com.chalkie.app.audio.Codec
import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 4/5 features on the simulator: channels, private calls, texts, supervisor, voice messages. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureSimTest {
    private val secret = ByteArray(32) { 42 }
    private val frame = ByteArray(160) { 0x7F }

    private class Net(val sim: SimNetwork, val events: Map<UInt, MutableList<NodeEvent>>) {
        fun node(id: UInt): ChalkieNode = sim.nodes.getValue(id)
        fun voice(at: UInt, from: UInt) = events.getValue(at).filterIsInstance<NodeEvent.VoiceIn>().count { it.srcId == from }
        fun texts(at: UInt) = events.getValue(at).filterIsInstance<NodeEvent.TextIn>()
        inline fun <reified T : NodeEvent> of(at: UInt) = events.getValue(at).filterIsInstance<T>()
    }

    private fun TestScope.build(vararg ids: UInt): Net {
        val sim = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val events = ids.associateWith { mutableListOf<NodeEvent>() }
        for (id in ids) {
            val node = sim.addNode(id, secret)
            backgroundScope.launch { node.events.collect { events.getValue(id) += it } }
        }
        runCurrent()
        backgroundScope.launch { sim.nodes.values.forEach { it.start() } }
        runCurrent()
        return Net(sim, events)
    }

    private fun TestScope.talk(node: ChalkieNode, packets: Int, target: UInt? = null, release: Boolean = true) {
        assertTrue(node.pttDown(target = target), "floor should be granted")
        repeat(packets) {
            node.sendVoice(listOf(frame, frame))
            advanceTimeBy(40)
        }
        if (release) node.pttUp()
        advanceTimeBy(300)
    }

    @Test
    fun channelsOnlyReachTheirListenersButGeneralReachesAll() = runTest {
        val net = build(1u, 2u, 3u)
        net.sim.connect(1u, 2u); net.sim.connect(2u, 3u); net.sim.connect(1u, 3u)
        net.node(1u).selectedChannel = 3
        net.node(2u).selectedChannel = 3
        net.node(3u).selectedChannel = 5
        advanceTimeBy(5000)

        talk(net.node(1u), 10)
        assertEquals(10, net.voice(2u, 1u))
        assertEquals(0, net.voice(3u, 1u), "3 is on another channel")

        net.node(3u).selectedChannel = 0
        talk(net.node(3u), 10)
        assertEquals(10, net.voice(1u, 3u), "General (0) reaches everyone")
        assertEquals(10, net.voice(2u, 3u))
    }

    @Test
    fun privateCallOnlyHeardByTargetAndNotBlockedByBusyChannel() = runTest {
        val net = build(1u, 2u, 3u)
        net.sim.connect(1u, 2u); net.sim.connect(2u, 3u); net.sim.connect(1u, 3u)
        advanceTimeBy(5000)

        // 3 holds the channel; 1 can still call 2 privately.
        assertTrue(net.node(3u).pttDown())
        net.node(3u).sendVoice(listOf(frame, frame))
        advanceTimeBy(40)
        assertFalse(net.node(1u).pttDown(), "channel busy")
        talk(net.node(1u), 10, target = 2u)
        net.node(3u).pttUp()
        advanceTimeBy(300)

        assertEquals(10, net.voice(2u, 1u))
        assertEquals(0, net.voice(3u, 1u), "private: 3 never hears it")
        assertTrue(net.of<NodeEvent.TalkStarted>(2u).any { it.srcId == 1u && it.private })
    }

    @Test
    fun unicastFollowsTheShortestPath() = runTest {
        val net = build(1u, 2u, 3u, 4u)
        net.sim.connect(1u, 2u); net.sim.connect(2u, 3u); net.sim.connect(3u, 4u)
        advanceTimeBy(8000)
        val before = net.sim.totalSent(TransportType.BLE, PacketType.TEXT)

        assertTrue(net.node(1u).sendText("Need help", target = 4u))
        advanceTimeBy(500)
        assertEquals(listOf("Need help"), net.texts(4u).map { it.text })
        assertTrue(net.texts(4u).single().private)
        assertTrue(net.texts(2u).isEmpty() && net.texts(3u).isEmpty(), "relays do not deliver")
        assertEquals(3, net.sim.totalSent(TransportType.BLE, PacketType.TEXT) - before, "one send per hop, no flood")
    }

    @Test
    fun channelTextReachesListeners() = runTest {
        val net = build(1u, 2u, 3u)
        net.sim.connect(1u, 2u); net.sim.connect(2u, 3u)
        net.node(1u).selectedChannel = 2
        net.node(2u).selectedChannel = 2
        net.node(3u).selectedChannel = 4
        advanceTimeBy(5000)
        assertTrue(net.node(1u).sendText("Arrived at FDB"))
        advanceTimeBy(500)
        assertEquals("Arrived at FDB", net.texts(2u).single().text)
        assertEquals(2, net.texts(2u).single().channel)
        assertTrue(net.texts(3u).isEmpty(), "3 listens to channel 4 + General only")
    }

    @Test
    fun supervisorPreemptsANormalTalker() = runTest {
        val net = build(1u, 2u, 3u)
        net.sim.connect(1u, 2u); net.sim.connect(2u, 3u); net.sim.connect(1u, 3u)
        net.node(2u).supervisor = true
        advanceTimeBy(5000)

        assertTrue(net.node(1u).pttDown())
        net.node(1u).sendVoice(listOf(frame, frame))
        advanceTimeBy(100)
        assertFalse(net.node(3u).pttDown(), "normal user gets busy")
        talk(net.node(2u), 5)
        assertTrue(net.of<NodeEvent.MyTalkStopped>(1u).any { it.reason == StopReason.PREEMPTED })
        assertFalse(net.node(1u).isTalking)
        assertEquals(5, net.voice(3u, 2u))
    }

    @Test
    fun voiceMessageWaitsForTheRecipientThenArrivesComplete() = runTest {
        val net = build(1u, 2u, 3u)
        net.sim.connect(1u, 2u, params = LinkParams(loss = 0.15))
        advanceTimeBy(5000)

        // 3 is out of reach: 1 records a message for it anyway (Opus-sized frames, 5 s).
        val frames = List(250) { i -> ByteArray(40) { (i + it).toByte() } }
        val msg = net.node(1u).sendVoiceMessage(3u, Codec.OPUS, frames, createdAt = 1234L)
        advanceTimeBy(20_000)
        assertTrue(net.of<NodeEvent.VoiceMessageIn>(3u).isEmpty(), "3 is unreachable")
        assertEquals(1, net.node(1u).mailbox.pending().size, "kept on the sender")

        net.sim.connect(2u, 3u, params = LinkParams(loss = 0.15))     // 3 comes back, two hops away, lossy
        advanceTimeBy(60_000)
        val got = net.of<NodeEvent.VoiceMessageIn>(3u).single().message
        assertEquals(msg.uid, got.uid)
        assertEquals(1u, got.from)
        assertEquals(Codec.OPUS, got.codec)
        assertEquals(1234L, got.createdAt)
        assertEquals(frames.size, got.frames.size)
        frames.zip(got.frames).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals(msg.uid, net.of<NodeEvent.VoiceMessageDelivered>(1u).single().uid)
        assertTrue(net.node(1u).mailbox.pending().isEmpty())
        assertTrue(net.of<NodeEvent.VoiceMessageIn>(2u).isEmpty(), "relay does not get it")
    }
}
