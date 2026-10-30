package com.chalkie.app.transport

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Priority
import java.util.ArrayDeque

/**
 * Per-link outgoing queue (SPEC §10): SOS > CONTROL > VOICE > NORMAL, FIFO within a priority.
 * VOICE older than VOICE_MAX_QUEUE_MS is dropped: late audio is worse than lost audio.
 */
class LinkSendQueue(private val maxPerPriority: Int = 256) {
    private class Item(val bytes: ByteArray, val enqueuedMs: Long)

    private val queues = Priority.entries.associateWith { ArrayDeque<Item>() }
    var droppedStaleVoice = 0L
        private set

    // Sender-side loss estimate: packets refused (queue full) or dropped stale, per stats window.
    private var windowStartMs = Long.MIN_VALUE
    private var offered = 0
    private var lost = 0
    private var lastRate = 0f

    /** Fraction of packets lost on this link in the last full LINK_STATS_WINDOW_MS (0..1). */
    @Synchronized
    fun lossRate(nowMs: Long): Float {
        roll(nowMs)
        return lastRate
    }

    private fun roll(nowMs: Long) {
        if (windowStartMs == Long.MIN_VALUE) windowStartMs = nowMs
        if (nowMs - windowStartMs < ChalkieConfig.LINK_STATS_WINDOW_MS) return
        lastRate = if (offered == 0) 0f else lost.toFloat() / offered
        offered = 0
        lost = 0
        windowStartMs = nowMs
    }

    @Synchronized
    fun offer(bytes: ByteArray, priority: Priority, nowMs: Long): Boolean {
        val q = queues.getValue(priority)
        roll(nowMs)
        offered++
        if (q.size >= maxPerPriority) { lost++; return false }
        q.addLast(Item(bytes, nowMs))
        return true
    }

    @Synchronized
    fun poll(nowMs: Long): ByteArray? {
        for (p in Priority.entries.reversed()) {
            val q = queues.getValue(p)
            while (q.isNotEmpty()) {
                val item = q.removeFirst()
                if (p == Priority.VOICE && nowMs - item.enqueuedMs > ChalkieConfig.VOICE_MAX_QUEUE_MS) {
                    droppedStaleVoice++
                    lost++
                    continue
                }
                return item.bytes
            }
        }
        return null
    }

    @Synchronized
    fun clear() = queues.values.forEach { it.clear() }

    @Synchronized
    fun size(): Int = queues.values.sumOf { it.size }
}
