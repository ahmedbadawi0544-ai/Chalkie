package com.chalkie.app.core

import java.security.SecureRandom

/** Node, session and message identifiers (SPEC §2, §4). */
object Ids {
    const val BROADCAST: UInt = 0xFFFFFFFFu

    private val random = SecureRandom()

    /** Random nodeId, never 0 and never the broadcast id. */
    fun newNodeId(): UInt {
        while (true) {
            val id = random.nextInt().toUInt()
            if (id != 0u && id != BROADCAST) return id
        }
    }

    fun newSessionId(): UInt = random.nextInt().toUInt()

    fun hex(id: UInt): String = id.toString(16).padStart(8, '0')
}

/** msgId = sessionId(4) ‖ counter(4). The counter never repeats within a session. */
class MsgIdGenerator(private val sessionId: UInt) {
    private var counter = 0u

    @Synchronized
    fun next(): Long {
        counter++
        return (sessionId.toLong() shl 32) or counter.toLong()
    }
}
