package com.chalkie.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.node.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Android audio around the pure-Kotlin voice path (SPEC §12):
 * mic → Opus (or G.711) → node.sendVoice, and node VOICE → Playout (jitter + decode + mix) → AudioTrack.
 * The same capture path records voice messages (phase 5).
 */
class AudioEngine(
    context: Context,
    private val node: ChalkieNode,
    private val scope: CoroutineScope,
    /** Prefer Opus when this phone has an encoder (settings). */
    private val useOpus: () -> Boolean = { true },
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playout = Playout(PlatformDecoders)

    /** What the capture thread does with frames: live talk, or a voice message being recorded. */
    private enum class Sink { TALK, RECORD }

    @Volatile private var sink = Sink.TALK
    private val recorded = ArrayList<ByteArray>()
    @Volatile private var recordedCodec = Codec.G711_ULAW
    private val toneQueue = ConcurrentLinkedQueue<ShortArray>()
    private var tone: ShortArray? = null
    private var tonePos = 0

    @Volatile private var running = false
    @Volatile private var capturing = false
    private var playThread: Thread? = null
    private var captureThread: Thread? = null
    private var eventsJob: Job? = null
    private var savedMode = AudioManager.MODE_NORMAL

    fun start() {
        if (running) return
        running = true
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput()
        eventsJob = scope.launch { node.events.collect(::onNodeEvent) }
        playThread = thread(name = "chalkie-play", priority = Thread.MAX_PRIORITY) { playLoop() }
    }

    fun stop() {
        running = false
        capturing = false
        eventsJob?.cancel()
        playThread?.join(500)
        captureThread?.join(500)
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 31) audioManager.isSpeakerphoneOn = false
        audioManager.mode = savedMode
    }

    /** PTT pressed. [target] = private call. Returns false (and plays the busy tone) if the floor is taken. */
    fun pttDown(target: UInt? = null): Boolean {
        if (capturing) return node.isTalking
        if (!node.pttDown(target = target)) {
            playTone(Tones.busy)
            return false
        }
        playTone(Tones.permit)
        sink = Sink.TALK
        startCapture()
        return true
    }

    /** Starts recording a voice message (max VMSG_MAX_MS). False if the mic is busy. */
    fun startRecording(): Boolean {
        if (capturing) return false
        synchronized(recorded) { recorded.clear() }
        playTone(Tones.permit)
        sink = Sink.RECORD
        startCapture()
        return true
    }

    /** Stops recording; returns the codec and encoded frames, or null if nothing was recorded. */
    fun stopRecording(): Pair<Codec, List<ByteArray>>? {
        if (sink != Sink.RECORD) return null
        capturing = false
        captureThread?.join(500)
        sink = Sink.TALK
        val frames = synchronized(recorded) { recorded.toList().also { recorded.clear() } }
        return if (frames.isEmpty()) null else recordedCodec to frames
    }

    /** Plays a stored voice message through the tone path. */
    fun playMessage(codec: Codec, frames: List<ByteArray>) {
        scope.launch {
            val decoder = PlatformDecoders.create(codec) ?: return@launch
            try {
                val pcm = ShortArray(frames.size * ChalkieConfig.SAMPLES_PER_FRAME)
                frames.forEachIndexed { i, f ->
                    decoder.decode(f).copyInto(pcm, i * ChalkieConfig.SAMPLES_PER_FRAME)
                }
                playTone(pcm)
            } finally {
                decoder.release()
            }
        }
    }

    fun pttUp() {
        capturing = false
        node.pttUp()
    }

    fun playTone(pcm: ShortArray) {
        toneQueue += pcm
    }

    /** Re-evaluate output when a headset connects or disconnects. */
    fun routeOutput() {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val target = headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            target?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        }
    }

    private fun onNodeEvent(e: NodeEvent) {
        when (e) {
            is NodeEvent.VoiceIn -> playout.onVoice(e.srcId, e.payload)
            is NodeEvent.TalkEnded -> {
                playout.onTalkEnd(e.srcId, e.streamId)
                playTone(Tones.roger)
            }
            is NodeEvent.MyTalkStopped -> {
                if (sink == Sink.TALK) capturing = false
                playTone(if (e.reason == StopReason.MAX_TALK_TIME) Tones.warning else Tones.busy)
            }
            is NodeEvent.TextIn, is NodeEvent.VoiceMessageIn -> playTone(Tones.roger)
            else -> Unit
        }
    }

    // ---- capture ----

    @SuppressLint("MissingPermission")   // RECORD_AUDIO is granted before the service starts.
    private fun startCapture() {
        if (capturing) return
        capturing = true
        captureThread = thread(name = "chalkie-capture", priority = Thread.MAX_PRIORITY) {
            val frame = ChalkieConfig.SAMPLES_PER_FRAME
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, ENC)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, ENC,
                    maxOf(minBuf, frame * 2 * 4))
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord failed", e); capturing = false; node.pttUp(); return@thread
            }
            val effects = enableEffects(rec.audioSessionId)
            val opus = if (useOpus()) OpusEncoder.create() else null
            val codec = if (opus != null) Codec.OPUS else Codec.G711_ULAW
            recordedCodec = codec
            val maxFrames = (ChalkieConfig.VMSG_MAX_MS / ChalkieConfig.VOICE_FRAME_MS).toInt()
            try {
                rec.startRecording()
                val pcm = ShortArray(frame)
                val pending = ArrayList<ByteArray>(ChalkieConfig.FRAMES_PER_PACKET)
                while (capturing && (sink == Sink.RECORD || node.isTalking)) {
                    var read = 0
                    while (read < frame) {
                        val n = rec.read(pcm, read, frame - read)
                        if (n <= 0) throw IllegalStateException("AudioRecord.read=$n")
                        read += n
                    }
                    val encoded = opus?.encode(pcm) ?: listOf(G711.encode(pcm))
                    if (sink == Sink.RECORD) {
                        val full = synchronized(recorded) { recorded += encoded; recorded.size >= maxFrames }
                        if (full) { playTone(Tones.warning); break }
                        continue
                    }
                    for (f in encoded) {
                        pending += f
                        if (pending.size == ChalkieConfig.FRAMES_PER_PACKET) {
                            node.sendVoice(pending.toList(), codec)
                            pending.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture stopped", e)
            } finally {
                runCatching { rec.stop() }
                rec.release()
                effects.forEach { it.release() }
                opus?.release()
                capturing = false
            }
        }
    }

    private fun enableEffects(session: Int): List<AudioEffect> = buildList {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.also { it.enabled = true; add(it) }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.also { it.enabled = true; add(it) }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.also { it.enabled = true; add(it) }
    }

    // ---- playback ----

    private fun playLoop() {
        val frame = ChalkieConfig.SAMPLES_PER_FRAME
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, ENC)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(ENC)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, frame * 2 * 4))
            .build()
        var playing = false
        var idleTicks = 0
        try {
            while (running) {
                val voice = playout.nextFrame()
                val toneChunk = nextToneChunk(frame)
                if (voice == null && toneChunk == null) {
                    if (playing && ++idleTicks > IDLE_TICKS_BEFORE_PAUSE) {
                        track.pause(); track.flush(); playing = false
                    }
                    Thread.sleep(ChalkieConfig.VOICE_FRAME_MS.toLong())
                    continue
                }
                idleTicks = 0
                val out = Mixer.mix(listOfNotNull(voice, toneChunk), frame)
                if (!playing) { track.play(); playing = true }
                track.write(out, 0, frame)   // blocking: paces the loop at real time
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun nextToneChunk(size: Int): ShortArray? {
        val t = tone ?: toneQueue.poll()?.also { tone = it; tonePos = 0 } ?: return null
        val chunk = ShortArray(size)
        val n = minOf(size, t.size - tonePos)
        System.arraycopy(t, tonePos, chunk, 0, n)
        tonePos += n
        if (tonePos >= t.size) tone = null
        return chunk
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val RATE = ChalkieConfig.SAMPLE_RATE
        const val ENC = AudioFormat.ENCODING_PCM_16BIT
        const val IDLE_TICKS_BEFORE_PAUSE = 50
    }
}
