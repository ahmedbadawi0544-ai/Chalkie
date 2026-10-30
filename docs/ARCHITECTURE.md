# Chalkie architecture

This document explains how Chalkie works and where the code lives. The normative description is [`SPEC.md`](../SPEC.md); this file records how it was built, including the places where the build deliberately differs.

## 1. Layers

```
 ┌──────────────── app (Android) ────────────────┐
 │ UI (Compose) ── ViewModel ── ChalkieSession    │
 │                     │            │             │
 │   AudioEngine ──────┤            ├── Room / DataStore
 │   (AudioRecord, AudioTrack, Opus MediaCodec)   │
 │                     │                          │
 │  BleTransport   WdTransport   LanTransport     │  ← move bytes only
 └─────────┬─────────────┬─────────────┬──────────┘
           │   Transport interface (core)
 ┌─────────▼─────────────▼─────────────▼──────────┐
 │ ChalkieNode                                     │
 │  Router ─ NeighborTable ─ TopologyGraph         │
 │  ElectionManager (hub) ─ BridgeElection         │
 │  FloorControl ─ VoiceMailbox ─ LanCore          │
 │  PacketCodec ─ CryptoBox ─ LinkHandshake        │
 └──────────────── core (pure Kotlin) ─────────────┘
```

`core` has no Android dependencies. Everything that decides something (routing, elections, floor, store & forward, LAN probing, packet format and crypto) is there and runs under the JVM simulator. `app` contributes radios, audio, storage and screens.

## 2. Packets

The fixed 30-byte header (SPEC §4) carries magic, version, type, flags (encrypted, priority, unicast), ttl, hopCount, channel, srcId, dstId, msgId (`sessionId‖counter`), timestamp and payloadLen. The payload and its 16-byte GCM tag follow.

- **AAD** is the header without ttl and hopCount, because relays change those two bytes.
- **Nonce** is `srcId ‖ msgId`; sessionId is random each time the app starts.
- **Types**: HELLO, HUB_CLAIM, BRIDGE_CLAIM, TALK_START, VOICE, TALK_END, TEXT, **VMSG 0x21**, **VMSG_ACK 0x22**. SOS and SOS_ACK are reserved.

VMSG and VMSG_ACK are additions to the spec for phase 5 voice messages.

VOICE payload: `streamId(2) ‖ frameSeq(2) ‖ codec(1) ‖ frameCount(1) ‖ frames`.
- G.711 (codec 0): fixed 160-byte frames, packed back to back.
- Opus (codec 1): variable-size frames, each prefixed with a 1-byte length.

## 3. Router (SPEC §10)

One router serves all transports. It runs the receive pipeline in this order: parse, dedup, authenticate and decrypt, replay window, deliver if the packet is for me, then forward.

**Broadcast, the complementary flood**. Each packet goes through these steps in order:
1. **LAN**: the originator sends one multicast, or unicasts to each peer. A packet that arrived over LAN is never sent back into LAN. An elected **bridge** relays BLE/WD traffic into the LAN.
2. **WD star**: a hub forwards to every leaf except the sender. A leaf sends only to its hub and never relays what the star already delivered.
3. **BLE**: goes only to neighbours that LAN and the star did *not* reach (the covered set). HELLO always goes over BLE because it is also the BLE keepalive.

**Unicast** (private calls, private texts, voice messages):
- `TopologyGraph` combines my own links with every neighbour's links as reported in its HELLO.
- Dijkstra over `LinkCost` gives the next hop: LAN 0.5, WD 1, good BLE 2, weak BLE 4, doubled above 10% loss, and down above 30%.
- The packet is sent once, over the best link to that hop.
- If there is no route, or the next hop would send the packet back where it came from, the router falls back to the broadcast flood. Only the destination delivers it.
- Reported links to myself are ignored; only my own live links count.

**Dedup** keeps an LRU of `(srcId, msgId)`, 4096 entries for 30 s. Each link has a priority queue (SOS > CONTROL > VOICE > NORMAL), and voice older than 200 ms is dropped.

The router counts TX/RX per transport. The main screen shows these counts as the debug counters.

## 4. Neighbours and HELLO

HELLO goes out every 2 s ± 300 ms and travels up to 3 hops. It carries:
- name, battery and charging state;
- LAN state: on LAN, IP and port (sent even before the LAN is up, so unicast fallback has hints), and the network hash;
- roles (hub, bridge, supervisor), current hub and epoch;
- a list of direct neighbours with transport and RSSI;
- hub score and bridge score.

A node silent for 15 s is dropped.

## 5. Elections

**Hub (Wi-Fi Direct star, SPEC §8)**
- Score = 0.4·battery + 0.3·signal + 0.3·centrality; phones that are on the LAN or low on battery are not eligible.
- Every phone computes the same winner, waits a settle delay, then claims with `HUB_CLAIM(epoch, score)`.
- Conflicts are resolved by epoch, then score, then lower id.
- The hub is replaced only by a challenger at least 1.25× better for 10 s.
- Failover: when the hub is silent for 6 s, a new one is elected; the simulator shows a new star in under 12 s.
- A hub refreshes its own published score so stale claims cannot cause split brain.

**Bridge (LAN ↔ off-LAN, SPEC §9)**
- Candidates are phones on the LAN that have a non-LAN link to a phone that is off the LAN.
- Bridge score = 0.4·battery + 0.6·reach. The top 2 claim with the same hysteresis rule.

## 6. Transports

**BLE** (`BleTransport`)
- Advertises `"CK" ‖ groupTag ‖ nodeId ‖ psm ‖ flags` in manufacturer data, on insecure L2CAP CoC.
- The lower id initiates; at most 4 links, with a swap rule for stronger neighbours.
- Framing is `len(2) ‖ packet`, with a 6 s keepalive.
- Follows the adapter state; a "Bluetooth is off" banner offers to turn it on.

**Wi-Fi Direct** (`WdTransport`)
- Nearby Connections `P2P_STAR`; the elected hub advertises, and leaves connect only to the elected hub and epoch.
- Payloads are BYTES with an in-flight window of 2; every link runs the same handshake.

**LAN** (`LanTransport` + `LanCore`)
- Wi-Fi is found with a NetworkCallback that does **not** require internet, and sockets are bound to that network.
- Ports: UDP 47000 for data, multicast 239.255.42.99:47001 for beacons; a MulticastLock is held while active.
- `LanCore` holds the logic: beacons, per-peer handshake over UDP, link liveness, and the probe state machine. The states are PROBING → ACTIVE_MULTICAST / ACTIVE_UNICAST / STANDBY / ISOLATED.
- If multicast is blocked, peers are found through unicast hints learned from HELLOs relayed over BLE or WD. A network that blocks both is reported as **isolated** (client isolation).

## 7. Voice

**Capture**
- 8 kHz mono, 20 ms frames, `VOICE_COMMUNICATION` source with echo cancellation, noise suppression and gain control.
- Opus (MediaCodec, 16 kbps) when available, otherwise G.711; 2 frames per VOICE packet.

**Playback**
- One jitter buffer per (src, stream), with an 80 ms target and 300 ms maximum.
- A missing frame repeats the last one once, then plays silence.
- Each stream gets its own decoder: G.711 in core, Opus through MediaCodec. MediaCodec output, usually 48 kHz, is down-mixed and decimated to 8 kHz.
- Streams are mixed with clipping and played through `AudioTrack` with voice-communication usage, routed to a headset when one is present.

**Floor control** (`FloorControl`)
- Busy if another talker sent anything in the last 500 ms; collisions inside 300 ms are decided by priority, then timestamp, then id.
- A higher-priority talker (supervisor) pre-empts; the loser gets `StopReason.PREEMPTED`.
- Talks are cut at 60 s.
- Private calls use a separate floor key (`FloorControl.PRIVATE`), so a busy channel never blocks them.

**Channels**: there are 16. A phone hears its selected channel, General (channel 0), and private calls addressed to it.

## 8. Voice messages (store & forward)

`VoiceMailbox` (core) works like this:
- The recorded frames are serialised (`createdAt ‖ codec ‖ frames`), split into 700-byte chunks and sent to the recipient as VMSG packets at NORMAL priority, one chunk every 40 ms, so live voice always goes first.
- The **sender keeps the message** until the recipient ACKs it as complete. While the recipient is unknown or out of reach it just waits.
- The **receiver** reassembles the chunks. When they stop arriving it asks for the missing ones (VMSG_ACK with a missing list). When complete it sends an ACK, and it remembers recent uids so duplicates are acknowledged without being delivered twice.
- The app persists messages in Room. Undelivered messages are re-queued when the app restarts, and received messages are kept for playback.

Store and forward happens on the **sender**: relays don't hold custody of messages. If the sender and recipient are never reachable at the same time, the message waits.

## 9. App services

- **ChalkieService** is a foreground service with types microphone and connected device, plus a wake lock.
  - Android 14+ refuses a microphone foreground service started from the background, for example by a sticky restart. The service then runs as connected-device only and regains the microphone when the app is opened.
  - It also owns the media session for the headset button and the receiver for rugged-phone PTT broadcasts. That receiver is used only when the user enables it, because those broadcasts are spoofable.
- **ChalkieSession** wires the node, radios, audio, Room logging, chat history and the status flow shown by the UI.
- **Storage**:
  - DataStore holds identity, settings and the join code, which is encrypted with a Keystore-held AES-GCM key.
  - Room holds the `log` and `voice_message` tables.

## 10. Deviations from SPEC.md

| Spec | Build | Why |
|---|---|---|
| Network identity = SSID + BSSID hash | CRC of subnet + prefix + gateway | Reading the SSID needs the location permission on Android 10+ |
| SOS (P4) | Not built (types reserved) | Left out on request |
| chpro sync (P5) | Not built | Out of scope for this project |
| Voice messages | New VMSG / VMSG_ACK packet types | The spec names the feature but not a wire format |
| Supervisor role "in the group QR" | Separate supervisor QR (`r=s`); the creator is a supervisor | Lets the admin choose who gets priority |
| Hardware PTT | Learned key code in the foreground, plus opt-in vendor broadcasts in the background | Android has no public API for background side keys |
