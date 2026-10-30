package com.chalkie.app.election

import java.nio.ByteBuffer

/** This node's place in the Wi-Fi Direct STAR (SPEC §8). */
sealed interface StarRole {
    /** No hub needed (or none claimed yet): Wi-Fi Direct stays off. */
    data object Off : StarRole
    /** I am the hub: advertise and accept leaves. [standbyId] takes over if I disappear. */
    data class Hub(val epoch: Int, val standbyId: UInt? = null) : StarRole
    /**
     * I am a leaf: connect only to this hub at this epoch. While [standbyId] is set, keep
     * discovering so the standby's advert is found the moment it starts (SPEC §19.1).
     * When [standbyId] is my own id, I am the standby.
     */
    data class Leaf(val hubId: UInt, val epoch: Int, val standbyId: UInt? = null) : StarRole
}

/** Implemented by transports that follow the STAR role (Wi-Fi Direct). */
interface StarControl {
    fun setStarRole(role: StarRole)
}

/** The current hub as this node believes it. */
data class HubState(val hubId: UInt, val epoch: Int, val score: Float)

/** HUB_CLAIM / BRIDGE_CLAIM payload: epoch(4) ‖ score(float 4). */
object ClaimPayload {
    fun encode(epoch: Int, score: Float): ByteArray = ByteBuffer.allocate(8).putInt(epoch).putFloat(score).array()

    fun decode(bytes: ByteArray): Pair<Int, Float>? {
        if (bytes.size < 8) return null
        val b = ByteBuffer.wrap(bytes)
        return b.int to b.float
    }
}
