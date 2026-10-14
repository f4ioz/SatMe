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
import kotlin.math.roundToLong

/**
 * QO-100's beacon followed over time: what the LNB's oscillator is off by,
 * how fast it drifts, and — when chosen — the calibration kept on it by
 * itself, a little at a time (an ordinary LNB drifts tens of hertz a minute
 * as it warms). A measure too weak or too far is never written in.
 */
class SuiviBaliseQo100(
    /** Below this, the beacon is not trusted (dB above the floor). */
    val seuilDb: Float = 12f,
    /** Closer than this, nothing to correct. */
    val toleranceHz: Long = 12L,
    /** Further than this in one go: another line caught, not the beacon. */
    val sautMaxHz: Long = 5_000L,
) {
    /** A measure: when, the calibration then, how far off the beacon was, how clear. */
    class Point(val t: Long, val calageHz: Long, val ecartHz: Double, val rapportDb: Float) {
        /** The calibration that would put the beacon right: the LNB's error. */
        val erreurHz: Double get() = calageHz + ecartHz
    }

    private val points = ArrayDeque<Point>()
    @Volatile var dernier: Point? = null
        private set

    /** The last hour of measures, oldest first. */
    fun historique(): List<Point> = synchronized(points) { points.toList() }

    /**
     * A measure at [t] with the calibration [calageHz]; null [m] or a weak
     * one: the beacon not heard. Returns the calibration to write when
     * [corrige] and worth it, else null.
     */
    fun mesure(t: Long, m: MesureBalise.Mesure?, calageHz: Long, corrige: Boolean): Long? {
        if (m == null || m.rapportDb < seuilDb) return null
        val p = Point(t, calageHz, m.ecartHz, m.rapportDb)
        dernier = p
        synchronized(points) {
            points.addLast(p)
            while (points.isNotEmpty() && points.first().t < t - 3_600_000L) points.removeFirst()
        }
        if (!corrige || abs(m.ecartHz) < toleranceHz || abs(m.ecartHz) > sautMaxHz) return null
        // Most of the way, not all: the measure itself wavers by a few hertz.
        return calageHz + (m.ecartHz * 0.8).roundToLong()
    }

    /** The beacon heard lately (within [depuisMs]). */
    fun verrouille(t: Long, depuisMs: Long = 30_000L): Boolean = dernier?.let { t - it.t <= depuisMs } == true

    /** The LNB's drift, Hz per minute, over the last [fenetreMs] (null with too few measures). */
    fun deriveHzParMin(t: Long, fenetreMs: Long = 10 * 60_000L): Double? {
        val l = historique().filter { it.t >= t - fenetreMs }
        if (l.size < 4 || l.last().t - l.first().t < 60_000L) return null
        val t0 = l.first().t
        val xs = l.map { (it.t - t0) / 60_000.0 }; val ys = l.map { it.erreurHz }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
    }

    companion object {
        /** The LNB's error as parts per million of its oscillator (9 750 MHz for a usual one). */
        fun ppm(erreurHz: Double, oscillateurHz: Double = 9_750_000_000.0): Double = erreurHz / oscillateurHz * 1e6
    }
}

/** The stations heard on QO-100's narrow transponder, from the dongle's view of it. */
object SignauxQo100 {

    class Signal(val hz: Long, val largeurHz: Long, val rapportDb: Float, val segment: String?)

    /**
     * Lines standing above the transponder's noise in [colonnes] (dB, the
     * band from [basHz] to [hautHz], [PanoramaQo100.HORS] where unseen):
     * each one's centre (weighted by power), width and height above the
     * noise; the three beacons left out.
     */
    fun trouve(colonnes: FloatArray, basHz: Long, hautHz: Long, seuilDb: Float = 8f): List<Signal> {
        val vus = colonnes.filter { it != PanoramaQo100.HORS }
        if (vus.size < colonnes.size / 4) return emptyList()
        val bruit = vus.sorted()[vus.size / 2]
        val hzCol = (hautHz - basHz).toDouble() / colonnes.size
        val out = ArrayList<Signal>()
        var i = 0
        while (i < colonnes.size) {
            if (colonnes[i] == PanoramaQo100.HORS || colonnes[i] < bruit + seuilDb) { i++; continue }
            var j = i; var poids = 0.0; var somme = 0.0; var haut = bruit
            while (j < colonnes.size && colonnes[j] != PanoramaQo100.HORS && colonnes[j] >= bruit + seuilDb - 3) {
                val w = Math.pow(10.0, (colonnes[j] - bruit) / 10.0)
                poids += w; somme += w * (j + 0.5); haut = maxOf(haut, colonnes[j]); j++
            }
            val hz = basHz + (somme / poids * hzCol).toLong()
            val balise = listOf(Qo100.BALISE_BASSE_HZ, Qo100.BALISE_MEDIANE_HZ, Qo100.BALISE_HAUTE_HZ).any { abs(it - hz) < 2_500 }
            if (!balise) out += Signal(hz, ((j - i) * hzCol).toLong(), haut - bruit, Qo100.segment(hz)?.cle)
            i = j
        }
        return out
    }
}
