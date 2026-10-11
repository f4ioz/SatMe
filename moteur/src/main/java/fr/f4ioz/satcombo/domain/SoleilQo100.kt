/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.util.Date
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Sun as a pointing target for the QO-100 dish.
 *
 * A geostationary target is invisible and still, and the signal that says you
 * found it only arrives once you have found it. Otherwise you aim blind with a
 * phone compass (several degrees off near any iron) and a spirit level.
 *
 * The Sun helps in two distinct ways, one daily, one twice a year:
 *
 * **Azimuth crossing**, [prochainPassageEnAzimut]. Every day the Sun is at
 * the satellite's azimuth at one instant. Then the shadow of a vertical stick
 * (or plumb line) lies exactly on the axis, 180° away. Align the dish azimuth
 * on it and no compass is needed; elevation, the easy angle, is set with a level.
 *
 * **Transit**, [prochainsTransits]. Around the equinoxes the Sun passes
 * within a degree of the satellite. The feed's shadow then centres itself in
 * the dish when pointing is right: the most precise setting possible without
 * measuring signal, and it gives both angles.
 *
 * During a transit the Sun is a huge X-band noise source: reception degrades
 * then disappears for a few minutes. Not a fault. That is why [Transit]
 * carries a duration.
 *
 * Accuracy: [SunCalc] (NOAA approximation) is good to a few hundredths of a
 * degree; the solar disc is half a degree. Times are good to the minute, which
 * is plenty. Refraction is ignored: it matters near the horizon only, and from
 * Europe the satellite is at 25° or more.
 */
object SoleilQo100 {

    /**
     * A Sun transit in front of the satellite.
     *
     * [debutMs]..[finMs] is the window where separation stays under the
     * threshold; [instantMs] is the minimum, not exactly mid-window since the
     * Sun does not cross the line of sight perpendicularly.
     */
    data class Transit(
        /** Instant of minimum separation, epoch ms. */
        val instantMs: Long,
        /** Minimum angular separation reached, degrees. */
        val ecartDeg: Double,
        /** Sun azimuth at that instant (practically the satellite's). */
        val azSoleilDeg: Double,
        /** Sun elevation at that instant. */
        val elSoleilDeg: Double,
        val debutMs: Long,
        val finMs: Long,
    ) {
        /** How long separation stays under the threshold, seconds. */
        val dureeS: Long get() = (finMs - debutMs) / 1000L
    }

    /**
     * Angular separation between two az/el directions.
     *
     * The [coerceIn] is required: two identical directions can give a cosine
     * of 1.000000000000002 from rounding, and [acos] returns NaN — on the one
     * value that matters most here.
     */
    fun ecartDeg(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(az1)
        val e1 = Math.toRadians(el1)
        val a2 = Math.toRadians(az2)
        val e2 = Math.toRadians(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** Azimuth difference wrapped to ±180°, so its sign is meaningful. */
    private fun ecartAzimut(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Next instant the Sun is at the satellite's azimuth, or `null` if none in
     * the next [jours] days, or if the satellite is not visible from here.
     *
     * Looks for a sign change of the azimuth difference in 2-minute steps,
     * then bisects to the second. Two minutes is enough at mid latitudes; near
     * the zenith in the tropics the Sun's azimuth can sweep very fast, which is
     * why the Sun must be up *at both ends* of the step before a crossing is
     * accepted.
     */
    fun prochainPassageEnAzimut(
        latDeg: Double,
        lonDeg: Double,
        depuisMs: Long,
        jours: Int = 3,
    ): Long? {
        val sat = Qo100.pointage(latDeg, lonDeg)
        if (!sat.visible) return null

        val pas = 120_000L
        val fin = depuisMs + jours * 86_400_000L
        var t = depuisMs
        var precedent = etat(latDeg, lonDeg, t, sat.azDeg)
        while (t < fin) {
            val suivant = etat(latDeg, lonDeg, t + pas, sat.azDeg)
            if (precedent.leve && suivant.leve &&
                precedent.ecartAz != 0.0 &&
                (precedent.ecartAz < 0) != (suivant.ecartAz < 0)
            ) {
                return affineAzimut(latDeg, lonDeg, t, t + pas, sat.azDeg)
            }
            t += pas
            precedent = suivant
        }
        return null
    }

    private class Etat(val leve: Boolean, val ecartAz: Double)

    private fun etat(latDeg: Double, lonDeg: Double, ms: Long, azSat: Double): Etat {
        val p = SunCalc.azElDeg(latDeg, lonDeg, Date(ms))
        return Etat(p[1] > 0.0, ecartAzimut(p[0], azSat))
    }

    /** Bisection on the sign change, down to one second. */
    private fun affineAzimut(
        latDeg: Double,
        lonDeg: Double,
        debut: Long,
        finBorne: Long,
        azSat: Double,
    ): Long {
        var a = debut
        var b = finBorne
        val signeA = etat(latDeg, lonDeg, a, azSat).ecartAz < 0
        while (b - a > 1000L) {
            val m = (a + b) / 2
            if ((etat(latDeg, lonDeg, m, azSat).ecartAz < 0) == signeA) a = m else b = m
        }
        return (a + b) / 2
    }

    /**
     * Next Sun transits in front of the satellite, within [seuilDeg].
     *
     * One-minute sweep over the whole period: about 600 000 Sun positions for
     * a year. Tens of milliseconds, but keep it off the main thread.
     *
     * A transit is kept when a day's minimum separation is under the
     * threshold. Neighbouring days qualify too (declination moves only a third
     * of a degree per day near the equinoxes); that is intended, so the
     * operator can pick one.
     *
     * **Why one degree.** A 1 m dish has a −3 dB beamwidth of about two
     * degrees in X band, so one degree is already inside the main lobe. Tighter
     * gives fewer dates and no better setting; wider drowns the real dates.
     */
    fun prochainsTransits(
        latDeg: Double,
        lonDeg: Double,
        depuisMs: Long,
        jours: Int = 400,
        seuilDeg: Double = 1.0,
        maximum: Int = 8,
    ): List<Transit> {
        val sat = Qo100.pointage(latDeg, lonDeg)
        if (!sat.visible) return emptyList()

        val out = ArrayList<Transit>()
        val pas = 60_000L
        var jour = 0
        while (jour < jours && out.size < maximum) {
            val debutJour = depuisMs + jour * 86_400_000L
            val finJour = debutJour + 86_400_000L

            // The day's minimum, to the minute.
            var meilleur = Long.MIN_VALUE
            var ecartMin = 360.0
            var t = debutJour
            while (t < finJour) {
                val e = ecartSoleil(latDeg, lonDeg, t, sat)
                if (e < ecartMin) {
                    ecartMin = e; meilleur = t
                }
                t += pas
            }

            if (ecartMin <= seuilDeg && meilleur != Long.MIN_VALUE) {
                // Refine to the second around that minute, then find both
                // edges of the under-threshold window.
                var instant = meilleur
                var e = ecartMin
                var s = meilleur - pas
                while (s <= meilleur + pas) {
                    val v = ecartSoleil(latDeg, lonDeg, s, sat)
                    if (v < e) { e = v; instant = s }
                    s += 1000L
                }
                val p = SunCalc.azElDeg(latDeg, lonDeg, Date(instant))
                out.add(
                    Transit(
                        instantMs = instant,
                        ecartDeg = e,
                        azSoleilDeg = p[0],
                        elSoleilDeg = p[1],
                        debutMs = bord(latDeg, lonDeg, instant, sat, seuilDeg, -1000L),
                        finMs = bord(latDeg, lonDeg, instant, sat, seuilDeg, 1000L),
                    )
                )
            }
            jour++
        }
        return out
    }

    private fun ecartSoleil(
        latDeg: Double,
        lonDeg: Double,
        ms: Long,
        sat: Qo100.Pointage,
    ): Double {
        val p = SunCalc.azElDeg(latDeg, lonDeg, Date(ms))
        return ecartDeg(p[0], p[1], sat.azDeg, sat.elDeg)
    }

    /**
     * Window edge, walking second by second from the minimum until back above
     * the threshold.
     *
     * Capped at half an hour: the Sun moves a quarter degree per minute, so a
     * one-degree window never exceeds about ten minutes. The cap stops an
     * absurd threshold (10°, 50°) from looping until tomorrow.
     */
    private fun bord(
        latDeg: Double,
        lonDeg: Double,
        instant: Long,
        sat: Qo100.Pointage,
        seuilDeg: Double,
        sens: Long,
    ): Long {
        var t = instant
        var n = 0
        while (n < 1800) {
            val suivant = t + sens
            if (ecartSoleil(latDeg, lonDeg, suivant, sat) > seuilDeg) return t
            t = suivant
            n++
        }
        return t
    }

    /**
     * Azimuth the shadow points to at the crossing: satellite azimuth + 180°.
     *
     * Kept here rather than in the screen: a sign error on this line turns the
     * dish exactly away from the satellite, and nothing on screen would show it.
     */
    fun azimutDeLOmbre(azSatDeg: Double): Double = (azSatDeg + 180.0) % 360.0

    /** True if the two azimuths are within [tolDeg] of each other. */
    fun memeAzimut(a: Double, b: Double, tolDeg: Double = 0.5): Boolean =
        abs(ecartAzimut(a, b)) <= tolDeg
}
