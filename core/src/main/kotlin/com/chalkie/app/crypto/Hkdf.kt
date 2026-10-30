package com.chalkie.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun derive(ikm: ByteArray, info: String, length: Int, salt: ByteArray = ByteArray(HASH_LEN)): ByteArray =
        derive(ikm, info.toByteArray(Charsets.UTF_8), length, salt)

    fun derive(ikm: ByteArray, infoBytes: ByteArray, length: Int, salt: ByteArray): ByteArray {
        require(length in 1..255 * HASH_LEN)
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t + infoBytes + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
