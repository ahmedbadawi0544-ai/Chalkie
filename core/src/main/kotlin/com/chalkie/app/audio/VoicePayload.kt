package com.chalkie.app.audio

import java.nio.ByteBuffer

/** Voice codecs. The id travels in every VOICE payload, so phones with different codecs interoperate. */
enum class Codec(val id: Int, val frameBytes: Int?) {
    /** G.711 μ-law, fixed 160 bytes per 20 ms frame. */
    G711_ULAW(0, 160),
    /** Opus (phase 5), variable-size frames, ~16 kbps. */
    OPUS(1, null);

    companion object {
        fun fromId(id: Int): Codec? = entries.firstOrNull { it.id == id }
    }
}

/**
 * `streamId(2) ‖ frameSeq(2) ‖ codec(1) ‖ frameCount(1) ‖ frames…` (SPEC §12).
 * Fixed-size codecs pack frames back to back; variable-size codecs prefix each with len(1).
 */
class VoicePayload(val streamId: Int, val frameSeq: Int, val codec: Codec, val frames: List<ByteArray>) {
    fun encode(): ByteArray {
        val fixed = codec.frameBytes
        val buf = ByteBuffer.allocate(6 + frames.sumOf { it.size } + if (fixed == null) frames.size else 0)
        buf.putShort(streamId.toShort()).putShort(frameSeq.toShort())
        buf.put(codec.id.toByte()).put(frames.size.toByte())
        frames.forEach {
            if (fixed != null) require(it.size == fixed) else { require(it.size in 1..255); buf.put(it.size.toByte()) }
            buf.put(it)
        }
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): VoicePayload? {
            if (bytes.size < 6) return null
            val buf = ByteBuffer.wrap(bytes)
            val streamId = buf.short.toInt() and 0xFFFF
            val seq = buf.short.toInt() and 0xFFFF
            val codec = Codec.fromId(buf.get().toInt() and 0xFF) ?: return null
            val count = buf.get().toInt() and 0xFF
            val fixed = codec.frameBytes
            if (fixed != null) {
                if (bytes.size != 6 + count * fixed) return null
                return VoicePayload(streamId, seq, codec, List(count) { ByteArray(fixed).also { f -> buf.get(f) } })
            }
            val frames = ArrayList<ByteArray>(count)
            repeat(count) {
                if (!buf.hasRemaining()) return null
                val len = buf.get().toInt() and 0xFF
                if (len == 0 || buf.remaining() < len) return null
                frames += ByteArray(len).also { f -> buf.get(f) }
            }
            if (buf.hasRemaining()) return null
            return VoicePayload(streamId, seq, codec, frames)
        }
    }
}

/** Decodes one stream's frames to PCM (8 kHz mono, one VOICE_FRAME_MS frame per call). Stateful per stream. */
interface FrameDecoder {
    fun decode(frame: ByteArray): ShortArray
    fun release() {}
}

/** Creates a decoder for a codec, or null if this phone cannot play it. */
fun interface DecoderFactory {
    fun create(codec: Codec): FrameDecoder?

    companion object {
        /** G.711 only: what pure Kotlin can decode. The app adds Opus via MediaCodec. */
        val G711_ONLY = DecoderFactory { codec -> if (codec == Codec.G711_ULAW) G711Decoder else null }
    }
}

object G711Decoder : FrameDecoder {
    override fun decode(frame: ByteArray): ShortArray = G711.decode(frame)
}

/** TALK_START = streamId(2) ‖ priority(1); TALK_END = streamId(2). */
object TalkPayload {
    fun start(streamId: Int, priority: TalkPriority): ByteArray =
        ByteBuffer.allocate(3).putShort(streamId.toShort()).put(priority.level.toByte()).array()

    fun end(streamId: Int): ByteArray = ByteBuffer.allocate(2).putShort(streamId.toShort()).array()

    fun streamId(bytes: ByteArray): Int? =
        if (bytes.size < 2) null else ByteBuffer.wrap(bytes).short.toInt() and 0xFFFF

    fun priority(bytes: ByteArray): TalkPriority =
        if (bytes.size < 3) TalkPriority.NORMAL else TalkPriority.fromLevel(bytes[2].toInt())
}

enum class TalkPriority(val level: Int) {
    NORMAL(0), SUPERVISOR(1), SOS(2);   // SOS: reserved, not used (SOS left out of this build)

    companion object {
        fun fromLevel(level: Int) = entries.firstOrNull { it.level == level } ?: NORMAL
    }
}
