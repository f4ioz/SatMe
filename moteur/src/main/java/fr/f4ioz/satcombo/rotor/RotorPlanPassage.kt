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
import kotlin.math.max
import kotlin.math.sin

/**
 * The whole pass decided before AOS: on which side of the end stop, upright or
 * over the top, and — when one is worth it — the single moment the mast
 * changes its mind (a flip, or a full unwind round the stop).
 *
 * Every candidate is played out against the rotator's real speed, second by
 * second, and judged on what the antennas actually see:
 *
 * 1. **coverage**, weighted by sin(elevation): a minute lost at 5° is worth
 *    little next to one at 60°; obstacle zones weigh almost nothing;
 * 2. then **travel** (mast degrees, the way to the start included: at equal
 *    coverage a short pass stays on the side nearest the mast; lying over
 *    the top, and each change, count [PRIX_RETOURNEMENT] more);
 * 3. then **fewest changes**.
 *
 * On an elevation rotator reaching 180° the pass is tried upright and over the
 * top (az + 180, 180 − el), and upright then flipped at each sample. At 90°
 * only the unwind is left, and its moment is the one losing least: while the
 * satellite is low, or behind an obstacle, rather than at the top of the pass.
 *
 * Tracking then reads the branch from the clock ([vise]): no flip is ever
 * reopened during the pass, so the mast cannot flap.
 */
object RotorPlanPassage {

    /**
     * A part of the sky not worth pointing at: a roof, a tree, a hill —
     * azimuth [azDe] to [azA] clockwise (wrapping through north allowed),
     * below [elSous] degrees.
     */
    data class Zone(val azDe: Double, val azA: Double, val elSous: Double) {
        fun contient(az: Double, el: Double): Boolean {
            if (el >= elSous) return false
            val a = RotorMath.norm360(az); val d = RotorMath.norm360(azDe); val f = RotorMath.norm360(azA)
            return if (d <= f) a in d..f else a >= d || a <= f
        }
    }

    /** Zones as stored: `170-190<20;300-20<10`. Bad parts are skipped. */
    fun litZones(s: String): List<Zone> = s.split(';').mapNotNull { p ->
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*-\s*(-?\d+(?:\.\d+)?)\s*<\s*(\d+(?:\.\d+)?)\s*$""").find(p) ?: return@mapNotNull null
        val e = m.groupValues[3].toDouble()
        if (e <= 0.0 || e > 90.0) null
        else Zone(RotorMath.norm360(m.groupValues[1].toDouble()), RotorMath.norm360(m.groupValues[2].toDouble()), e)
    }

    fun ecritZones(l: List<Zone>): String = l.joinToString(";") {
        Math.round(it.azDe).toString() + "-" + Math.round(it.azA) + "<" + Math.round(it.elSous)
    }

    /** What a sample inside a zone weighs, relative to the open sky. */
    const val POIDS_ZONE = 0.05

    /** Two coverages closer than this are equal: travel decides. A few low seconds are not worth a flip. */
    const val EGALITE_COUVERTURE = 0.01

    /**
     * What lying over the top costs, in degrees of travel, when coverage is
     * equal: upright stays unless the flip saves more than that (the same
     * margin as the per-second hysteresis of [RotorMath.FLIP_HYSTERESIS_DEG]).
     */
    const val PRIX_RETOURNEMENT = RotorMath.FLIP_HYSTERESIS_DEG

    /** Two travels closer than this (degrees) are equal: the number of changes decides. */
    const val EGALITE_COURSE = 5.0

    /** One way of writing the pass for the mast: upright or over the top, and which turn of the ring. */
    data class Branche(val retourne: Boolean, val decalage: Double)

    /** A change of branch during the pass, as the operator is told: when, how long, what. */
    data class Evenement(val debutMs: Long, val finMs: Long, val retournement: Boolean) {
        val dureeS: Int get() = ((finMs - debutMs + 999) / 1000).toInt()
    }

    /**
     * The plan: the mast simulated sample by sample ([points], unwrapped
     * azimuth, elevation up to 180; `tour` marks a change under way), the
     * branch before and after [basculeMs], and its marks.
     */
    data class Plan(
        val points: List<RotorChemin.Point>,
        val avant: Branche,
        val apres: Branche?,
        val basculeMs: Long?,
        val couverture: Double,
        val courseDeg: Double,
        val evenement: Evenement?,
        /** Where the branch wants the mast, sample by sample, before clamping: the reference for [vise]. */
        internal val tMs: LongArray,
        internal val refAvant: DoubleArray,
        internal val refApres: DoubleArray?
    ) {
        val changements: Int get() = if (basculeMs == null) 0 else 1
        val retourne: Boolean get() = avant.retourne || apres?.retourne == true
    }

    /** The sky direction a mast command points at (elevation above 90 looks over the top). */
    fun versCiel(azDeg: Double, elDeg: Double): Pair<Double, Double> =
        if (elDeg > 90.0) RotorMath.norm360(azDeg + 180.0) to 180.0 - elDeg
        else RotorMath.norm360(azDeg) to elDeg

    private class Serie(val b: Branche, val want: DoubleArray, val el: DoubleArray)

    /**
     * Plans [trace] (time, azimuth, elevation of the satellite, AOS to LOS).
     * [depuis]: the mast now (unwrapped), null when unknown; with
     * [imposeDepart] the mast starts there (a plan redone during the pass),
     * else it is assumed waiting at the start and only the way there counts
     * as travel. [degParS]: the rotator's speed. [toleranceDeg]: a miss the
     * beam forgives. [images]: expected SSTV pictures (ten times the weight).
     */
    fun plan(
        trace: List<Triple<Long, Double, Double>>,
        limits: RotorMath.Limits,
        degParS: Double,
        toleranceDeg: Double,
        zones: List<Zone> = emptyList(),
        depuis: RotorPos? = null,
        imposeDepart: Boolean = false,
        images: List<LongRange> = emptyList(),
        flipPermis: Boolean = limits.elMaxDeg >= 180.0 - 1e-9
    ): Plan? {
        val n = trace.size
        if (n < 2) return null
        val vitesse = max(0.5, degParS)
        val poids = DoubleArray(n) { i ->
            val (t, az, el) = trace[i]
            max(0.0, sin(Math.toRadians(el))) *
                (if (zones.any { it.contient(az, el) }) POIDS_ZONE else 1.0) *
                (if (images.any { t in it }) 10.0 else 1.0)
        }
        val total = poids.sum()
        val series = branches(trace, limits, flipPermis)
        if (series.isEmpty()) return null

        var best: Plan? = null
        fun garde(p: Plan) {
            val b = best
            if (b == null || meilleur(p, b)) best = p
        }
        // One branch the whole pass, then one change at each sample. Upright
        // and the fewest turns first: on a perfect tie the plainest plan stays.
        for (s in series) garde(joue(trace, s, null, -1, limits, vitesse, toleranceDeg, poids, total, depuis, imposeDepart))
        for (s1 in series) for (s2 in series) {
            if (s1 === s2) continue
            for (k in 1 until n - 1)
                garde(joue(trace, s1, s2, k, limits, vitesse, toleranceDeg, poids, total, depuis, imposeDepart))
        }
        return best
    }

    /** Better: more coverage; at equal coverage less travel; then fewer changes. */
    fun meilleur(p: Plan, b: Plan): Boolean {
        if (p.couverture > b.couverture + EGALITE_COUVERTURE) return true
        if (p.couverture < b.couverture - EGALITE_COUVERTURE) return false
        val cp = p.courseDeg + (if (p.retourne) PRIX_RETOURNEMENT else 0.0) + p.changements * PRIX_RETOURNEMENT
        val cb = b.courseDeg + (if (b.retourne) PRIX_RETOURNEMENT else 0.0) + b.changements * PRIX_RETOURNEMENT
        if (cp < cb - EGALITE_COURSE) return true
        if (cp > cb + EGALITE_COURSE) return false
        return p.changements < b.changements
    }

    /** The ways of writing the pass that reach at least one of its points. */
    private fun branches(trace: List<Triple<Long, Double, Double>>, limits: RotorMath.Limits, flip: Boolean): List<Serie> {
        val out = ArrayList<Serie>()
        for (retourne in if (flip) listOf(false, true) else listOf(false)) {
            val pts = trace.map { (_, az, el) -> Pair(if (retourne) az + 180.0 else az, el) }
            val base = RotorMath.unroll(pts, limits.azStopDeg + limits.azMaxDeg / 2)
            val els = DoubleArray(trace.size) { val e = trace[it].third; if (retourne) 180.0 - e else e }
            for (d in listOf(0.0, -360.0, 360.0, -720.0, 720.0)) {
                val want = DoubleArray(base.size) { base[it] + d }
                if (want.none { it >= limits.azMinReach - 1e-9 && it <= limits.azMaxReach + 1e-9 }) continue
                out += Serie(Branche(retourne, d), want, els)
            }
        }
        return out
    }

    /** Where a branch puts the mast for sample [i]: clamped azimuth, and the elevation nearest the satellite from there. */
    private fun cible(s: Serie, i: Int, azSat: Double, elSat: Double, limits: RotorMath.Limits): Pair<Double, Double> {
        val w = s.want[i]
        val az = RotorMath.clampAz(w, limits)
        val el = if (abs(az - w) < 1e-9) s.el[i].coerceIn(0.0, limits.elMaxDeg)
            else RotorChemin.meilleureElevation(azSat, elSat, az, limits.elMaxDeg)
        return az to el
    }

    private fun joue(
        trace: List<Triple<Long, Double, Double>>, s1: Serie, s2: Serie?, k: Int,
        limits: RotorMath.Limits, vitesse: Double, tol: Double, poids: DoubleArray, total: Double,
        depuis: RotorPos?, imposeDepart: Boolean
    ): Plan {
        val n = trace.size
        val pts = ArrayList<RotorChemin.Point>(n)
        var course = 0.0
        var couvert = 0.0
        var nCouvert = 0
        var az = 0.0; var el = 0.0
        var debutTour: Long? = null; var finTour: Long? = null
        for (i in 0 until n) {
            val (t, azS, elS) = trace[i]
            val s = if (s2 != null && i >= k) s2 else s1
            val (ca, ce) = cible(s, i, azS, elS, limits)
            if (i == 0) {
                if (depuis != null && imposeDepart) { az = depuis.azDeg; el = depuis.elDeg }
                else {
                    if (depuis != null) course += abs(ca - depuis.azDeg) + abs(ce - depuis.elDeg)
                    az = ca; el = ce
                }
                if (depuis != null && imposeDepart) {
                    val b = vitesse * 5.0
                    val na = pas(az, ca, b); val ne = pas(el, ce, b)
                    course += abs(na - az) + abs(ne - el); az = na; el = ne
                }
            } else {
                val dt = (t - trace[i - 1].first) / 1000.0
                val b = vitesse * max(0.0, dt)
                val na = pas(az, ca, b); val ne = pas(el, ce, b)
                course += abs(na - az) + abs(ne - el)
                az = na; el = ne
            }
            val (va, ve) = versCiel(az, el)
            val err = RotorChemin.separation(azS, elS, va, ve)
            if (err <= tol + 1e-9) { couvert += poids[i]; nCouvert++ }
            val loin = abs(ca - az) > 2 * vitesse + 1.0 || abs(ce - el) > 2 * vitesse + 1.0
            val tour = s2 != null && i >= k && loin
            if (tour) { if (debutTour == null) debutTour = trace[k].first; finTour = t }
            pts += RotorChemin.Point(t, az, el, err, tour)
        }
        val cov = if (total > 1e-12) couvert / total else nCouvert.toDouble() / n
        val ev = if (s2 != null) {
            val d = trace[k].first
            Evenement(d, max(d + 1_000L, finTour ?: d), s1.b.retourne != s2.b.retourne)
        } else null
        return Plan(pts, s1.b, s2?.b, if (s2 != null) trace[k].first else null, cov, course, ev,
            LongArray(n) { trace[it].first }, s1.want, s2?.want)
    }

    private fun pas(de: Double, vers: Double, budget: Double): Double {
        val d = vers - de
        return if (abs(d) <= budget) vers else de + budget * Math.signum(d)
    }

    /**
     * The aim of this second: the satellite where it is now, written on the
     * plan's branch for this time — the side and the flip come from the
     * clock, never from the mast's position, so nothing flips back and forth.
     */
    fun vise(plan: Plan, tMs: Long, azSat: Double, elSat: Double, limits: RotorMath.Limits): RotorMath.Aim {
        val apres = plan.basculeMs != null && tMs >= plan.basculeMs && plan.apres != null && plan.refApres != null
        val b = if (apres) plan.apres!! else plan.avant
        val ref = interpole(plan.tMs, if (apres) plan.refApres!! else plan.refAvant, tMs)
        val want = RotorMath.unwrapFree(if (b.retourne) azSat + 180.0 else azSat, ref)
        val az = RotorMath.clampAz(want, limits)
        val elW = if (b.retourne) 180.0 - elSat else elSat
        val el = if (abs(az - want) < 1e-9) elW.coerceIn(0.0, limits.elMaxDeg)
            else RotorChemin.meilleureElevation(azSat, elSat, az, limits.elMaxDeg)
        val (va, ve) = versCiel(az, el)
        return RotorMath.Aim(az, el, el > 90.0, RotorChemin.separation(azSat, max(0.0, elSat), va, ve))
    }

    private fun interpole(t: LongArray, v: DoubleArray, x: Long): Double {
        if (x <= t[0]) return v[0]
        if (x >= t[t.size - 1]) return v[v.size - 1]
        var i = 1
        while (i < t.size && t[i] < x) i++
        val f = (x - t[i - 1]).toDouble() / max(1L, t[i] - t[i - 1])
        return v[i - 1] + (v[i] - v[i - 1]) * f
    }

    /**
     * The mast as it went, read back from a pass journal (unwrapped into the
     * range: a south-stop mast never crosses south): its azimuth travel, and
     * its coverage on the same scale as a plan's.
     */
    data class Bilan(val courseDeg: Double, val couverture: Double)

    fun bilan(sat: List<Triple<Long, Double, Double>>, mat: List<RotorPos>, limits: RotorMath.Limits,
              toleranceDeg: Double, zones: List<Zone> = emptyList()): Bilan {
        var course = 0.0; var couvert = 0.0; var total = 0.0
        var prec: RotorPos? = null
        for (i in sat.indices) {
            val m = mat[i]
            // A reading is a sky azimuth: the turn of the ring is the one nearest the last
            // (at the stop itself "180" may be either end).
            val p = prec
            val base = limits.azStopDeg + RotorMath.norm360(m.azDeg - limits.azStopDeg)
            val a = if (p == null) base
                else listOf(base - 360.0, base, base + 360.0)
                    .filter { it >= limits.azMinReach - 10.0 && it <= limits.azMaxReach + 10.0 }
                    .minByOrNull { abs(it - p.azDeg) } ?: base
            if (p != null) course += abs(a - p.azDeg)
            prec = RotorPos(a, m.elDeg)
            val (_, az, el) = sat[i]
            val w = max(0.0, sin(Math.toRadians(el))) * (if (zones.any { it.contient(az, el) }) POIDS_ZONE else 1.0)
            total += w
            val (va, ve) = versCiel(m.azDeg, m.elDeg)
            if (RotorChemin.separation(az, el, va, ve) <= toleranceDeg + 1e-9) couvert += w
        }
        return Bilan(course, if (total > 1e-12) couvert / total else 0.0)
    }

    /** Degrees of azimuth travel only, for the operator (what turns at the foot of the mast). */
    fun courseAz(points: List<RotorChemin.Point>): Double =
        points.zipWithNext { a, b -> abs(b.azDeg - a.azDeg) }.sum()

}
