# Chalkie — Technical Specification

Offline push-to-talk (walkie-talkie) app for Android field teams.
Works with **no internet**, combining three transports that complement each other:

1. **Building Wi-Fi (LAN)**: used when available and usable.
2. **Wi-Fi Direct STAR** (Google Nearby Connections): high-bandwidth hub network with automatic hub election.
3. **Bluetooth LE mesh** (BLE advertising + L2CAP CoC): always on, multi-hop relay, and the control plane.

All three feed **one router**. A packet may arrive from several paths; it is played once and relayed only where needed.

---

## 0. Instructions for Claude Code

- Build **phase by phase** (§14). Do not start a phase before the previous one passes its tests.
- Keep transport, routing, election and audio **decoupled** behind the interfaces in §3, so routing and election logic are pure Kotlin and testable on the JVM without devices.
- Write the **JVM network simulator** (§15) early. Router and election behaviour must be proven there before testing on phones.
- All tunable numbers live in one `ChalkieConfig` object (§16). No magic numbers in code.
- Package name placeholder: `com.chalkie.app` (change if needed).

---

## 1. Tech stack

| Item | Choice |
|---|---|
| Language | Kotlin, Coroutines + Flow |
| minSdk / targetSdk | 29 (needed for BLE L2CAP CoC) / 35 |
| UI | Jetpack Compose |
| Persistence | Room (logs, group, settings), DataStore (prefs) |
| Wi-Fi Direct layer | `com.google.android.gms:play-services-nearby` |
| Crypto | `javax.crypto` AES-256-GCM, HKDF (small local implementation) |
| QR | CameraX + ML Kit barcode scanning; ZXing core for generating |
| DI | Manual (single `AppGraph` object), no Hilt in v1 |

---

## 2. Core concepts

- **Node**: one phone. Identified by `nodeId` (UInt32, random, generated on first install, persisted). Never identify a node by IP or MAC.
- **Session**: random UInt32 `sessionId` generated at each service start (used in msgId / nonce).
- **Group**: the team. Defined by a 32-byte `groupSecret` shared by QR. Only nodes with the same secret can see or hear each other.
- **Channel**: talk group inside a group (0 = General, 1..15 = teams like FTTH, CCTV, Electrical).
- **Link**: one connection to one neighbor over one transport. A neighbor may have up to 3 links (LAN, WD, BLE).
- **Roles**:
  - `HUB`: Wi-Fi Direct STAR center (Nearby advertiser).
  - `BRIDGE`: a node on LAN that also relays to/from off-LAN nodes.
  - `LEAF`: everyone else.

  A node can hold none, one, or two roles.

---

## 3. Architecture

```
UI (Compose)  ──  ChalkieViewModel
                      │
              ChalkieService (foreground)
                      │
   ┌──────────┬───────┴───────┬─────────────┐
 AudioEngine  FloorControl   Router  ◄──  ElectionManager
   │                          │            ▲
   │                    NeighborTable ─────┘
   │                    TopologyGraph
   │                          │
   └── codec       ┌──────────┼──────────┐
                LanTransport  WdTransport  BleTransport
                          (all implement Transport)
                         CryptoBox (encrypt/auth every packet)
```

### Packages
```
com.chalkie.app
 ├─ core/        Packet, PacketCodec, Ids, ChalkieConfig, Clock
 ├─ crypto/      CryptoBox, Hkdf, GroupKeys
 ├─ transport/   Transport, LinkInfo, lan/, wd/, ble/
 ├─ routing/     Router, DedupCache, NeighborTable, TopologyGraph, LinkCost
 ├─ election/    ElectionManager, ScoreCalculator, RoleState
 ├─ audio/       AudioEngine, G711, JitterBuffer, Mixer, Tones
 ├─ ptt/         FloorControl, PttInput (screen, volume keys, headset)
 ├─ group/       GroupManager, QrCodec
 ├─ service/     ChalkieService, Notifications
 ├─ data/        Room DB, LogRepository
 └─ ui/          screens
```

### Transport interface
```kotlin
enum class TransportType { LAN, WD, BLE }

interface Transport {
    val type: TransportType
    val events: Flow<TransportEvent>          // LinkUp, LinkDown, PacketIn, LinkStats
    suspend fun start()
    suspend fun stop()
    fun send(neighborId: UInt, bytes: ByteArray, priority: Priority): Boolean
    fun links(): List<LinkInfo>               // current links
}

data class LinkInfo(
    val neighborId: UInt,
    val type: TransportType,
    val rssi: Int?,              // BLE only
    val lossRate: Float,         // 0..1, rolling 10 s
    val rttMs: Int?,
    val lastSeenMs: Long,
)
```
Transports only move bytes. They never decide routing.

---

## 4. Packet format

Binary, big-endian. All transports carry the same packet bytes.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 2 | magic | `0x43 0x4B` ("CK") |
| 2 | 1 | version | `1` |
| 3 | 1 | type | see below |
| 4 | 1 | flags | bit0 encrypted, bit1-2 priority, bit3 unicast |
| 5 | 1 | ttl | decremented per hop, **not** authenticated |
| 6 | 1 | hopCount | incremented per hop, **not** authenticated |
| 7 | 1 | channel | 0..15 |
| 8 | 4 | srcId | origin nodeId |
| 12 | 4 | dstId | `0xFFFFFFFF` = broadcast |
| 16 | 8 | msgId | `sessionId(4) ‖ counter(4)` of the source |
| 24 | 4 | timestamp | source clock, ms, mod 2^32 |
| 28 | 2 | payloadLen | ciphertext length |
| 30 | n | payload | AES-GCM ciphertext |
| 30+n | 16 | tag | GCM auth tag |

**Types**

| Code | Type | Default TTL | Priority |
|---|---|---|---|
| 0x01 | HELLO | 3 | CONTROL |
| 0x02 | HUB_CLAIM | 5 | CONTROL |
| 0x03 | BRIDGE_CLAIM | 5 | CONTROL |
| 0x10 | TALK_START | 5 | CONTROL |
| 0x11 | VOICE | 3 | VOICE |
| 0x12 | TALK_END | 5 | CONTROL |
| 0x20 | TEXT | 5 | NORMAL |
| 0x30 | SOS | 7 | SOS |
| 0x31 | SOS_ACK | 7 | SOS |
| 0x40 | LAN_PROBE | 1 | CONTROL |

Priority order: `SOS > CONTROL > VOICE > NORMAL`.

**Crypto rules**
- AAD = header bytes `0..4` and `7..29`. This excludes ttl and hopCount, because relays change them.
- Nonce (12 bytes) = `srcId(4) ‖ msgId(8)`. It is unique because sessionId is random per start and the counter never repeats within a session.
- A packet failing authentication is dropped silently and counted in stats.
- Replay: reject if `msgId` is in the dedup cache, or if `timestamp` is more than 60 s older than the latest seen from that src (per-src window).

---

## 5. Group and security

### Create group (admin)
- Generate `groupSecret` (32 random bytes), `groupName`, and optional building Wi-Fi `ssid` + `password`.
- Show a QR: `chalkie://join?v=1&n=<name>&s=<base64url secret>&w=<ssid>&p=<password>`.

### Join
Scan QR → store in encrypted DataStore (Android Keystore-wrapped key).

### Key derivation (HKDF-SHA256)
- `encKey   = HKDF(secret, info="chalkie-enc-v1", 32)`
- `groupTag = first 4 bytes of HMAC-SHA256(secret, "chalkie-tag-v1")`. Used in discovery advertising; it identifies the group without exposing the secret.
- `authKey  = HKDF(secret, info="chalkie-auth-v1", 32)`. Used for the link handshake challenge.

### Link handshake (every new link, every transport)
1. Both sides send `nonce(16)`.
2. Each side replies `HMAC(authKey, peerNonce ‖ ownNodeId)`.
3. If the check fails, close the link and blacklist that endpoint for 60 s.

Rotating the group = create a new group and new QR. Old phones lose access.

---

## 6. Neighbor table and topology

### NeighborEntry
```kotlin
data class NeighborEntry(
    val nodeId: UInt,
    var name: String,
    val links: MutableMap<TransportType, LinkInfo>,
    var battery: Int,          // 0..100
    var charging: Boolean,
    var onLan: Boolean,
    var lanAddr: InetSocketAddress?,
    var ssidHash: Int?,        // hash of SSID+BSSID-prefix, never raw SSID
    var roles: Set<Role>,
    var hubEpoch: Int,
    var reportedNeighbors: List<ReportedNeighbor>, // from its HELLO
    var lastHelloMs: Long,
)
data class ReportedNeighbor(val id: UInt, val type: TransportType, val rssi: Int?)
```

- **Direct neighbors**: have at least one link.
- **Remote nodes**: known only through relayed HELLOs.
- A node is removed when there is no HELLO for `NODE_TIMEOUT_MS` (15 s).

### TopologyGraph
Built from every node's `reportedNeighbors`. It is an undirected weighted graph, where edge weight = the best `LinkCost` between the two nodes. Used for:
- Centrality in election.
- Next-hop for unicast (Dijkstra).
- The network map screen.

### LinkCost
```
base:  LAN = 0.5, WD = 1.0, BLE (rssi >= -70) = 2.0, BLE (rssi < -70) = 4.0
if lossRate > 0.10  → cost × 2
if lossRate > 0.30  → link considered DOWN for routing
```
A neighbor's **best link** = the minimum-cost link that is UP.

---

## 7. HELLO

- Sent every `HELLO_INTERVAL_MS` (2000 ms ± 300 ms jitter).
- Goes over **every** transport the node has: BLE always, LAN if active, WD if connected. Relayed with TTL 3.

### Payload
- `name` (≤ 24 bytes UTF-8)
- `battery`, `charging`
- `onLan`, `lanIp`, `lanPort`, `ssidHash`
- `roles` bitset, `hubId`, `hubEpoch`, `bridgeIds` (≤ 2)
- `neighbors` (≤ 16): `(nodeId, transportType, rssi)`
- Election score inputs (§8) as the node itself computed them.

Also send a HELLO **immediately** on:
- Any link up or down.
- A network change (LAN joined, left, or IP changed).
- Battery crossing 20% or charging state change.

---

## 8. Hub election (Wi-Fi Direct STAR)

### When a hub is needed
At least 2 nodes are **not on a usable LAN**. Only those off-LAN nodes take part. If all nodes are on LAN, there is no hub, and Wi-Fi Direct is stopped to save battery.

### Score (each node computes its own; published in HELLO)
```
eligible = battery >= 20 || charging

B = charging ? 1.0 : battery / 100
S = mean over BLE neighbors of norm(rssi),  norm(r) = clamp((r + 90) / 50, 0, 1)
    (0 if no BLE neighbors)
C = directNeighbors / max(1, knownNodes - 1)       // centrality

score = 0.4·B + 0.3·S + 0.3·C          (0 if not eligible)
```
Weights live in config.

### Deterministic choice
Every node runs the same function on its own table:
```
candidates = eligible off-LAN nodes with lastHello < 6 s
winner     = max by score, tie → lowest nodeId
```
Because all nodes see nearly the same HELLOs, they agree without voting.

### Hysteresis (no flapping)
The current hub stays unless:
1. It is **lost**: no HELLO for `HUB_LOSS_MS` (6 s), **or**
2. It became **ineligible** (battery < 20% and not charging), **or**
3. A candidate scores above `currentScore × 1.25` continuously for `HUB_SWITCH_HOLD_MS` (10 s).

### Claim and conflict resolution
- The winner increments `hubEpoch = max(seenEpochs) + 1`, sends `HUB_CLAIM(epoch, score)`, and starts Nearby advertising.
- If a node receives 2 claims:
  1. Higher epoch wins.
  2. If epochs are equal, higher score wins.
  3. If still equal, the lower nodeId wins.
- The losing claimer stops advertising immediately and becomes a LEAF.
- Leaves connect **only** to the hub whose `(hubId, epoch)` is current.

### Failover timeline
| Time | Event |
|---|---|
| t=0 | Hub disappears |
| 0–6 s | Voice continues over BLE mesh (and LAN if present). No action needed. |
| 6 s | All nodes mark the hub lost and compute the winner |
| ~7 s | Winner sends HUB_CLAIM and starts advertising |
| 8–12 s | Leaves connect; WD becomes the main path again |

During handover the user hears no gap, as long as BLE neighbors exist.

---

## 9. Bridge election (LAN ↔ off-LAN)

- Needed when some nodes are on LAN and some are not.
- Candidates: LAN nodes with at least one non-LAN link (BLE or WD) to an off-LAN node.

```
R = Σ over off-LAN direct neighbors of norm(rssi)  /  max(1, offLanCount)
bridgeScore = 0.4·B + 0.6·R
```
- Pick the top **2** (redundancy); dedup makes duplicates harmless.
- Same hysteresis and claim rules as the hub, using `BRIDGE_CLAIM` with its own epoch.
- A bridge keeps LAN + BLE. It may also join the WD star as a leaf if the phone supports STA+P2P concurrency. Nearby picks hotspot vs Wi-Fi Direct itself, and **hotspot + Wi-Fi client together usually fails**, so treat WD on a bridge as best-effort.

---

## 10. Router (the heart)

### On packet from link L (transport T, neighbor n)
```
1. parse header; bad magic/version → drop
2. if (srcId, msgId) in DedupCache → drop
3. decrypt+verify → fail → drop
4. DedupCache.add(srcId, msgId)
5. update stats for n
6. if dst == me or dst == BROADCAST → deliver locally (by type)
7. if dst == me → stop
8. if ttl == 0 → stop
9. forward(packet with ttl-1, hopCount+1, arrivedVia = T, from = n)
```

### Forwarding rules (complementarity)
Each transport has its own "who already has it" logic.

**LAN**
- The originator sends to **all** LAN peers: unicast to each, or multicast if verified (§11.1).
- So a packet that arrived via LAN has already reached every LAN peer. **Never relay back into LAN.**
- Exception: a BRIDGE relays into LAN a packet that arrived via BLE or WD.

**WD (STAR)**
- A leaf sends only to the hub.
- The hub forwards to all leaves except the one it came from.
- So a packet arriving at a leaf via WD has already reached the whole star. **Leaves never relay into WD.**
- A leaf relays into WD (to the hub) only packets that arrived via BLE or LAN.

**BLE**
For each BLE neighbor m (m ≠ from, m ≠ src), relay via BLE **unless m is covered**. m is covered when:
- The packet traversed LAN and m is on the same LAN, **or**
- The packet traversed WD and m is a member of the same star, **or**
- m's best link is not BLE and I already sent it to m through that better link in this forward step.

Order of sending inside one forward step: cheapest transport first.

The result:
- LAN and WD carry the bulk to everyone they reach.
- BLE only reaches the nodes they miss, and carries everything when they fail.
- Dedup guarantees single playback.

### Origination (my own packet)
- Send via LAN to all LAN peers (if LAN active).
- Send via WD: a leaf sends to the hub; a hub sends to all leaves.
- Send via BLE to every BLE neighbor that is not covered by LAN/WD membership.

### Unicast (private call, SOS_ACK, text to one person)
- `dstId` set, flag bit3.
- Next hop = first hop on the Dijkstra shortest path in TopologyGraph, sent over the best link to that hop.
- No route → fall back to broadcast flood with the same dstId; only dst delivers.

### DedupCache
LRU of `(srcId, msgId)`: max `4096` entries, expiry `30 s`.

### Queues
- Per link, a priority queue (SOS > CONTROL > VOICE > NORMAL).
- VOICE older than `VOICE_MAX_QUEUE_MS` (200 ms) is dropped. Late audio is worse than lost audio.

---

## 11. Transports

### 11.1 LanTransport (building Wi-Fi)
**Activation**
- `ConnectivityManager.NetworkCallback` for `TRANSPORT_WIFI`. Do **not** require `NET_CAPABILITY_INTERNET`.
- Bind all sockets to that `Network` (`network.bindSocket`), so traffic never leaks to mobile data.
- Acquire a `WifiManager.MulticastLock` while active.

**Sockets**
- UDP `47000`: data, unicast.
- UDP multicast `239.255.42.99:47001`: HELLO / discovery.
- The app never joins a network by itself. Joining uses the group QR Wi-Fi creds via `WifiNetworkSuggestion` (one-time user approval), or the user connects manually.

**Usability check (state machine)**
```
OFF → (wifi up) → PROBING
PROBING:
  send multicast HELLO + LAN_PROBE every 1 s for 5 s
  peer HELLO received via multicast      → ACTIVE_MULTICAST
  BLE says a peer has the same ssidHash, multicast silent,
    try unicast LAN_PROBE to its lanIp:
      reply                              → ACTIVE_UNICAST
      no reply                           → ISOLATED
  nobody else with same ssidHash         → STANDBY (re-probe every 15 s)
ISOLATED: client isolation suspected; LAN unused; re-probe every 30 s
any network change (roam, IP change)     → PROBING
```

**Behaviour by state**
- `ACTIVE_*`: `onLan = true` in HELLO.
- `ACTIVE_UNICAST`: peers' IPs are learned from HELLOs relayed over BLE/WD.
- Voice is sent by multicast in `ACTIVE_MULTICAST`, otherwise by unicast to each LAN peer.
- Link loss and RTT come from HELLO/PROBE echo stats.

### 11.2 WdTransport (Nearby Connections, P2P_STAR)
- serviceId: `com.chalkie.app.wd`
- endpointInfo: `groupTag(4) ‖ nodeId(4) ‖ hubEpoch(4)`
- **HUB**: `startAdvertising`; accepts only endpoints with a matching groupTag after the handshake (§5).
- **LEAF**: `startDiscovery`; requests a connection only to the endpoint whose nodeId == elected hubId and epoch == current.
- Payload type: `BYTES` (one packet per payload).
- Stop advertising/discovery when the role changes. Fully stop WD when no hub is needed (§8).
- Note: Nearby also uses BLE for discovery. Test coexistence with BleTransport; if conflicts appear, pause BLE scanning during WD connection setup (max 3 s).

### 11.3 BleTransport (mesh)
**Advertising** (legacy, low-latency mode while the service runs)
- Manufacturer data, company id `0xFFFF` (test id): `"CK"(2) ‖ groupTag(4) ‖ nodeId(4) ‖ psm(2) ‖ flags(1)`
- Scan response: Chalkie 128-bit service UUID.

**Server**
- `BluetoothAdapter.listenUsingInsecureL2capChannel()`; the PSM is put in the advertisement.

**Scanning**
- Filter on manufacturer id `0xFFFF` + prefix `CK` + own groupTag.
- Track RSSI per nodeId (EMA, α = 0.3).

**Connection rules**
- To avoid double links, **only the lower nodeId initiates** (`createInsecureL2capChannel(psm)`).
- Max `BLE_MAX_LINKS` = 4. Keep the strongest RSSI, but always keep at least 1 link to a node that is not reachable by LAN/WD (diversity).
- Drop the weakest link if a much stronger candidate (≥ 10 dB) is seen for 5 s.

**Framing and liveness**
- Stream framing: `length(2) ‖ packet`.
- Keepalive: HELLO serves as keepalive. Link down after 6 s silence or socket error.
- No pairing is needed (insecure L2CAP); security comes from the §5 handshake + encryption.

---

## 12. Voice

### Floor control (half-duplex, per channel)
- PTT down:
  - If the channel is busy (VOICE from another node in the last 500 ms) and my priority isn't higher → play the busy tone and don't transmit.
  - Otherwise send `TALK_START(streamId, priority)`, then VOICE frames.
- Collision (two TALK_STARTs within 300 ms): the winner is the higher priority, then the earlier timestamp, then the lower nodeId. The loser stops immediately and hears the busy tone.
- PTT up → `TALK_END`. Max talk time 60 s (auto-release with a warning tone).
- Priorities: `SOS > SUPERVISOR > NORMAL`. The supervisor role is set in the group QR (optional, phase 4).

### Capture
- `AudioRecord`, source `VOICE_COMMUNICATION`, 8 kHz mono PCM16.
- Enable `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` if available.
- Frame = 20 ms (160 samples). Codec v1 = **G.711 μ-law** (160 bytes/frame).
- Bundle 2 frames per VOICE packet (40 ms): ≈ 320 B payload, ≈ 25 pkt/s, ≈ 75 kbps on air.

### VOICE payload
`streamId(2) ‖ frameSeq(2) ‖ codec(1) ‖ frameCount(1) ‖ frames…`

### Playback
- A JitterBuffer per `(srcId, streamId)`: target 80 ms, max 300 ms.
- Late frames are dropped. A missing frame → repeat the last frame once, then silence.
- The Mixer sums active streams of the **selected channel** with clipping (normally 1 stream).
- `AudioTrack` usage `VOICE_COMMUNICATION`. Route to a BT headset if connected (`setCommunicationDevice`, API 31+).

### Tones
- Roger beep on TALK_END received.
- Talk-permit chirp on own PTT accepted.
- Busy tone.
- SOS alarm.

### Phase 5
Opus via `MediaCodec` (API 29+, about 16 kbps). Codec id in the payload, so mixed versions interoperate.

---

## 13. Features

### Channels
- 16 per group, with names stored in the group config.
- A node listens to its selected channel + channel 0 (General) + SOS always.

### SOS
- Long-press the SOS button for 2 s → `SOS(location?, text?)`, resent every 5 s until ≥ 1 `SOS_ACK`.
- Receivers: full-screen alert + alarm + vibration, even when the screen is locked (full-screen intent notification). Tapping "Acknowledge" sends `SOS_ACK`.

### Text quick messages
Predefined list (editable): "Arrived at FDB", "Cut power", "Need help", "Done". Sent as TEXT.

### Network map
Shows the TopologyGraph:
- Nodes with role badges.
- Edges colored by transport.
- RSSI labels.
- Battery.

### Logs (Room)
- Every talk: who, channel, start, duration.
- SOS events.
- Role changes and network events.
- Export as CSV (phase 4); chpro sync later (phase 5).

### Status bar on the main screen
- Icons for LAN / WD / BLE: active / standby / off.
- My role.
- Peer count.
- Current talker.

---

## 14. Phases

**P1: Core + BLE mesh voice**
- Packet codec, CryptoBox, group create/join QR (secret only).
- BleTransport, Router (BLE only), NeighborTable, DedupCache.
- AudioEngine G.711 + JitterBuffer, FloorControl, single channel.
- ChalkieService (foreground, types `microphone|connectedDevice`), a basic PTT screen.
- Done when: 3 phones in a line (A–B–C, A and C out of range of each other) talk both ways through B.

**P2: Wi-Fi Direct STAR + hub election**
- WdTransport, ElectionManager (hub), STAR forwarding rules, covered-set logic.
- Done when: killing the hub phone mid-talk causes no audible gap, and a new hub forms in under 12 s.

**P3: Building Wi-Fi + bridge**
- LanTransport with the probe state machine, BRIDGE election, LAN forwarding rules.
- Wi-Fi creds in the QR + WifiNetworkSuggestion.
- Done when: 2 phones on LAN + 1 phone off-LAN (BLE only to one LAN phone) all talk, and the client-isolation AP is detected as ISOLATED.

**P4: Product features**
- Channels, private call (unicast), quick text, SOS, network map, logs + CSV, volume-key and headset PTT, supervisor priority.

**P5: Extras**
- Opus.
- Store & forward voice messages.
- chpro sync when internet appears.
- Hardware PTT keys on rugged phones (e.g. Samsung XCover / Tab Active side key).

---

## 15. Testing

### JVM simulator (must exist from P1)
- `FakeTransport` per type with a configurable loss, latency and link up/down.
- `SimNetwork` lets tests build topologies: line, star, star + BLE tail, LAN + isolated node, and node killing.

### Required tests
- **Codec**: round-trip; AAD excludes ttl/hopCount; tamper → reject.
- **Dedup**: same packet through LAN + BLE → delivered once.
- **Covered-set**: in a full LAN, BLE relays are 0; in LAN + 1 BLE-only node, exactly the needed relays happen.
- **Election**:
  - Same tables → same winner on every node.
  - Hysteresis prevents flapping under noisy RSSI.
  - Split-brain resolves by the epoch/score/id rules.
- **Failover**: hub kill → voice frames keep arriving through BLE; a new hub is claimed in under 7 s of sim time.
- **Floor collision**: exactly one talker survives.

### Device test checklist
- Battery drain per hour (leaf / hub / bridge).
- Range per transport in a real building.
- Basement scenario.
- AP roaming between floors.
- Screen-off operation for 30 min.

---

## 16. ChalkieConfig (defaults)

| Key | Value |
|---|---|
| HELLO_INTERVAL_MS | 2000 (±300 jitter) |
| NODE_TIMEOUT_MS | 15000 |
| HUB_LOSS_MS | 6000 |
| HUB_SWITCH_FACTOR | 1.25 |
| HUB_SWITCH_HOLD_MS | 10000 |
| MIN_BATTERY_FOR_ROLE | 20 |
| W_BATTERY / W_SIGNAL / W_CENTRALITY | 0.4 / 0.3 / 0.3 |
| BRIDGE_W_BATTERY / BRIDGE_W_REACH | 0.4 / 0.6 |
| MAX_BRIDGES | 2 |
| TTL_VOICE / TTL_CONTROL / TTL_SOS | 3 / 5 / 7 |
| DEDUP_MAX / DEDUP_TTL_MS | 4096 / 30000 |
| BLE_MAX_LINKS | 4 |
| BLE_RSSI_GOOD | -70 |
| BLE_LINK_TIMEOUT_MS | 6000 |
| LAN_DATA_PORT / LAN_MCAST | 47000 / 239.255.42.99:47001 |
| LAN_PROBE_MS / LAN_REPROBE_MS | 5000 / 15000 (isolated: 30000) |
| VOICE_FRAME_MS / FRAMES_PER_PACKET | 20 / 2 |
| JITTER_TARGET_MS / JITTER_MAX_MS | 80 / 300 |
| VOICE_MAX_QUEUE_MS | 200 |
| CHANNEL_BUSY_MS | 500 |
| FLOOR_COLLISION_MS | 300 |
| MAX_TALK_MS | 60000 |
| SOS_RESEND_MS | 5000 |

---

## 17. Permissions and manifest

**Permissions**
- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`
- `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` (API ≤ 30 BLE scan, Nearby on ≤ 32)
- `NEARBY_WIFI_DEVICES` (API 33+), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
- `INTERNET`, `ACCESS_NETWORK_STATE` (LAN sockets, no internet needed)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` (SOS), `WAKE_LOCK`, `VIBRATE`
- `CAMERA` (QR scan)

**Service**
- `ChalkieService` holds a partial WakeLock while the group session is active.
- Ask the user to exclude Chalkie from battery optimization (explain why in onboarding).

---

## 18. Known risks

| Risk | Mitigation |
|---|---|
| Nearby needs Google Play Services (no GMS on new Huawei) | BLE + LAN still work; WD is simply unavailable on that phone |
| Nearby picks hotspot, which can kill the LAN link on a bridge | Bridge prefers LAN + BLE; WD on a bridge is best-effort |
| BLE throughput varies by phone | Voice ≈ 75 kbps fits; Opus in P5 cuts it to about 20 kbps |
| OEM background killing (Xiaomi, Samsung) | Foreground service + battery-optimization exemption + onboarding guide |
| Clock differences between phones | Only per-source ordering is used (frameSeq, msgId); no global clock needed |
