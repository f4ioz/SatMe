/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10

/**
 * The MiniTiouner's input level from its tuner's two AGC loops (LongMynd's
 * $26 and $27), on the scale the MiniTioune software and BATC's Portsdown
 * and Ryde show: about −70 where the tuner reaches its full gain.
 *
 * From the points G8GKQ measured on the QO-100 beacon (BATC wiki,
 * « MiniTiouner Power Level Indication », July 2021): with AGC1 at 0 the
 * tuner is at its full gain and AGC2 follows the level (log scale); above,
 * AGC1 does. In between, a straight line; beyond, the last slope.
 *
 * Not a power meter: neither linear nor absolute (the « dBm » are
 * indicative), and it measures the whole ~10 MHz the tuner lets in, noise
 * included — useless to peak a weak signal (the MER does that), useful to
 * see that the level is in the tuner's good range (−70 to −35), not too
 * low (LNB unpowered, a cable, too long a run) nor so high that the LNA's
 * gain is cut.
 */
object NiveauAgc {
    /** AGC1 at 0: (AGC2, input dB) — log10 of AGC2 interpolated. */
    private val PAR_AGC2 = listOf(164.0 to -40.0, 182.0 to -41.0, 500.0 to -50.0, 3200.0 to -67.0)
    /** AGC1 above 0: (AGC1, input dB). */
    private val PAR_AGC1 = listOf(1.0 to -40.0, 21_800.0 to -38.0, 31_000.0 to -30.0, 34_200.0 to -20.0,
        36_600.0 to -10.0, 37_600.0 to -5.0, 37_700.0 to -4.0)
    /** The figure shown (MiniTioune, Portsdown) is the measured input less this. */
    const val DECALAGE_DB = 30.0
    /** Below: the tuner at its full gain, short of signal (reduced MER). */
    const val BAS_DB = -70.0
    /** From here: the LNA's gain is cut (saturation near). */
    const val HAUT_DB = -35.0

    private fun interpole(l: List<Pair<Double, Double>>, x: Double): Double {
        val i = l.indexOfFirst { it.first >= x }
        val (a, b) = when (i) { -1 -> l[l.size - 2] to l[l.size - 1]; 0 -> l[0] to l[1]; else -> l[i - 1] to l[i] }
        return a.second + (x - a.first) * (b.second - a.second) / (b.first - a.first)
    }

    /** The level as shown (≈ dBm, indicative), or null when the AGCs say nothing yet. */
    fun indicationDb(agc1: Int, agc2: Int): Double? {
        if (agc1 <= 0 && agc2 <= 0) return null
        val entree = if (agc1 <= 0) {
            val l = PAR_AGC2.map { log10(it.first) to it.second }
            if (agc2 <= PAR_AGC2[0].first) PAR_AGC2[0].second else interpole(l, log10(agc2.toDouble()))
        } else if (agc1 >= PAR_AGC1.last().first) PAR_AGC1.last().second
        else interpole(PAR_AGC1, agc1.toDouble())
        return Math.round((entree - DECALAGE_DB).coerceIn(-110.0, 0.0) * 10) / 10.0
    }

    /** -1 too low, 0 good, 1 high (the LNA's gain cut). */
    fun zone(db: Double): Int = when { db < BAS_DB -> -1; db >= HAUT_DB -> 1; else -> 0 }
}

/**
 * The pointing aid: the last two minutes of MER, margin and level, the
 * trend (better or worse than 5 s ago), the best seen since the last
 * reset, and how long the lock has been lost. Fed a few times a second.
 */
class SuiviPointage(private val dureeMs: Long = 120_000L) {
    data class Point(val t: Long, val mer: Double?, val marge: Double?, val niveau: Double?, val verrouille: Boolean)

    /** The trend: [deltaDb] now against 5 s ago, [sens] -1 worse, 0 steady, 1 better. */
    data class Tendance(val deltaDb: Double, val sens: Int)

    private val points = ArrayDeque<Point>()
    @Volatile var maxMer: Double? = null; private set
    @Volatile var maxMarge: Double? = null; private set
    @Volatile var maxMs = 0L; private set
    /** When the lock was lost (it had been locked), null while locked or never locked. */
    @Volatile var decrocheMs: Long? = null; private set
    @Volatile var verrouMs: Long? = null; private set
    private var avant = false

    @Synchronized
    fun ajoute(p: Point) {
        points.addLast(p)
        while (points.isNotEmpty() && points.first().t < p.t - dureeMs) points.removeFirst()
        if (p.verrouille && !avant) { verrouMs = p.t; decrocheMs = null }
        if (!p.verrouille && avant) { decrocheMs = p.t; verrouMs = null }
        avant = p.verrouille
        val m = p.mer
        if (p.verrouille && m != null && (maxMer == null || m > maxMer!!)) { maxMer = m; maxMarge = p.marge; maxMs = p.t }
    }

    /** The best forgotten (a new reference: another station, a touch-up started over). */
    @Synchronized
    fun raz() { maxMer = null; maxMarge = null; maxMs = 0L }

    /** Everything forgotten (another station tuned). */
    @Synchronized
    fun oublie() { points.clear(); raz(); decrocheMs = null; verrouMs = null; avant = false }

    private fun moyenne(de: Long, a: Long): Double? =
        points.filter { it.t > de && it.t <= a && it.verrouille && it.mer != null }.map { it.mer!! }.takeIf { it.isNotEmpty() }?.average()

    /** The last 2 s against those 5 s before; null without both. */
    @Synchronized
    fun tendance(t: Long): Tendance? {
        val ici = moyenne(t - 2_000L, t) ?: return null
        val la = moyenne(t - 7_000L, t - 4_000L) ?: return null
        val d = Math.round((ici - la) * 100) / 100.0
        return Tendance(d, if (abs(d) < STABLE_DB) 0 else if (d > 0) 1 else -1)
    }

    @Synchronized
    fun points(): List<Point> = points.toList()

    fun json(t: Long): JSONObject {
        fun nul(x: Any?) = x ?: JSONObject.NULL
        val l = points()
        val tend = tendance(t)
        return JSONObject()
            .put("tendance", tend?.let { JSONObject().put("delta", it.deltaDb).put("sens", it.sens) } ?: JSONObject.NULL)
            .put("max", nul(maxMer)).put("maxMarge", nul(maxMarge)).put("maxIlYa", if (maxMs > 0) (t - maxMs) / 1000 else JSONObject.NULL)
            .put("decroche", decrocheMs?.let { (t - it) / 1000 } ?: JSONObject.NULL)
            .put("verrouDepuis", verrouMs?.let { (t - it) / 1000 } ?: JSONObject.NULL)
            .put("serie", JSONArray(l.map { p -> JSONArray().put((p.t - t) / 100 / 10.0).put(nul(p.mer)).put(nul(p.marge)) }))
    }

    companion object {
        /** Under this, steady: LongMynd gives the MER to 0.1 dB and it wavers by about that. */
        const val STABLE_DB = 0.15
    }
}

/**
 * The reception over hours (as the narrow band's « courbe du jour »): a
 * point every [pasMs] (the median of its samples while receiving: MER,
 * margin, level, the share of time locked), a day kept, and marks — a
 * station tuned, a stop, the LNB's oscillator calibrated, a touch-up the
 * operator noted. In memory: as long as SatMe runs.
 */
class HistoriqueDatv(private val pasMs: Long = 10_000L, private val dureeMs: Long = 86_400_000L) {
    data class Point(val t: Long, val mer: Double?, val marge: Double?, val niveau: Double?, val verrouPct: Int)
    data class Marque(val t: Long, val texte: String, val genre: String)

    private val points = ArrayDeque<Point>()
    private val marques = ArrayDeque<Marque>()
    private var case = -1L
    private val mers = ArrayList<Double>()
    private val marges = ArrayList<Double>()
    private val niveaux = ArrayList<Double>()
    private var n = 0
    private var verrous = 0

    private fun mediane(l: List<Double>): Double? = if (l.isEmpty()) null else l.sorted().let { s ->
        val m = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        Math.round(m * 100) / 100.0
    }

    private fun ferme() {
        if (case < 0 || n == 0) return
        points.addLast(Point(case * pasMs, mediane(mers), mediane(marges), mediane(niveaux), Math.round(100.0 * verrous / n).toInt()))
        mers.clear(); marges.clear(); niveaux.clear(); n = 0; verrous = 0
    }

    /** A sample at [t] while receiving (nothing is noted while stopped: a gap). */
    @Synchronized
    fun echantillon(t: Long, verrouille: Boolean, mer: Double?, marge: Double?, niveau: Double?) {
        val c = t / pasMs
        if (c != case) { ferme(); case = c; oublieVieux(t) }
        n++
        if (verrouille) { verrous++; mer?.let { mers += it }; marge?.let { marges += it } }
        niveau?.let { niveaux += it }
    }

    @Synchronized
    fun marque(t: Long, texte: String, genre: String) {
        marques.addLast(Marque(t, texte.take(80), genre))
        oublieVieux(t)
    }

    private fun oublieVieux(t: Long) {
        while (points.isNotEmpty() && points.first().t < t - dureeMs) points.removeFirst()
        while (marques.isNotEmpty() && (marques.first().t < t - dureeMs || marques.size > 500)) marques.removeFirst()
    }

    @Synchronized fun tous(): List<Point> = points.toList()
    @Synchronized fun marques(): List<Marque> = marques.toList()

    /**
     * The last [fenetreMs] for the page: at most [max] points (consecutive
     * ones merged: the median of their medians, the worst lock share), the
     * marks in it.
     */
    fun json(t: Long, fenetreMs: Long, max: Int = 600): JSONObject {
        val l = tous().filter { it.t >= t - fenetreMs }
        val k = maxOf(1, (l.size + max - 1) / max)
        fun nul(x: Any?) = x ?: JSONObject.NULL
        val a = JSONArray()
        l.chunked(k).forEach { g ->
            a.put(JSONArray().put(g.first().t).put(nul(mediane(g.mapNotNull { it.mer }))).put(nul(mediane(g.mapNotNull { it.marge })))
                .put(nul(mediane(g.mapNotNull { it.niveau }))).put(g.minOf { it.verrouPct }))
        }
        val m = JSONArray(marques().filter { it.t >= t - fenetreMs }.map { JSONObject().put("t", it.t).put("texte", it.texte).put("genre", it.genre) })
        return JSONObject().put("ok", true).put("t", t).put("fenetre", fenetreMs).put("pas", pasMs * k).put("points", a).put("marques", m)
    }
}

/**
 * The LNB's oscillator measured on QO-100's wide band beacon (WinterHill's
 * idea): locked on it, LongMynd says at what intermediate frequency it
 * found it, so the real oscillator is the beacon's frequency less that —
 * the LNB's error and the NIM's own crystal's together, the same for every
 * station. Followed slowly (a median of the last half minute; the drift
 * over the last hours from a point a minute).
 *
 * Only a lock that is surely the beacon is taken: DVB-S2, tuned to the
 * beacon, about 1500 kS/s found, held 5 s, within [ECART_MAX_KHZ] of
 * where it should be (the wide stations' channels are further).
 */
class CalageBalise(private val dureeMs: Long = 3 * 3_600_000L) {
    private val recentes = ArrayDeque<Pair<Long, Long>>()
    private val minutes = ArrayDeque<Pair<Long, Double>>()
    private var minute = -1L
    private val dansLaMinute = ArrayList<Long>()
    @Volatile var derniereMs = 0L; private set
    @Volatile var refusees = 0; private set

    /** Whether [e] (tuned with [r]) is the beacon held long enough to measure. */
    fun surBalise(e: EtatDatv, r: ReglageDatv, verrouDepuisMs: Long, t: Long): Boolean =
        e.actif && e.etat == 4 && e.fiTrouveeKhz > 0 && abs(r.cielKhz - Datv.BALISE_KHZ) <= 100 &&
            e.srTrouveKs in 1400..1600 && t - verrouDepuisMs >= 5_000L

    /** A measure (once a second at most): true when kept. */
    @Synchronized
    fun mesure(t: Long, e: EtatDatv, r: ReglageDatv, verrouDepuisMs: Long): Boolean {
        if (!surBalise(e, r, verrouDepuisMs, t)) return false
        val ol = Datv.BALISE_KHZ - e.fiTrouveeKhz
        // Further than this from the oscillator set: not the beacon (or the OL set is far off: « Caler sur le signal » first).
        if (abs(ol - r.olKhz) > ECART_MAX_KHZ) { refusees++; return false }
        recentes.addLast(t to ol)
        while (recentes.size > 30 || (recentes.isNotEmpty() && recentes.first().first < t - 120_000L)) recentes.removeFirst()
        val m = t / 60_000L
        if (m != minute && dansLaMinute.isNotEmpty()) {
            minutes.addLast(minute * 60_000L to dansLaMinute.sorted()[dansLaMinute.size / 2].toDouble()); dansLaMinute.clear()
            while (minutes.isNotEmpty() && minutes.first().first < t - dureeMs) minutes.removeFirst()
        }
        minute = m; dansLaMinute += ol
        derniereMs = t
        return true
    }

    /** The real oscillator (kHz), the median of the recent measures; null with fewer than [minimum]. */
    @Synchronized
    fun olReel(minimum: Int = 10): Long? = recentes.map { it.second }.takeIf { it.size >= minimum }?.sorted()?.let { it[it.size / 2] }

    /** The drift (kHz an hour) over the minutes measured, null under 10 minutes of them. */
    @Synchronized
    fun deriveKhzH(): Double? {
        val l = minutes.toList() + (if (dansLaMinute.isNotEmpty()) listOf(minute * 60_000L to dansLaMinute.sorted()[dansLaMinute.size / 2].toDouble()) else emptyList())
        if (l.size < 10 || l.last().first - l.first().first < 9 * 60_000L) return null
        val xs = l.map { (it.first - l.first().first) / 3_600_000.0 }; val ys = l.map { it.second }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return Math.round(xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den * 10) / 10.0
    }

    /**
     * The oscillator to set instead of [olKhz] when the measures say it is
     * off by [SEUIL_KHZ] or more; null otherwise (or too few of them).
     */
    fun correction(olKhz: Long): Long? = olReel(20)?.takeIf { abs(it - olKhz) >= SEUIL_KHZ }

    @Synchronized
    fun oublie() { recentes.clear(); minutes.clear(); dansLaMinute.clear(); minute = -1L }

    /** For the page: the beacon's offset with [olKhz] set (cielTrouve − beacon), the real oscillator, the drift. */
    fun json(olKhz: Long, t: Long): JSONObject {
        val o = olReel(5)
        return JSONObject().put("olReel", o ?: JSONObject.NULL).put("ecart", o?.let { olKhz - it } ?: JSONObject.NULL)
            .put("derive", deriveKhzH() ?: JSONObject.NULL).put("ilYa", if (derniereMs > 0) (t - derniereMs) / 1000 else JSONObject.NULL)
            .put("refusees", refusees)
    }

    companion object {
        /** A measure further than this from the oscillator set is refused (kHz). */
        const val ECART_MAX_KHZ = 600L
        /** Corrected from this far (kHz): LongMynd gives the carrier to the kHz, and a lock wavers by one or two. */
        const val SEUIL_KHZ = 3L
    }
}
