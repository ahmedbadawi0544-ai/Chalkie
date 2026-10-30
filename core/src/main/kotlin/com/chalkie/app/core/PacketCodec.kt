package com.chalkie.app.core

import com.chalkie.app.crypto.CryptoBox
import java.nio.ByteBuffer

/**
 * Wire format (SPEC §4), big-endian:
 * magic(2) version(1) type(1) flags(1) ttl(1) hopCount(1) channel(1) src(4) dst(4)
 * msgId(8) timestamp(4) payloadLen(2) payload(n) tag(16)
 */
object PacketCodec {
    const val MAGIC_0: Byte = 0x43
    const val MAGIC_1: Byte = 0x4B
    const val VERSION = 1
    const val HEADER_SIZE = 30
    const val TAG_SIZE = 16
    const val OFFSET_TTL = 5
    const val OFFSET_HOP = 6
    const val MAX_PAYLOAD = 0xFFFF

    /** Builds and encrypts a packet. Returns the complete wire bytes. */
    fun encode(
        crypto: CryptoBox,
        type: PacketType,
        channel: Int,
        srcId: UInt,
        dstId: UInt,
        msgId: Long,
        timestamp: UInt,
        payload: ByteArray,
        ttl: Int = type.defaultTtl,
        priority: Priority = type.priority,
    ): ByteArray {
        require(channel in 0 until ChalkieConfig.CHANNEL_COUNT) { "channel $channel" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArray(HEADER_SIZE + payload.size + TAG_SIZE)
        ByteBuffer.wrap(out).apply {
            put(MAGIC_0); put(MAGIC_1)
            put(VERSION.toByte())
            put(type.code.toByte())
            put(PacketFlags.build(priority, unicast = dstId != Ids.BROADCAST).toByte())
            put(ttl.toByte())
            put(0)
            put(channel.toByte())
            putInt(srcId.toInt())
            putInt(dstId.toInt())
            putLong(msgId)
            putInt(timestamp.toInt())
            putShort(payload.size.toShort())
        }
        val sealed = crypto.seal(out, payload)
        System.arraycopy(sealed, 0, out, HEADER_SIZE, sealed.size)
        return out
    }

    /** Parses the header only. Returns null on bad magic, version, type or length. */
    fun parseHeader(bytes: ByteArray): PacketHeader? {
        if (bytes.size < HEADER_SIZE + TAG_SIZE) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
        if (bytes[2].toInt() != VERSION) return null
        val type = PacketType.fromCode(bytes[3].toInt() and 0xFF) ?: return null
        val buf = ByteBuffer.wrap(bytes)
        val payloadLen = buf.getShort(28).toInt() and 0xFFFF
        if (bytes.size != HEADER_SIZE + payloadLen + TAG_SIZE) return null
        val channel = bytes[7].toInt() and 0xFF
        if (channel >= ChalkieConfig.CHANNEL_COUNT) return null
        return PacketHeader(
            type = type,
            flags = bytes[4].toInt() and 0xFF,
            ttl = bytes[OFFSET_TTL].toInt() and 0xFF,
            hopCount = bytes[OFFSET_HOP].toInt() and 0xFF,
            channel = channel,
            srcId = buf.getInt(8).toUInt(),
            dstId = buf.getInt(12).toUInt(),
            msgId = buf.getLong(16),
            timestamp = buf.getInt(24).toUInt(),
            payloadLen = payloadLen,
        )
    }

    /** Header + decrypt in one step. Null if malformed or not authentic. */
    fun decode(crypto: CryptoBox, bytes: ByteArray): DecodedPacket? {
        val header = parseHeader(bytes) ?: return null
        val plain = crypto.open(bytes, header) ?: return null
        return DecodedPacket(header, plain)
    }

    /** AAD = header bytes 0..4 and 7..29: everything except ttl and hopCount. */
    fun aad(bytes: ByteArray): ByteArray {
        val aad = ByteArray(HEADER_SIZE - 2)
        System.arraycopy(bytes, 0, aad, 0, OFFSET_TTL)
        System.arraycopy(bytes, OFFSET_HOP + 1, aad, OFFSET_TTL, HEADER_SIZE - OFFSET_HOP - 1)
        return aad
    }

    /** Copy for relaying: ttl-1, hopCount+1. Authentication stays valid. */
    fun forRelay(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val ttl = copy[OFFSET_TTL].toInt() and 0xFF
        val hop = copy[OFFSET_HOP].toInt() and 0xFF
        copy[OFFSET_TTL] = (ttl - 1).coerceAtLeast(0).toByte()
        copy[OFFSET_HOP] = (hop + 1).coerceAtMost(0xFF).toByte()
        return copy
    }
}
