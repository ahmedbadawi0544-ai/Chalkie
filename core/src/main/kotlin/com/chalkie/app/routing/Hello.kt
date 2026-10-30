package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.transport.TransportType
import java.nio.ByteBuffer

enum class Role(val bit: Int) { HUB(0x1), BRIDGE(0x2), SUPERVISOR(0x4) }

data class ReportedNeighbor(val id: UInt, val type: TransportType, val rssi: Int?)

/** HELLO payload (SPEC §7). */
data class HelloPayload(
    val name: String,
    val battery: Int,
    val charging: Boolean,
    val onLan: Boolean = false,
    val lanIp: ByteArray? = null,       // IPv4, 4 bytes
    val lanPort: Int = 0,
    val ssidHash: Int? = null,
    val roles: Set<Role> = emptySet(),
    val hubId: UInt = 0u,
    val hubEpoch: Int = 0,
    val bridgeIds: List<UInt> = emptyList(),
    val neighbors: List<ReportedNeighbor> = emptyList(),
    val hubScore: Float = 0f,
    val bridgeScore: Float = 0f,
) {
    fun encode(): ByteArray {
        val nameBytes = truncateUtf8(name, ChalkieConfig.HELLO_MAX_NAME_BYTES)
        val bridges = bridgeIds.take(ChalkieConfig.HELLO_MAX_BRIDGES)
        val nbs = neighbors.take(ChalkieConfig.HELLO_MAX_NEIGHBORS)
        val buf = ByteBuffer.allocate(1 + nameBytes.size + 2 + 4 + 2 + 4 + 1 + 4 + 4 + 1 + 4 * bridges.size + 1 + 6 * nbs.size + 8)
        buf.put(nameBytes.size.toByte()).put(nameBytes)
        buf.put(battery.coerceIn(0, 100).toByte())
        var flags = 0
        if (charging) flags = flags or F_CHARGING
        if (onLan) flags = flags or F_ON_LAN
        if (ssidHash != null) flags = flags or F_HAS_SSID
        if (lanIp?.size == 4) flags = flags or F_HAS_IP
        buf.put(flags.toByte())
        buf.put(lanIp?.takeIf { it.size == 4 } ?: ByteArray(4))
        buf.putShort(lanPort.toShort())
        buf.putInt(ssidHash ?: 0)
        buf.put(roles.fold(0) { acc, r -> acc or r.bit }.toByte())
        buf.putInt(hubId.toInt())
        buf.putInt(hubEpoch)
        buf.put(bridges.size.toByte())
        bridges.forEach { buf.putInt(it.toInt()) }
        buf.put(nbs.size.toByte())
        nbs.forEach {
            buf.putInt(it.id.toInt())
            buf.put(it.type.ordinal.toByte())
            buf.put((it.rssi ?: NO_RSSI).coerceIn(-128, 127).toByte())
        }
        buf.putFloat(hubScore)
        buf.putFloat(bridgeScore)
        return buf.array()
    }

    companion object {
        private const val F_CHARGING = 0x1
        private const val F_ON_LAN = 0x2
        private const val F_HAS_SSID = 0x4
        private const val F_HAS_IP = 0x8
        private const val NO_RSSI = 127

        fun decode(bytes: ByteArray): HelloPayload? = try {
            val buf = ByteBuffer.wrap(bytes)
            val nameLen = buf.get().toInt() and 0xFF
            val name = ByteArray(nameLen).also { buf.get(it) }.toString(Charsets.UTF_8)
            val battery = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val ip = ByteArray(4).also { buf.get(it) }
            val port = buf.short.toInt() and 0xFFFF
            val ssid = buf.int
            val roleBits = buf.get().toInt() and 0xFF
            val hubId = buf.int.toUInt()
            val hubEpoch = buf.int
            val bridges = List(buf.get().toInt() and 0xFF) { buf.int.toUInt() }
            val nbs = List(buf.get().toInt() and 0xFF) {
                val id = buf.int.toUInt()
                val type = TransportType.entries.getOrNull(buf.get().toInt()) ?: TransportType.BLE
                val rssi = buf.get().toInt().takeIf { it != NO_RSSI }
                ReportedNeighbor(id, type, rssi)
            }
            HelloPayload(
                name = name,
                battery = battery,
                charging = flags and F_CHARGING != 0,
                onLan = flags and F_ON_LAN != 0,
                // Sent whenever on Wi-Fi, not only once "on LAN": peers need it to probe by unicast.
                lanIp = ip.takeIf { flags and F_HAS_IP != 0 },
                lanPort = port,
                ssidHash = ssid.takeIf { flags and F_HAS_SSID != 0 },
                roles = Role.entries.filter { roleBits and it.bit != 0 }.toSet(),
                hubId = hubId,
                hubEpoch = hubEpoch,
                bridgeIds = bridges,
                neighbors = nbs,
                hubScore = buf.float,
                bridgeScore = buf.float,
            )
        } catch (e: java.nio.BufferUnderflowException) {
            null
        }

        private fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
            var bytes = s.toByteArray(Charsets.UTF_8)
            var end = s.length
            while (bytes.size > maxBytes && end > 0) {
                end--
                bytes = s.substring(0, end).toByteArray(Charsets.UTF_8)
            }
            return bytes
        }
    }
}
