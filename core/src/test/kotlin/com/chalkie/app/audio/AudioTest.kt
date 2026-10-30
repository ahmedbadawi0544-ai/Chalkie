package com.chalkie.app.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTest {
    @Test
    fun g711RoundTripIsClose() {
        val pcm = ShortArray(160) { (it * 400 - 32000).toShort() }
        val back = G711.decode(G711.encode(pcm))
        for (i in pcm.indices) {
            val err = abs(pcm[i] - back[i])
            assertTrue(err <= maxOf(16, abs(pcm[i].toInt()) / 16), "sample $i: ${pcm[i]} → ${back[i]}")
        }
        assertEquals(0xFF.toByte(), G711.encodeOne(0))
    }

    @Test
    fun voicePayloadRoundTrip() {
        val frames = List(2) { n -> ByteArray(160) { (it + n).toByte() } }
        val back = assertNotNull(VoicePayload.decode(VoicePayload(65000, 65535, Codec.G711_ULAW, frames).encode()))
        assertEquals(65000, back.streamId)
        assertEquals(65535, back.frameSeq)
        assertEquals(2, back.frames.size)
        assertContentEquals(frames[1], back.frames[1])
        assertNull(VoicePayload.decode(ByteArray(7)))
    }

    private fun f(n: Int) = ByteArray(160) { n.toByte() }

    @Test
    fun jitterBufferReordersAndWaitsForTarget() {
        val jb = JitterBuffer()
        jb.push(1, f(1)); jb.push(0, f(0))
        assertNull(jb.pull(), "still buffering")
        jb.push(3, f(3)); jb.push(2, f(2))
        assertEquals(0, jb.pull()!![0].toInt())
        assertEquals(1, jb.pull()!![0].toInt())
        assertEquals(2, jb.pull()!![0].toInt())
        assertEquals(3, jb.pull()!![0].toInt())
    }

    @Test
    fun jitterBufferConcealsOnePacketThenSilenceAndDropsLate() {
        val jb = JitterBuffer()
        (0..3).forEach { jb.push(it, f(it)) }
        repeat(4) { jb.pull() }
        val repeated = jb.pull()
        assertEquals(3, repeated!![0].toInt(), "missing frame repeats last")
        assertEquals(3, jb.pull()!![0].toInt(), "for a whole packet (2 frames)")
        assertNull(jb.pull(), "then silence")
        jb.push(4, f(4))
        assertEquals(1, jb.lateDropped)
    }

    @Test
    fun jitterBufferHandlesSeqWrap() {
        val jb = JitterBuffer()
        listOf(65534, 65535, 0, 1).forEach { jb.push(it, f(it and 0xFF)) }
        val out = List(4) { jb.pull()!![0].toInt() and 0xFF }
        assertEquals(listOf(0xFE, 0xFF, 0, 1), out)
    }

    @Test
    fun jitterBufferBoundsLatency() {
        val jb = JitterBuffer()
        (0..3).forEach { jb.push(it, f(it)) }
        jb.pull()
        (4..40).forEach { jb.push(it, f(it)) }   // burst far beyond 300 ms
        val next = jb.pull()!![0].toInt()
        assertTrue(next >= 40 - 4, "skipped ahead, got $next")
    }

    @Test
    fun shortStreamStillPlaysAfterEnd() {
        val jb = JitterBuffer()
        jb.push(0, f(9))
        jb.end()
        assertEquals(9, jb.pull()!![0].toInt())
        assertTrue(jb.isFinished)
    }

    @Test
    fun mixerClips() {
        val out = Mixer.mix(listOf(ShortArray(2) { 30000 }, ShortArray(2) { 30000 }), 2)
        assertEquals(Short.MAX_VALUE, out[0])
    }

    @Test
    fun playoutMixesAndFinishes() {
        val p = Playout()
        val frame = G711.encode(ShortArray(160) { 1000 })
        p.onVoice(1u, VoicePayload(1, 0, Codec.G711_ULAW, listOf(frame, frame)))
        p.onVoice(2u, VoicePayload(7, 0, Codec.G711_ULAW, listOf(frame, frame)))
        p.onTalkEnd(1u, 1); p.onTalkEnd(2u, 7)
        assertEquals(2, p.activeStreams())
        val mixed = assertNotNull(p.nextFrame())
        assertTrue(mixed[0] in 1900..2100, "two streams summed: ${mixed[0]}")
        repeat(5) { p.nextFrame() }
        assertEquals(0, p.activeStreams())
        assertNull(p.nextFrame())
    }

    @Test
    fun tonesAreNonEmpty() {
        listOf(Tones.roger, Tones.permit, Tones.busy, Tones.warning, Tones.sosAlarm).forEach {
            assertTrue(it.isNotEmpty() && it.any { s -> s != 0.toShort() })
        }
    }

    @Test
    fun opusPayloadCarriesVariableFrames() {
        val frames = listOf(ByteArray(37) { 1 }, ByteArray(52) { 2 })
        val back = assertNotNull(VoicePayload.decode(VoicePayload(9, 4, Codec.OPUS, frames).encode()))
        assertEquals(Codec.OPUS, back.codec)
        assertEquals(listOf(37, 52), back.frames.map { it.size })
        assertNull(VoicePayload.decode(VoicePayload(9, 4, Codec.OPUS, frames).encode().copyOf(50)), "truncated")
    }

    @Test
    fun playoutSkipsCodecsItCannotDecode() {
        val p = Playout()      // G.711 only
        p.onVoice(1u, VoicePayload(1, 0, Codec.OPUS, listOf(ByteArray(40), ByteArray(40))))
        assertEquals(2, p.undecodable)
        p.onTalkEnd(1u, 1)
        assertEquals(0, p.activeStreams())
    }
}
