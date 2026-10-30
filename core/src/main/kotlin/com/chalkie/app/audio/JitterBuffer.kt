package com.chalkie.app.audio

import com.chalkie.app.core.ChalkieConfig

/**
 * One stream's frame reorder/playout buffer (SPEC §12). Pulled once per frame tick.
 * Late frames are dropped; a missing frame repeats the last one once, then silence.
 */
class JitterBuffer(
    private val frameMs: Int = ChalkieConfig.VOICE_FRAME_MS,
    targetMs: Int = ChalkieConfig.JITTER_TARGET_MS,
    maxMs: Int = ChalkieConfig.JITTER_MAX_MS,
    idleMs: Long = ChalkieConfig.JITTER_STREAM_IDLE_MS,
) {
    private val targetFrames = maxOf(1, targetMs / frameMs)
    private val maxFrames = maxOf(targetFrames + 1, maxMs / frameMs)
    private val idleFrames = maxOf(1, (idleMs / frameMs).toInt())

    private val frames = HashMap<Long, ByteArray>()
    private var reference: Long? = null      // unwrapped seq anchor
    private var nextSeq = 0L
    private var highest = Long.MIN_VALUE
    private var started = false
    private var waitTicks = 0
    private var missRun = 0
    private var last: ByteArray? = null
    private var ending = false

    var lateDropped = 0L
        private set

    /** True once the stream has drained after TALK_END or went idle. */
    val isFinished: Boolean
        get() = (ending && frames.isEmpty() && started) || missRun >= idleFrames

    @Synchronized
    fun push(seq16: Int, frame: ByteArray) {
        val seq = unwrap(seq16)
        if (started && seq < nextSeq) { lateDropped++; return }
        if (frames.containsKey(seq)) return
        frames[seq] = frame
        if (seq > highest) highest = seq
        if (!started) return
        // Keep latency bounded: skip ahead if the buffer grew past max.
        if (highest - nextSeq + 1 > maxFrames) {
            val newNext = highest - targetFrames + 1
            frames.keys.removeAll { it < newNext }
            nextSeq = newNext
        }
    }

    /** Marks TALK_END: play what is buffered, then finish. */
    @Synchronized
    fun end() { ending = true }

    /** Next encoded frame to play, or null for silence. */
    @Synchronized
    fun pull(): ByteArray? {
        if (!started) {
            if (frames.isEmpty()) { missRun++; return null }
            waitTicks++
            if (frames.size < targetFrames && waitTicks < targetFrames && !ending) return null
            started = true
            nextSeq = frames.keys.min()
        }
        val f = frames.remove(nextSeq)
        nextSeq++
        if (f != null) {
            last = f
            missRun = 0
            return f
        }
        missRun++
        return if (missRun == 1) last else null
    }

    private fun unwrap(seq16: Int): Long {
        val ref = reference ?: seq16.toLong().also { reference = it }
        val diff = ((seq16 - ref.toInt()) shl 16) shr 16   // signed 16-bit distance
        val u = ref + diff
        reference = maxOf(ref, u)
        return u
    }
}
