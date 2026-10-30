package com.chalkie.app.message

import com.chalkie.app.audio.Codec
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
import java.nio.ByteBuffer

/** A recorded voice message for one teammate (phase 5). [frames] are encoded 20 ms frames. */
class VoiceMessage(
    val uid: Long,
    val from: UInt,
    val to: UInt,
    /** Wall-clock creation time (ms since epoch), for display. */
    val createdAt: Long,
    val codec: Codec,
    val frames: List<ByteArray>,
) {
    val durationMs: Long get() = frames.size.toLong() * ChalkieConfig.VOICE_FRAME_MS

    /** createdAt(8) ‖ codec(1) ‖ frameCount(2) ‖ (len(2) ‖ frame)… */
    fun encodeBody(): ByteArray {
        val buf = ByteBuffer.allocate(11 + frames.sumOf { 2 + it.size })
        buf.putLong(createdAt).put(codec.id.toByte()).putShort(frames.size.toShort())
        frames.forEach { buf.putShort(it.size.toShort()).put(it) }
        return buf.array()
    }

    companion object {
        fun decodeBody(uid: Long, from: UInt, to: UInt, body: ByteArray): VoiceMessage? = try {
            val buf = ByteBuffer.wrap(body)
            val created = buf.long
            val codec = Codec.fromId(buf.get().toInt() and 0xFF) ?: throw IllegalArgumentException()
            val frames = List(buf.short.toInt() and 0xFFFF) {
                ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
            }
            VoiceMessage(uid, from, to, created, codec, frames)
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * Store & forward voice messages (SPEC §14 P5). Pure logic, driven by [tick] and the two
 * packet handlers; the node supplies routing and reachability.
 *
 * Sender keeps every message until the recipient ACKs it complete: it waits while the recipient
 * is unreachable, sends the chunks paced (NORMAL priority, so live voice goes first), then
 * resends whatever the recipient reports missing. The receiver reassembles, ACKs, and asks for
 * gaps when chunks stop arriving.
 */
class VoiceMailbox(
    private val myId: UInt,
    private val clock: Clock,
    /** Sends a unicast packet of [type] to [to]. */
    private val send: (type: PacketType, to: UInt, payload: ByteArray) -> Unit,
    /** True if [nodeId] is currently known in the team (heard its HELLO). */
    private val reachable: (UInt) -> Boolean,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data class Received(val message: VoiceMessage) : Event
        data class Delivered(val uid: Long, val to: UInt) : Event
    }

    private class Outgoing(val msg: VoiceMessage, val chunks: List<ByteArray>) {
        val pending = ArrayDeque<Int>()
        var awaitingSinceMs: Long? = null
        var retryAtMs = 0L
    }

    private class Incoming(val from: UInt, val count: Int) {
        val chunks = arrayOfNulls<ByteArray>(count)
        var received = 0
        var lastChunkMs = 0L
        var startedMs = 0L
        var lastNackMs = 0L
    }

    private val outbox = LinkedHashMap<Long, Outgoing>()
    private val inbox = HashMap<Pair<UInt, Long>, Incoming>()
    private val done = LinkedHashMap<Pair<UInt, Long>, Unit>()

    /** Queues [msg] for delivery. Survives until the recipient ACKs it. */
    @Synchronized
    fun enqueue(msg: VoiceMessage) {
        if (msg.uid in outbox) return
        val body = msg.encodeBody()
        val size = ChalkieConfig.VMSG_CHUNK_BYTES
        val chunks = (body.indices step size).map { body.copyOfRange(it, minOf(body.size, it + size)) }
        require(chunks.size <= 0xFFFF) { "message too long" }
        outbox[msg.uid] = Outgoing(msg, chunks)
    }

    @Synchronized
    fun pending(): List<VoiceMessage> = outbox.values.map { it.msg }

    @Synchronized
    fun cancel(uid: Long) { outbox.remove(uid) }

    @Synchronized
    fun tick() {
        val now = clock.nowMs()
        for (o in outbox.values) {
            val since = o.awaitingSinceMs
            when {
                o.pending.isNotEmpty() -> repeat(ChalkieConfig.VMSG_CHUNKS_PER_TICK) {
                    val i = o.pending.removeFirstOrNull() ?: return@repeat
                    send(PacketType.VMSG, o.msg.to, chunkPayload(o.msg.uid, i, o.chunks.size, o.chunks[i]))
                    if (o.pending.isEmpty()) o.awaitingSinceMs = now
                }
                since != null -> if (now - since > ChalkieConfig.VMSG_ACK_TIMEOUT_MS) {
                    o.awaitingSinceMs = null
                    o.retryAtMs = now + ChalkieConfig.VMSG_RETRY_MS
                }
                now >= o.retryAtMs && reachable(o.msg.to) -> {
                    o.pending.addAll(o.chunks.indices)
                }
            }
        }
        val it = inbox.entries.iterator()
        while (it.hasNext()) {
            val (key, inc) = it.next()
            if (now - inc.startedMs > ChalkieConfig.VMSG_INCOMPLETE_DROP_MS) { it.remove(); continue }
            if (now - inc.lastChunkMs > ChalkieConfig.VMSG_GAP_MS && now - inc.lastNackMs > ChalkieConfig.VMSG_GAP_MS) {
                inc.lastNackMs = now
                val missing = inc.chunks.indices.filter { inc.chunks[it] == null }
                send(PacketType.VMSG_ACK, inc.from, ackPayload(key.second, missing))
            }
        }
    }

    @Synchronized
    fun onChunk(from: UInt, payload: ByteArray) {
        if (payload.size < 12) return
        val buf = ByteBuffer.wrap(payload)
        val uid = buf.long
        val index = buf.short.toInt() and 0xFFFF
        val count = buf.short.toInt() and 0xFFFF
        val key = from to uid
        if (key in done) {
            // Our ACK was lost: say again that we have it all.
            if (index == count - 1) send(PacketType.VMSG_ACK, from, ackPayload(uid, emptyList()))
            return
        }
        if (count == 0 || index >= count) return
        val now = clock.nowMs()
        val inc = inbox.getOrPut(key) { Incoming(from, count).also { it.startedMs = now } }
        if (inc.count != count) return
        inc.lastChunkMs = now
        if (inc.chunks[index] == null) {
            inc.chunks[index] = payload.copyOfRange(12, payload.size)
            inc.received++
        }
        if (inc.received < count) return
        inbox.remove(key)
        remember(key)
        send(PacketType.VMSG_ACK, from, ackPayload(uid, emptyList()))
        val body = inc.chunks.fold(ByteArray(0)) { acc, c -> acc + c!! }
        VoiceMessage.decodeBody(uid, from, myId, body)?.let { onEvent(Event.Received(it)) }
    }

    @Synchronized
    fun onAck(payload: ByteArray) {
        if (payload.size < 10) return
        val buf = ByteBuffer.wrap(payload)
        val uid = buf.long
        val o = outbox[uid] ?: return
        val missing = List(buf.short.toInt() and 0xFFFF) { buf.short.toInt() and 0xFFFF }
        if (missing.isEmpty()) {
            outbox.remove(uid)
            onEvent(Event.Delivered(uid, o.msg.to))
            return
        }
        o.awaitingSinceMs = null
        o.pending.clear()
        o.pending.addAll(missing.filter { it < o.chunks.size })
    }

    private fun remember(key: Pair<UInt, Long>) {
        done[key] = Unit
        if (done.size > ChalkieConfig.VMSG_DONE_MEMORY) done.remove(done.keys.first())
    }

    private fun chunkPayload(uid: Long, index: Int, count: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + data.size).putLong(uid).putShort(index.toShort()).putShort(count.toShort()).put(data).array()

    private fun ackPayload(uid: Long, missing: List<Int>): ByteArray {
        val m = missing.take((ChalkieConfig.VMSG_CHUNK_BYTES - 10) / 2)
        return ByteBuffer.allocate(10 + 2 * m.size).putLong(uid).putShort(m.size.toShort())
            .apply { m.forEach { putShort(it.toShort()) } }.array()
    }
}
