package com.chalkie.app.sim

import com.chalkie.app.core.Clock
import com.chalkie.app.core.PacketType
import com.chalkie.app.election.StarRole
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.LocalStatus
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 3: building Wi-Fi + bridges, on the JVM simulator (SPEC §15). */
@OptIn(ExperimentalCoroutinesApi::class)
class LanSimTest {
    private val secret = ByteArray(32) { 42 }
    private val frame = ByteArray(160) { 0x7F }
    private val all = setOf(TransportType.BLE, TransportType.WD, TransportType.LAN)
    private val offLan = setOf(TransportType.BLE, TransportType.WD)

    private class Net(val sim: SimNetwork, val voice: Map<UInt, MutableList<NodeEvent.VoiceIn>>) {
        fun node(id: UInt): ChalkieNode = sim.nodes.getValue(id)
        fun heard(id: UInt, from: UInt) = voice.getValue(id).count { it.srcId == from }
        fun bridges() = sim.nodes.filterValues { it.bridges.amBridge }.keys
    }

    private fun TestScope.build(types: Map<UInt, Set<TransportType>>, charging: Set<UInt> = emptySet()): Net {
        val sim = SimNetwork(backgroundScope, Clock { testScheduler.currentTime })
        val voice = types.keys.associateWith { mutableListOf<NodeEvent.VoiceIn>() }
        for ((id, t) in types) {
            val node = sim.addNode(id, secret, t, LocalStatus("n$id", 60, id in charging))
            backgroundScope.launch { node.events.collect { if (it is NodeEvent.VoiceIn) voice.getValue(id) += it } }
        }
        runCurrent()
        backgroundScope.launch { sim.nodes.values.forEach { it.start() } }
        runCurrent()
        return Net(sim, voice)
    }

    private fun Net.lan(ids: List<UInt>) {
        for (i in ids.indices) for (j in i + 1 until ids.size) sim.connect(ids[i], ids[j], TransportType.LAN, LinkParams(latencyMs = 3, rssi = null))
    }

    private fun Net.ble(a: UInt, b: UInt, rssi: Int = -60) = sim.connect(a, b, TransportType.BLE, LinkParams(rssi = rssi))

    private fun TestScope.talk(node: ChalkieNode, packets: Int) {
        assertTrue(node.pttDown(), "floor should be granted")
        repeat(packets) {
            node.sendVoice(listOf(frame, frame))
            advanceTimeBy(40)
        }
        node.pttUp()
        advanceTimeBy(300)
    }

    private fun Net.bleVoice() = sim.totalSent(TransportType.BLE, PacketType.VOICE)

    @Test
    fun fullLanSendsNoBleVoiceAndHasNoHub() = runTest {
        val ids = (1u..4u).toList()
        val net = build(ids.associateWith { all })
        net.lan(ids)
        for (i in ids.indices) for (j in i + 1 until ids.size) net.ble(ids[i], ids[j])
        advanceTimeBy(10_000)

        val before = net.bleVoice()
        talk(net.node(1u), 25)
        assertEquals(0, net.bleVoice() - before, "LAN covers everyone")
        ids.drop(1).forEach { assertEquals(25, net.heard(it, 1u), "node $it") }
        ids.forEach { assertEquals(StarRole.Off, net.node(it).election.role, "all on LAN → no hub, WD off") }
        assertTrue(net.bridges().isEmpty(), "nobody off-LAN → no bridge")
    }

    @Test
    fun twoOnLanPlusOneOffLanAllTalkWithExactlyTheNeededRelays() = runTest {
        // SPEC P3 done test: 1 and 2 on LAN, 3 off-LAN with BLE only to 1.
        val net = build(mapOf(1u to all, 2u to all, 3u to offLan))
        net.lan(listOf(1u, 2u))
        net.ble(1u, 2u)
        net.ble(1u, 3u)
        advanceTimeBy(10_000)
        assertEquals(setOf(1u), net.bridges(), "1 links the LAN to node 3")

        var before = net.bleVoice()
        talk(net.node(2u), 25)
        assertEquals(25, net.heard(3u, 2u))
        assertEquals(25, net.heard(1u, 2u))
        assertEquals(25, net.bleVoice() - before, "one BLE relay per packet: 1 → 3")

        before = net.bleVoice()
        talk(net.node(3u), 25)
        assertEquals(25, net.heard(2u, 3u), "bridge relays BLE → LAN")
        assertEquals(25, net.bleVoice() - before, "only 3 → 1 on BLE; 2 is LAN-covered")

        listOf(1u, 2u, 3u).forEach { assertEquals(StarRole.Off, net.node(it).election.role, "only one off-LAN node → no hub") }
    }

    @Test
    fun bridgeElectionPicksTopTwo() = runTest {
        val lanIds = (1u..4u).toList()
        val net = build(lanIds.associateWith { all } + (5u to offLan))
        net.lan(lanIds)
        net.ble(5u, 1u, rssi = -50)
        net.ble(5u, 2u, rssi = -60)
        net.ble(5u, 3u, rssi = -80)
        advanceTimeBy(15_000)
        assertEquals(setOf(1u, 2u), net.bridges())

        talk(net.node(5u), 10)
        lanIds.forEach { assertEquals(10, net.heard(it, 5u), "node $it") }
    }

    @Test
    fun lanNodesNeverBecomeHub() = runTest {
        val net = build(mapOf(1u to all, 2u to all, 3u to offLan, 4u to offLan), charging = setOf(1u))
        net.lan(listOf(1u, 2u))
        for (a in 1u..4u) for (b in (a + 1u)..4u) net.ble(a, b)
        advanceTimeBy(15_000)
        assertEquals(StarRole.Off, net.node(1u).election.role, "charging, but on LAN")
        assertEquals(StarRole.Off, net.node(2u).election.role)
        assertIs<StarRole.Hub>(net.node(3u).election.role)
        assertEquals(StarRole.Leaf(3u, (net.node(3u).election.role as StarRole.Hub).epoch), net.node(4u).election.role)

        // Everyone hears everyone across LAN, bridge, star.
        talk(net.node(2u), 10)
        listOf(1u, 3u, 4u).forEach { assertEquals(10, net.heard(it, 2u), "node $it") }
        talk(net.node(4u), 10)
        listOf(1u, 2u, 3u).forEach { assertEquals(10, net.heard(it, 4u), "node $it") }
    }

    @Test
    fun leavingTheLanFallsBackToBle() = runTest {
        val ids = (1u..3u).toList()
        val net = build(ids.associateWith { all })
        net.lan(ids)
        for (a in 1u..3u) for (b in (a + 1u)..3u) net.ble(a, b)
        advanceTimeBy(10_000)
        net.sim.disconnect(1u, 3u, TransportType.LAN)
        net.sim.disconnect(2u, 3u, TransportType.LAN)       // 3 walks out of Wi-Fi range
        advanceTimeBy(5000)
        talk(net.node(1u), 10)
        assertEquals(10, net.heard(3u, 1u))
        talk(net.node(3u), 10)
        assertEquals(10, net.heard(2u, 3u))
    }
}
