package com.chalkie.app.audio

/** Sums PCM16 frames with clipping (SPEC §12). */
object Mixer {
    fun mix(frames: List<ShortArray>, size: Int): ShortArray {
        if (frames.size == 1 && frames[0].size == size) return frames[0]
        val acc = IntArray(size)
        for (f in frames) for (i in 0 until minOf(size, f.size)) acc[i] += f[i]
        return ShortArray(size) { acc[it].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }
}
