package com.chalkie.app.transport.lan

import com.chalkie.app.core.Clock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.transport.TransportEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanCoreTest {
    /** What the access point lets through. */
    enum class Ap { OPEN, NO_MULTICAST, CLIENT_ISOLATION }

    private var now = 0L
    private val clock = Clock { now }

    private inner class Wifi(var ap: Ap) {
        val cores = LinkedHashMap<InetSocketAddress, LanCore>()
        val events = HashMap<UInt, MutableList<TransportEvent>>()
        private val queue = ArrayDeque<Triple<InetSocketAddress, InetSocketAddress?, ByteArray>>()  // from, to (null = multicast), bytes

        fun join(id: UInt, host: Int, secret: ByteArray = SECRET): LanCore {
            val addr = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, host.toByte())), 47000)
            val evs = mutableListOf<TransportEvent>().also { events[id] = it }
            val core = LanCore(id, GroupKeys(secret), clock, object : UdpIo {
                override fun sendTo(addr2: InetSocketAddress, bytes: ByteArray) { queue += Triple(addr, addr2, bytes) }
                override fun sendMulticast(bytes: ByteArray) { queue += Triple(addr, null, bytes) }
            }) { evs += it }
            cores[addr] = core
            core.networkUp(addr)
            return core
        }

        fun pump() {
            while (queue.isNotEmpty()) {
                val (from, to, bytes) = queue.removeFirst()
                if (ap == Ap.CLIENT_ISOLATION) continue
                if (to == null) {
                    if (ap == Ap.NO_MULTICAST) continue
                    cores.filterKeys { it != from }.values.forEach { it.onDatagram(from, bytes, viaMulticast = true) }
                } else {
                    cores[to]?.onDatagram(from, bytes, viaMulticast = false)
                }
            }
        }

        fun run(ms: Long) {
            val end = now + ms
            while (now < end) {
                now += 250
                cores.values.forEach { it.tick() }
                pump()
            }
        }
    }

    @Test
    fun openApGoesMulticastAndCarriesPackets() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        val b = w.join(2u, 2)
        w.run(2000)
        assertEquals(LanState.ACTIVE_MULTICAST, a.state)
        assertEquals(LanState.ACTIVE_MULTICAST, b.state)
        assertEquals(listOf(2u), a.links().map { it.neighborId })
        assertEquals(listOf(1u), b.links().map { it.neighborId })

        assertTrue(a.send(2u, byteArrayOf(1, 2, 3)))
        assertTrue(a.sendAll(byteArrayOf(4)))
        w.pump()
        val got = w.events.getValue(2u).filterIsInstance<TransportEvent.PacketIn>()
        assertEquals(2, got.size)
        assertContentEquals(byteArrayOf(1, 2, 3), got[0].bytes)
        assertEquals(1u, got[0].neighborId)
    }

    @Test
    fun multicastBlockedFallsBackToUnicastViaBleHints() {
        val w = Wifi(Ap.NO_MULTICAST)
        val a = w.join(1u, 1)
        val b = w.join(2u, 2)
        a.hints = { listOf(LanPeerHint(2u, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)), 47000))) }
        b.hints = { listOf(LanPeerHint(1u, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)), 47000))) }
        w.run(5000)
        assertEquals(LanState.ACTIVE_UNICAST, a.state)
        assertEquals(LanState.ACTIVE_UNICAST, b.state)
        assertEquals(false, a.sendAll(byteArrayOf(1)), "no multicast in unicast mode")
        assertTrue(a.send(2u, byteArrayOf(9)))
        w.pump()
        assertEquals(1, w.events.getValue(2u).count { it is TransportEvent.PacketIn })
    }

    @Test
    fun clientIsolationIsDetected() {
        val w = Wifi(Ap.CLIENT_ISOLATION)
        val a = w.join(1u, 1)
        a.hints = { listOf(LanPeerHint(2u, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)), 47000))) }
        w.join(2u, 2)
        w.run(4750)
        assertEquals(LanState.PROBING, a.state)
        w.run(500)
        assertEquals(LanState.ISOLATED, a.state, "teammate on this Wi-Fi but nothing gets through")
        assertTrue(a.links().isEmpty())
        w.run(30_000)
        assertEquals(LanState.PROBING, a.state, "re-probes after 30 s")
    }

    @Test
    fun aloneOnTheNetworkIsStandby() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        w.run(5250)
        assertEquals(LanState.STANDBY, a.state)
        // A teammate arrives: its probing beacons wake us up.
        w.join(2u, 2)
        w.run(2000)
        assertEquals(LanState.ACTIVE_MULTICAST, a.state)
    }

    @Test
    fun otherGroupNeverLinks() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        w.join(2u, 2, secret = ByteArray(32) { 1 })
        w.run(6000)
        assertTrue(a.links().isEmpty())
        assertEquals(LanState.STANDBY, a.state)
    }

    @Test
    fun linkTimesOutWhenPeerGoesSilentAndRecovers() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        w.join(2u, 2)
        w.run(2000)
        assertEquals(1, a.links().size)
        w.ap = Ap.CLIENT_ISOLATION            // AP starts dropping (e.g. roamed away)
        w.run(6500)
        assertTrue(a.links().isEmpty())
        assertTrue(w.events.getValue(1u).any { it is TransportEvent.LinkDown })
        w.ap = Ap.OPEN
        w.run(3000)
        assertEquals(1, a.links().size, "relinks once traffic flows again")
    }

    @Test
    fun packetsFromUnknownAddressesAreIgnored() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        w.run(1000)
        a.onDatagram(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 66)), 47000),
            byteArrayOf(LanCore.KIND_PACKET, 1, 2, 3), viaMulticast = false)
        assertTrue(w.events.getValue(1u).none { it is TransportEvent.PacketIn })
    }

    @Test
    fun networkChangeReprobes() {
        val w = Wifi(Ap.OPEN)
        val a = w.join(1u, 1)
        w.join(2u, 2)
        w.run(2000)
        a.networkChanged()
        assertEquals(LanState.PROBING, a.state)
        assertTrue(a.links().isEmpty())
        w.run(2000)
        assertEquals(LanState.ACTIVE_MULTICAST, a.state)
    }

    private companion object {
        val SECRET = ByteArray(32) { 42 }
    }
}
