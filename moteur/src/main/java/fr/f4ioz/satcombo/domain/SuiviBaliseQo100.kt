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
    val toleranceHz: Long = 30L,
    /** Further than this in one go: another line caught, not the beacon. */
    val sautMaxHz: Long = 5_000L,
) {
    /** A measure: when, the calibration then, how far off the beacon was, how clear. */
    class Point(val t: Long, val calageHz: Long, val ecartHz: Double, val rapportDb: Float) {
        /** The calibration that would put the beacon right: the LNB's error. */
        val erreurHz: Double get() = calageHz + ecartHz
    }

    private val points = ArrayDeque<Point>()
    /** A day, a point a minute (the median of its measures): the night's drift. */
    private val minutes = ArrayDeque<Pair<Long, Double>>()
    private var minuteEnCours = -1L
    private val dansLaMinute = ArrayList<Double>()
    /** The same day, the beacon's level a minute (median dB above the noise). */
    private val minutesNiveau = ArrayDeque<Pair<Long, Double>>()
    private val niveauxDansLaMinute = ArrayList<Double>()
    @Volatile var dernier: Point? = null
        private set
    /** Measures thrown away: too far from the others (another line, not the beacon). */
    @Volatile var ecartees = 0
        private set

    /** The last hour of measures, oldest first. */
    fun historique(): List<Point> = synchronized(points) { points.toList() }

    /** The last day, a point a minute: (time, the LNB's error). */
    fun journee(): List<Pair<Long, Double>> = synchronized(points) { minutes.toList() }

    /** The last day, a point a minute: (time, the beacon's level above the noise, dB). */
    fun journeeNiveau(): List<Pair<Long, Double>> = synchronized(points) { minutesNiveau.toList() }

    private fun mediane(l: List<Double>): Double = l.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /**
     * The LNB's error as the last [n] measures (two minutes) give it: their
     * mean — the lines too far are already out, and a median would jump from
     * one of the PSK beacon's lobes to the other. Null before [minimum].
     */
    fun erreurRecente(n: Int = 24, minimum: Int = 6): Double? = historique().takeLast(n).takeIf { it.size >= minimum }?.map { it.erreurHz }?.average()

    /**
     * A measure at [t] with the calibration [calageHz]; null [m] or a weak
     * one: the beacon not heard. One too far from the recent ones is
     * another line, thrown away. Returns the calibration to write when
     * [corrige] and the recent measures agree it is worth it, else null.
     */
    fun mesure(t: Long, m: MesureBalise.Mesure?, calageHz: Long, corrige: Boolean): Long? {
        if (m == null || m.rapportDb < seuilDb) return null
        val p = Point(t, calageHz, m.ecartHz, m.rapportDb)
        val ref = historique().takeLast(12).takeIf { it.size >= 3 }?.map { it.erreurHz }?.let { mediane(it) }
        if (ref != null && abs(p.erreurHz - ref) > ECARTEE_HZ) { ecartees++; return null }
        dernier = p
        synchronized(points) {
            points.addLast(p)
            while (points.isNotEmpty() && points.first().t < t - 3_600_000L) points.removeFirst()
            val minute = t / 60_000L
            if (minute != minuteEnCours && dansLaMinute.isNotEmpty()) {
                minutes.addLast(minuteEnCours * 60_000L to mediane(dansLaMinute)); dansLaMinute.clear()
                while (minutes.isNotEmpty() && minutes.first().first < t - 86_400_000L) minutes.removeFirst()
                minutesNiveau.addLast(minuteEnCours * 60_000L to mediane(niveauxDansLaMinute)); niveauxDansLaMinute.clear()
                while (minutesNiveau.isNotEmpty() && minutesNiveau.first().first < t - 86_400_000L) minutesNiveau.removeFirst()
            }
            minuteEnCours = minute; dansLaMinute += p.erreurHz; niveauxDansLaMinute += p.rapportDb.toDouble()
        }
        if (!corrige) return null
        // The recent measures' median, never one alone: each one wavers, and the PSK beacon's two
        // lobes pull it either way. Nothing written before half a minute of them.
        val cible = erreurRecente() ?: return null
        val delta = cible - calageHz
        if (abs(delta) < toleranceHz || abs(delta) > sautMaxHz) return null
        return calageHz + (delta * 0.8).roundToLong()
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
        /** Further than this from the recent measures: not the beacon. */
        const val ECARTEE_HZ = 1_500.0
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
