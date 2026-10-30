package com.chalkie.app.transport

import com.chalkie.app.core.Priority
import kotlinx.coroutines.flow.Flow

enum class TransportType { LAN, WD, BLE }

data class LinkInfo(
    val neighborId: UInt,
    val type: TransportType,
    val rssi: Int?,              // BLE only
    val lossRate: Float,         // 0..1, rolling 10 s
    val rttMs: Int?,
    val lastSeenMs: Long,
)

sealed interface TransportEvent {
    val type: TransportType

    data class LinkUp(val info: LinkInfo) : TransportEvent {
        override val type get() = info.type
    }

    data class LinkDown(override val type: TransportType, val neighborId: UInt) : TransportEvent

    class PacketIn(override val type: TransportType, val neighborId: UInt, val bytes: ByteArray) : TransportEvent

    data class LinkStats(val info: LinkInfo) : TransportEvent {
        override val type get() = info.type
    }
}

/** Moves bytes to authenticated neighbors. Never decides routing (SPEC §3). */
interface Transport {
    val type: TransportType
    val events: Flow<TransportEvent>
    suspend fun start()
    suspend fun stop()
    fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean
    fun links(): List<LinkInfo>
}

/** Implemented by the LAN transport so the node can publish LAN facts in HELLO (SPEC §7, §11.1). */
interface LanStatus {
    /** True in ACTIVE_MULTICAST / ACTIVE_UNICAST: the node is "on LAN". */
    val lanActive: Boolean
    val lanIp: ByteArray?
    val lanPort: Int
    /** Identifies the network without exposing its name; null when not on Wi-Fi. */
    val ssidHash: Int?
}

/** A transport that can reach every peer with one send (LAN multicast). */
interface BroadcastCapable {
    /** Returns false if it could not broadcast; the router then sends per link. */
    fun sendAll(bytes: ByteArray, priority: Priority): Boolean
}
