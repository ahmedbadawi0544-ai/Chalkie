package com.chalkie.app.crypto

import com.chalkie.app.core.PacketCodec
import com.chalkie.app.core.PacketHeader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM over every packet (SPEC §4).
 * Nonce = srcId(4) ‖ msgId(8); AAD = header without ttl/hopCount.
 */
class CryptoBox(encKey: ByteArray) {
    private val key = SecretKeySpec(encKey, "AES")

    /** Packets that failed authentication (stats). */
    val authFailures = AtomicLong()

    /** [packet] must already hold the full header. Returns ciphertext ‖ tag. */
    fun seal(packet: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFrom(packet)))
        cipher.updateAAD(PacketCodec.aad(packet))
        return cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the packet is not authentic. */
    fun open(packet: ByteArray, header: PacketHeader): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFrom(packet)))
        cipher.updateAAD(PacketCodec.aad(packet))
        cipher.doFinal(packet, PacketCodec.HEADER_SIZE, header.payloadLen + PacketCodec.TAG_SIZE)
    } catch (e: java.security.GeneralSecurityException) {
        authFailures.incrementAndGet()
        null
    }

    private fun nonceFrom(packet: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(packet)
        return ByteBuffer.allocate(12).putInt(buf.getInt(8)).putLong(buf.getLong(16)).array()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
