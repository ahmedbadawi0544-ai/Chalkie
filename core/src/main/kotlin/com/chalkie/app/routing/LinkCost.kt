package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportType

/** SPEC §6 link cost. Null means the link is DOWN for routing. */
object LinkCost {
    fun of(link: LinkInfo): Double? {
        if (link.lossRate > ChalkieConfig.LOSS_DOWN_THRESHOLD) return null
        val base = when (link.type) {
            TransportType.LAN -> ChalkieConfig.COST_LAN
            TransportType.WD -> ChalkieConfig.COST_WD
            TransportType.BLE ->
                if ((link.rssi ?: Int.MIN_VALUE) >= ChalkieConfig.BLE_RSSI_GOOD) ChalkieConfig.COST_BLE_GOOD
                else ChalkieConfig.COST_BLE_WEAK
        }
        return if (link.lossRate > ChalkieConfig.LOSS_PENALTY_THRESHOLD) base * ChalkieConfig.LOSS_PENALTY_FACTOR else base
    }

    /** Minimum-cost link that is UP, or null. */
    fun best(links: Collection<LinkInfo>): LinkInfo? =
        links.mapNotNull { l -> of(l)?.let { l to it } }.minByOrNull { it.second }?.first
}
