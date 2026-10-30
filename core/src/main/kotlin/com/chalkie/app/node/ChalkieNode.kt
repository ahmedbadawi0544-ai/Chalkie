package com.chalkie.app.node

import com.chalkie.app.audio.Codec
import com.chalkie.app.audio.TalkPayload
import com.chalkie.app.audio.TalkPriority
import com.chalkie.app.audio.VoicePayload
import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.core.DecodedPacket
import com.chalkie.app.core.Ids
import com.chalkie.app.core.PacketType
import com.chalkie.app.crypto.CryptoBox
import com.chalkie.app.crypto.GroupKeys
import com.chalkie.app.ptt.FloorControl
import com.chalkie.app.election.BridgeElection
import com.chalkie.app.election.ClaimPayload
import com.chalkie.app.election.ElectionManager
import com.chalkie.app.election.StarControl
import com.chalkie.app.election.StarRole
import com.chalkie.app.message.VoiceMailbox
import com.chalkie.app.message.VoiceMessage
import com.chalkie.app.routing.LinkCost
import com.chalkie.app.routing.LinkHealth
import com.chalkie.app.routing.TopologyGraph
import com.chalkie.app.routing.HelloPayload
import com.chalkie.app.routing.Role
import com.chalkie.app.routing.NeighborSnapshot
import com.chalkie.app.routing.NeighborTable
import com.chalkie.app.routing.Router
import com.chalkie.app.transport.LanStatus
import com.chalkie.app.transport.LinkPreparer
import com.chalkie.app.transport.TalkAware
import com.chalkie.app.transport.Transport
import com.chalkie.app.transport.TransportEvent
import com.chalkie.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Local status that goes into HELLO. Supplied by the platform (battery etc.). */
data class LocalStatus(val name: String, val battery: Int, val charging: Boolean)

sealed interface NodeEvent {
    /** [private] = a call addressed to me only (unicast), whatever the channel. */
    data class VoiceIn(val srcId: UInt, val channel: Int, val payload: VoicePayload, val private: Boolean = false) : NodeEvent
    data class TalkStarted(
        val srcId: UInt, val channel: Int, val streamId: Int, val priority: TalkPriority, val private: Boolean = false,
    ) : NodeEvent
    data class TalkEnded(val srcId: UInt, val channel: Int, val streamId: Int, val private: Boolean = false) : NodeEvent
    /** The callee of my private call heard its TALK_START. */
    data class TalkAcked(val srcId: UInt, val streamId: Int) : NodeEvent
    data class TextIn(val srcId: UInt, val channel: Int, val text: String, val private: Boolean) : NodeEvent
    data class VoiceMessageIn(val message: VoiceMessage) : NodeEvent
    data class VoiceMessageDelivered(val uid: Long, val to: UInt) : NodeEvent
    /** My talk ended without me releasing PTT: lost a collision, or hit MAX_TALK_MS. */
    data class MyTalkStopped(val reason: StopReason) : NodeEvent
    data object PeersChanged : NodeEvent
    data class RoleChanged(val role: StarRole) : NodeEvent
}

enum class StopReason { COLLISION_LOST, PREEMPTED, MAX_TALK_TIME }

/**
 * Platform-independent node: router + neighbor table + HELLO + floor control + voice framing.
 * The Android service plugs real transports and audio into it; the simulator plugs fakes.
 */
class ChalkieNode(
    val nodeId: UInt,
    keys: GroupKeys,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val status: () -> LocalStatus,
    sessionId: UInt = Ids.newSessionId(),
    private val random: Random = Random.Default,
) {
    val crypto = CryptoBox(keys.encKey)
    val neighbors = NeighborTable(clock)
    val router = Router(nodeId, sessionId, crypto, clock)
    val floor = FloorControl(nodeId, clock, random)
    val election = ElectionManager(nodeId, clock, neighbors)
    val bridges = BridgeElection(nodeId, clock, neighbors)
    val health = LinkHealth(clock)

    private val _role = MutableStateFlow<StarRole>(StarRole.Off)
    val role: StateFlow<StarRole> = _role.asStateFlow()

    private val _isBridge = MutableStateFlow(false)
    val isBridge: StateFlow<Boolean> = _isBridge.asStateFlow()

    private val _events = MutableSharedFlow<NodeEvent>(
        extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    private val _peers = MutableStateFlow<List<NeighborSnapshot>>(emptyList())
    val peers: StateFlow<List<NeighborSnapshot>> = _peers.asStateFlow()

    /** Channel I talk on and listen to (plus General, channel 0). */
    @Volatile var selectedChannel: Int = 0
        set(value) { require(value in 0 until ChalkieConfig.CHANNEL_COUNT); field = value }

    /** Supervisors talk with SUPERVISOR priority and pre-empt normal talkers (SPEC §12). */
    @Volatile var supervisor: Boolean = false

    /** Target of my current private call, or null for a channel talk. */
    @Volatile private var talkTarget: UInt? = null

    val mailbox = VoiceMailbox(
        myId = nodeId,
        clock = clock,
        send = { type, to, payload -> router.originate(type, 0, payload, dstId = to) },
        reachable = { id -> neighbors.get(id)?.heardHello == true },
        onEvent = { e ->
            when (e) {
                is VoiceMailbox.Event.Received -> _events.tryEmit(NodeEvent.VoiceMessageIn(e.message))
                is VoiceMailbox.Event.Delivered -> _events.tryEmit(NodeEvent.VoiceMessageDelivered(e.uid, e.to))
            }
        },
    )

    private val transports = mutableListOf<Transport>()
    private val jobs = mutableListOf<Job>()
    private var talkTimer: Job? = null
    private var frameSeq = 0
    /** Frames of my previous VOICE packet, repeated in the next one (SPEC §19.4). */
    private var lastFrames: List<ByteArray> = emptyList()
    private var lastCodec: Codec? = null

    private val _talkActive = MutableStateFlow(false)
    /** True while I talk or hear anyone talking (SPEC §19.5). */
    val talkActive: StateFlow<Boolean> = _talkActive.asStateFlow()

    init {
        router.listener = Router.Listener { p, via, from -> onDeliver(p, via, from) }
        router.degrading = health::isDegrading
    }

    /** Star membership as seen from here, over live WD links only. */
    private val starView = object : Router.StarView {
        override val role: StarRole get() = election.role

        override fun covers(nodeId: UInt): Boolean {
            val wd = transports.firstOrNull { it.type == TransportType.WD } ?: return false
            val wdLinks = wd.links().map { it.neighborId }
            return when (val r = election.role) {
                is StarRole.Hub -> nodeId in wdLinks
                is StarRole.Leaf -> r.hubId in wdLinks && (nodeId == r.hubId ||
                    neighbors.get(r.hubId)?.reportedNeighbors?.any { it.id == nodeId && it.type == TransportType.WD } == true)
                StarRole.Off -> false
            }
        }

        override val amBridge: Boolean get() = bridges.amBridge
    }

    private val lan: LanStatus? get() = transports.firstNotNullOfOrNull { it as? LanStatus }

    init {
        router.star = starView
        router.unicast = Router.UnicastRoute { dst ->
            val hop = TopologyGraph.nextHop(nodeId, dst, topology()) ?: return@UnicastRoute null
            val links = neighbors.get(hop)?.links?.values ?: return@UnicastRoute null
            // Make-before-break: prefer a healthy link over a degrading one, even if it costs more.
            val link = LinkCost.best(links.filter { !health.isDegrading(hop, it.type) }) ?: LinkCost.best(links)
                ?: return@UnicastRoute null
            hop to link.type
        }
    }

    /** Current team graph: my links + every HELLO's reported links. */
    fun topology() = TopologyGraph.edges(nodeId, neighbors.all())

    fun addTransport(t: Transport) {
        transports += t
        router.attach(t)
        election.canBeHub = transports.any { it.type == TransportType.WD }
    }

    suspend fun start() {
        transports.forEach { t ->
            jobs += scope.launch { t.events.collect(::onTransportEvent) }
        }
        transports.forEach { it.start() }
        jobs += scope.launch { helloLoop() }
        jobs += scope.launch { expireLoop() }
        jobs += scope.launch { electionLoop() }
        jobs += scope.launch {
            while (isActive) {
                delay(ChalkieConfig.VMSG_TICK_MS)
                mailbox.tick()
            }
        }
    }

    suspend fun stop() {
        pttUp()
        jobs.forEach { it.cancel() }
        jobs.clear()
        transports.forEach { it.stop() }
    }

    // ---- PTT ----

    val myPriority: TalkPriority get() = if (supervisor) TalkPriority.SUPERVISOR else TalkPriority.NORMAL

    /**
     * Returns true if the floor was granted and TALK_START sent. With [target] it is a private
     * call: unicast to that teammate, on its own floor so a busy channel never blocks it.
     */
    fun pttDown(priority: TalkPriority = myPriority, target: UInt? = null): Boolean {
        val channel = if (target != null) 0 else selectedChannel
        val floorKey = if (target != null) FloorControl.PRIVATE else channel
        val ts = router.nowTimestamp()
        return when (val r = floor.requestTalk(floorKey, priority, ts)) {
            FloorControl.Request.Busy -> false
            is FloorControl.Request.Granted -> {
                frameSeq = 0
                lastFrames = emptyList()
                talkTarget = target
                router.originate(PacketType.TALK_START, channel, TalkPayload.start(r.streamId, priority), dst(), timestamp = ts)
                talkTimer?.cancel()
                talkTimer = scope.launch {
                    delay(ChalkieConfig.MAX_TALK_MS)
                    if (floor.talkExpired()) stopMyTalk(StopReason.MAX_TALK_TIME)
                }
                updateTalkActive()
                true
            }
        }
    }

    fun pttUp() {
        talkTimer?.cancel()
        val talk = floor.myTalk ?: return
        floor.release()
        router.originate(PacketType.TALK_END, wireChannel(talk), TalkPayload.end(talk.claim.streamId), dst())
    }

    val isTalking: Boolean get() = floor.myTalk != null

    /** Private-call target while talking privately. */
    val privateTarget: UInt? get() = talkTarget.takeIf { isTalking }

    private fun dst(): UInt = talkTarget ?: Ids.BROADCAST

    private fun wireChannel(talk: FloorControl.MyTalk) = if (talk.channel == FloorControl.PRIVATE) 0 else talk.channel

    /** Quick text (SPEC §13) on my channel, or to one teammate. False if empty or too long. */
    fun sendText(text: String, target: UInt? = null): Boolean {
        val bytes = text.trim().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > ChalkieConfig.TEXT_MAX_BYTES) return false
        router.originate(PacketType.TEXT, if (target != null) 0 else selectedChannel, bytes, dstId = target ?: Ids.BROADCAST)
        return true
    }

    /** Queues a recorded voice message for [to]; delivered whenever [to] is reachable (store and forward). */
    fun sendVoiceMessage(to: UInt, codec: Codec, frames: List<ByteArray>, createdAt: Long): VoiceMessage {
        val msg = VoiceMessage(random.nextLong(), nodeId, to, createdAt, codec, frames)
        mailbox.enqueue(msg)
        return msg
    }

    /** Sends one VOICE packet with [frames] (FRAMES_PER_PACKET encoded frames). False if not talking. */
    fun sendVoice(frames: List<ByteArray>, codec: Codec = Codec.G711_ULAW): Boolean {
        val talk = floor.myTalk ?: return false
        val redundant = if (ChalkieConfig.VOICE_REDUNDANT_PACKETS > 0 && codec == lastCodec) lastFrames else emptyList()
        val payload = VoicePayload(talk.claim.streamId, frameSeq and 0xFFFF, codec, frames, redundant)
        frameSeq += frames.size
        lastFrames = frames
        lastCodec = codec
        router.originate(PacketType.VOICE, wireChannel(talk), payload.encode(), dst())
        return true
    }

    private fun stopMyTalk(reason: StopReason) {
        val talk = floor.myTalk ?: return
        floor.release()
        router.originate(PacketType.TALK_END, wireChannel(talk), TalkPayload.end(talk.claim.streamId), dst())
        _events.tryEmit(NodeEvent.MyTalkStopped(reason))
    }

    // ---- HELLO ----

    fun sendHello() {
        val s = status()
        val l = lan
        val onLan = l?.lanActive == true
        election.onLan = onLan
        val score = election.computeMyScore(s.battery, s.charging)
        val bridgeScore = bridges.computeMyScore(s.battery, s.charging, onLan)
        val hub = election.current
        val hello = HelloPayload(
            name = s.name,
            battery = s.battery,
            charging = s.charging,
            onLan = onLan,
            lanIp = l?.lanIp,
            lanPort = l?.lanPort ?: 0,
            ssidHash = l?.ssidHash,
            roles = buildSet {
                if (election.role is StarRole.Hub) add(Role.HUB)
                if (bridges.amBridge) add(Role.BRIDGE)
                if (supervisor) add(Role.SUPERVISOR)
            },
            hubId = hub?.hubId ?: 0u,
            hubEpoch = hub?.epoch ?: election.maxEpochSeen,
            neighbors = neighbors.reportable(),
            hubScore = score,
            bridgeScore = bridgeScore,
        )
        router.originate(PacketType.HELLO, 0, hello.encode())
    }

    private suspend fun helloLoop() {
        while (scope.isActive) {
            sendHello()
            val jitter = random.nextLong(-ChalkieConfig.HELLO_JITTER_MS, ChalkieConfig.HELLO_JITTER_MS + 1)
            val interval = if (_talkActive.value) ChalkieConfig.HELLO_INTERVAL_TALK_MS else ChalkieConfig.HELLO_INTERVAL_MS
            delay(interval + jitter)
        }
    }

    private suspend fun electionLoop() {
        while (scope.isActive) {
            delay(ChalkieConfig.ELECTION_TICK_MS)
            updateTalkActive()
            // Joining or leaving the LAN changes who takes part in elections: tell everyone now.
            val onLan = lan?.lanActive == true
            if (onLan != election.onLan) {
                election.onLan = onLan
                sendHello()
            }
            election.tick()?.let { (epoch, score) ->
                router.originate(PacketType.HUB_CLAIM, 0, ClaimPayload.encode(epoch, score))
            }
            applyRole()
            val wasBridge = bridges.amBridge
            bridges.tick()?.let { epoch ->
                router.originate(PacketType.BRIDGE_CLAIM, 0, ClaimPayload.encode(epoch, bridges.publishedScore))
            }
            if (bridges.amBridge != wasBridge) {
                _isBridge.value = bridges.amBridge
                sendHello()
            }
        }
    }

    /**
     * Pushes a changed role to the STAR transports and tells everyone at once. A new standby
     * only goes to the transports (they pre-discover it); it isn't news for the team.
     */
    private fun applyRole() {
        val r = election.role
        val old = _role.value
        if (r == old) return
        _role.value = r
        transports.filterIsInstance<StarControl>().forEach { it.setStarRole(r) }
        if (withoutStandby(r) == withoutStandby(old)) return
        _events.tryEmit(NodeEvent.RoleChanged(r))
        sendHello()
    }

    private fun withoutStandby(r: StarRole): StarRole = when (r) {
        is StarRole.Hub -> r.copy(standbyId = null)
        is StarRole.Leaf -> r.copy(standbyId = null)
        StarRole.Off -> r
    }

    /** Talk on/off: sparser HELLOs, longer loss windows, low-power BLE scanning (SPEC §19.5). */
    private fun updateTalkActive() {
        val active = floor.anyActive()
        if (active == _talkActive.value) return
        _talkActive.value = active
        election.talkActive = active
        bridges.talkActive = active
        transports.filterIsInstance<TalkAware>().forEach { it.setTalkActive(active) }
    }

    /** A link to [neighborId] is degrading: bring up a backup on every other transport that can. */
    private fun prepareBackup(neighborId: UInt, degraded: TransportType) {
        transports.filter { it.type != degraded && it is LinkPreparer && it.links().none { l -> l.neighborId == neighborId } }
            .forEach { (it as LinkPreparer).prepareLink(neighborId) }
    }

    private suspend fun expireLoop() {
        while (scope.isActive) {
            delay(1000)
            if (neighbors.expire().isNotEmpty()) publishPeers()
        }
    }

    private fun publishPeers() {
        _peers.value = neighbors.all().sortedBy { it.nodeId }
        _events.tryEmit(NodeEvent.PeersChanged)
    }

    // ---- inbound ----

    private fun onTransportEvent(e: TransportEvent) {
        when (e) {
            is TransportEvent.PacketIn -> router.onPacketIn(e.type, e.neighborId, e.bytes)
            is TransportEvent.LinkUp -> {
                neighbors.onLinkUp(e.info)
                if (health.onSample(e.info)) prepareBackup(e.info.neighborId, e.type)
                publishPeers(); sendHello()
            }
            is TransportEvent.LinkDown -> {
                neighbors.onLinkDown(e.type, e.neighborId)
                health.onLinkDown(e.type, e.neighborId)
                publishPeers(); sendHello()
            }
            is TransportEvent.LinkStats -> {
                neighbors.onLinkStats(e.info)
                if (health.onSample(e.info)) prepareBackup(e.info.neighborId, e.type)
            }
        }
    }

    private fun listens(channel: Int) = channel == selectedChannel || channel == 0

    private fun onDeliver(p: DecodedPacket, via: TransportType, from: UInt) {
        val h = p.header
        val private = !h.isBroadcast
        val floorKey = if (private) FloorControl.PRIVATE else h.channel
        val hear = private || listens(h.channel)
        when (h.type) {
            PacketType.HELLO -> {
                HelloPayload.decode(p.payload)?.let {
                    neighbors.onHello(h.srcId, it, h.hopCount)
                    election.onHello(h.srcId, it)
                    applyRole()
                    publishPeers()
                }
            }
            PacketType.TALK_START -> {
                val streamId = TalkPayload.streamId(p.payload) ?: return
                val priority = TalkPayload.priority(p.payload)
                val claim = FloorControl.Claim(h.srcId, streamId, priority, h.timestamp)
                val mine = floor.myTalk
                if (floor.onTalkStart(floorKey, claim)) {
                    talkTimer?.cancel()
                    // Let listeners clear my half-started stream right away.
                    mine?.let { router.originate(PacketType.TALK_END, wireChannel(it), TalkPayload.end(it.claim.streamId), dst()) }
                    val reason = if (mine != null && priority > mine.claim.priority) StopReason.PREEMPTED else StopReason.COLLISION_LOST
                    _events.tryEmit(NodeEvent.MyTalkStopped(reason))
                }
                if (hear) _events.tryEmit(NodeEvent.TalkStarted(h.srcId, h.channel, streamId, priority, private))
                // A private call to me: tell the caller it got through.
                if (private) router.originate(PacketType.TALK_ACK, 0, TalkPayload.end(streamId), dstId = h.srcId)
                updateTalkActive()
            }
            PacketType.TALK_ACK -> {
                val streamId = TalkPayload.streamId(p.payload) ?: return
                if (private && floor.myTalk?.claim?.streamId == streamId) _events.tryEmit(NodeEvent.TalkAcked(h.srcId, streamId))
            }
            PacketType.VOICE -> {
                val v = VoicePayload.decode(p.payload) ?: return
                floor.onVoice(floorKey, h.srcId, v.streamId)
                if (hear) _events.tryEmit(NodeEvent.VoiceIn(h.srcId, h.channel, v, private))
            }
            PacketType.TALK_END -> {
                val streamId = TalkPayload.streamId(p.payload) ?: return
                floor.onTalkEnd(floorKey, h.srcId, streamId)
                if (hear) _events.tryEmit(NodeEvent.TalkEnded(h.srcId, h.channel, streamId, private))
            }
            PacketType.TEXT -> {
                if (hear) _events.tryEmit(NodeEvent.TextIn(h.srcId, h.channel, p.payload.toString(Charsets.UTF_8), private))
            }
            PacketType.VMSG -> if (private) mailbox.onChunk(h.srcId, p.payload)
            PacketType.VMSG_ACK -> if (private) mailbox.onAck(p.payload)
            PacketType.HUB_CLAIM -> {
                val (epoch, score) = ClaimPayload.decode(p.payload) ?: return
                election.onClaim(h.srcId, epoch, score)
                applyRole()
            }
            PacketType.BRIDGE_CLAIM -> ClaimPayload.decode(p.payload)?.let { (epoch, _) -> bridges.onClaim(epoch) }
            else -> Unit // SOS: not in this build
        }
    }
}
