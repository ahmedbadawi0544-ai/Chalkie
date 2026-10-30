package com.chalkie.app.core

/** Monotonic millisecond clock, swappable for virtual time in tests. */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}
