package com.chalkie.app.core

import com.chalkie.app.crypto.CryptoBox
import com.chalkie.app.crypto.GroupKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketCodecTest {
    private val keys = GroupKeys(ByteArray(32) { it.toByte() })
    private val crypto = CryptoBox(keys.encKey)

    private fun sample(payload: ByteArray = "hello".toByteArray()) = PacketCodec.encode(
        crypto, PacketType.VOICE, channel = 3, srcId = 0x11223344u, dstId = Ids.BROADCAST,
        msgId = 0x0102030405060708, timestamp = 123456u, payload = payload,
    )

    @Test
    fun roundTrip() {
        val bytes = sample()
        assertEquals(PacketCodec.HEADER_SIZE + 5 + PacketCodec.TAG_SIZE, bytes.size)
        val p = assertNotNull(PacketCodec.decode(crypto, bytes))
        with(p.header) {
            assertEquals(PacketType.VOICE, type)
            assertEquals(3, channel)
            assertEquals(0x11223344u, srcId)
            assertEquals(Ids.BROADCAST, dstId)
            assertEquals(0x0102030405060708, msgId)
            assertEquals(123456u, timestamp)
            assertEquals(ChalkieConfig.TTL_VOICE, ttl)
            assertEquals(0, hopCount)
            assertEquals(Priority.VOICE, priority)
            assertTrue(encrypted)
        }
        assertContentEquals("hello".toByteArray(), p.payload)
    }

    @Test
    fun payloadIsEncrypted() {
        val plain = ByteArray(64) { 0x55 }
        val bytes = sample(plain)
        val body = bytes.copyOfRange(PacketCodec.HEADER_SIZE, PacketCodec.HEADER_SIZE + plain.size)
        assertTrue(!body.contentEquals(plain))
    }

    @Test
    fun aadExcludesTtlAndHopCount() {
        val relayed = PacketCodec.forRelay(PacketCodec.forRelay(sample()))
        val p = assertNotNull(PacketCodec.decode(crypto, relayed), "relay must stay authentic")
        assertEquals(ChalkieConfig.TTL_VOICE - 2, p.header.ttl)
        assertEquals(2, p.header.hopCount)
    }

    @Test
    fun tamperedHeaderRejected() {
        // Every authenticated header byte, except magic/version/type/len which fail parsing anyway.
        for (offset in listOf(4, 7, 8, 12, 16, 23, 24)) {
            val bytes = sample()
            bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
            assertNull(PacketCodec.decode(crypto, bytes), "tamper at $offset accepted")
        }
    }

    @Test
    fun tamperedPayloadAndTagRejected() {
        val a = sample(); a[PacketCodec.HEADER_SIZE] = (a[PacketCodec.HEADER_SIZE] + 1).toByte()
        assertNull(PacketCodec.decode(crypto, a))
        val b = sample(); b[b.size - 1] = (b[b.size - 1] + 1).toByte()
        assertNull(PacketCodec.decode(crypto, b))
    }

    @Test
    fun wrongGroupRejected() {
        val other = CryptoBox(GroupKeys(ByteArray(32) { 7 }).encKey)
        assertNull(PacketCodec.decode(other, sample()))
        assertEquals(1, other.authFailures.get())
    }

    @Test
    fun malformedRejected() {
        val bytes = sample()
        assertNull(PacketCodec.parseHeader(bytes.copyOf(bytes.size - 1)))
        assertNull(PacketCodec.parseHeader(bytes.copyOf().also { it[0] = 0 }))
        assertNull(PacketCodec.parseHeader(bytes.copyOf().also { it[2] = 2 }))
        assertNull(PacketCodec.parseHeader(bytes.copyOf().also { it[3] = 0x7F }))
    }

    @Test
    fun msgIdsAreUniqueAndCarrySession() {
        val gen = MsgIdGenerator(0xCAFEBABEu)
        val ids = List(1000) { gen.next() }
        assertEquals(1000, ids.toSet().size)
        assertTrue(ids.all { (it ushr 32).toUInt() == 0xCAFEBABEu })
    }
}
