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
 * `streamId(2) ‖ frameSeq(2) ‖ codec(1) ‖ frameCount(1) ‖ frames… [‖ redCount(1) ‖ redFrames…]` (SPEC §12, §19.4).
 * Fixed-size codecs pack frames back to back; variable-size codecs prefix each with len(1).
 *
 * RFC 2198-style redundancy: when bit 7 of the codec byte is set, the packet also carries
 * [redundant] = the frames just before [frameSeq] (seq `frameSeq - redCount` …), so a receiver
 * can fill one lost packet from the next.
 */
class VoicePayload(
    val streamId: Int,
    val frameSeq: Int,
    val codec: Codec,
    val frames: List<ByteArray>,
    val redundant: List<ByteArray> = emptyList(),
) {
    /** Sequence number of the first redundant frame. */
    val redundantSeq: Int get() = (frameSeq - redundant.size) and 0xFFFF

    fun encode(): ByteArray {
        val fixed = codec.frameBytes
        val red = redundant.isNotEmpty()
        fun size(list: List<ByteArray>) = list.sumOf { it.size } + if (fixed == null) list.size else 0
        val buf = ByteBuffer.allocate(6 + size(frames) + if (red) 1 + size(redundant) else 0)
        buf.putShort(streamId.toShort()).putShort(frameSeq.toShort())
        buf.put((codec.id or if (red) RED_FLAG else 0).toByte()).put(frames.size.toByte())
        fun put(f: ByteArray) {
            if (fixed != null) require(f.size == fixed) else { require(f.size in 1..255); buf.put(f.size.toByte()) }
            buf.put(f)
        }
        frames.forEach(::put)
        if (red) {
            buf.put(redundant.size.toByte())
            redundant.forEach(::put)
        }
        return buf.array()
    }

    companion object {
        private const val RED_FLAG = 0x80

        fun decode(bytes: ByteArray): VoicePayload? {
            if (bytes.size < 6) return null
            val buf = ByteBuffer.wrap(bytes)
            val streamId = buf.short.toInt() and 0xFFFF
            val seq = buf.short.toInt() and 0xFFFF
            val codecByte = buf.get().toInt() and 0xFF
            val codec = Codec.fromId(codecByte and RED_FLAG.inv()) ?: return null
            val count = buf.get().toInt() and 0xFF
            val frames = readFrames(buf, codec, count) ?: return null
            val redundant = if (codecByte and RED_FLAG != 0) {
                if (!buf.hasRemaining()) return null
                readFrames(buf, codec, buf.get().toInt() and 0xFF) ?: return null
            } else {
                emptyList()
            }
            if (buf.hasRemaining()) return null
            return VoicePayload(streamId, seq, codec, frames, redundant)
        }

        private fun readFrames(buf: ByteBuffer, codec: Codec, count: Int): List<ByteArray>? {
            val fixed = codec.frameBytes
            val frames = ArrayList<ByteArray>(count)
            repeat(count) {
                val len = if (fixed != null) fixed else {
                    if (!buf.hasRemaining()) return null
                    buf.get().toInt() and 0xFF
                }
                if (len == 0 || buf.remaining() < len) return null
                frames += ByteArray(len).also { f -> buf.get(f) }
            }
            return frames
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
