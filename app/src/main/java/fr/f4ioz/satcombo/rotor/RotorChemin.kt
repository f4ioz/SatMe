/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mast's path for a whole pass, chosen before it: continuous, at the
 * rotator's real speed, within its travel, missing the satellite as little
 * as possible **on the sky** — not in azimuth.
 *
 * Near the zenith a large azimuth gap is a small miss: at 79° of elevation,
 * pointing 180° of azimuth away with the antennas raised to the zenith misses
 * by 11°. So a pass going overhead on the side of the end stop is followed
 * the other way round, through the far side of the zenith, with no turn:
 * Olivier's ISS pass of 08/10 (south stop, culmination at 79° on the south
 * side). The antennas are raised when that brings them closer, and aim over
 * the top on a rotator that flips (elevation to 180°).
 *
 * A full turn (≈ a minute of antennas sweeping the sky) is only taken when it
 * is clearly worth it — a low pass staying long behind the stop — and never
 * during an expected SSTV picture.
 *
 * Dynamic programming over the pass's samples × the reachable azimuths (2°
 * steps): a few hundred thousand operations, nothing for a phone.
 */
object RotorChemin {

    /** One moment of the path: the command (unwrapped azimuth), the miss, a turn under way. */
    data class Point(val tMs: Long, val azDeg: Double, val elDeg: Double, val erreurDeg: Double, val tour: Boolean = false)

    /** Angle between two directions of the sky, degrees. Elevations above 90 aim over the top. */
    fun separation(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(az1); val e1 = Math.toRadians(el1)
        val a2 = Math.toRadians(az2); val e2 = Math.toRadians(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /**
     * The antennas' elevation closest to the satellite ([azSat], [elSat]) when
     * they point at azimuth [azAnt]: raised towards the zenith when the
     * azimuths differ, over the top up to [elMax] on a rotator that flips.
     */
    fun meilleureElevation(azSat: Double, elSat: Double, azAnt: Double, elMax: Double): Double {
        val e = Math.toRadians(elSat)
        val d = Math.toRadians(azSat - azAnt)
        val best = Math.toDegrees(atan2(sin(e), cos(e) * cos(d)))   // 0..180
        return best.coerceIn(0.0, elMax)
    }

    /** Missed by more than this beyond the tolerance, the satellite is lost: it costs no more however far. */
    const val PERDU_DEG = 30.0

    /**
     * What a miss costs: nothing within [tolerance] but a trifle, then fast,
     * up to "lost" — so a turn is weighed against the time lost at the stop:
     * a lost second is a lost second, whether by 50° or by 120°.
     */
    private fun cout(erreur: Double, tolerance: Double): Double {
        val x = max(0.0, erreur - tolerance)
        return min(x * x, PERDU_DEG * PERDU_DEG) + 0.05 * erreur
    }

    /**
     * The path for [trace] (time, azimuth, elevation of the satellite).
     * [degParS]: the rotator's speed. [depuisAz]: the mast's unwrapped azimuth
     * now; with [imposeDepart] the path must start within reach of it (a plan
     * redone during the pass), else it only weighs the travel to get there.
     * [images]: expected SSTV pictures (no turn during them, the miss weighs
     * more there).
     */
    fun plan(
        trace: List<Triple<Long, Double, Double>>,
        limits: RotorMath.Limits,
        degParS: Double,
        toleranceDeg: Double,
        images: List<LongRange> = emptyList(),
        depuisAz: Double? = null,
        imposeDepart: Boolean = false,
        pasDeg: Double = 2.0
    ): List<Point> {
        if (trace.size < 2) return emptyList()
        val azMin = limits.azMinReach
        val k = (limits.azMaxDeg / pasDeg).roundToInt()
        val azs = DoubleArray(k + 1) { azMin + it * pasDeg }
        val n = trace.size
        val dtS = (trace.last().first - trace.first().first) / 1000.0 / (n - 1)
        // How far the rotator turns between two samples. A turn back the other way is just
        // that, at full speed: no special move, the real speed decides where it fits.
        val saut = max(1, (degParS * dtS / pasDeg).toInt())
        fun dansImage(t: Long) = images.any { t in it }
        // What each sample weighs: the high part of the pass most, an expected picture far more
        // (the antennas do not leave the satellite during one when they can help it).
        val poids = DoubleArray(n) { i ->
            val (t, _, el) = trace[i]
            (0.2 + max(0.0, sin(Math.toRadians(el)))) * (if (dansImage(t)) 10.0 else 1.0)
        }
        val elMax = limits.elMaxDeg
        val err = Array(n) { i -> DoubleArray(k + 1) { j ->
            val (_, az, el) = trace[i]
            separation(az, el, azs[j], meilleureElevation(az, el, azs[j], elMax))
        } }
        val inf = Double.MAX_VALUE / 4
        val c = Array(n) { DoubleArray(k + 1) { inf } }
        val avant = Array(n) { IntArray(k + 1) { -1 } }
        for (j in 0..k) {
            val depart = depuisAz?.let { abs(azs[j] - it) } ?: 0.0
            if (imposeDepart && depart > saut * pasDeg + 1e-9) continue
            c[0][j] = poids[0] * cout(err[0][j], toleranceDeg) + 0.002 * depart
        }
        for (i in 0 until n - 1) {
            for (j in 0..k) {
                val ci = c[i][j]
                if (ci >= inf) continue
                // Within the rotator's speed, a trifle per degree turned (wear).
                for (d in -saut..saut) {
                    val jj = j + d
                    if (jj < 0 || jj > k) continue
                    val v = ci + poids[i + 1] * cout(err[i + 1][jj], toleranceDeg) + 0.01 * abs(d) * pasDeg
                    if (v < c[i + 1][jj]) { c[i + 1][jj] = v; avant[i + 1][jj] = j }
                }
            }
        }
        var j = (0..k).minByOrNull { c[n - 1][it] } ?: return emptyList()
        if (c[n - 1][j] >= inf) return emptyList()
        val etats = IntArray(n)
        for (i in n - 1 downTo 0) { etats[i] = j; if (i > 0) j = avant[i][j] }
        val out = ArrayList<Point>(n)
        for (i in 0 until n) {
            val (t, az, el) = trace[i]
            val a = azs[etats[i]]
            val e = meilleureElevation(az, el, a, elMax)
            // A turn: the antennas turning fast, far from the satellite (it is lost meanwhile).
            val vite = i > 0 && abs(a - azs[etats[i - 1]]) >= 0.6 * saut * pasDeg ||
                i < n - 1 && abs(azs[etats[i + 1]] - a) >= 0.6 * saut * pasDeg
            val ecart = separation(az, el, a, e)
            out += Point(t, a, e, ecart, tour = vite && ecart > toleranceDeg + 20.0)
        }
        return out
    }

    /** The turns of a path (start..end), where the antennas lose the satellite while going round. */
    fun tours(chemin: List<Point>): List<LongRange> {
        val out = ArrayList<LongRange>()
        var debut: Long? = null
        for ((i, p) in chemin.withIndex()) {
            if (p.tour && debut == null) debut = p.tMs
            if ((!p.tour || i == chemin.size - 1) && debut != null) { out += debut..p.tMs; debut = null }
        }
        return out
    }

    /** The command for [tMs]: the path interpolated (a little ahead, the rotator lags). */
    fun a(chemin: List<Point>, tMs: Long): Point? {
        if (chemin.isEmpty()) return null
        if (tMs <= chemin.first().tMs) return chemin.first()
        if (tMs >= chemin.last().tMs) return chemin.last()
        val i = chemin.indexOfFirst { it.tMs >= tMs }.coerceAtLeast(1)
        val p = chemin[i - 1]; val q = chemin[i]
        val f = (tMs - p.tMs).toDouble() / (q.tMs - p.tMs).coerceAtLeast(1)
        return Point(tMs, p.azDeg + (q.azDeg - p.azDeg) * f, p.elDeg + (q.elDeg - p.elDeg) * f,
            p.erreurDeg + (q.erreurDeg - p.erreurDeg) * f, p.tour || q.tour)
    }
}
