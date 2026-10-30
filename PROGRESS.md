# Chalkie — Build Progress

Legend: [x] done · [~] in progress · [ ] todo

## Phase 1 — Core + BLE mesh voice

### Project setup
- [x] Gradle project (`:core` pure Kotlin/JVM, `:app` Android)

### `:core` (pure Kotlin, JVM-testable)
- [x] ChalkieConfig, Ids, Clock
- [x] Packet + PacketCodec (header, AAD, relay patching)
- [x] Crypto: Hkdf, GroupKeys, CryptoBox (AES-256-GCM), LinkHandshake
- [x] Group QR codec (`chalkie://join?...`)
- [x] Transport interface, LinkInfo, events
- [x] DedupCache, LinkCost, NeighborTable, HELLO payload
- [x] Router (BLE flooding, dedup, replay window)
- [x] LinkSendQueue (priority + stale-voice drop)
- [x] Audio DSP: G.711 μ-law, VoicePayload, JitterBuffer, Mixer, Tones
- [x] FloorControl (busy, collision, max talk time)
- [x] ChalkieNode (composition: router + HELLO + floor + voice)

### JVM simulator + tests (§15)
- [x] FakeTransport + SimNetwork
- [x] Codec tests (round-trip, AAD excludes ttl/hop, tamper reject)
- [x] Dedup test
- [x] Line topology A–B–C voice both ways through B
- [x] Floor collision test

### `:app` (Android)
- [x] Manifest + permissions
- [x] GroupManager (Keystore-wrapped secret) + QR create (ZXing) / scan (CameraX + ML Kit)
- [x] BleTransport (advertise, scan, L2CAP CoC, handshake, framing, keepalive)
- [x] AudioEngine (AudioRecord/AudioTrack, AEC/NS/AGC, jitter playout, tones)
- [x] ChalkieService (foreground, microphone|connectedDevice, wakelock)
- [x] PTT screen (Compose) + permissions onboarding
- [x] `assembleDebug` builds → `app/build/outputs/apk/debug/app-debug.apk`

**JVM tests: 43/43 passing** (codec 8, crypto 8, routing 6, audio 10, mesh sim 11)

### Next
- [x] Installed on S24 Ultra, Tab Active3, OPPO A73 (fixed: Android<13 URL codec crash, BLE start when Bluetooth was off)

### Done criterion
- [x] 3 devices talk to each other — confirmed by you on 2026-10-30

---

## Phase 2 — Wi-Fi Direct STAR + hub election

### `:core`
- [x] StarRole / StarControl, HUB_CLAIM payload, config keys
- [x] ScoreCalculator (B/S/C weights, eligibility)
- [x] ElectionManager (deterministic winner, hysteresis, claims, split-brain, step-down)
- [x] Router: STAR forwarding + covered-set (hub → all leaves, leaves never relay into WD, BLE skips covered nodes)
- [x] ChalkieNode: election loop, roles in HELLO, role → transports

### Simulator + tests (§15)
- [x] Fake WD transport (hub advertising, leaf connect delay)
- [x] Same tables → same winner
- [x] Hysteresis under noisy RSSI (no flapping)
- [x] Split-brain resolves by epoch/score/id
- [x] Failover: hub kill → voice keeps arriving via BLE; new hub claimed < 7 s
- [x] Covered-set: full star = 0 BLE voice relays; star + BLE tail = exactly the needed relays

**JVM tests: 59/59 passing** (Phase 2 adds election 7, star sim 9)

### `:app`
- [x] WdTransport (Nearby Connections P2P_STAR, handshake, in-flight window)
- [x] Permissions (NEARBY_WIFI_DEVICES, location ≤ API 32)
- [x] UI: WD chip + my role
- [~] Build + install on 3 devices

### Done criterion
- [ ] Kill the hub phone mid-talk: no audible gap, new hub in < 12 s — **device test by you**

---

## Phase 3 — Building Wi-Fi (LAN) + bridges

### `:core`
- [x] LanCore: UDP framing, beacons, per-peer handshake, probe state machine (PROBING / ACTIVE_MULTICAST / ACTIVE_UNICAST / STANDBY / ISOLATED)
- [x] BridgeElection (top 2 by bridge score, hysteresis, BRIDGE_CLAIM)
- [x] Router: LAN rules (originator → all LAN peers, never relay back into LAN, bridge relays BLE/WD → LAN, BLE skips LAN-covered nodes)
- [x] ChalkieNode: onLan / lanIp / ssidHash in HELLO, LAN nodes leave hub election

### Simulator + tests
- [x] LanCore: multicast OK → ACTIVE_MULTICAST; multicast blocked → ACTIVE_UNICAST; client isolation → ISOLATED; alone → STANDBY
- [x] Covered-set: full LAN = 0 BLE voice relays
- [x] 2 LAN + 1 off-LAN (BLE to one LAN phone): all talk, exactly the needed relays
- [x] Bridge election picks top 2; LAN nodes never hub

### `:app`
- [x] LanTransport (NetworkCallback w/o INTERNET, bindSocket, MulticastLock, UDP 47000 + multicast 239.255.42.99:47001)
- [x] Wi-Fi creds in QR + WifiNetworkSuggestion
- [x] UI: LAN chip + bridge badge, Wi-Fi fields when creating a team
- [x] Build + install (S24, Tab, OPPO)

### Device feedback fixes
- [x] Peer row lists every live link, cheapest first, ★ on the preferred one (e.g. "WD ★ + BLE -39 dBm"); hub/bridge badges
- [x] Debug counters per transport (↑TX ↓RX packets) under the status chips
- [x] Router unit tests: leaf voice goes over WD with no BLE duplicate to the hub; hub relays leaf voice over WD only; BLE carries voice when the WD link is missing; WD cheaper than BLE (77/77 tests)
- [x] LAN chip shows probe state (checking… / LAN · n / unicast / standby / blocked)
- [x] Talk line: "<name> is talking" during, "Last: <name> · Ns" after

### Done criterion
- [ ] 2 phones on LAN + 1 off-LAN (BLE only to one LAN phone) all talk; client-isolation AP detected as ISOLATED — **device test by you**

## Phase 4 — Product features (SOS left out on request)

### `:core`
- [x] Channels: 16 per team, names in the QR, listen to selected + General
- [x] TopologyGraph (my links + HELLO-reported links) + Dijkstra next hop over LinkCost
- [x] Router unicast: next hop over its best link; no route / bounce → flood, only dst delivers
- [x] Private calls (unicast TALK_START/VOICE/TALK_END on a separate floor), private texts
- [x] Quick texts (TEXT, channel or private)
- [x] Supervisor priority: SUPERVISOR talk pre-empts NORMAL (StopReason.PREEMPTED); supervisor QR (`r=s`)

### `:app`
- [x] Room DB: activity log (talk / call / text / voice msg / role / network) + CSV export
- [x] Tabs: Talk (channel picker, quick texts) · Team (private call, text, voice msg) · Messages · Map · Log
- [x] Network map (Canvas): nodes, role badges, battery, edges colored by transport, RSSI labels
- [x] PTT on volume key / learned key (foreground), headset button (media session: tap toggles, hold = hold-to-talk)
- [x] Settings: PTT key, Opus, rugged PTT, quick texts, offline / leave

### Tests
- [x] Channels, private call vs busy channel, shortest-path unicast (one send per hop), channel text, supervisor pre-emption
- [x] TopologyGraph next hop, unicast flood fallback, QR channels + supervisor

## Phase 5 — Extras (chpro left out on request)
- [x] Opus via MediaCodec (16 kbps), codec id in payload, variable-size frames, G.711 fallback, per-stream decoders
- [x] Store & forward voice messages: VoiceMailbox (chunks, ACK + missing list, retry, waits for reachability), Room persistence, re-queue on restart
- [x] Sim test: message to an unreachable node delivered complete over 2 lossy hops once it returns
- [x] Hardware PTT on rugged phones: learned side key (XCover / Tab Active) + opt-in vendor PTT broadcasts (Kyocera/Sonim/Knox)
- [x] Fix: Android 14+ refuses a microphone FGS from the background (sticky restart) → fall back to connected-device, upgrade on resume

**Core tests: 88/88 passing.**

## Wi-Fi 8–style reliability (SPEC §19)

### `:core`
- [x] Election: primary + standby hub, fast loss on WD link-down, provisional succession (confirm / revert / timeout)
- [x] LinkHealth: RSSI slope EMA + loss; degrading links lose coverage, unicast prefers healthy links, backup link requested
- [x] Critical packets (SOS, SOS_ACK, TALK_START, TALK_END, TALK_ACK) on 2 transports; VOICE duplicated only while degrading
- [x] TALK_ACK for private calls
- [x] VOICE redundancy (previous packet's frames, codec bit 7); jitter buffer conceals one whole packet
- [x] Talk mode: HELLO 5 s, longer loss windows, `TalkAware` transports
- [x] Send queues report loss (refused + stale drops per window)

### `:app`
- [x] WdTransport: leaves keep discovering while a standby exists, cached adverts → instant connect to the new hub
- [x] BleTransport: low-power scan during talks, `prepareLink` (preferred + 1 spare link), loss in LinkStats
- [x] "X is hearing you" on TALK_ACK

### Tests
- [x] Hub kill with standby → voice gap < 2 s (star-only path)
- [x] 20 % random loss → no silent frames
- [x] Critical duplication, degrade-only VOICE duplication, slope detection, HELLO interval, TALK_ACK, succession unit tests

**Core tests: 104/104 passing.**

### Done criterion
- [ ] Kill the hub phone mid-talk with 3+ phones on Wi-Fi Direct: no audible gap — **device test by you**

