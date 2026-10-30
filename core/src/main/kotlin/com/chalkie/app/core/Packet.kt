package com.chalkie.app.core

/** Lowest to highest; ordinal order is the send-queue order (SPEC §4, §10). */
enum class Priority(val bits: Int) {
    NORMAL(0), VOICE(1), CONTROL(2), SOS(3);

    companion object {
        fun fromBits(bits: Int): Priority = entries.first { it.bits == (bits and 0x3) }
    }
}

enum class PacketType(val code: Int, val defaultTtl: Int, val priority: Priority) {
    HELLO(0x01, ChalkieConfig.TTL_HELLO, Priority.CONTROL),
    HUB_CLAIM(0x02, ChalkieConfig.TTL_CLAIM, Priority.CONTROL),
    BRIDGE_CLAIM(0x03, ChalkieConfig.TTL_CLAIM, Priority.CONTROL),
    TALK_START(0x10, ChalkieConfig.TTL_CONTROL, Priority.CONTROL),
    VOICE(0x11, ChalkieConfig.TTL_VOICE, Priority.VOICE),
    TALK_END(0x12, ChalkieConfig.TTL_CONTROL, Priority.CONTROL),
    TEXT(0x20, ChalkieConfig.TTL_TEXT, Priority.NORMAL),
    /** Store & forward voice message chunk (P5): uid(8) ‖ index(2) ‖ count(2) ‖ data. */
    VMSG(0x21, ChalkieConfig.TTL_TEXT, Priority.NORMAL),
    /** uid(8) ‖ missingCount(2) ‖ missing index(2)…; missingCount 0 = complete. */
    VMSG_ACK(0x22, ChalkieConfig.TTL_TEXT, Priority.CONTROL),
    SOS(0x30, ChalkieConfig.TTL_SOS, Priority.SOS),
    SOS_ACK(0x31, ChalkieConfig.TTL_SOS, Priority.SOS),
    LAN_PROBE(0x40, ChalkieConfig.TTL_LAN_PROBE, Priority.CONTROL);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PacketType? = byCode[code]
    }
}

/** Parsed fixed header (SPEC §4). [payloadLen] is the ciphertext length, tag excluded. */
data class PacketHeader(
    val type: PacketType,
    val flags: Int,
    val ttl: Int,
    val hopCount: Int,
    val channel: Int,
    val srcId: UInt,
    val dstId: UInt,
    val msgId: Long,
    val timestamp: UInt,
    val payloadLen: Int,
) {
    val encrypted: Boolean get() = flags and PacketFlags.ENCRYPTED != 0
    val unicast: Boolean get() = flags and PacketFlags.UNICAST != 0
    val priority: Priority get() = Priority.fromBits(flags shr PacketFlags.PRIORITY_SHIFT)
    val isBroadcast: Boolean get() = dstId == Ids.BROADCAST
}

object PacketFlags {
    const val ENCRYPTED = 0x01
    const val PRIORITY_SHIFT = 1
    const val UNICAST = 0x08

    fun build(priority: Priority, unicast: Boolean, encrypted: Boolean = true): Int =
        (if (encrypted) ENCRYPTED else 0) or
            (priority.bits shl PRIORITY_SHIFT) or
            (if (unicast) UNICAST else 0)
}

/** A packet that passed authentication, with its decrypted payload. */
class DecodedPacket(val header: PacketHeader, val payload: ByteArray)
