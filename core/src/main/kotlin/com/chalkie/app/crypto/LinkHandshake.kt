package com.chalkie.app.crypto

import com.chalkie.app.core.ChalkieConfig
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Per-link mutual authentication (SPEC §5), transport independent.
 *
 * 1. Each side sends HELLO_NONCE = tag(1) ‖ nodeId(4) ‖ nonce(16).
 * 2. Each side replies PROOF = tag(1) ‖ HMAC(authKey, peerNonce ‖ ownNodeId).
 * 3. Each side checks the peer proof against the nodeId the peer claimed.
 */
class LinkHandshake(
    private val authKey: ByteArray,
    private val ownNodeId: UInt,
    random: SecureRandom = SecureRandom(),
) {
    sealed interface Step {
        /** Send these bytes to the peer, keep waiting. */
        class Send(val bytes: ByteArray) : Step
        /** Send these bytes (may be empty), then the link is authenticated. */
        class Done(val peerId: UInt, val bytes: ByteArray?) : Step
        /** Close the link and blacklist the endpoint. */
        data object Fail : Step
        /** Nothing to do yet. */
        data object Wait : Step
    }

    private val ownNonce = ByteArray(ChalkieConfig.HANDSHAKE_NONCE_BYTES).also(random::nextBytes)
    private var peerId: UInt? = null
    private var proofSent = false
    private var peerVerified = false
    var finished = false
        private set

    /** First message; send it as soon as the link opens. */
    fun start(): ByteArray =
        ByteBuffer.allocate(1 + 4 + ownNonce.size)
            .put(TAG_NONCE).putInt(ownNodeId.toInt()).put(ownNonce).array()

    fun onMessage(msg: ByteArray): Step {
        if (finished || msg.isEmpty()) return Step.Fail
        return when (msg[0]) {
            TAG_NONCE -> onNonce(msg)
            TAG_PROOF -> onProof(msg)
            else -> Step.Fail
        }
    }

    private fun onNonce(msg: ByteArray): Step {
        if (peerId != null || msg.size != 1 + 4 + ChalkieConfig.HANDSHAKE_NONCE_BYTES) return Step.Fail
        val buf = ByteBuffer.wrap(msg, 1, msg.size - 1)
        val id = buf.int.toUInt()
        if (id == ownNodeId) return Step.Fail
        peerId = id
        val peerNonce = ByteArray(ChalkieConfig.HANDSHAKE_NONCE_BYTES).also { buf.get(it) }
        val proof = byteArrayOf(TAG_PROOF) + proof(peerNonce, ownNodeId)
        proofSent = true
        return if (peerVerified) finish(proof) else Step.Send(proof)
    }

    private fun onProof(msg: ByteArray): Step {
        val id = peerId ?: return Step.Fail
        if (peerVerified) return Step.Fail
        val expected = proof(ownNonce, id)
        val got = msg.copyOfRange(1, msg.size)
        if (!MessageDigest.isEqual(expected, got)) return Step.Fail
        peerVerified = true
        return if (proofSent) finish(null) else Step.Wait
    }

    private fun finish(bytes: ByteArray?): Step {
        finished = true
        return Step.Done(peerId!!, bytes)
    }

    private fun proof(nonce: ByteArray, nodeId: UInt): ByteArray =
        Hkdf.hmacSha256(authKey, nonce + ByteBuffer.allocate(4).putInt(nodeId.toInt()).array())

    companion object {
        const val TAG_NONCE: Byte = 0x01
        const val TAG_PROOF: Byte = 0x02
    }
}
