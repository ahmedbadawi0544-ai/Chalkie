package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.Ids
import com.chalkie.app.core.PacketCodec
import com.chalkie.app.core.PacketType
import com.chalkie.app.core.Priority
import com.chalkie.app.crypto.CryptoBox
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.election.StarRole
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.LinkSendQueue
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingUnitTest {
    private var now = 0L
    private val clock = Clock { now }
    private val crypto = CryptoBox(GroupKeys(ByteArray(32) { 3 }).encKey)

    @Test
    fun dedupExpiresAndEvicts() {
        val d = DedupCache(clock, maxEntries = 3, ttlMs = 1000)
        d.add(1u, 1); d.add(1u, 2); d.add(1u, 3)
        assertTrue(d.contains(1u, 1))
        d.add(1u, 4)
        assertFalse(d.contains(1u, 1), "LRU evicts oldest")
        now = 1500
        assertFalse(d.contains(1u, 4), "expired")
        assertEquals(0, d.size())
    }

    @Test
    fun samePacketViaLanAndBleDeliveredOnce() {
        val router = Router(myId = 2u, sessionId = 1u, crypto = crypto, clock = clock)
        var delivered = 0
        router.listener = Router.Listener { _, _, _ -> delivered++ }
        val pkt = PacketCodec.encode(crypto, PacketType.VOICE, 0, 1u, Ids.BROADCAST, 77, 0u, ByteArray(10))
        router.onPacketIn(TransportType.LAN, 1u, pkt)
        router.onPacketIn(TransportType.BLE, 1u, PacketCodec.forRelay(pkt))
        router.onPacketIn(TransportType.WD, 5u, pkt)
        assertEquals(1, delivered)
        assertEquals(2, router.stats.duplicates.get())
    }

    @Test
    fun replayWindowRejectsOldTimestamps() {
        val router = Router(myId = 2u, sessionId = 1u, crypto = crypto, clock = clock)
        var delivered = 0
        router.listener = Router.Listener { _, _, _ -> delivered++ }
        fun pkt(msgId: Long, ts: UInt) = PacketCodec.encode(crypto, PacketType.TEXT, 0, 1u, Ids.BROADCAST, msgId, ts, ByteArray(1))
        router.onPacketIn(TransportType.BLE, 1u, pkt(1, 100_000u))
        router.onPacketIn(TransportType.BLE, 1u, pkt(2, 100_000u - 30_000u))   // within window
        router.onPacketIn(TransportType.BLE, 1u, pkt(3, 100_000u - 61_000u))   // too old
        assertEquals(2, delivered)
        assertEquals(1, router.stats.replayed.get())
    }

    @Test
    fun linkCostRules() {
        fun l(t: TransportType, rssi: Int? = null, loss: Float = 0f) = LinkInfo(1u, t, rssi, loss, null, 0)
        assertEquals(0.5, LinkCost.of(l(TransportType.LAN)))
        assertEquals(1.0, LinkCost.of(l(TransportType.WD)))
        assertEquals(2.0, LinkCost.of(l(TransportType.BLE, -70)))
        assertEquals(4.0, LinkCost.of(l(TransportType.BLE, -71)))
        assertEquals(2.0, LinkCost.of(l(TransportType.WD, loss = 0.2f)))
        assertNull(LinkCost.of(l(TransportType.LAN, loss = 0.31f)))
        val best = LinkCost.best(listOf(l(TransportType.LAN, loss = 0.5f), l(TransportType.BLE, -60)))
        assertEquals(TransportType.BLE, best?.type)
    }

    @Test
    fun helloRoundTrip() {
        val h = HelloPayload(
            name = "Ahmed — FTTH crew long name here", battery = 57, charging = true, onLan = true,
            lanIp = byteArrayOf(10, 0, 0, 7), lanPort = 47000, ssidHash = 0x1234, roles = setOf(Role.HUB),
            hubId = 9u, hubEpoch = 4, bridgeIds = listOf(1u, 2u, 3u),
            neighbors = listOf(ReportedNeighbor(5u, TransportType.BLE, -66), ReportedNeighbor(6u, TransportType.LAN, null)),
            hubScore = 0.7f, bridgeScore = 0.2f,
        )
        val back = assertNotNull(HelloPayload.decode(h.encode()))
        assertTrue(back.name.toByteArray().size <= ChalkieConfig.HELLO_MAX_NAME_BYTES)
        assertTrue(h.name.startsWith(back.name))
        assertEquals(57, back.battery)
        assertTrue(back.charging && back.onLan)
        assertContentEquals(byteArrayOf(10, 0, 0, 7), back.lanIp)
        assertEquals(0x1234, back.ssidHash)
        assertEquals(setOf(Role.HUB), back.roles)
        assertEquals(listOf(1u, 2u), back.bridgeIds)
        assertEquals(h.neighbors, back.neighbors)
        assertEquals(0.7f, back.hubScore)
        assertNull(HelloPayload.decode(byteArrayOf(3, 1)))
        val probing = HelloPayload.decode(HelloPayload("p", 50, false, onLan = false, lanIp = byteArrayOf(10, 0, 0, 9), lanPort = 47000).encode())
        assertContentEquals(byteArrayOf(10, 0, 0, 9), probing?.lanIp, "IP shared before LAN is active")
    }

    @Test
    fun sendQueuePriorityAndStaleVoice() {
        val q = LinkSendQueue()
        q.offer(byteArrayOf(1), Priority.NORMAL, 0)
        q.offer(byteArrayOf(2), Priority.VOICE, 0)
        q.offer(byteArrayOf(3), Priority.VOICE, 150)
        q.offer(byteArrayOf(4), Priority.CONTROL, 0)
        q.offer(byteArrayOf(5), Priority.SOS, 0)
        assertContentEquals(byteArrayOf(5), q.poll(250))
        assertContentEquals(byteArrayOf(4), q.poll(250))
        assertContentEquals(byteArrayOf(3), q.poll(250), "voice from t=0 is 250 ms old → dropped")
        assertEquals(1, q.droppedStaleVoice)
        assertContentEquals(byteArrayOf(1), q.poll(250))
        assertNull(q.poll(250))
    }

    /** Records sends to its fixed set of linked peers. */
    private class RecordingTransport(override val type: TransportType, peers: List<UInt>) : Transport {
        val sends = mutableListOf<UInt>()
        private val links = peers.map { LinkInfo(it, type, if (type == TransportType.BLE) -39 else null, 0f, null, 0) }
        override val events: Flow<TransportEvent> = emptyFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean =
            links.any { it.neighborId == neighborId }.also { if (it) sends += neighborId }
        override fun links(): List<LinkInfo> = links
    }

    private class FixedStar(override val role: StarRole, private val members: Set<UInt>) : Router.StarView {
        override fun covers(nodeId: UInt) = nodeId in members
    }

    private fun voice(src: UInt, msgId: Long) =
        PacketCodec.encode(crypto, PacketType.VOICE, 0, src, Ids.BROADCAST, msgId, 0u, ByteArray(40))

    @Test
    fun wdIsCheaperThanBle() {
        val entry = NeighborEntry(1u)
        entry.links[TransportType.BLE] = LinkInfo(1u, TransportType.BLE, -39, 0f, null, 0)
        entry.links[TransportType.WD] = LinkInfo(1u, TransportType.WD, null, 0f, null, 0)
        assertEquals(TransportType.WD, entry.bestLink?.type, "SPEC §6: WD 1.0 < BLE 2.0")
    }

    @Test
    fun leafSendsVoiceOverWdAndNoBleDuplicateToTheHub() {
        // Tab (1) is hub, phone (2) is its leaf; they also share a BLE link.
        val router = Router(myId = 2u, sessionId = 1u, crypto = crypto, clock = clock)
        val wd = RecordingTransport(TransportType.WD, listOf(1u))
        val ble = RecordingTransport(TransportType.BLE, listOf(1u))
        router.attach(wd); router.attach(ble)
        router.star = FixedStar(StarRole.Leaf(1u, 1), setOf(1u))

        router.originate(PacketType.VOICE, 0, ByteArray(40))
        assertEquals(listOf(1u), wd.sends)
        assertTrue(ble.sends.isEmpty(), "hub is covered by the star: no BLE duplicate")
        assertEquals(1, router.stats.tx.getValue(TransportType.WD).get())
        assertEquals(0, router.stats.tx.getValue(TransportType.BLE).get())

        router.originate(PacketType.HELLO, 0, ByteArray(8))
        assertEquals(listOf(1u), ble.sends, "HELLO still goes over BLE: it is the BLE keepalive")
    }

    @Test
    fun hubForwardsLeafVoiceOverWdOnly() {
        val router = Router(myId = 1u, sessionId = 1u, crypto = crypto, clock = clock)
        val wd = RecordingTransport(TransportType.WD, listOf(2u, 3u))
        val ble = RecordingTransport(TransportType.BLE, listOf(2u, 3u))
        router.attach(wd); router.attach(ble)
        router.star = FixedStar(StarRole.Hub(1), setOf(2u, 3u))

        router.onPacketIn(TransportType.WD, 2u, voice(src = 2u, msgId = 5))
        assertEquals(listOf(3u), wd.sends, "hub relays to the other leaf only")
        assertTrue(ble.sends.isEmpty(), "both leaves are covered by the star")
        assertEquals(1, router.stats.rx.getValue(TransportType.WD).get())

        router.originate(PacketType.VOICE, 0, ByteArray(40))
        assertEquals(listOf(3u, 2u, 3u), wd.sends)
        assertTrue(ble.sends.isEmpty())
    }

    @Test
    fun bleCarriesVoiceWhenTheWdLinkIsMissing() {
        val router = Router(myId = 2u, sessionId = 1u, crypto = crypto, clock = clock)
        val wd = RecordingTransport(TransportType.WD, emptyList())      // star elected, link not up yet
        val ble = RecordingTransport(TransportType.BLE, listOf(1u))
        router.attach(wd); router.attach(ble)
        router.star = FixedStar(StarRole.Leaf(1u, 1), emptySet())

        router.originate(PacketType.VOICE, 0, ByteArray(40))
        assertEquals(listOf(1u), ble.sends)
    }

    @Test
    fun topologyNextHopPrefersCheapPaths() {
        fun snap(id: UInt, links: Map<TransportType, LinkInfo> = emptyMap(), reported: List<ReportedNeighbor> = emptyList()) =
            NeighborSnapshot(id, "n$id", links, 50, false, false, emptySet(), 0u, 0, reported, 0, 0)
        val me = 1u
        // 1 —BLE weak— 4 directly, or 1 —WD— 2 —LAN— 4.
        val nodes = listOf(
            snap(4u, mapOf(TransportType.BLE to LinkInfo(4u, TransportType.BLE, -85, 0f, null, 0))),
            snap(2u, mapOf(TransportType.WD to LinkInfo(2u, TransportType.WD, null, 0f, null, 0)),
                listOf(ReportedNeighbor(4u, TransportType.LAN, null), ReportedNeighbor(1u, TransportType.BLE, -50))),
        )
        val edges = TopologyGraph.edges(me, nodes)
        assertEquals(2u, TopologyGraph.nextHop(me, 4u, edges), "WD 1.0 + LAN 0.5 < BLE weak 4.0")
        assertEquals(2u, TopologyGraph.nextHop(me, 2u, edges))
        assertNull(TopologyGraph.nextHop(me, 9u, edges))
        assertTrue(edges.none { it.type == TransportType.BLE && setOf(it.a, it.b) == setOf(1u, 2u) }, "stale reports about my links ignored")
    }

    @Test
    fun unicastFloodsWhenThereIsNoRoute() {
        val router = Router(myId = 1u, sessionId = 1u, crypto = crypto, clock = clock)
        val ble = RecordingTransport(TransportType.BLE, listOf(2u, 3u))
        router.attach(ble)
        router.unicast = Router.UnicastRoute { null }
        router.originate(PacketType.TEXT, 0, ByteArray(4), dstId = 9u)
        assertEquals(listOf(2u, 3u), ble.sends)

        ble.sends.clear()
        router.unicast = Router.UnicastRoute { 3u to TransportType.BLE }
        router.originate(PacketType.TEXT, 0, ByteArray(4), dstId = 9u)
        assertEquals(listOf(3u), ble.sends, "route known: one send to the next hop")
    }
}
