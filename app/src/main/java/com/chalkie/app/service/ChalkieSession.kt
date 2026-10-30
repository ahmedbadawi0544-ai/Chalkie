package com.chalkie.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.BatteryManager
import android.util.Log
import com.chalkie.app.AppGraph
import com.chalkie.app.audio.AudioEngine
import com.chalkie.app.audio.Codec
import com.chalkie.app.core.Ids
import com.chalkie.app.core.SystemClock
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.data.LogEntry
import com.chalkie.app.data.VoiceMessageEntity
import com.chalkie.app.election.StarRole
import com.chalkie.app.group.GroupInfo
import com.chalkie.app.group.Identity
import com.chalkie.app.group.Settings
import com.chalkie.app.message.VoiceMessage
import com.chalkie.app.node.ChalkieNode
import com.chalkie.app.node.LocalStatus
import com.chalkie.app.node.NodeEvent
import com.chalkie.app.node.StopReason
import com.chalkie.app.ptt.FloorControl
import com.chalkie.app.routing.NeighborSnapshot
import com.chalkie.app.routing.TopoEdge
import com.chalkie.app.transport.TransportType
import com.chalkie.app.transport.ble.BleTransport
import com.chalkie.app.transport.lan.LanPeerHint
import com.chalkie.app.transport.lan.LanTransport
import com.chalkie.app.transport.wd.WdTransport
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress

/** What the main screen shows. */
data class SessionStatus(
    val groupName: String = "",
    val myId: UInt = 0u,
    val myName: String = "",
    val bleState: String = "off",
    val bleLinks: Int = 0,
    /** "hub" / "leaf" / "off", or "unavailable" without Play Services. */
    val wdState: String = "off",
    val wdLinks: Int = 0,
    /** LanState name in lower case: off / probing / active_multicast / active_unicast / standby / isolated. */
    val lanState: String = "off",
    val lanLinks: Int = 0,
    val isBridge: Boolean = false,
    val isHub: Boolean = false,
    val supervisor: Boolean = false,
    /** Packets sent / received per transport (debug counters). */
    val counters: Map<TransportType, Pair<Long, Long>> = emptyMap(),
    val peers: List<NeighborSnapshot> = emptyList(),
    val channel: Int = 0,
    val talking: Boolean = false,
    /** Name of the teammate I am calling privately, while I do. */
    val privateTarget: String? = null,
    val talkerName: String? = null,
    /** "<name> is talking" during a talk, "Last: <name> · <n>s" after it. */
    val talkLine: String? = null,
    val lastEvent: String? = null,
    val recording: Boolean = false,
    /** Voice messages waiting for their recipient. */
    val outbox: Int = 0,
)

/** One quick text, sent or received. */
data class ChatLine(
    val timeMs: Long,
    val from: String,
    val mine: Boolean,
    val text: String,
    val channel: Int,
    /** Teammate name for a private text, else null (channel text). */
    val privatePeer: String?,
)

/** One running group session: node + BLE + WD + LAN + audio. Owned by ChalkieService. */
class ChalkieSession(
    private val context: Context,
    val group: GroupInfo,
    val identity: Identity,
    private val scope: CoroutineScope,
) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val keys = GroupKeys(group.secret)
    private val store = AppGraph.groupStore
    private val db = AppGraph.db

    val node = ChalkieNode(
        nodeId = identity.nodeId,
        keys = keys,
        clock = SystemClock,
        scope = scope,
        status = {
            LocalStatus(
                name = identity.name,
                battery = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100),
                charging = battery.isCharging,
            )
        },
    )
    private val ble = BleTransport(context, identity.nodeId, keys)
    /** Nearby needs Google Play Services; without it the phone still works over BLE (SPEC §18). */
    private val wd: WdTransport? =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            WdTransport(context, identity.nodeId, keys)
        } else null
    private val lan = LanTransport(context, identity.nodeId, keys)

    @Volatile var settings = Settings()
        private set
    val audio = AudioEngine(context, node, scope, useOpus = { settings.useOpus })

    private val _status = MutableStateFlow(SessionStatus(group.name, identity.nodeId, identity.name))
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat.asStateFlow()

    @Volatile private var lastEvent: String? = null
    @Volatile private var lastTalk: String? = null
    @Volatile private var recording = false
    private val talkStartMs = HashMap<UInt, Long>()

    init {
        node.addTransport(ble)
        wd?.let(node::addTransport)
        node.addTransport(lan)
        lan.hints = ::lanHints
        node.supervisor = group.supervisor
    }

    /** Teammates whose HELLO says they are on my Wi-Fi network: unicast fallback targets. */
    private fun lanHints(): List<LanPeerHint> {
        val mine = lan.ssidHash ?: return emptyList()
        return node.neighbors.all().mapNotNull { p ->
            val ip = p.lanIp ?: return@mapNotNull null
            if (p.ssidHash != mine || p.lanPort == 0) return@mapNotNull null
            LanPeerHint(p.nodeId, InetSocketAddress(InetAddress.getByAddress(ip), p.lanPort))
        }
    }

    suspend fun start() {
        settings = store.settings.first()
        node.selectedChannel = settings.channel.coerceIn(0, com.chalkie.app.core.ChalkieConfig.CHANNEL_COUNT - 1)
        scope.launch { store.settings.collect { settings = it } }
        // Store & forward survives restarts: re-queue what was never delivered.
        db.voiceMessages().undelivered().forEach { e ->
            VoiceMessage.decodeBody(e.uid, e.fromId.toUInt(), e.toId.toUInt(), e.body)?.let(node.mailbox::enqueue)
        }
        suggestTeamWifi()
        node.start()
        audio.start()
        scope.launch { node.events.collect(::onEvent) }
        scope.launch {
            while (isActive) {
                refresh()
                delay(STATUS_REFRESH_MS)
            }
        }
        scope.launch { networkLogLoop() }
        log("NETWORK", identity.name, text = "Went online")
    }

    suspend fun stop() {
        log("NETWORK", identity.name, text = "Went offline")
        audio.stop()
        node.stop()
    }

    // ---- actions (UI, keys, headset) ----

    fun setChannel(channel: Int) {
        if (node.isTalking) return
        node.selectedChannel = channel
        scope.launch { store.setChannel(channel) }
        refresh()
    }

    /** [target] = private call to that teammate. */
    fun pttDown(target: UInt? = null): Boolean = audio.pttDown(target).also { granted ->
        if (granted) synchronized(talkStartMs) { talkStartMs[identity.nodeId] = System.currentTimeMillis() }
        refresh()
    }

    fun pttUp() {
        val target = node.privateTarget
        val channel = node.selectedChannel
        audio.pttUp()
        myTalkEnded(target, channel)
        refresh()
    }

    fun sendText(text: String, target: UInt? = null): Boolean {
        if (!node.sendText(text, target)) return false
        val line = ChatLine(System.currentTimeMillis(), "You", true, text.trim(), node.selectedChannel, target?.let(::name))
        _chat.update { (it + line).takeLast(CHAT_KEEP) }
        log("TEXT", identity.name, if (target == null) node.selectedChannel else null,
            text = (target?.let { "→ ${name(it)}: " } ?: "") + text.trim())
        return true
    }

    fun startRecording(): Boolean = audio.startRecording().also { recording = it; refresh() }

    fun cancelRecording() {
        audio.stopRecording()
        recording = false
        refresh()
    }

    /** Stops recording and queues the message for [to]. False if nothing was recorded. */
    fun finishRecording(to: UInt): Boolean {
        recording = false
        val (codec, frames) = audio.stopRecording() ?: return false.also { refresh() }
        val msg = node.sendVoiceMessage(to, codec, frames, System.currentTimeMillis())
        scope.launch {
            db.voiceMessages().insert(
                VoiceMessageEntity(msg.uid, identity.nodeId.toLong(), to.toLong(), name(to), msg.createdAt,
                    codec.id, msg.encodeBody(), outgoing = true, heard = true)
            )
        }
        log("VOICE_MSG", identity.name, durationMs = msg.durationMs, text = "→ ${name(to)} (queued)")
        refresh()
        return true
    }

    fun playVoiceMessage(e: VoiceMessageEntity) {
        val msg = VoiceMessage.decodeBody(e.uid, e.fromId.toUInt(), e.toId.toUInt(), e.body) ?: return
        audio.playMessage(msg.codec, msg.frames)
        if (!e.heard) scope.launch { db.voiceMessages().markHeard(e.uid) }
    }

    fun deleteVoiceMessage(uid: Long) {
        node.mailbox.cancel(uid)
        scope.launch { db.voiceMessages().delete(uid) }
    }

    /** Current team graph for the network map. */
    fun topology(): List<TopoEdge> = node.topology()

    fun name(id: UInt): String =
        if (id == identity.nodeId) identity.name else node.neighbors.get(id)?.name?.ifBlank { null } ?: Ids.hex(id)

    // ---- events ----

    private fun onEvent(e: NodeEvent) {
        lastEvent = when (e) {
            is NodeEvent.TalkStarted -> {
                synchronized(talkStartMs) { talkStartMs.getOrPut(e.srcId) { System.currentTimeMillis() } }
                if (e.private) "Private call from ${name(e.srcId)}" else lastEvent
            }
            is NodeEvent.TalkEnded -> {
                talkEnded(e.srcId, name(e.srcId), if (e.private) "PRIVATE_CALL" else "TALK", e.channel)
                lastEvent
            }
            is NodeEvent.MyTalkStopped -> {
                myTalkEnded(null, node.selectedChannel)
                when (e.reason) {
                    StopReason.PREEMPTED -> "A supervisor took the channel"
                    StopReason.COLLISION_LOST -> "Someone else started talking first"
                    StopReason.MAX_TALK_TIME -> "Talk stopped after 60 s"
                }
            }
            is NodeEvent.RoleChanged -> when (val r = e.role) {
                is StarRole.Hub -> "You are now the Wi-Fi hub"
                is StarRole.Leaf -> "Wi-Fi hub: ${name(r.hubId)}"
                StarRole.Off -> "Wi-Fi Direct off"
            }.also { log("ROLE", identity.name, text = it) }
            is NodeEvent.TextIn -> {
                val line = ChatLine(System.currentTimeMillis(), name(e.srcId), false, e.text, e.channel,
                    if (e.private) name(e.srcId) else null)
                _chat.update { (it + line).takeLast(CHAT_KEEP) }
                log("TEXT", name(e.srcId), if (e.private) null else e.channel, text = (if (e.private) "(private) " else "") + e.text)
                "${name(e.srcId)}: ${e.text}"
            }
            is NodeEvent.VoiceMessageIn -> {
                val m = e.message
                scope.launch {
                    db.voiceMessages().insert(
                        VoiceMessageEntity(m.uid, m.from.toLong(), m.to.toLong(), name(m.from), m.createdAt,
                            m.codec.id, m.encodeBody(), outgoing = false)
                    )
                }
                log("VOICE_MSG", name(m.from), durationMs = m.durationMs, text = "received")
                "Voice message from ${name(m.from)}"
            }
            is NodeEvent.VoiceMessageDelivered -> {
                scope.launch { db.voiceMessages().markDelivered(e.uid) }
                log("VOICE_MSG", identity.name, text = "delivered to ${name(e.to)}")
                "Voice message delivered to ${name(e.to)}"
            }
            else -> return
        }
        refresh()
    }

    private fun myTalkEnded(target: UInt?, channel: Int) {
        val kind = if (target != null) "PRIVATE_CALL" else "TALK"
        talkEnded(identity.nodeId, "You", kind, channel, target)
    }

    private fun talkEnded(id: UInt, display: String, kind: String, channel: Int, target: UInt? = null) {
        val start = synchronized(talkStartMs) { talkStartMs.remove(id) } ?: return
        val ms = System.currentTimeMillis() - start
        lastTalk = "Last: $display · ${(ms + 500) / 1000}s"
        log(kind, if (id == identity.nodeId) identity.name else display, if (kind == "TALK") channel else null,
            durationMs = ms, text = target?.let { "→ ${name(it)}" })
    }

    /** Logs network changes (SPEC §13): LAN state, bridge role, teammates joining and leaving. */
    private suspend fun networkLogLoop() {
        var lanState = lan.state
        var bridge = node.bridges.amBridge
        var known = emptySet<UInt>()
        while (scope.isActive) {
            delay(NETWORK_LOG_MS)
            if (lan.state != lanState) {
                lanState = lan.state
                log("NETWORK", identity.name, text = "LAN ${lanState.name.lowercase()}")
            }
            if (node.bridges.amBridge != bridge) {
                bridge = node.bridges.amBridge
                log("ROLE", identity.name, text = if (bridge) "Became a LAN bridge" else "No longer a bridge")
            }
            val now = node.neighbors.all().filter { it.heardHello }.associate { it.nodeId to it.name }
            (now.keys - known).forEach { log("NETWORK", now.getValue(it), text = "joined") }
            (known - now.keys).forEach { log("NETWORK", Ids.hex(it), text = "left") }
            known = now.keys
        }
    }

    private fun log(kind: String, who: String, channel: Int? = null, durationMs: Long? = null, text: String? = null) {
        scope.launch {
            runCatching { db.log().insert(LogEntry(0, System.currentTimeMillis(), kind, who, channel, durationMs, text)) }
        }
    }

    /** The team QR may carry the site Wi-Fi (SPEC §11.1): offer it to the system once per session. */
    private fun suggestTeamWifi() {
        val ssid = group.wifiSsid ?: return
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        group.wifiPassword?.let(b::setWpa2Passphrase)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val result = runCatching { wifi.addNetworkSuggestions(listOf(b.build())) }.getOrNull()
        Log.i(TAG, "Wi-Fi suggestion for team network: $result")
    }

    private fun refresh() {
        val channel = node.selectedChannel
        val talker = node.floor.currentTalker(FloorControl.PRIVATE)?.takeIf { it != identity.nodeId }
            ?: node.floor.currentTalker(channel)
        val talkerName = talker?.let { if (it == identity.nodeId) "You" else name(it) }
        val target = node.privateTarget
        val stats = node.router.stats
        _status.value = SessionStatus(
            groupName = group.name,
            myId = identity.nodeId,
            myName = identity.name,
            bleState = ble.state,
            bleLinks = ble.links().size,
            wdState = wd?.state ?: "unavailable",
            wdLinks = wd?.links()?.size ?: 0,
            lanState = lan.state.name.lowercase(),
            lanLinks = lan.links().size,
            isBridge = node.bridges.amBridge,
            isHub = node.election.role is StarRole.Hub,
            supervisor = node.supervisor,
            counters = TransportType.entries.associateWith { stats.tx.getValue(it).get() to stats.rx.getValue(it).get() },
            peers = node.neighbors.all().sortedWith(compareBy({ !it.isDirect }, { it.name })),
            channel = channel,
            talking = node.isTalking,
            privateTarget = target?.let(::name),
            talkerName = talkerName,
            talkLine = when {
                target != null -> "Private call → ${name(target)}"
                talkerName == null -> lastTalk
                talkerName == "You" -> "You are talking"
                else -> "$talkerName is talking"
            },
            lastEvent = lastEvent,
            recording = recording,
            outbox = node.mailbox.pending().size,
        )
    }

    private companion object {
        const val TAG = "ChalkieSession"
        const val STATUS_REFRESH_MS = 250L
        const val NETWORK_LOG_MS = 2000L
        const val CHAT_KEEP = 100
    }
}
