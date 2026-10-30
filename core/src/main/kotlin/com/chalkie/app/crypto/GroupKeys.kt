package com.chalkie.app.crypto

import com.chalkie.app.core.ChalkieConfig
import java.nio.ByteBuffer

/** Keys derived from the 32-byte group secret (SPEC §5). */
class GroupKeys(secret: ByteArray) {
    init {
        require(secret.size == ChalkieConfig.GROUP_SECRET_BYTES) { "group secret must be 32 bytes" }
    }

    val encKey: ByteArray = Hkdf.derive(secret, "chalkie-enc-v1", 32)
    val authKey: ByteArray = Hkdf.derive(secret, "chalkie-auth-v1", 32)

    /** First 4 bytes of HMAC-SHA256(secret, "chalkie-tag-v1"); safe to advertise. */
    val groupTag: ByteArray =
        Hkdf.hmacSha256(secret, "chalkie-tag-v1".toByteArray(Charsets.UTF_8)).copyOf(4)

    val groupTagInt: Int get() = ByteBuffer.wrap(groupTag).int
}
