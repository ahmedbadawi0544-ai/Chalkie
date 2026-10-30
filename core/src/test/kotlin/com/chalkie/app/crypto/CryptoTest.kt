package com.chalkie.app.crypto

import com.chalkie.app.group.GroupInfo
import com.chalkie.app.group.QrCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStdlibApi::class)
class CryptoTest {
    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hexToByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray()
        val okm = Hkdf.derive(ikm, info, 42, salt)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHexString(),
        )
    }

    @Test
    fun groupKeysAreDistinctAndStable() {
        val secret = ByteArray(32) { 1 }
        val a = GroupKeys(secret)
        val b = GroupKeys(secret.copyOf())
        assertContentEquals(a.encKey, b.encKey)
        assertContentEquals(a.groupTag, b.groupTag)
        assertTrue(!a.encKey.contentEquals(a.authKey))
        assertEquals(4, a.groupTag.size)
        assertTrue(!a.groupTag.contentEquals(GroupKeys(ByteArray(32) { 2 }).groupTag))
    }

    private fun runHandshake(keyA: ByteArray, keyB: ByteArray): Pair<LinkHandshake.Step, LinkHandshake.Step> {
        val a = LinkHandshake(keyA, 1u)
        val b = LinkHandshake(keyB, 2u)
        // Both open simultaneously.
        val sa = b.onMessage(a.start())
        val sb = a.onMessage(b.start())
        val proofA = (sb as? LinkHandshake.Step.Send)?.bytes ?: return sb to sa
        val proofB = (sa as? LinkHandshake.Step.Send)?.bytes ?: return sb to sa
        return a.onMessage(proofB) to b.onMessage(proofA)
    }

    @Test
    fun handshakeSucceedsWithSameGroup() {
        val key = GroupKeys(ByteArray(32) { 9 }).authKey
        val (ra, rb) = runHandshake(key, key)
        assertEquals(2u, assertIs<LinkHandshake.Step.Done>(ra).peerId)
        assertEquals(1u, assertIs<LinkHandshake.Step.Done>(rb).peerId)
    }

    @Test
    fun handshakeFailsWithOtherGroup() {
        val (ra, rb) = runHandshake(GroupKeys(ByteArray(32) { 9 }).authKey, GroupKeys(ByteArray(32) { 8 }).authKey)
        assertIs<LinkHandshake.Step.Fail>(ra)
        assertIs<LinkHandshake.Step.Fail>(rb)
    }

    @Test
    fun handshakeRejectsProofForAnotherId() {
        val key = GroupKeys(ByteArray(32) { 9 }).authKey
        val a = LinkHandshake(key, 1u)
        val b = LinkHandshake(key, 2u)
        val mallory = LinkHandshake(key, 3u)
        // A believes its peer is node 2, but the proof it gets is bound to node 3.
        a.onMessage(b.start())
        val forged = (mallory.onMessage(a.start()) as LinkHandshake.Step.Send).bytes
        assertIs<LinkHandshake.Step.Fail>(a.onMessage(forged))
    }

    @Test
    fun qrRoundTrip() {
        val g = GroupInfo.create("FTTH Team ä/&=", wifiSsid = "Site WiFi", wifiPassword = "p@ss&word=1")
        val text = QrCodec.encode(g)
        assertTrue(text.startsWith("chalkie://join?v=1&n="))
        assertEquals(g, QrCodec.decode(text))
    }

    @Test
    fun qrSecretOnly() {
        val g = GroupInfo.create("Team")
        val back = assertNotNull(QrCodec.decode(QrCodec.encode(g)))
        assertNull(back.wifiSsid)
        assertContentEquals(g.secret, back.secret)
    }

    @Test
    fun qrCarriesChannelsAndSupervisorRole() {
        val admin = GroupInfo.create("Team", channels = listOf("Splicing", "Civil|works", " "))
        assertTrue(admin.supervisor, "creator is supervisor")
        val back = assertNotNull(QrCodec.decode(QrCodec.encode(admin)))
        assertEquals("General", back.channelName(0))
        assertEquals("Splicing", back.channelName(1))
        assertEquals("Civilworks", back.channelName(2))
        assertEquals("Channel 3", back.channelName(3))
        assertTrue(back.supervisor)
        val worker = assertNotNull(QrCodec.decode(QrCodec.encode(admin.copy(supervisor = false))))
        assertFalse(worker.supervisor)
    }

    @Test
    fun qrRejectsGarbage() {
        assertNull(QrCodec.decode("https://example.com"))
        assertNull(QrCodec.decode("chalkie://join?v=2&n=a&s=AAAA"))
        assertNull(QrCodec.decode("chalkie://join?v=1&n=a&s=AAAA"))
        assertNull(QrCodec.decode("chalkie://join?v=1&s=" + "A".repeat(43)))
    }
}
