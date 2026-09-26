/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Following an operator on the move.
 *
 * Location tracking used to run only while the map was open. On the passes
 * page — the one watched while operating — the position stayed where the app
 * started, and a portable or mobile operator got azimuths for the place he
 * left, with no warning.
 *
 * Two questions, two answers:
 *
 *  - **Redraw?** Yes, on every fix. Grid square, marker, coordinates cost
 *    nothing, and the operator must see his position move.
 *  - **Recompute passes?** Not on every fix. A 48-hour SGP4 prediction for
 *    every tracked satellite is real work; rerunning it every two seconds
 *    because the GPS jittered by three metres drains the battery for nothing.
 *
 * Hence a threshold. This file holds only its arithmetic, testable on the
 * bench.
 */
object SuiviPosition {

    /**
     * Distance beyond which passes are recomputed: 3 km. Below that a LEO
     * azimuth moves by less than 0.1° and AOS times not by a second.
     */
    const val SEUIL_M: Double = 3_000.0

    /**
     * Minimum delay between two recomputes, in ms. Distance alone is not
     * enough: by car the threshold is crossed every two minutes, on the
     * motorway every ninety seconds.
     */
    const val DELAI_MIN_MS: Long = 120_000L

    /** Fix rate while the map is open: the marker must follow. */
    const val CADENCE_CARTE_MS: Long = 2_000L

    /**
     * Fix rate elsewhere in the app. Tracking now runs all the time, so this
     * rate is a battery item: twenty seconds is plenty to see a square change
     * and wakes the receiver ten times less.
     */
    const val CADENCE_FOND_MS: Long = 20_000L

    /**
     * Silence after which tracking is considered dead. More than four times
     * the background rate: one or two missed fixes trigger nothing, but a
     * mute provider is replaced before anyone notices on screen.
     */
    const val SILENCE_MAX_MS: Long = 90_000L

    /**
     * Should tracking be restarted?
     *
     * **A live job is no proof of work; a recent fix is.** The old guard only
     * checked that the job existed (`if (job.isActive) return`). A location
     * request made to Google services before they are ready leaves a job that
     * is alive but never delivers. So nothing was restarted, and the position
     * stuck ("I stay on JN28FT until I open a map") until opening the map
     * asked for another rate, which cancelled and restarted the job.
     */
    fun doitRelancer(
        auto: Boolean,
        tacheActive: Boolean,
        dernierPointMs: Long,
        demarreDepuisMs: Long,
        maintenantMs: Long,
        silenceMaxMs: Long = SILENCE_MAX_MS,
    ): Boolean {
        if (!auto) return false
        if (!tacheActive) return true
        // Give the provider time for its first fix before calling it mute:
        // a cold start sometimes takes a minute.
        if (maintenantMs - demarreDepuisMs < silenceMaxMs) return false
        return maintenantMs - dernierPointMs > silenceMaxMs
    }

    /** Great-circle distance between two points, in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Should the prediction be rerun?
     *
     * [depuisLat]/[depuisLon] are where the last computation was made. With
     * no earlier computation (app just started) it always triggers.
     */
    fun doitRecalculer(
        depuisLat: Double?, depuisLon: Double?,
        versLat: Double, versLon: Double,
        dernierCalculMs: Long, maintenantMs: Long,
        seuilM: Double = SEUIL_M, delaiMinMs: Long = DELAI_MIN_MS,
    ): Boolean {
        if (depuisLat == null || depuisLon == null) return true
        if (maintenantMs - dernierCalculMs < delaiMinMs) return false
        return distanceM(depuisLat, depuisLon, versLat, versLon) >= seuilM
    }

    /**
     * Is a fix plausible?
     *
     * GPS receivers sometimes return junk — zero coordinates on a cold start,
     * or a jump of hundreds of km on a bad ephemeris — which would move the
     * QTH, the azimuths and the antenna. (0, 0) is what a receiver with no
     * position returns; it lies in the Gulf of Guinea, a perfectly valid
     * place, which makes it all the more treacherous.
     */
    fun vraisemblable(lat: Double, lon: Double): Boolean {
        if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
        if (abs(lat) < 1e-9 && abs(lon) < 1e-9) return false
        return true
    }
}
