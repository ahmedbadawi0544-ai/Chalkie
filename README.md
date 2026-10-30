# Chalkie

**Offline push-to-talk for field teams.** Chalkie turns Android phones into a walkie-talkie network that needs no internet, no SIM and no infrastructure. It uses every radio the phones have, at the same time:

| Transport | What it is | Range / role |
|---|---|---|
| **Building Wi-Fi (LAN)** | Any Wi-Fi access point, even one with no internet | Fastest, longest reach inside a site |
| **Wi-Fi Direct STAR** | Google Nearby Connections, one elected hub phone | High bandwidth between phones with no access point |
| **Bluetooth LE mesh** | L2CAP links between neighbours, multi-hop relays | Always on, reaches phones nobody else reaches |

All three feed **one router**. It sends each packet over the cheapest path and avoids duplicates on the slower radios. Every packet is encrypted with the team key, and each receiver plays it once.

> Built from [`SPEC.md`](SPEC.md), phase by phase. [`PROGRESS.md`](PROGRESS.md) is the build log and checklist.

---

## Features

- **Push-to-talk**: hold the big button to talk. The channel shows as busy while someone else talks. A collision between two talkers is resolved the same way on every phone.
- **16 channels**: the admin names them when creating the team. Everyone also hears **General** (channel 0).
- **Private calls**: hold *Call privately* on a teammate. The call goes only to them, along the shortest path through the mesh.
- **Quick texts**: one-tap messages such as "Arrived at FDB", "Cut power", "Need help" or "Done". They go to the channel or privately, and the list is editable.
- **Voice messages, store & forward**: record a message for a teammate who is out of reach. It waits on your phone and is delivered, acknowledged and retried automatically when they come back.
- **Supervisor priority**: supervisors, who join with a supervisor QR, talk over normal users.
- **Network map**: a live graph of the team with links coloured by transport, RSSI labels, battery and hub/bridge/supervisor badges.
- **Activity log**: talks, calls, texts, voice messages, role changes and network events, stored in Room and exportable as **CSV**.
- **Hardware PTT**:
  - the volume key or any key you teach it, such as the Samsung XCover or Tab Active side key;
  - headset and Bluetooth buttons: a short press toggles, holding gives hold-to-talk;
  - rugged-phone PTT broadcasts, which must be enabled in Settings.
- **Opus voice codec**: about 16 kbps through Android's MediaCodec, with G.711 fallback. The codec id travels in every packet, so phones with either codec can talk to each other.
- **Automatic roles**:
  - **Hub election** for the Wi-Fi Direct star, with hysteresis and failover.
  - **Bridge election**: the best phones on Wi-Fi relay to the phones that aren't on it.
  - **LAN probing**: detects multicast, unicast-only and client-isolated access points.
- **Team Wi-Fi in the QR**: the admin can add the site Wi-Fi name and password, and joining phones get it as a network suggestion.

SOS is specified, and its packet types are reserved, but it is **not included** in this build.

---

## Quick start

### Requirements
- Android **10 (API 29)** or newer. BLE L2CAP channels need API 29.
- Google Play Services for Wi-Fi Direct. Without it, the phone still works over BLE and LAN.
- To build: JDK 17+ (21 recommended) and the Android SDK with platform 36.

### Build & install
```bash
./gradlew :core:test            # 88 JVM tests: codec, crypto, routing, elections, simulator
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Create a `local.properties` with `sdk.dir=/path/to/Android/Sdk` if Android Studio has not already created one.

### First use
1. **Admin**: enter your name, then **Create team**. Channel names and the site Wi-Fi are optional.
2. Tap the **QR** icon. Teammates scan the *Team member* code. Show the *Supervisor* code only to supervisors.
3. Allow the permissions (microphone, nearby devices, notifications) and battery optimisation.
4. Hold the button and talk.

The full walkthrough is in [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md).

---

## Project layout

```
core/   Pure Kotlin (JVM), no Android. All logic, unit-tested and simulated.
  core/        ChalkieConfig (every tunable), packet format, ids, clock
  crypto/      HKDF, AES-256-GCM CryptoBox, group keys, link handshake
  group/       GroupInfo + QR join code
  routing/     Router, NeighborTable, HELLO, LinkCost, DedupCache, TopologyGraph
  election/    Hub election (WD star), bridge election (LAN ↔ off-LAN)
  transport/   Transport interfaces, send queues, LanCore (LAN protocol logic)
  audio/       G.711, VoicePayload (G.711 + Opus), JitterBuffer, Mixer, Playout, tones
  ptt/         FloorControl (busy, collisions, priority, private floor)
  message/     VoiceMailbox (store & forward voice messages)
  node/        ChalkieNode: composes everything; what the app and simulator drive
  test/        Unit tests + JVM network simulator (virtual time, fake BLE/WD/LAN)

app/    Android: transports, audio I/O, service, storage, UI.
  transport/ble  BleTransport (advertise/scan, L2CAP CoC, framing, keepalive)
  transport/wd   WdTransport (Nearby Connections P2P_STAR)
  transport/lan  LanTransport (NetworkCallback, bound UDP sockets, multicast lock)
  audio/         AudioEngine (capture, playback, routing), Opus via MediaCodec
  service/       ChalkieService (foreground), ChalkieSession (wires node + radios + UI state)
  data/          Room: activity log, voice messages
  group/         GroupStore (DataStore; join code encrypted with an Android Keystore key)
  ui/            Compose: setup, Talk / Team / Messages / Map / Log tabs, settings
```

The architecture, protocol and design decisions are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Testing

- **JVM** (`./gradlew :core:test`): 88 tests, including a network simulator that runs whole teams in virtual time. It covers multi-hop BLE, hub failover, split brain, LAN + bridge topologies, client-isolated access points, channels, private calls, shortest-path unicast, supervisor pre-emption, and voice messages delivered over lossy multi-hop links after the recipient returns.
- **Devices**: tested on a Galaxy S24 Ultra (Android 14+), a Galaxy Tab Active3 (Android 13) and an OPPO A73 (Android 11).

## Security

- The team secret comes only from the QR code. Separate encryption and authentication keys are derived from it with HKDF-SHA256.
- Every packet is encrypted with AES-256-GCM, with the header authenticated; relays can change only the TTL and hop count. Replays are rejected by dedup and a 60 s window.
- Every new link, on every transport, runs a challenge–response handshake before any data is accepted.
- The join code is stored encrypted with an AES key that never leaves the Android Keystore.
- Rugged-phone PTT broadcasts are off by default, because any app can send them.

## License

No license has been chosen yet; all rights reserved by the author.
