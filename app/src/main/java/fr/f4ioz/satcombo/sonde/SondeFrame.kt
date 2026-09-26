/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

/**
 * A decoded radiosonde frame, whatever the manufacturer.
 *
 * Sondes don't all send the same fields. Unknown fields are zero rather than
 * null: what matters for the hunt is whether the fix can be trusted, and
 * [trusted] says so at a glance.
 */
data class SondeFrame(
    /** Sonde type: "RS41", "M20", "M10". */
    val type: String,
    /** Serial printed on the sonde; empty if the frame doesn't carry it. */
    val serial: String = "",
    /** Sonde frame counter, used to spot gaps. */
    val frameNo: Int = 0,
    /** GPS time as Unix milliseconds, 0 if unknown. */
    val timeUtcMs: Long = 0L,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** GPS altitude above the ellipsoid, metres. */
    val altM: Double = 0.0,
    /** Ground speed, m/s. */
    val speedMps: Double = 0.0,
    /** Heading, degrees from north. */
    val headingDeg: Double = 0.0,
    /** Vertical speed, m/s. Negative when descending. */
    val climbMps: Double = 0.0,
    /** Satellites used for the fix. */
    val sats: Int = 0,
    /**
     * True when the frame does not carry the satellite count.
     *
     * The M10 is such a case. Without this flag [trusted] would be false for
     * every M10, and the app would never have a safe last fix, burst or landing
     * point to give. The guarantee then is the GPS clock: a frame with a valid
     * GPS week and time comes from a receiver that has a fix.
     */
    val satsUnknown: Boolean = false,
    /** Battery voltage, volts. 0 = not sent. */
    val batteryV: Double = 0.0,
    /** Frequency the frame was received on, Hz. */
    val freqHz: Long = 0L,
    /** Phone clock at reception, milliseconds. */
    val heardAtMs: Long = 0L
) {

    /**
     * Is the fix usable?
     *
     * Below four satellites a GPS still outputs numbers, but they can be off by
     * kilometres. Better to show the last safe fix, even a minute old, than to
     * waste a hunter's afternoon.
     */
    val trusted: Boolean
        get() = (sats >= 4 || (satsUnknown && timeUtcMs > 0L)) &&
            (lat != 0.0 || lon != 0.0) &&
            lat > -90.0 && lat < 90.0 && lon >= -180.0 && lon <= 180.0

    /** Descending? The switch from positive to negative is the burst. */
    val descending: Boolean get() = climbMps < -1.0

    /**
     * Sanity check, applied even before [trusted].
     *
     * M10 and M20 have no redundancy check we can reliably reproduce, so
     * physics is the guard: a weather balloon stays below ~40 km, above sea
     * level, and under 100 m/s ground speed. A frame claiming otherwise is
     * noise.
     */
    val plausible: Boolean
        get() = lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 &&
            altM > -500.0 && altM < 45_000.0 &&
            speedMps >= 0.0 && speedMps < 200.0 &&
            climbMps > -120.0 && climbMps < 60.0
}
