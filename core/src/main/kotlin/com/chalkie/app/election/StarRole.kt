package com.chalkie.app.election

import java.nio.ByteBuffer

/** This node's place in the Wi-Fi Direct STAR (SPEC §8). */
sealed interface StarRole {
    /** No hub needed (or none claimed yet): Wi-Fi Direct stays off. */
    data object Off : StarRole
    /** I am the hub: advertise and accept leaves. */
    data class Hub(val epoch: Int) : StarRole
    /** I am a leaf: connect only to this hub at this epoch. */
    data class Leaf(val hubId: UInt, val epoch: Int) : StarRole
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
