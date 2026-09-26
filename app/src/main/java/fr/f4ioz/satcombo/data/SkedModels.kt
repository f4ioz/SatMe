/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/** One timed look-angle sample along a pass (for the sked animation/export). */
data class SkedSample(val tMs: Long, val azDeg: Double, val elDeg: Double)

/** A satellite pass as seen from one station, densely sampled for animation. */
data class SkedStationTrack(
    val label: String,     // station id shown on the plot (locator / callsign)
    val locator: String,
    val aosMs: Long,
    val losMs: Long,
    val maxElDeg: Double,
    val samples: List<SkedSample>
) {
    /** (az, el) pairs above the horizon — the visible arc drawn on the polar plot. */
    val arc: List<Pair<Double, Double>>
        get() = samples.filter { it.elDeg >= 0.0 }.map { it.azDeg to it.elDeg }

    /** Interpolated look-angle at [tMs]; null when outside the sampled window. */
    fun sampleAt(tMs: Long): SkedSample? {
        if (samples.isEmpty()) return null
        if (tMs <= samples.first().tMs) return samples.first()
        if (tMs >= samples.last().tMs) return samples.last()
        var lo = 0; var hi = samples.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (samples[mid].tMs <= tMs) lo = mid else hi = mid
        }
        val a = samples[lo]; val b = samples[hi]
        val span = (b.tMs - a.tMs).toDouble()
        val f = if (span <= 0.0) 0.0 else (tMs - a.tMs) / span
        // Interpolate azimuth on the shortest arc to avoid a 359->0 jump.
        var dAz = b.azDeg - a.azDeg
        if (dAz > 180) dAz -= 360; if (dAz < -180) dAz += 360
        val az = ((a.azDeg + dAz * f) % 360 + 360) % 360
        val el = a.elDeg + (b.elDeg - a.elDeg) * f
        return SkedSample(tMs, az, el)
    }
}

/** A mutual-visibility sked between two stations for one satellite pass. */
data class SkedPlan(
    val satName: String,
    val catnum: Int,
    val mutualStartMs: Long,       // both stations above their horizon
    val mutualEndMs: Long,
    val you: SkedStationTrack,
    val dx: SkedStationTrack,
    val distanceKm: Double
) {
    /** Union of both passes — the range the animation scrubber covers. */
    val spanStartMs: Long get() = minOf(you.aosMs, dx.aosMs)
    val spanEndMs: Long get() = maxOf(you.losMs, dx.losMs)
    val mutualDurationSec: Long get() = ((mutualEndMs - mutualStartMs) / 1000).coerceAtLeast(0)
    fun bothVisibleAt(tMs: Long): Boolean = tMs in mutualStartMs..mutualEndMs
}
