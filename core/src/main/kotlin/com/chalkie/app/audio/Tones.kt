package com.chalkie.app.audio

import com.chalkie.app.core.ChalkieConfig
import kotlin.math.PI
import kotlin.math.sin

/** PCM16 tones at SAMPLE_RATE (SPEC §12). */
object Tones {
    private const val RATE = ChalkieConfig.SAMPLE_RATE
    private const val AMP = 0.35

    /** Roger beep: played when a remote TALK_END arrives. */
    val roger: ShortArray by lazy { tone(1200.0, 70) + tone(1800.0, 70) }

    /** Talk-permit chirp: own PTT accepted. */
    val permit: ShortArray by lazy { tone(1500.0, 40) + silence(20) + tone(2000.0, 40) }

    /** Busy: floor denied or lost. */
    val busy: ShortArray by lazy { (1..3).map { dual(480.0, 620.0, 150) + silence(100) }.reduce { a, b -> a + b } }

    /** Talk-time warning before auto-release. */
    val warning: ShortArray by lazy { tone(900.0, 150) + silence(80) + tone(900.0, 150) }

    /** SOS alarm, one cycle (loop it). */
    val sosAlarm: ShortArray by lazy { tone(960.0, 400) + tone(770.0, 400) }

    fun tone(freq: Double, ms: Int): ShortArray = envelope(ShortArray(RATE * ms / 1000) {
        (sin(2 * PI * freq * it / RATE) * AMP * Short.MAX_VALUE).toInt().toShort()
    })

    private fun dual(f1: Double, f2: Double, ms: Int): ShortArray = envelope(ShortArray(RATE * ms / 1000) {
        ((sin(2 * PI * f1 * it / RATE) + sin(2 * PI * f2 * it / RATE)) / 2 * AMP * Short.MAX_VALUE).toInt().toShort()
    })

    private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

    /** 5 ms fade in/out to avoid clicks. */
    private fun envelope(s: ShortArray): ShortArray {
        val ramp = minOf(RATE / 200, s.size / 2)
        for (i in 0 until ramp) {
            val g = i.toDouble() / ramp
            s[i] = (s[i] * g).toInt().toShort()
            s[s.size - 1 - i] = (s[s.size - 1 - i] * g).toInt().toShort()
        }
        return s
    }
}
