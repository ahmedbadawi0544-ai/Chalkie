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

    @Synchronized
    fun offer(bytes: ByteArray, priority: Priority, nowMs: Long): Boolean {
        val q = queues.getValue(priority)
        if (q.size >= maxPerPriority) return false
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
