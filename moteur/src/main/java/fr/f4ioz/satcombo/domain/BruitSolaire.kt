/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.rotor.RotorMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * The Sun as a noise source: how much the noise rises when the dish looks
 * at it rather than at cold sky (the Y factor), what that rise should be
 * for a dish of that size on that band with today's solar flux, and a small
 * az/el raster around the computed Sun to find where the dish really looks.
 *
 * The rise says the whole receive chain is healthy (dish, feed, LNB, cable);
 * the raster's peak, off the computed Sun, is the rotor's pointing error.
 * Receive only: nothing here transmits; the raster is a list of directions,
 * moving a rotor is the caller's business.
 */
object BruitSolaire {

    /** Boltzmann's constant, J/K. */
    private const val K = 1.380649e-23
    /** One solar flux unit, W/m²/Hz. */
    const val SFU = 1e-22
    /** The Sun's apparent diameter, degrees (the radio Sun is a little wider than the optical one at 10 GHz). */
    const val DISQUE_DEG = 0.53

    /**
     * The quiet Sun's flux at [fHz] from the 10.7 cm (2.8 GHz) index [sfu107].
     * A rough fit of the observatories' usual spectrum: it rises about as
     * f^1.05 above 2.8 GHz (some 4 times the index at 10 GHz) and falls as
     * f^0.4 below. Good to a few tens of percent: a dB on the rise at most.
     */
    fun fluxSfu(fHz: Double, sfu107: Double): Double {
        val r = (fHz / 2.8e9).coerceIn(0.03, 15.0)
        return sfu107 * if (r >= 1.0) r.pow(1.05) else r.pow(0.4)
    }

    /** The −3 dB beam width of a [diametreM] dish at [fHz], degrees (70 λ / D). */
    fun lobeDeg(diametreM: Double, fHz: Double): Double = 70.0 * (299_792_458.0 / fHz) / diametreM

    /** What to expect: the flux there, the beam, the antenna's temperature rise, the noise rise. */
    class Attendu(val fluxSfu: Double, val lobeDeg: Double, val deltaTK: Double, val yDb: Double)

    /**
     * The noise rise a [diametreM] dish (aperture [rendement]) should see at
     * [fHz] on the Sun, solar index [sfu107], against a system at [tsysK] on
     * cold sky. One polarisation receives half the Sun's (unpolarised) flux:
     * ΔT = S·Ae / 2k; the disc, not small next to a narrow beam, is
     * weighed down by 1 + 0.38 (θsun/θbeam)².
     */
    fun attendu(diametreM: Double, fHz: Double, sfu107: Double, rendement: Double = 0.6, tsysK: Double = 100.0): Attendu {
        val flux = fluxSfu(fHz, sfu107)
        val ae = rendement * PI * diametreM * diametreM / 4.0
        val lobe = lobeDeg(diametreM, fHz)
        val x = DISQUE_DEG / lobe
        val dt = flux * SFU * ae / (2 * K) / (1 + 0.38 * x * x)
        return Attendu(flux, lobe, dt, 10 * log10(1 + dt / tsysK))
    }

    /** The system's temperature that a measured rise [yDb] implies, given the expected [deltaTK] (null below 0.1 dB). */
    fun tsysDepuisY(yDb: Double, deltaTK: Double): Double? {
        if (yDb < 0.1) return null
        return deltaTK / (10.0.pow(yDb / 10) - 1)
    }

    /** Sun minus cold sky, dB. */
    fun facteurY(soleilDb: Double, froidDb: Double): Double = soleilDb - froidDb

    /**
     * The noise in a wide band of a spectrum [db] (one value per bin, dB),
     * bins [de] until [a]: the mean power of the bins that stand less than
     * [margeDb] above the band's median — the signals (and a receiver's
     * centre spike) left out. Null with too few bins.
     */
    fun bruitLargeBande(db: FloatArray, de: Int = 0, a: Int = db.size, margeDb: Float = 3f): Double? {
        val d = de.coerceIn(0, db.size); val f = a.coerceIn(d, db.size)
        if (f - d < 8) return null
        val l = (d until f).map { db[it] }.filter { it.isFinite() }
        if (l.size < 8) return null
        val med = l.sorted()[l.size / 2]
        val calmes = l.filter { it <= med + margeDb }
        return 10 * log10(calmes.sumOf { 10.0.pow(it / 10.0) } / calmes.size)
    }

    /** Several measures (dB) as one: the mean power. */
    fun moyennePuissance(l: List<Double>): Double? =
        l.filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.let { m -> 10 * log10(m.sumOf { 10.0.pow(it / 10.0) } / m.size) }

    // ------------------------------------------------------------------ the raster around the Sun

    /** What the rotor reaches: its azimuth range from its stop, its elevations. */
    class Bornes(val azMaxDeg: Double = 450.0, val azStopDeg: Double = 0.0, val elMinDeg: Double = 0.0, val elMaxDeg: Double = 90.0)

    /** A cell of the raster: its row and column, its offset across ([dXDeg], on the sky) and up ([dElDeg]). */
    class Case(val i: Int, val j: Int, val dXDeg: Double, val dElDeg: Double)

    /** At most 11 × 11 cells: ten minutes or so of rotor. */
    const val COTE_MAX = 11

    /**
     * The raster's cells around the target, [demiDeg] each way in steps of
     * [pasDeg], row by row from the lowest, each row the other way from the
     * last (the rotor never runs back across the whole width).
     */
    fun plan(demiDeg: Double, pasDeg: Double): List<Case> {
        val pas = pasDeg.coerceIn(0.05, 5.0)
        val n = (Math.round(demiDeg.coerceIn(0.0, 10.0) / pas).toInt()).coerceAtMost(COTE_MAX / 2)
        val out = ArrayList<Case>()
        for (i in -n..n) {
            val cols = (-n..n).let { if ((i + n) % 2 == 0) it.toList() else it.reversed() }
            for (j in cols) out += Case(i, j, j * pas, i * pas)
        }
        return out
    }

    /** Where a cell points, the target at [az]/[el]: an offset across the sky widens in azimuth as 1/cos(el). */
    fun vise(az: Double, el: Double, c: Case): Pair<Double, Double> {
        val ce = cos(Math.toRadians(el + c.dElDeg)).coerceAtLeast(0.05)
        val a = ((az + c.dXDeg / ce) % 360.0 + 360.0) % 360.0
        return a to el + c.dElDeg
    }

    /** Whether the rotor reaches [az]/[el] within [b], end stops included. */
    fun atteignable(az: Double, el: Double, b: Bornes): Boolean {
        if (el < b.elMinDeg || el > b.elMaxDeg || el < 0.0) return false
        return RotorMath.manual(az, el, RotorMath.Limits(azMaxDeg = b.azMaxDeg, elMaxDeg = b.elMaxDeg, deadbandDeg = 0.0, azStopDeg = b.azStopDeg)) != null
    }

    /** The raster around [az]/[el] with only the cells the rotor reaches. */
    fun planDansLesBornes(az: Double, el: Double, demiDeg: Double, pasDeg: Double, b: Bornes): List<Case> =
        plan(demiDeg, pasDeg).filter { c -> vise(az, el, c).let { (a, e) -> atteignable(a, e, b) } }

    /** The raster's peak: where (across, up), how strong, and its rise above the weakest cell. */
    class Pic(val dXDeg: Double, val dElDeg: Double, val db: Double, val monteeDb: Double)

    /**
     * The peak of a raster's measures: the power-weighted centre of the
     * cells within 3 dB of the strongest (finer than the step), what stands
     * above the weakest cell giving the weight. Null with fewer than 3 cells.
     */
    fun pic(mesures: List<Pair<Case, Double>>): Pic? {
        val l = mesures.filter { it.second.isFinite() }
        if (l.size < 3) return null
        val max = l.maxOf { it.second }; val min = l.minOf { it.second }
        val fond = 10.0.pow(min / 10)
        var sx = 0.0; var se = 0.0; var sw = 0.0
        for ((c, v) in l) if (v >= max - 3.0) {
            val w = 10.0.pow(v / 10) - fond
            sx += w * c.dXDeg; se += w * c.dElDeg; sw += w
        }
        if (sw <= 0.0) { val b = l.maxBy { it.second }.first; return Pic(b.dXDeg, b.dElDeg, max, max - min) }
        return Pic(sx / sw, se / sw, max, max - min)
    }

    /** An offset across the sky [dXDeg] at elevation [el] as an azimuth offset. */
    fun enAzimut(dXDeg: Double, el: Double): Double = dXDeg / cos(Math.toRadians(el)).coerceAtLeast(0.05)

    /**
     * Where the Sun is seen from here: its computed az/el, the elevation
     * raised by the air's refraction (it matters below 10°, where a raster
     * low on the horizon would otherwise miss by a few tenths).
     */
    fun soleil(latDeg: Double, lonDeg: Double, ms: Long): Pair<Double, Double> {
        val p = SunCalc.azElDeg(latDeg, lonDeg, java.util.Date(ms))
        val h = p[1]
        val r = if (h > -1.0) 1.02 / tan(Math.toRadians(h + 10.3 / (h + 5.11))) / 60.0 else 0.0
        return p[0] to h + r
    }

    /** Two azimuths' difference, ±180°. */
    fun ecartAz(a: Double, b: Double): Double { var d = (a - b) % 360.0; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

    /** Whether a rise of [yDb] is close to the [attenduDb] (within [toleranceDb]). */
    fun conforme(yDb: Double, attenduDb: Double, toleranceDb: Double = 2.0): Boolean = abs(yDb - attenduDb) <= toleranceDb
}
