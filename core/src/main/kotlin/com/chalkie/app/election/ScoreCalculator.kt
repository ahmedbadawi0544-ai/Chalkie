package com.chalkie.app.election

import com.chalkie.app.core.ChalkieConfig

/** Hub score (SPEC §8). Each node computes its own and publishes it in HELLO. */
object ScoreCalculator {
    fun eligible(battery: Int, charging: Boolean): Boolean =
        battery >= ChalkieConfig.MIN_BATTERY_FOR_ROLE || charging

    fun normRssi(rssi: Int): Double =
        ((rssi - ChalkieConfig.RSSI_NORM_FLOOR).toDouble() / ChalkieConfig.RSSI_NORM_RANGE).coerceIn(0.0, 1.0)

    /**
     * @param bleRssis RSSI of each BLE neighbor
     * @param directNeighbors neighbors reached over a physical link (BLE/LAN; WD links are
     *   excluded so the current hub does not score itself up just for being the hub)
     * @param knownNodes every node in the table, me included
     */
    fun hubScore(battery: Int, charging: Boolean, bleRssis: List<Int>, directNeighbors: Int, knownNodes: Int): Float {
        if (!eligible(battery, charging)) return 0f
        val b = if (charging) 1.0 else battery / 100.0
        val s = if (bleRssis.isEmpty()) 0.0 else bleRssis.map(::normRssi).average()
        val c = directNeighbors.toDouble() / maxOf(1, knownNodes - 1)
        return (ChalkieConfig.W_BATTERY * b + ChalkieConfig.W_SIGNAL * s + ChalkieConfig.W_CENTRALITY * c.coerceAtMost(1.0)).toFloat()
    }
}
