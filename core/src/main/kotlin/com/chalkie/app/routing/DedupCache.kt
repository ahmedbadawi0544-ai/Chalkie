package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock

/** LRU of (srcId, msgId) with expiry (SPEC §10). */
class DedupCache(
    private val clock: Clock,
    private val maxEntries: Int = ChalkieConfig.DEDUP_MAX,
    private val ttlMs: Long = ChalkieConfig.DEDUP_TTL_MS,
) {
    private data class Key(val srcId: UInt, val msgId: Long)

    // Insertion order == age order, so expiry only needs to look at the head.
    private val entries = LinkedHashMap<Key, Long>()

    @Synchronized
    fun contains(srcId: UInt, msgId: Long): Boolean {
        evict()
        return entries.containsKey(Key(srcId, msgId))
    }

    @Synchronized
    fun add(srcId: UInt, msgId: Long) {
        evict()
        val key = Key(srcId, msgId)
        entries.remove(key)
        entries[key] = clock.nowMs()
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun evict() {
        val now = clock.nowMs()
        val it = entries.values.iterator()
        while (it.hasNext()) {
            if (now - it.next() > ttlMs) it.remove() else break
        }
    }
}
