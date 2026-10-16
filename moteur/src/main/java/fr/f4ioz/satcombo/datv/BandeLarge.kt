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

/**
 * One signal seen in QO-100's wide band spectrum: where, how wide (the
 * nearest standard symbol rate), how strong against the beacon.
 */
data class SignalLarge(
    val debutKhz: Long,
    val finKhz: Long,
    /** Its centre (sky frequency, kHz). */
    val centreKhz: Long,
    /** The width measured, kHz. */
    val largeurKhz: Long,
    /** The nearest standard symbol rate, kS/s. */
    val srKs: Int,
    /** Its level, the spectrum's units (noise ~11000). */
    val niveau: Int,
    /** Its level against the beacon's, dB (null without a beacon). */
    val parRapportBaliseDb: Double?,
    val balise: Boolean,
    /** ≥ 1 MS/s and as strong as the beacon (less than ~0.75 dB under): the band plan asks 1 dB under. */
    val auDessusBalise: Boolean,
)

/**
 * QO-100's wide band transponder as the ground station measures it (the
 * BATC's public spectrum): frames of little-endian unsigned 16-bit values
 * spanning [DEBUT_KHZ] to [DEBUT_KHZ] + [LARGEUR_KHZ], on a log scale (the
 * noise ~11000, the beacon ~38000; [UNITES_PAR_DB] units a dB).
 *
 * Signals are found on a 3-point average over [SEUIL]; each one's edges
 * where it falls to 75 % of its height above the noise; its width taken to
 * the nearest standard symbol rate.
 */
object BandeLarge {
    const val DEBUT_KHZ = 10_490_500L
    const val LARGEUR_KHZ = 9_000L
    const val BRUIT = 11_000
    const val SEUIL = 16_000
    /** The scale: 65536 units for 20 dB. */
    const val UNITES_PAR_DB = 3276.8
    /** How close to the beacon's level a wide signal may come (dB) before it is said over it. */
    const val MARGE_BALISE_DB = 0.75
    /** The standard symbol rates (kS/s). */
    val DEBITS = listOf(35, 66, 125, 250, 333, 500, 1000, 1500, 2000)
    /** The wide band plan's channels for 1 MS/s and more (kHz). */
    val CANAUX_LARGES_KHZ = listOf(10_493_250L, 10_494_750L, 10_496_250L)

    /** A frame's values (little-endian uint16). */
    fun valeurs(b: ByteArray, n: Int = b.size): IntArray =
        IntArray(n / 2) { ((b[2 * it].toInt() and 0xFF) or ((b[2 * it + 1].toInt() and 0xFF) shl 8)) }

    /** The sky frequency of point [i] of [n] (kHz, may be fractional). */
    fun khz(i: Double, n: Int): Double = DEBUT_KHZ + LARGEUR_KHZ * i / n

    /** The standard symbol rate nearest to a width (on a log scale: 0.4 MHz is nearer 500 than 333). */
    fun debit(largeurKhz: Double): Int =
        DEBITS.minByOrNull { Math.abs(Math.log(largeurKhz.coerceAtLeast(1.0) / it)) }!!

    /** The averages of the last frames: steadier signals. */
    fun moyenne(trames: List<IntArray>): IntArray {
        val n = trames.minOfOrNull { it.size } ?: return IntArray(0)
        return IntArray(n) { i -> (trames.sumOf { it[i].toLong() } / trames.size).toInt() }
    }

    /** The signals in [v], low to high. */
    fun detecte(v: IntArray): List<SignalLarge> {
        val n = v.size
        if (n < 8) return emptyList()
        val r = DoubleArray(n) { v[it].toDouble() }
        val s = DoubleArray(n) { i -> if (i == 0 || i == n - 1) r[i] else (r[i - 1] + r[i] + r[i + 1]) / 3.0 }
        // The frame's ends roll off to nothing: a few points left out.
        val bord = maxOf(2, n / 200)
        val trouves = ArrayList<Triple<Double, Double, Double>>()   // left, right (fractional points), peak
        var i = bord
        while (i < n - bord) {
            if (s[i] <= SEUIL) { i++; continue }
            var j = i
            while (j + 1 < n - bord && s[j + 1] > SEUIL) j++
            decoupe(s, r, i, j, trouves)
            i = j + 1
        }
        val brut = trouves.sortedBy { it.first }.mapNotNull { (g, d, pic) ->
            val largeur = (d - g) * LARGEUR_KHZ / n
            if (largeur < 15.0) return@mapNotNull null
            val centre = khz((g + d) / 2, n)
            // Its level: the top's average (a flat top's maximum is the noise's luck).
            val a = Math.ceil(g).toInt(); val b = Math.floor(d).toInt()
            val q = (b - a) / 4
            val niveau = if (b - a >= 8) (a + q..b - q).map { s[it] }.average() else pic
            Triple(Triple(g, d, centre), largeur, niveau)
        }
        val sr = brut.map { debit(it.second) }
        val iBalise = brut.indices.filter { Math.abs(brut[it].first.third - Datv.BALISE_KHZ) <= 150 && sr[it] >= 1000 }
            .maxByOrNull { brut[it].third }
        val niveauBalise = iBalise?.let { brut[it].third }
        return brut.mapIndexed { k, (pos, largeur, niveau) ->
            val db = niveauBalise?.let { (niveau - it) / UNITES_PAR_DB }
            SignalLarge(Math.round(khz(pos.first, n)), Math.round(khz(pos.second, n)), Math.round(pos.third), Math.round(largeur), sr[k],
                Math.round(niveau).toInt(), db?.let { Math.round(it * 10) / 10.0 }, k == iBalise,
                k != iBalise && db != null && sr[k] >= 1000 && db > -MARGE_BALISE_DB)
        }
    }

    /**
     * The signals in the run [a]..[b] over the threshold: its peak's, its
     * edges at 75 % of the peak's height; the rest, past the skirts (where
     * the level rises again), looked into the same way.
     */
    private fun decoupe(s: DoubleArray, r: DoubleArray, a: Int, b: Int, l: MutableList<Triple<Double, Double, Double>>) {
        if (b < a) return
        var p = a
        for (k in a..b) if (s[k] > s[p]) p = k
        if (s[p] <= SEUIL) return
        val pic = s[p]
        val bord = BRUIT + 0.75 * (pic - BRUIT)
        // The edges on the points themselves (the average would narrow a narrow signal), between two points.
        var g = p; while (g > a && r[g - 1] >= bord) g--
        var d = p; while (d < b && r[d + 1] >= bord) d++
        val gf = if (g > 0 && r[g - 1] < bord && r[g] > r[g - 1]) g - (r[g] - bord) / (r[g] - r[g - 1]) else g.toDouble()
        val df = if (d < r.size - 1 && r[d + 1] < bord && r[d] > r[d + 1]) d + (r[d] - bord) / (r[d] - r[d + 1]) else d.toDouble()
        l += Triple(maxOf(gf, g - 1.0), minOf(df, d + 1.0), pic)
        g = minOf(g, p); d = maxOf(d, p)
        // Down the skirts to their lowest, then what lies beyond.
        var gg = g - 1; while (gg > a && s[gg - 1] <= s[gg]) gg--
        var dd = d + 1; while (dd < b && s[dd + 1] <= s[dd]) dd++
        if (gg > a) decoupe(s, r, a, gg - 1, l)
        if (dd < b) decoupe(s, r, dd + 1, b, l)
    }

    /** The point of [n] nearest to a frequency. */
    fun point(khz: Long, n: Int): Int = Math.round((khz - DEBUT_KHZ).toDouble() * n / LARGEUR_KHZ).toInt()

    /** A signal for the page. */
    fun json(sg: SignalLarge): JSONObject = JSONObject().put("debut", sg.debutKhz).put("fin", sg.finKhz).put("centre", sg.centreKhz)
        .put("largeur", sg.largeurKhz).put("sr", sg.srKs).put("niveau", sg.niveau)
        .put("db", sg.parRapportBaliseDb ?: JSONObject.NULL).put("balise", sg.balise).put("auDessus", sg.auDessusBalise)

    fun json(l: List<SignalLarge>): JSONArray = JSONArray(l.map { json(it) })
}

/**
 * The names seen on the wide band: « frequency + symbol rate → name »
 * (the SDT's service, else the station card's or the picture's callsign),
 * kept [dureeMs] (24 h).
 */
class NomsSignaux(private val dureeMs: Long = 24 * 3600_000L) {
    data class Nom(val khz: Long, val srKs: Int, val nom: String, val ms: Long)

    private val noms = ArrayList<Nom>()

    @Synchronized fun tous(): List<Nom> = noms.toList()

    /** [nom] received on [khz] at [srKs]; true when that is new. */
    @Synchronized
    fun note(khz: Long, srKs: Int, nom: String, maintenant: Long): Boolean {
        val n = nom.trim().take(40)
        if (n.isEmpty()) return false
        val i = noms.indexOfFirst { it.srKs == srKs && Math.abs(it.khz - khz) <= tolerance(srKs) }
        val avant = noms.getOrNull(i)
        if (i >= 0) noms.removeAt(i)
        noms += Nom(khz, srKs, n, maintenant)
        oublieVieux(maintenant)
        return avant?.nom != n
    }

    /** The last name known for a signal there at that rate, or null. */
    @Synchronized
    fun nom(khz: Long, srKs: Int, maintenant: Long): String? =
        noms.filter { it.srKs == srKs && Math.abs(it.khz - khz) <= tolerance(srKs) && maintenant - it.ms < dureeMs }
            .maxByOrNull { it.ms }?.nom

    @Synchronized
    private fun oublieVieux(maintenant: Long) {
        noms.removeAll { maintenant - it.ms >= dureeMs }
        while (noms.size > 200) noms.removeAt(0)
    }

    /** As kept (JSON). */
    @Synchronized
    fun texte(): String = JSONArray(noms.map { JSONObject().put("k", it.khz).put("sr", it.srKs).put("n", it.nom).put("t", it.ms) }).toString()

    /** Taken back from [texte], what is still fresh. */
    @Synchronized
    fun reprends(t: String?, maintenant: Long) {
        noms.clear()
        val a = runCatching { JSONArray(t ?: return) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val n = Nom(o.optLong("k"), o.optInt("sr"), o.optString("n"), o.optLong("t"))
            if (n.nom.isNotBlank() && maintenant - n.ms < dureeMs) noms += n
        }
    }

    companion object {
        /** How far apart the same station may be measured (kHz): a third of its rate, 30 to 300 kHz. */
        fun tolerance(srKs: Int): Long = (srKs / 3L).coerceIn(30L, 300L)
    }
}

/**
 * « Veille »: the occupied channels visited in turn, low to high. The
 * receiver stays on one while it holds the signal; [attenteMs] after it was
 * tuned or last held, it moves on. Stopped when the operator tunes by hand.
 */
class VeilleDatv(var attenteMs: Long = 12_000L, var avecBalise: Boolean = false) {
    data class Cible(val khz: Long, val srKs: Int)

    var active = false; private set
    var cible: Cible? = null; private set
    var depuisMs = 0L; private set
    var tenuMs = 0L; private set
    var etat = ""; private set

    fun demarre() { active = true; cible = null; etat = "en veille" }

    fun arrete(pourquoi: String) { active = false; cible = null; etat = pourquoi }

    /**
     * Each second: the signals seen now, whether the receiver holds one.
     * Returns where to tune next, or null to stay.
     */
    fun tic(maintenant: Long, signaux: List<SignalLarge>, accroche: Boolean): Cible? {
        if (!active) return null
        val c = cible
        if (c != null && accroche) { tenuMs = maintenant; etat = "accroché : on reste"; return null }
        val candidats = signaux.filter { avecBalise || !it.balise }.sortedBy { it.centreKhz }
        if (c != null && maintenant - maxOf(depuisMs, tenuMs) < attenteMs) {
            etat = "recherche… " + ((attenteMs - (maintenant - maxOf(depuisMs, tenuMs)) + 999) / 1000) + " s"
            return null
        }
        if (candidats.isEmpty()) { etat = "aucun signal occupé"; return null }
        // The next one up from where we are (around to the lowest).
        val suivant = if (c == null) candidats.first()
            else candidats.firstOrNull { it.centreKhz > c.khz + NomsSignaux.tolerance(c.srKs) } ?: candidats.first()
        val n = Cible(suivant.centreKhz, suivant.srKs)
        depuisMs = maintenant; tenuMs = 0L
        // The only one, already there: no need to start again.
        if (c != null && c.srKs == n.srKs && Math.abs(c.khz - n.khz) <= NomsSignaux.tolerance(n.srKs)) { etat = "un seul signal, sans accroche"; return null }
        cible = n
        etat = "essai " + String.format(java.util.Locale.FRANCE, "%.3f", n.khz / 1000.0) + " MHz · " + n.srKs + " kS/s"
        return n
    }
}
