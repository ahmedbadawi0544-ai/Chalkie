package com.chalkie.app.core

/** Every tunable number in Chalkie (SPEC §16). No magic numbers elsewhere. */
object ChalkieConfig {
    // HELLO / neighbor liveness
    const val HELLO_INTERVAL_MS = 2000L
    const val HELLO_JITTER_MS = 300L
    const val NODE_TIMEOUT_MS = 15_000L
    const val HELLO_MAX_NAME_BYTES = 24
    const val HELLO_MAX_NEIGHBORS = 16
    const val HELLO_MAX_BRIDGES = 2
    const val BATTERY_LOW_THRESHOLD = 20

    // Election (P2/P3)
    const val HUB_LOSS_MS = 6000L
    const val HUB_SWITCH_FACTOR = 1.25
    const val HUB_SWITCH_HOLD_MS = 10_000L
    const val MIN_BATTERY_FOR_ROLE = 20
    const val W_BATTERY = 0.4
    const val W_SIGNAL = 0.3
    const val W_CENTRALITY = 0.3
    const val BRIDGE_W_BATTERY = 0.4
    const val BRIDGE_W_REACH = 0.6
    const val MAX_BRIDGES = 2
    const val RSSI_NORM_FLOOR = -90
    const val RSSI_NORM_RANGE = 50
    const val ELECTION_TICK_MS = 250L
    /** Don't claim before hearing everyone's first HELLO (one interval + jitter). */
    const val HUB_SETTLE_MS = HELLO_INTERVAL_MS + HELLO_JITTER_MS + 200

    // Wi-Fi Direct STAR (Nearby Connections)
    const val WD_SERVICE_ID = "com.chalkie.app.wd"
    const val WD_MAX_IN_FLIGHT = 2
    const val WD_LINK_TIMEOUT_MS = 6000L
    const val WD_CONNECT_RETRY_MS = 3000L
    const val WD_BLE_SCAN_PAUSE_MS = 3000L

    // TTLs
    const val TTL_HELLO = 3
    const val TTL_CLAIM = 5
    const val TTL_VOICE = 3
    const val TTL_CONTROL = 5
    const val TTL_TEXT = 5
    const val TTL_SOS = 7
    const val TTL_LAN_PROBE = 1

    // Security
    const val REPLAY_WINDOW_MS = 60_000L
    const val HANDSHAKE_NONCE_BYTES = 16
    const val HANDSHAKE_BLACKLIST_MS = 60_000L
    const val HANDSHAKE_TIMEOUT_MS = 5000L
    const val GROUP_SECRET_BYTES = 32

    // Dedup
    const val DEDUP_MAX = 4096
    const val DEDUP_TTL_MS = 30_000L

    // Link cost
    const val COST_LAN = 0.5
    const val COST_WD = 1.0
    const val COST_BLE_GOOD = 2.0
    const val COST_BLE_WEAK = 4.0
    const val LOSS_PENALTY_THRESHOLD = 0.10f
    const val LOSS_PENALTY_FACTOR = 2.0
    const val LOSS_DOWN_THRESHOLD = 0.30f
    const val LINK_STATS_WINDOW_MS = 10_000L

    // BLE
    const val BLE_MAX_LINKS = 4
    const val BLE_RSSI_GOOD = -70
    const val BLE_LINK_TIMEOUT_MS = 6000L
    const val BLE_RSSI_EMA_ALPHA = 0.3
    const val BLE_SWAP_RSSI_MARGIN_DB = 10
    const val BLE_SWAP_HOLD_MS = 5000L
    const val BLE_COMPANY_ID = 0xFFFF
    const val BLE_CONNECT_RETRY_MS = 3000L
    const val BLE_MAX_FRAME_BYTES = 2048

    // LAN (P3)
    const val LAN_DATA_PORT = 47000
    const val LAN_MCAST_ADDR = "239.255.42.99"
    const val LAN_MCAST_PORT = 47001
    const val LAN_PROBE_MS = 5000L
    const val LAN_REPROBE_MS = 15_000L
    const val LAN_REPROBE_ISOLATED_MS = 30_000L
    const val LAN_PROBE_BEACON_MS = 1000L
    const val LAN_ACTIVE_BEACON_MS = 2000L
    const val LAN_LINK_TIMEOUT_MS = 6000L
    const val LAN_HANDSHAKE_RETRY_MS = 1500L
    const val LAN_TICK_MS = 250L
    const val LAN_MAX_DATAGRAM = 1400

    // Voice
    const val SAMPLE_RATE = 8000
    const val VOICE_FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * VOICE_FRAME_MS / 1000
    const val FRAMES_PER_PACKET = 2
    const val JITTER_TARGET_MS = 80
    const val JITTER_MAX_MS = 300
    const val JITTER_STREAM_IDLE_MS = 1000L
    const val VOICE_MAX_QUEUE_MS = 200L

    // Floor control
    const val CHANNEL_BUSY_MS = 500L
    const val FLOOR_COLLISION_MS = 300L
    const val MAX_TALK_MS = 60_000L

    // Features (P4)
    const val CHANNEL_COUNT = 16
    const val CHANNEL_NAME_MAX = 20
    const val SOS_RESEND_MS = 5000L
    const val TEXT_MAX_BYTES = 200
    /** Short headset-button press toggles talk; longer is hold-to-talk. */
    const val HEADSET_TOGGLE_MAX_PRESS_MS = 400L

    // Voice messages, store & forward (P5)
    const val VMSG_MAX_MS = 30_000L
    const val VMSG_CHUNK_BYTES = 700
    const val VMSG_TICK_MS = 40L
    const val VMSG_CHUNKS_PER_TICK = 1
    /** Sender: no ACK this long after the last chunk → try again later. */
    const val VMSG_ACK_TIMEOUT_MS = 4000L
    const val VMSG_RETRY_MS = 10_000L
    /** Receiver: chunks stopped arriving this long ago → ask for the missing ones. */
    const val VMSG_GAP_MS = 1500L
    const val VMSG_INCOMPLETE_DROP_MS = 60_000L
    const val VMSG_DONE_MEMORY = 256

    // Opus (P5)
    const val OPUS_BITRATE = 16_000
}
