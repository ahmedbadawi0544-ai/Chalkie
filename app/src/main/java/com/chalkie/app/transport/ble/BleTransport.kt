package com.chalkie.app.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.Ids
import com.chalkie.app.core.Priority
import com.chalkie.app.core.SystemClock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.crypto.LinkHandshake
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.LinkPreparer
import com.chalkie.app.transport.LinkSendQueue
import com.chalkie.app.transport.TalkAware
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * BLE mesh transport (SPEC §11.3): legacy advertising carries
 * `"CK" ‖ groupTag ‖ nodeId ‖ psm ‖ flags`; links are insecure L2CAP CoC,
 * authenticated with the §5 handshake, framed as `length(2) ‖ packet`.
 */
@SuppressLint("MissingPermission")   // ChalkieService only starts after permissions are granted.
class BleTransport(
    context: Context,
    private val myId: UInt,
    private val keys: GroupKeys,
    private val clock: Clock = SystemClock,
) : Transport, TalkAware, LinkPreparer {
    override val type = TransportType.BLE

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var scope: CoroutineScope? = null
    @Volatile private var started = false   // transport wanted by the node
    @Volatile private var running = false   // radio actually advertising/scanning
    private var server: BluetoothServerSocket? = null

    /** A peer seen in scan results. */
    private class Candidate(var device: BluetoothDevice, var psm: Int, var rssi: Double, var lastSeenMs: Long) {
        var lastAttemptMs = Long.MIN_VALUE / 2
        var strongerSinceMs: Long? = null
    }

    private val candidates = ConcurrentHashMap<UInt, Candidate>()
    private val links = ConcurrentHashMap<UInt, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<UInt>()
    private val blacklist = ConcurrentHashMap<String, Long>()   // address -> until
    /** Make-before-break: neighbors to link with first, until the given time (SPEC §19.2). */
    private val preferred = ConcurrentHashMap<UInt, Long>()
    /** Low-power scanning while a talk is active (SPEC §19.5). */
    @Volatile private var lowPowerScan = false

    /** Live state for the UI. */
    @Volatile var state: String = "off"
        private set

    // ---- Transport ----

    override suspend fun start() {
        if (started) return
        started = true
        // Follow the Bluetooth switch: the radio may be off at start, or toggled later.
        appContext.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (adapter?.isEnabled == true) startRadio() else state = STATE_BT_OFF
    }

    override suspend fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(adapterReceiver) }
        stopRadio()
        state = "off"
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (started) startRadio()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopRadio()
                    state = STATE_BT_OFF
                }
            }
        }
    }

    @Synchronized
    private fun startRadio() {
        if (running) return
        val srv = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            Log.w(TAG, "L2CAP listen failed: ${e.message}")
            state = "L2CAP unavailable"
            return
        }
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        server = srv
        s.launch { acceptLoop(srv) }
        startAdvertising(srv.psm)
        startScanning()
        s.launch { maintenanceLoop() }
        state = "active"
        Log.i(TAG, "radio started, psm=${srv.psm}")
    }

    @Synchronized
    private fun stopRadio() {
        if (!running) return
        running = false
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { server?.close() }
        // Report every link as down before forgetting it, so the node drops them too.
        links.forEach { (id, link) ->
            link.close()
            if (links.remove(id, link)) _events.tryEmit(TransportEvent.LinkDown(TransportType.BLE, id))
        }
        connecting.clear()
        candidates.clear()
        scope?.cancel()
        scope = null
        Log.i(TAG, "radio stopped")
    }

    override fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean =
        links[neighborId]?.enqueue(bytes, priority) ?: false

    override fun links(): List<LinkInfo> = links.values.map { it.info() }

    override fun setTalkActive(active: Boolean) {
        if (active == lowPowerScan) return
        lowPowerScan = active
        synchronized(this) {
            if (!running) return
            runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
            startScanning()
        }
        Log.i(TAG, "scan mode: ${if (active) "low power (talk)" else "low latency"}")
    }

    override fun prepareLink(neighborId: UInt) {
        if (links.containsKey(neighborId)) return
        preferred[neighborId] = clock.nowMs() + ChalkieConfig.BLE_PREPARE_HOLD_MS
        Log.i(TAG, "make-before-break: preparing a link to ${Ids.hex(neighborId)}")
        maybeConnect()
    }

    private fun isPreferred(id: UInt, now: Long): Boolean {
        val until = preferred[id] ?: return false
        if (now < until) return true
        preferred.remove(id)
        return false
    }

    // ---- advertising / scanning ----

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            state = "advertise failed ($errorCode)"
        }
    }

    private fun startAdvertising(psm: Int) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { state = "no BLE advertiser"; return }
        val payload = ByteBuffer.allocate(MANUF_LEN)
            .put(MAGIC).put(keys.groupTag).putInt(myId.toInt()).putShort(psm.toShort()).put(0)
            .array()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(ChalkieConfig.BLE_COMPANY_ID, payload)
            .build()
        val scanResponse = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        advertiser.startAdvertising(settings, data, scanResponse, advCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onScan)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            state = "scan failed ($errorCode)"
        }
    }

    private fun startScanning() {
        val scanner = adapter.bluetoothLeScanner ?: return
        val prefix = MAGIC + keys.groupTag
        val mask = ByteArray(prefix.size) { 0xFF.toByte() }
        val filter = ScanFilter.Builder()
            .setManufacturerData(ChalkieConfig.BLE_COMPANY_ID, prefix, mask)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPowerScan) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun onScan(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ChalkieConfig.BLE_COMPANY_ID) ?: return
        if (data.size < MANUF_LEN) return
        if (!data.copyOfRange(0, 2).contentEquals(MAGIC) || !data.copyOfRange(2, 6).contentEquals(keys.groupTag)) return
        val buf = ByteBuffer.wrap(data)
        val nodeId = buf.getInt(6).toUInt()
        val psm = buf.getShort(10).toInt() and 0xFFFF
        if (nodeId == myId) return
        val now = clock.nowMs()
        val c = candidates[nodeId]
        if (c == null) {
            candidates[nodeId] = Candidate(result.device, psm, result.rssi.toDouble(), now)
        } else {
            c.device = result.device
            c.psm = psm
            c.rssi = ChalkieConfig.BLE_RSSI_EMA_ALPHA * result.rssi + (1 - ChalkieConfig.BLE_RSSI_EMA_ALPHA) * c.rssi
            c.lastSeenMs = now
        }
        maybeConnect()
    }

    // ---- link selection (SPEC §11.3 connection rules) ----

    @Synchronized
    private fun maybeConnect() {
        if (!running) return
        val now = clock.nowMs()
        // Backup links for degrading ones first, then strongest first.
        val sorted = candidates.entries.sortedWith(
            compareByDescending<Map.Entry<UInt, Candidate>> { isPreferred(it.key, now) }.thenByDescending { it.value.rssi },
        )
        for ((id, c) in sorted) {
            if (myId > id) continue                          // only the lower nodeId initiates
            if (links.containsKey(id) || id in connecting) continue
            if (isBlacklisted(c.device.address, now)) continue
            if (now - c.lastAttemptMs < ChalkieConfig.BLE_CONNECT_RETRY_MS) continue
            val spare = if (isPreferred(id, now)) ChalkieConfig.BLE_SPARE_LINKS else 0
            if (links.size + connecting.size < ChalkieConfig.BLE_MAX_LINKS + spare) {
                connect(id, c)
            } else {
                considerSwap(c, now)
            }
        }
    }

    /** Drop the weakest link if [c] has been ≥10 dB stronger for 5 s. */
    private fun considerSwap(c: Candidate, now: Long) {
        val weakest = links.values.minByOrNull { rssiOf(it.peerId) ?: Double.MAX_VALUE } ?: return
        val weakRssi = rssiOf(weakest.peerId) ?: return
        if (c.rssi >= weakRssi + ChalkieConfig.BLE_SWAP_RSSI_MARGIN_DB) {
            val since = c.strongerSinceMs ?: now.also { c.strongerSinceMs = it }
            if (now - since >= ChalkieConfig.BLE_SWAP_HOLD_MS) {
                Log.i(TAG, "swap: dropping ${Ids.hex(weakest.peerId)} for stronger candidate")
                c.strongerSinceMs = null
                weakest.close()
            }
        } else {
            c.strongerSinceMs = null
        }
    }

    private fun connect(id: UInt, c: Candidate) {
        val s = scope ?: return
        c.lastAttemptMs = clock.nowMs()
        connecting += id
        s.launch {
            try {
                val sock = c.device.createInsecureL2capChannel(c.psm)
                sock.connect()
                runLink(sock, expectedId = id)
            } catch (e: IOException) {
                Log.d(TAG, "connect ${Ids.hex(id)} failed: ${e.message}")
            } finally {
                connecting -= id
            }
        }
    }

    private suspend fun acceptLoop(srv: BluetoothServerSocket) {
        while (running) {
            val sock = try {
                srv.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            val addr = sock.remoteDevice.address
            // One spare slot: the other side may be making a backup link to me (make-before-break).
            if (isBlacklisted(addr, clock.nowMs()) || links.size >= ChalkieConfig.BLE_MAX_LINKS + ChalkieConfig.BLE_SPARE_LINKS) {
                runCatching { sock.close() }
                continue
            }
            scope?.launch { runLink(sock, expectedId = null) }
        }
    }

    private fun isBlacklisted(address: String, now: Long): Boolean {
        val until = blacklist[address] ?: return false
        if (now < until) return true
        blacklist.remove(address)
        return false
    }

    private fun rssiOf(id: UInt): Double? = candidates[id]?.rssi

    // ---- one link ----

    /** Handshake, then read until the socket dies. Runs on an IO coroutine. */
    private suspend fun runLink(sock: BluetoothSocket, expectedId: UInt?) {
        val addr = sock.remoteDevice.address
        val input = DataInputStream(BufferedInputStream(sock.inputStream))
        val output = DataOutputStream(BufferedOutputStream(sock.outputStream))
        val hs = LinkHandshake(keys.authKey, myId)
        val watchdog = scope?.launch {
            delay(ChalkieConfig.HANDSHAKE_TIMEOUT_MS)
            if (!hs.finished) runCatching { sock.close() }
        }
        val peerId: UInt
        try {
            writeFrame(output, hs.start())
            var done: UInt? = null
            while (done == null) {
                when (val step = hs.onMessage(readFrame(input))) {
                    is LinkHandshake.Step.Send -> writeFrame(output, step.bytes)
                    is LinkHandshake.Step.Done -> { step.bytes?.let { writeFrame(output, it) }; done = step.peerId }
                    LinkHandshake.Step.Wait -> Unit
                    LinkHandshake.Step.Fail -> throw HandshakeFailed()
                }
            }
            peerId = done
            if (expectedId != null && peerId != expectedId) throw HandshakeFailed()
        } catch (e: HandshakeFailed) {
            Log.w(TAG, "handshake failed with $addr; blacklisting")
            blacklist[addr] = clock.nowMs() + ChalkieConfig.HANDSHAKE_BLACKLIST_MS
            runCatching { sock.close() }
            return
        } catch (e: IOException) {
            runCatching { sock.close() }
            return
        } finally {
            watchdog?.cancel()
        }

        val link = Link(peerId, sock, output)
        if (links.putIfAbsent(peerId, link) != null) {
            runCatching { sock.close() }            // duplicate link to the same node
            return
        }
        connecting -= peerId
        _events.tryEmit(TransportEvent.LinkUp(link.info()))
        Log.i(TAG, "link up ${Ids.hex(peerId)} ($addr)")
        link.startWriter()
        try {
            while (running && !link.closed) {
                val bytes = readFrame(input)
                link.lastRxMs = clock.nowMs()
                _events.tryEmit(TransportEvent.PacketIn(TransportType.BLE, peerId, bytes))
            }
        } catch (_: IOException) {
        } finally {
            link.close()
            if (links.remove(peerId, link)) {
                _events.tryEmit(TransportEvent.LinkDown(TransportType.BLE, peerId))
                Log.i(TAG, "link down ${Ids.hex(peerId)}")
            }
        }
    }

    private suspend fun maintenanceLoop() {
        var tick = 0
        while (running && scope?.isActive == true) {
            delay(1000)
            tick++
            val now = clock.nowMs()
            // Liveness: HELLO is the keepalive (SPEC §11.3).
            links.values.filter { now - it.lastRxMs > ChalkieConfig.BLE_LINK_TIMEOUT_MS }.forEach { it.close() }
            candidates.entries.removeIf { now - it.value.lastSeenMs > ChalkieConfig.NODE_TIMEOUT_MS && !links.containsKey(it.key) }
            // Back to BLE_MAX_LINKS once the spare is no longer needed: drop the weakest non-preferred.
            if (links.size > ChalkieConfig.BLE_MAX_LINKS) {
                links.values.filter { !isPreferred(it.peerId, now) }.minByOrNull { rssiOf(it.peerId) ?: Double.MAX_VALUE }?.close()
            }
            if (tick % 2 == 0) links.values.forEach { _events.tryEmit(TransportEvent.LinkStats(it.info())) }
            maybeConnect()
        }
    }

    private inner class Link(val peerId: UInt, private val sock: BluetoothSocket, private val out: DataOutputStream) {
        private val queue = LinkSendQueue()
        private val signal = Semaphore(0)
        @Volatile var lastRxMs = clock.nowMs()
        @Volatile var closed = false

        fun info() = LinkInfo(peerId, TransportType.BLE, rssiOf(peerId)?.toInt(), queue.lossRate(clock.nowMs()), null, lastRxMs)

        fun enqueue(bytes: ByteArray, priority: Priority): Boolean {
            if (closed) return false
            val ok = queue.offer(bytes, priority, clock.nowMs())
            if (ok) signal.release()
            return ok
        }

        fun startWriter() = thread(name = "ble-tx-${Ids.hex(peerId)}", isDaemon = true) {
            try {
                while (!closed) {
                    if (!signal.tryAcquire(1, TimeUnit.SECONDS)) continue
                    signal.drainPermits()
                    while (true) writeFrame(out, queue.poll(clock.nowMs()) ?: break)
                }
            } catch (_: IOException) {
                close()
            } catch (_: InterruptedException) {
            }
        }

        fun close() {
            if (closed) return
            closed = true
            queue.clear()
            signal.release()
            runCatching { sock.close() }
        }
    }

    private class HandshakeFailed : Exception()

    companion object {
        private const val TAG = "BleTransport"
        const val STATE_BT_OFF = "bluetooth off"
        private val MAGIC = byteArrayOf(0x43, 0x4B)
        private const val MANUF_LEN = 2 + 4 + 4 + 2 + 1
        val SERVICE_UUID: UUID = UUID.fromString("6c1b0001-7a4e-4d1c-9c8e-43484c4b4945")

        private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
            synchronized(out) {
                out.writeShort(bytes.size)
                out.write(bytes)
                out.flush()
            }
        }

        private fun readFrame(input: DataInputStream): ByteArray {
            val len = input.readUnsignedShort()
            if (len == 0 || len > ChalkieConfig.BLE_MAX_FRAME_BYTES) throw IOException("bad frame length $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
