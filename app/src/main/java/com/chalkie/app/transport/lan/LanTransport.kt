package com.chalkie.app.transport.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.Priority
import com.chalkie.app.core.SystemClock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.transport.BroadcastCapable
import com.chalkie.app.transport.LanStatus
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.CRC32
import kotlin.concurrent.thread

/**
 * Building Wi-Fi transport (SPEC §11.1). Owns the Android side only: the Wi-Fi network
 * callback (no INTERNET capability required), sockets bound to that network so nothing leaks
 * to mobile data, the multicast lock, and socket threads. All logic lives in [LanCore].
 *
 * - UDP 47000: data + unicast beacons/handshakes; also the source of every send.
 * - UDP 239.255.42.99:47001: multicast beacons, and packets in ACTIVE_MULTICAST.
 */
class LanTransport(
    context: Context,
    myId: UInt,
    keys: GroupKeys,
    private val clock: Clock = SystemClock,
) : Transport, LanStatus, BroadcastCapable {
    override val type = TransportType.LAN

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val group = InetSocketAddress(InetAddress.getByName(ChalkieConfig.LAN_MCAST_ADDR), ChalkieConfig.LAN_MCAST_PORT)

    private val outbox = LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>(1024)
    private val io = object : UdpIo {
        // Router sends can come from the main thread: never touch sockets here.
        override fun sendTo(addr: InetSocketAddress, bytes: ByteArray) { outbox.offer(addr to bytes) }
        override fun sendMulticast(bytes: ByteArray) { outbox.offer(group to bytes) }
    }
    private val core = LanCore(myId, keys, clock, io) { _events.tryEmit(it) }

    /** Same-network teammates (from HELLOs) to probe by unicast when multicast is blocked. */
    var hints: () -> List<LanPeerHint>
        get() = core.hints
        set(value) { core.hints = value }

    private var scope: CoroutineScope? = null
    @Volatile private var started = false
    private var network: Network? = null
    private var dataSock: MulticastSocket? = null
    private var mcastSock: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var localIp: Inet4Address? = null
    @Volatile override var ssidHash: Int? = null
        private set

    val state: LanState get() = core.state

    // ---- LanStatus ----

    override val lanActive: Boolean get() = core.state.active
    override val lanIp: ByteArray? get() = localIp?.address
    override val lanPort: Int get() = ChalkieConfig.LAN_DATA_PORT

    // ---- Transport ----

    override suspend fun start() {
        if (started) return
        started = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // offline sites have no internet
            .build()
        cm.registerNetworkCallback(request, callback)
        s.launch {
            while (isActive) {
                core.tick()
                delay(ChalkieConfig.LAN_TICK_MS)
            }
        }
        thread(name = "lan-tx", isDaemon = true) { senderLoop() }
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(callback) }
        teardown()
        outbox.clear()
        outbox.offer(POISON)
        scope?.cancel()
        scope = null
    }

    override fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean = core.send(neighborId, bytes)

    override fun sendAll(bytes: ByteArray, priority: Priority): Boolean = core.sendAll(bytes)

    override fun links(): List<LinkInfo> = core.links()

    // ---- network ----

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = onNetwork(n, lp)

        override fun onLost(n: Network) {
            if (n == network) {
                Log.i(TAG, "Wi-Fi lost")
                teardown()
            }
        }
    }

    @Synchronized
    private fun onNetwork(n: Network, lp: LinkProperties) {
        if (!started) return
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        val ip = la.address as Inet4Address
        if (n == network && ip == localIp) return          // nothing changed
        if (network != null && n != network) return        // stay on the current Wi-Fi until it is lost
        val roamed = network != null
        teardown()
        try {
            openSockets(n, lp)
        } catch (e: IOException) {
            Log.w(TAG, "LAN sockets failed: ${e.message}")
            teardown()
            return
        }
        network = n
        localIp = ip
        ssidHash = networkHash(ip, la.prefixLength, lp)
        Log.i(TAG, "Wi-Fi up ${ip.hostAddress}/${la.prefixLength}${if (roamed) " (changed)" else ""}")
        core.networkUp(InetSocketAddress(ip, ChalkieConfig.LAN_DATA_PORT))
    }

    private fun openSockets(n: Network, lp: LinkProperties) {
        val iface = lp.interfaceName?.let(NetworkInterface::getByName)
        dataSock = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(ChalkieConfig.LAN_DATA_PORT))
            n.bindSocket(this)
            iface?.let { networkInterface = it }   // outgoing multicast leaves through Wi-Fi
            loopbackMode = true                    // true = disable loopback of my own multicast
        }
        mcastSock = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(ChalkieConfig.LAN_MCAST_PORT))
            n.bindSocket(this)
            joinGroup(group, iface)
        }
        multicastLock = wifi.createMulticastLock("chalkie").apply { setReferenceCounted(false); acquire() }
        val d = dataSock!!
        val m = mcastSock!!
        thread(name = "lan-rx", isDaemon = true) { receiveLoop(d, viaMulticast = false) }
        thread(name = "lan-rx-mc", isDaemon = true) { receiveLoop(m, viaMulticast = true) }
    }

    @Synchronized
    private fun teardown() {
        core.networkDown()
        runCatching { dataSock?.close() }
        runCatching { mcastSock?.close() }
        dataSock = null
        mcastSock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        network = null
        localIp = null
        ssidHash = null
    }

    private fun receiveLoop(sock: MulticastSocket, viaMulticast: Boolean) {
        val buf = ByteArray(ChalkieConfig.LAN_MAX_DATAGRAM + 64)
        while (!sock.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                sock.receive(p)
            } catch (_: IOException) {
                break
            }
            val from = p.socketAddress as? InetSocketAddress ?: continue
            core.onDatagram(from, p.data.copyOfRange(p.offset, p.offset + p.length), viaMulticast)
        }
    }

    private fun senderLoop() {
        while (true) {
            val (addr, bytes) = outbox.take()
            if (bytes === POISON.second) break
            val sock = dataSock ?: continue
            try {
                sock.send(DatagramPacket(bytes, bytes.size, addr))
            } catch (e: IOException) {
                Log.d(TAG, "send to $addr failed: ${e.message}")
            }
        }
    }

    /**
     * Identifies "the same network" for HELLO without the SSID (which would need the location
     * permission on API 29+): CRC of the subnet, prefix length and default gateway.
     */
    private fun networkHash(ip: Inet4Address, prefix: Int, lp: LinkProperties): Int {
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val subnet = ByteBuffer.wrap(ip.address).int and mask
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.address ?: ByteArray(4)
        val crc = CRC32()
        crc.update(ByteBuffer.allocate(9).putInt(subnet).put(prefix.toByte()).put(gateway).array())
        return crc.value.toInt()
    }

    private companion object {
        const val TAG = "LanTransport"
        val POISON = InetSocketAddress(0) to ByteArray(0)
    }
}
