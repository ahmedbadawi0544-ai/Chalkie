package com.chalkie.app.ptt

import com.chalkie.app.audio.TalkPriority
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import kotlin.random.Random

/**
 * Half-duplex floor control per channel (SPEC §12). Pure state, driven by the node.
 *
 * Collision rule (both sides compare the same two TALK_STARTs, so they agree):
 * higher priority, then earlier timestamp, then lower nodeId.
 */
class FloorControl(private val myId: UInt, private val clock: Clock, private val random: Random = Random.Default) {

    data class Claim(val nodeId: UInt, val streamId: Int, val priority: TalkPriority, val timestamp: UInt)

    class MyTalk(val channel: Int, val claim: Claim, val startMs: Long)

    sealed interface Request {
        data class Granted(val streamId: Int) : Request
        data object Busy : Request
    }

    private class Remote(val claim: Claim, var lastActivityMs: Long)

    private val remotes = HashMap<Int, HashMap<UInt, Remote>>()   // channel -> src -> talk

    var myTalk: MyTalk? = null
        private set

    @Synchronized
    fun requestTalk(channel: Int, priority: TalkPriority, timestamp: UInt): Request {
        if (myTalk != null) return Request.Granted(myTalk!!.claim.streamId)
        val now = clock.nowMs()
        val blocking = active(channel, now).any { it.claim.priority >= priority }
        if (blocking) return Request.Busy
        val streamId = random.nextInt(0x10000)
        myTalk = MyTalk(channel, Claim(myId, streamId, priority, timestamp), now)
        return Request.Granted(streamId)
    }

    /** Ends my talk. Returns the stream id that ended, or null if I wasn't talking. */
    @Synchronized
    fun release(): Int? = myTalk?.claim?.streamId.also { myTalk = null }

    /** Returns true if this TALK_START makes me lose the floor (I must stop now). */
    @Synchronized
    fun onTalkStart(channel: Int, claim: Claim): Boolean {
        val now = clock.nowMs()
        remotes.getOrPut(channel) { HashMap() }[claim.nodeId] = Remote(claim, now)
        val mine = myTalk?.takeIf { it.channel == channel } ?: return false
        val lose = when {
            claim.priority > mine.claim.priority -> true
            claim.priority < mine.claim.priority -> false
            now - mine.startMs <= ChalkieConfig.FLOOR_COLLISION_MS -> beats(claim, mine.claim)
            else -> false
        }
        if (lose) myTalk = null
        return lose
    }

    @Synchronized
    fun onVoice(channel: Int, srcId: UInt, streamId: Int, priorityIfUnknown: TalkPriority = TalkPriority.NORMAL) {
        val now = clock.nowMs()
        val map = remotes.getOrPut(channel) { HashMap() }
        val r = map[srcId]
        if (r != null && r.claim.streamId == streamId) {
            r.lastActivityMs = now
        } else {
            // Missed the TALK_START (joined mid-talk); treat the stream as a talk anyway.
            map[srcId] = Remote(Claim(srcId, streamId, priorityIfUnknown, 0u), now)
        }
    }

    @Synchronized
    fun onTalkEnd(channel: Int, srcId: UInt, streamId: Int) {
        val map = remotes[channel] ?: return
        if (map[srcId]?.claim?.streamId == streamId) map.remove(srcId)
    }

    /** The node to show as "talking" on a channel, if any. */
    @Synchronized
    fun currentTalker(channel: Int): UInt? {
        myTalk?.takeIf { it.channel == channel }?.let { return myId }
        return active(channel, clock.nowMs()).map { it.claim }.reduceOrNull { a, b -> if (beats(a, b)) a else b }?.nodeId
    }

    @Synchronized
    fun isBusy(channel: Int): Boolean = active(channel, clock.nowMs()).isNotEmpty()

    /** True while I talk or anyone is heard talking, on any channel or private call. */
    @Synchronized
    fun anyActive(): Boolean {
        if (myTalk != null) return true
        val now = clock.nowMs()
        return remotes.keys.toList().any { active(it, now).isNotEmpty() }
    }

    /** True when my talk passed MAX_TALK_MS and must be auto-released. */
    @Synchronized
    fun talkExpired(): Boolean = myTalk?.let { clock.nowMs() - it.startMs >= ChalkieConfig.MAX_TALK_MS } ?: false

    private fun active(channel: Int, now: Long): List<Remote> {
        val map = remotes[channel] ?: return emptyList()
        map.values.removeAll { now - it.lastActivityMs > ChalkieConfig.CHANNEL_BUSY_MS }
        return map.values.toList()
    }

    companion object {
        /** Floor key for private (unicast) calls, kept apart from the 16 channels. */
        const val PRIVATE = -1

        /** True if [a] wins over [b]. */
        fun beats(a: Claim, b: Claim): Boolean = when {
            a.priority != b.priority -> a.priority > b.priority
            a.timestamp != b.timestamp -> (a.timestamp - b.timestamp).toInt() < 0
            else -> a.nodeId < b.nodeId
        }
    }
}
