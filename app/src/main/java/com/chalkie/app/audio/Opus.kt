package com.chalkie.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.chalkie.app.core.ChalkieConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus over the platform MediaCodec (SPEC §12 phase 5; encoder present since Android 10).
 * 8 kHz mono in, one ~40-byte packet per 20 ms frame out (≈16 kbps vs 64 kbps for G.711).
 */
class OpusEncoder private constructor(private val codec: MediaCodec) {
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    /** Feeds one 20 ms PCM frame; returns the packets that came out (usually one). */
    fun encode(pcm: ShortArray): List<ByteArray> {
        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            buf.clear()
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            codec.queueInputBuffer(inIdx, 0, pcm.size * 2, ptsUs, 0)
            ptsUs += ChalkieConfig.VOICE_FRAME_MS * 1000L
        }
        val out = ArrayList<ByteArray>(1)
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size in 1..MAX_PACKET) {
                val b = codec.getOutputBuffer(idx)!!
                b.position(info.offset)
                out += ByteArray(info.size).also { b.get(it) }
            }
            codec.releaseOutputBuffer(idx, false)
        }
        return out
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        private const val TAG = "Opus"
        private const val TIMEOUT_US = 5_000L
        /** Frames travel with a 1-byte length (VoicePayload). */
        private const val MAX_PACKET = 255

        fun create(): OpusEncoder? = try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, ChalkieConfig.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, ChalkieConfig.OPUS_BITRATE)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
            } catch (e: Exception) {
                c.release(); throw e
            }
            OpusEncoder(c)
        } catch (e: Exception) {
            Log.w(TAG, "no Opus encoder: ${e.message}")
            null
        }
    }
}

/**
 * Opus decoder for one stream. The platform decoder may output 48 kHz and lag a frame, so
 * output goes through a FIFO and is decimated to 8 kHz.
 */
class OpusDecoder private constructor(private val codec: MediaCodec) : FrameDecoder {
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var rate = 48_000
    private var channels = 1
    private val fifo = ShortArray(FRAME * 8)
    private var fifoLen = 0

    override fun decode(frame: ByteArray): ShortArray {
        try {
            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(frame)
                codec.queueInputBuffer(inIdx, 0, frame.size, ptsUs, 0)
                ptsUs += ChalkieConfig.VOICE_FRAME_MS * 1000L
            }
            var wait = TIMEOUT_US
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                        rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    idx < 0 -> break
                    else -> {
                        val b = codec.getOutputBuffer(idx)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        push(b.slice().order(ByteOrder.nativeOrder()))
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.w("Opus", "decode failed: ${e.message}")
        }
        val out = ShortArray(FRAME)
        val n = minOf(FRAME, fifoLen)
        System.arraycopy(fifo, 0, out, 0, n)
        System.arraycopy(fifo, n, fifo, 0, fifoLen - n)
        fifoLen -= n
        return out
    }

    /** Down-mix and decimate to 8 kHz by block averaging. */
    private fun push(pcm: ByteBuffer) {
        val s = pcm.asShortBuffer()
        val step = maxOf(1, rate / ChalkieConfig.SAMPLE_RATE) * channels
        while (s.remaining() >= step && fifoLen < fifo.size) {
            var sum = 0
            repeat(step) { sum += s.get() }
            fifo[fifoLen++] = (sum / step).toShort()
        }
    }

    override fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        private const val TIMEOUT_US = 2_000L
        private const val FRAME = ChalkieConfig.SAMPLES_PER_FRAME

        fun create(): OpusDecoder? = try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, ChalkieConfig.SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", opusHead())
                setByteBuffer("csd-1", le64(0))                 // codec delay, ns
                setByteBuffer("csd-2", le64(80_000_000))        // seek pre-roll, ns
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try {
                c.configure(fmt, null, null, 0)
                c.start()
            } catch (e: Exception) {
                c.release(); throw e
            }
            OpusDecoder(c)
        } catch (e: Exception) {
            Log.w("Opus", "no Opus decoder: ${e.message}")
            null
        }

        /** RFC 7845 identification header: mono, no pre-skip, 8 kHz input. */
        private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1).put(1)                    // version, channels
            putShort(0)                       // pre-skip
            putInt(ChalkieConfig.SAMPLE_RATE)
            putShort(0)                       // output gain
            put(0)                            // mapping family
            flip()
        }

        private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }
    }
}

/** Decoders for every codec this phone can play. */
val PlatformDecoders = DecoderFactory { codec ->
    when (codec) {
        Codec.G711_ULAW -> G711Decoder
        Codec.OPUS -> OpusDecoder.create()
    }
}
