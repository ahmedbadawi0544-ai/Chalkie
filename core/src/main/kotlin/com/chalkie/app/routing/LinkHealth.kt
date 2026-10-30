package com.chalkie.app.routing

import com.chalkie.app.core.ChalkieConfig
import com.chalkie.app.core.Clock
import com.chalkie.app.transport.LinkInfo
import com.chalkie.app.transport.TransportType

/**
 * Per-link trend tracking for make-before-break (SPEC §19.2).
 *
 * Each sample (LinkUp / LinkStats) updates an RSSI EMA, an EMA of its slope in dB per
 * LINK_SLOPE_WINDOW_MS, and takes the transport's rolling loss rate (already a 10 s window, so it
 * is not smoothed again: that would only delay the switch). A link is **degrading** when the slope
 * falls below LINK_DEGRADE_SLOPE_DB or the loss passes LINK_DEGRADE_LOSS: the node then
 * brings up / prefers the next-best link before this one drops.
 */
class LinkHealth(private val clock: Clock) {
    private class Track(var rssi: Double?, var loss: Double, var lastMs: Long) {
        var slope = 0.0
        var degrading = false
    }

    private val tracks = HashMap<Pair<UInt, TransportType>, Track>()

    /** Feeds one sample. Returns true when the link just turned degrading. */
    @Synchronized
    fun onSample(info: LinkInfo): Boolean {
        val now = clock.nowMs()
        val key = info.neighborId to info.type
        val a = ChalkieConfig.LINK_HEALTH_EMA_ALPHA
        val t = tracks[key]
        if (t == null) {
            tracks[key] = Track(info.rssi?.toDouble(), info.lossRate.toDouble(), now).also { it.degrading = check(it) }
            return tracks.getValue(key).degrading
        }
        val r = info.rssi
        val prev = t.rssi
        if (r != null) {
            val ema = if (prev == null) r.toDouble() else a * r + (1 - a) * prev
            val dt = now - t.lastMs
            if (prev != null && dt > 0) {
                val inst = (ema - prev) * ChalkieConfig.LINK_SLOPE_WINDOW_MS / dt
                t.slope = a * inst + (1 - a) * t.slope
            }
            t.rssi = ema
        }
        t.loss = info.lossRate.toDouble()
        t.lastMs = now
        val was = t.degrading
        t.degrading = check(t)
        return t.degrading && !was
    }

    @Synchronized
    fun onLinkDown(type: TransportType, neighborId: UInt) {
        tracks.remove(neighborId to type)
    }

    @Synchronized
    fun isDegrading(neighborId: UInt, type: TransportType): Boolean = tracks[neighborId to type]?.degrading == true

    /** dB per LINK_SLOPE_WINDOW_MS (negative = getting weaker). */
    @Synchronized
    fun slope(neighborId: UInt, type: TransportType): Double = tracks[neighborId to type]?.slope ?: 0.0

    private fun check(t: Track) = t.slope < ChalkieConfig.LINK_DEGRADE_SLOPE_DB || t.loss > ChalkieConfig.LINK_DEGRADE_LOSS
}
