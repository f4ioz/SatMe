/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/** A parsed two-line element set plus optional amateur-radio frequency metadata. */
data class TleEntry(
    val name: String,
    val line1: String,
    val line2: String,
    val catalogNumber: Int = line1.drop(2).take(5).trim().toIntOrNull() ?: 0,
    val uplinkHz: Long? = null,
    val downlinkHz: Long? = null,
    val mode: String? = null
) {
    /**
     * Mean motion (revolutions per day), columns 53–63 of line 2. Tells a
     * passing satellite from a stationary one without a hard-coded list.
     */
    val toursParJour: Double?
        get() = runCatching {
            line2.substring(52, 63).trim().toDouble()
        }.getOrNull()

    /**
     * Does this satellite stay still in the sky?
     *
     * Geostationary is 1.0027 rev/day. The 0.9–1.1 range also covers slightly
     * inclined geosynchronous birds: they trace a figure eight but the dish
     * does not move.
     *
     * No readable line 2 → false: better a useless compass than a hidden one
     * on a passing satellite.
     */
    val estImmobile: Boolean
        get() = toursParJour?.let { it in 0.9..1.1 } ?: false

    /** Epoch of the TLE (when the elements were measured), in epoch millis. */
    val epochMs: Long?
        get() = runCatching {
            // Columns 19-32 of line 1: 2-digit year + fractional day-of-year.
            val raw = line1.substring(18, 32).trim()
            val yy = raw.substring(0, 2).toInt()
            val year = if (yy < 57) 2000 + yy else 1900 + yy
            val dayOfYear = raw.substring(2).toDouble()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear(); cal.set(java.util.Calendar.YEAR, year)
            val millis = ((dayOfYear - 1.0) * 86_400_000.0).toLong()
            cal.timeInMillis + millis
        }.getOrNull()
}


/** Observer ground station. */
data class Observer(
    val latDeg: Double,
    val lonDeg: Double,
    val altMeters: Double = 0.0,
    val name: String = "QTH"
)

/** One predicted pass over the observer. */
data class SatPass(
    val satName: String,
    val catalogNumber: Int,
    val aosEpochMs: Long,        // acquisition of signal
    val losEpochMs: Long,        // loss of signal
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    val sunlit: Boolean,         // satellite illuminated -> visible to eye
    val nightAtObserver: Boolean, // dark sky at QTH
    val track: List<Pair<Double, Double>> = emptyList() // (az, el) samples AOS->LOS
) {
    /** ISS-Detector-style: a pass you can actually see with the naked eye. */
    val visualPass: Boolean get() = sunlit && nightAtObserver && maxElevationDeg >= 10.0
    val durationSec: Long get() = (losEpochMs - aosEpochMs) / 1000
}

/** Instantaneous look-angles used for the live polar plot / compass. */
data class SatPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,    // + = moving away
    val altKm: Double,
    val latDeg: Double,
    val lonDeg: Double,
    val sunlit: Boolean
)
