package com.chalkie.app.routing

import com.chalkie.app.audio.Codec
import com.chalkie.app.audio.DecoderFactory
import com.chalkie.app.audio.FrameDecoder
import com.chalkie.app.audio.Playout
import com.chalkie.app.audio.VoicePayload
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.PacketType
import com.chalkie.app.core.Priority
import com.chalkie.app.election.ElectionManager
import com.chalkie.app.election.StarRole
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.LinkSendQueue
import com.chalkie.app.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §19 building blocks. */
class ReliabilityUnitTest {
    private var now = 0L

    private fun ble(rssi: Int, loss: Float = 0f) = LinkInfo(7u, TransportType.BLE, rssi, loss, null, now)

    @Test
    fun linkHealthFlagsFallingRssi() {
        val h = LinkHealth { now }
        h.onSample(ble(-55))
        repeat(10) { now += 1000; h.onSample(ble(-55)) }
        assertFalse(h.isDegrading(7u, TransportType.BLE), "steady signal")
        var turned = false
        var rssi = -55
        repeat(6) {
            now += 1000
            rssi -= 3                                   // -6 dB per 2 s
            if (h.onSample(ble(rssi))) turned = true
        }
        assertTrue(turned && h.isDegrading(7u, TransportType.BLE), "slope ${h.slope(7u, TransportType.BLE)}")
        repeat(15) { now += 1000; h.onSample(ble(rssi)) }
        assertFalse(h.isDegrading(7u, TransportType.BLE), "settled at the new level")
    }

    @Test
    fun linkHealthFlagsLossOverTenPercent() {
        val h = LinkHealth { now }
        assertFalse(h.onSample(ble(-60, 0.05f)))
        now += 2000
        repeat(5) { h.onSample(ble(-60, 0.25f)); now += 2000 }
        assertTrue(h.isDegrading(7u, TransportType.BLE))
        h.onLinkDown(TransportType.BLE, 7u)
        assertFalse(h.isDegrading(7u, TransportType.BLE), "forgotten when the link drops")
    }

    @Test
    fun voicePayloadCarriesRedundantFrames() {
        val f = List(2) { i -> ByteArray(160) { (i + 1).toByte() } }
        val r = List(2) { i -> ByteArray(160) { (i + 10).toByte() } }
        val back = assertNotNull(VoicePayload.decode(VoicePayload(5, 1, Codec.G711_ULAW, f, r).encode()))
        assertEquals(1, back.frameSeq)
        assertEquals(0xFFFF, back.redundantSeq, "wraps below zero")
        back.frames.zip(f).forEach { (a, b) -> assertContentEquals(b, a) }
        back.redundant.zip(r).forEach { (a, b) -> assertContentEquals(b, a) }

        val opus = VoicePayload(5, 8, Codec.OPUS, listOf(ByteArray(40), ByteArray(33)), listOf(ByteArray(41), ByteArray(12)))
        val o = assertNotNull(VoicePayload.decode(opus.encode()))
        assertEquals(listOf(41, 12), o.redundant.map { it.size })
        assertEquals(6, o.redundantSeq)
        assertNull(VoicePayload.decode(opus.encode().copyOf(opus.encode().size - 1)), "truncated redundant block")
        assertTrue(VoicePayload.decode(VoicePayload(5, 8, Codec.G711_ULAW, f).encode())!!.redundant.isEmpty())
    }

    @Test
    fun redundancyFillsASingleLostPacket() {
        val decoder = object : FrameDecoder { override fun decode(frame: ByteArray) = ShortArray(160) { frame[0].toShort() } }
        val p = Playout(DecoderFactory { decoder })
        fun frames(seq: Int) = List(2) { i -> ByteArray(160) { (seq + i + 1).toByte() } }
        p.onVoice(1u, VoicePayload(3, 0, Codec.G711_ULAW, frames(0)))
        // Packet with frames 2..3 is lost; the next one carries them as redundancy.
        p.onVoice(1u, VoicePayload(3, 4, Codec.G711_ULAW, frames(4), frames(2)))
        p.onTalkEnd(1u, 3)
        val out = generateSequence { p.nextFrame() }.take(20).toList().map { it[0].toInt() }.filter { it != 0 }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), out, "every frame played, none concealed")
    }

    @Test
    fun sendQueueReportsLoss() {
        val q = LinkSendQueue(maxPerPriority = 4)
        repeat(10) { q.offer(ByteArray(1), Priority.VOICE, 0) }    // 6 refused
        assertEquals(0f, q.lossRate(0), "no full window yet")
        assertEquals(0.6f, q.lossRate(ChalkieConfig.LINK_STATS_WINDOW_MS))
    }

    @Test
    fun criticalTypes() {
        val critical = PacketType.entries.filter { it.critical }.toSet()
        assertEquals(setOf(PacketType.SOS, PacketType.SOS_ACK, PacketType.TALK_START, PacketType.TALK_END, PacketType.TALK_ACK), critical)
    }

    @Test
    fun standbyClaimsTheMomentItsWdLinkToTheHubIsGone() {
        val table = NeighborTable { now }
        val em = ElectionManager(2u, { now }, table)
        table.onLinkUp(LinkInfo(1u, TransportType.WD, null, 0f, null, 0))
        table.onLinkUp(LinkInfo(3u, TransportType.BLE, -60, 0f, null, 0))
        fun hellos() {
            val hub = HelloPayload("hub", 100, true, roles = setOf(Role.HUB), hubId = 1u, hubEpoch = 4, hubScore = 0.9f)
            table.onHello(1u, hub, 0); em.onHello(1u, hub)
            val n3 = HelloPayload("n3", 50, false, hubId = 1u, hubEpoch = 4, hubScore = 0.3f)
            table.onHello(3u, n3, 0); em.onHello(3u, n3)
        }
        hellos()
        em.computeMyScore(80, false)
        repeat(4) { now += 250; assertNull(em.tick()) }
        assertEquals(StarRole.Leaf(1u, 4, standbyId = 2u), em.role, "I am the standby")

        now += 250
        table.onLinkDown(TransportType.WD, 1u)                   // hub gone, HELLO still fresh
        assertEquals(5 to em.publishedScore, em.tick(), "claim at once with the derived epoch")
        assertEquals(StarRole.Hub(5), em.role)
        now += 250; em.tick()
        assertEquals(StarRole.Hub(5, standbyId = 3u), em.role, "and a new standby")
    }

    @Test
    fun leafSwitchesToTheStandbyAndRevertsIfTheHubWasFine() {
        val table = NeighborTable { now }
        val em = ElectionManager(3u, { now }, table)
        table.onLinkUp(LinkInfo(1u, TransportType.WD, null, 0f, null, 0))
        val hub = HelloPayload("hub", 100, true, roles = setOf(Role.HUB), hubId = 1u, hubEpoch = 4, hubScore = 0.9f)
        val sb = HelloPayload("sb", 90, false, hubId = 1u, hubEpoch = 4, hubScore = 0.7f)
        table.onHello(1u, hub, 0); em.onHello(1u, hub)
        table.onHello(2u, sb, 1); em.onHello(2u, sb)
        em.computeMyScore(30, false)
        now += 250; em.tick()
        assertEquals(StarRole.Leaf(1u, 4, standbyId = 2u), em.role)

        now += 250
        table.onLinkDown(TransportType.WD, 1u)
        assertNull(em.tick())
        assertEquals(StarRole.Leaf(2u, 5), em.role, "straight to the standby, at the epoch it will claim")

        // Only my link broke: the hub is still hub at epoch 4 → go back.
        table.onHello(1u, hub, 1); em.onHello(1u, hub)
        assertEquals(1u, (em.role as StarRole.Leaf).hubId)

        // Real loss this time, and the standby confirms with its claim.
        table.onLinkUp(LinkInfo(1u, TransportType.WD, null, 0f, null, now))
        now += 250; em.tick()
        table.onLinkDown(TransportType.WD, 1u)
        now += 250; em.tick()
        em.onClaim(2u, 5, 0.7f)
        now += ChalkieConfig.STANDBY_CONFIRM_MS + 500
        table.onHello(2u, sb.copy(roles = setOf(Role.HUB), hubId = 2u, hubEpoch = 5), 1)
        em.tick()
        assertEquals(2u to 5, (em.role as StarRole.Leaf).let { it.hubId to it.epoch }, "confirmed, not timed out")
    }
}
