package com.chalkie.app.audio

/** G.711 μ-law (ITU-T), 1 byte per 16-bit sample. */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val decodeTable = ShortArray(256) { decodeOne(it.toByte()) }

    fun encode(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray =
        ByteArray(length) { encodeOne(pcm[offset + it]) }

    fun decode(ulaw: ByteArray): ShortArray = ShortArray(ulaw.size) { decodeTable[ulaw[it].toInt() and 0xFF] }

    fun encodeOne(sample: Short): Byte {
        var s = sample.toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (s and mask == 0 && exponent > 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun decodeOne(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -magnitude else magnitude).toShort()
    }
}
