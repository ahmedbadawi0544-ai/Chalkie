package com.chalkie.app.audio

import com.chalkie.app.core.ChalkieConfig

/**
 * Receive side of voice: one JitterBuffer + decoder per (srcId, streamId), mixed.
 * The audio thread calls [nextFrame] every VOICE_FRAME_MS.
 */
class Playout(
    private val decoders: DecoderFactory = DecoderFactory.G711_ONLY,
    private val samplesPerFrame: Int = ChalkieConfig.SAMPLES_PER_FRAME,
) {
    private data class Key(val srcId: UInt, val streamId: Int)

    private class Stream(val decoder: FrameDecoder?, val jb: JitterBuffer = JitterBuffer())

    private val streams = LinkedHashMap<Key, Stream>()

    /** Frames dropped because this phone has no decoder for their codec. */
    var undecodable = 0L
        private set

    @Synchronized
    fun onVoice(srcId: UInt, payload: VoicePayload) {
        val s = streams.getOrPut(Key(srcId, payload.streamId)) { Stream(decoders.create(payload.codec)) }
        if (s.decoder == null) { undecodable += payload.frames.size; return }
        // Redundant copies first: the buffer ignores frames it already has or that are too late.
        payload.redundant.forEachIndexed { i, f -> s.jb.push((payload.redundantSeq + i) and 0xFFFF, f) }
        payload.frames.forEachIndexed { i, f -> s.jb.push((payload.frameSeq + i) and 0xFFFF, f) }
    }

    @Synchronized
    fun onTalkEnd(srcId: UInt, streamId: Int) {
        val key = Key(srcId, streamId)
        val s = streams[key] ?: return
        if (s.decoder == null) streams.remove(key) else s.jb.end()
    }

    /** Stops every stream now (e.g. a higher-priority talk took over). */
    @Synchronized
    fun clear() {
        streams.values.forEach { it.decoder?.release() }
        streams.clear()
    }

    @Synchronized
    fun activeStreams(): Int = streams.size

    /** Mixed PCM for the next tick, or null when nothing is playing. */
    @Synchronized
    fun nextFrame(): ShortArray? {
        if (streams.isEmpty()) return null
        val pcm = ArrayList<ShortArray>(streams.size)
        val it = streams.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val d = s.decoder
            if (d != null) s.jb.pull()?.let { f -> pcm += d.decode(f) }
            if (d == null || s.jb.isFinished) { d?.release(); it.remove() }
        }
        return if (pcm.isEmpty()) ShortArray(samplesPerFrame) else Mixer.mix(pcm, samplesPerFrame)
    }
}
