/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

/**
 * Where a KISS radio should listen for APRS, the same on the phone and on
 * SatMe-PC: the ground frequency, the ISS's, or the ISS's when it is up —
 * stepped by its Doppler when asked (5 kHz, the TH-D72's finest grid).
 */
object FrequenceKiss {

    const val TERRE_HZ = 144_800_000L
    const val ISS_HZ = 145_825_000L
    /** A switch costs some 5 s of deafness: one a minute at most. */
    const val INTERVALLE_MS = 60_000L

    /**
     * [travail]: "TERRE", "ISS", "AUTO" (the ISS when up, the ground
     * otherwise); "POSTE" or anything else: null — the radio left where it is. [issElDeg] and
     * [issVitesseKmS] the ISS's elevation and range rate now, when known.
     */
    fun voulue(travail: String, issEnVue: Boolean, doppler: Boolean, issElDeg: Double?, issVitesseKmS: Double?): Long? {
        val base = when (travail) {
            "TERRE" -> TERRE_HZ
            "ISS" -> ISS_HZ
            "AUTO" -> if (issEnVue) ISS_HZ else TERRE_HZ
            else -> return null
        }
        return if (base == ISS_HZ && doppler) base + palier(base, issElDeg, issVitesseKmS) else base
    }

    /** The ISS Doppler at [hz] in whole 5 kHz steps: +5 kHz early in the pass, 0 around its top, −5 kHz at its end; 0 when down. */
    fun palier(hz: Long, issElDeg: Double?, issVitesseKmS: Double?): Long {
        if (issElDeg == null || issVitesseKmS == null || issElDeg <= 0.0) return 0L
        val decalage = -hz * issVitesseKmS / 299_792.458
        return kotlin.math.round(decalage / 5_000.0).toLong() * 5_000L
    }

    /** Is the frequency followed by itself (the ISS's arrival, or its Doppler)? */
    fun suivie(travail: String, doppler: Boolean): Boolean = travail == "AUTO" || (travail == "ISS" && doppler)
}
