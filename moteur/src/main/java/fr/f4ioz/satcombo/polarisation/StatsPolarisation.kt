/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.polarisation

import org.json.JSONObject
import kotlin.math.abs

/**
 * What the H/V probes taught, satellite by satellite and elevation band by
 * elevation band: each probe kept as one line (JSON) of a file in the data
 * folder; from them, the best polarisation to start a pass with, and how
 * sure it is.
 */
object StatsPolarisation {

    /** One probe: where the satellite was, what each polarisation gave, which one won. */
    data class Mesure(
        val tMs: Long,
        val catnum: Int,
        val satellite: String,
        val mode: String,
        val bande: Bande,
        val elDeg: Double,
        val azDeg: Double,
        /** « montee », « culmination », « descente ». */
        val phase: String,
        val hDb: Double,
        val vDb: Double,
        val gagnante: Pol,
        /** « smetre », « sdr », « simulee ». */
        val source: String,
    ) {
        val ecartDb: Double get() = hDb - vDb

        fun json(): JSONObject = JSONObject().put("t", tMs).put("cat", catnum).put("sat", satellite).put("mode", mode).put("bande", bande.name)
            .put("el", arrondi(elDeg)).put("az", arrondi(azDeg)).put("phase", phase).put("h", arrondi(hDb)).put("v", arrondi(vDb))
            .put("gagnante", gagnante.name).put("ecart", arrondi(ecartDb)).put("source", source)

        companion object {
            fun de(o: JSONObject): Mesure? = runCatching {
                Mesure(o.getLong("t"), o.getInt("cat"), o.optString("sat"), o.optString("mode"), Bande.de(o.optString("bande")) ?: Bande.U,
                    o.getDouble("el"), o.optDouble("az", 0.0), o.optString("phase"), o.getDouble("h"), o.getDouble("v"),
                    Pol.de(o.getString("gagnante")) ?: return null, o.optString("source"))
            }.getOrNull()
        }
    }

    private fun arrondi(x: Double) = Math.round(x * 10.0) / 10.0

    /** The elevation bands' lower bounds (degrees): 0–10, 10–20, 20–35, 35–60, 60–90. */
    val TRANCHES = listOf(0, 10, 20, 35, 60)

    fun tranche(elDeg: Double): Int = TRANCHES.indexOfLast { elDeg >= it }.coerceAtLeast(0)

    fun nomTranche(i: Int): String = "${TRANCHES[i]}–${TRANCHES.getOrNull(i + 1) ?: 90}°"

    /** The pass's phase at [t] between [aosMs] and [losMs]. */
    fun phase(t: Long, aosMs: Long, losMs: Long): String {
        if (losMs <= aosMs) return "culmination"
        val f = (t - aosMs).toDouble() / (losMs - aosMs)
        return when { f < 0.35 -> "montee"; f > 0.65 -> "descente"; else -> "culmination" }
    }

    /** Fewer probes than this: no prediction. */
    const val MIN_MESURES = 3
    /** Under this share of wins, the two are as good as each other: no prediction. */
    const val MIN_PART = 0.6
    /** Under this gap the two are counted as even (half a win each). */
    const val EGAL_DB = 1.0

    /** H against V over some probes: [n] probes, [h] and [v] wins (even ones count half), the mean gap H − V. */
    data class Bilan(val n: Int, val h: Double, val v: Double, val ecartMoyenDb: Double) {
        val meilleure: Pol get() = if (h >= v) Pol.H else Pol.V
        val part: Double get() = if (n == 0) 0.0 else maxOf(h, v) / n
        /** 0 to 1: the share of wins, weighed by how many probes there are (full from 5). */
        val confiance: Double get() = part * minOf(1.0, n / 5.0)
        val predit: Boolean get() = n >= MIN_MESURES && part >= MIN_PART
    }

    fun bilan(l: List<Mesure>): Bilan {
        var h = 0.0; var v = 0.0
        l.forEach { m -> if (abs(m.ecartDb) < EGAL_DB) { h += 0.5; v += 0.5 } else if (m.ecartDb > 0) h += 1.0 else v += 1.0 }
        return Bilan(l.size, h, v, if (l.isEmpty()) 0.0 else l.map { it.ecartDb }.average())
    }

    data class Prediction(val pol: Pol, val bilan: Bilan, val tranche: Int?) {
        fun texte(): String = "${pol.court} (${fmt(maxOf(bilan.h, bilan.v))}/${bilan.n}, confiance ${(bilan.confiance * 100).toInt()} %" +
            (tranche?.let { ", " + nomTranche(it) } ?: ", toutes élévations") + ")"
    }

    private fun fmt(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)

    /**
     * The best polarisation for [catnum] on [bande] at [elDeg]: from the
     * probes in that elevation band, else (too few there) from all of the
     * satellite's; null when nothing clear came out.
     */
    fun prediction(mesures: List<Mesure>, catnum: Int, bande: Bande, elDeg: Double): Prediction? {
        val sat = mesures.filter { it.catnum == catnum && it.bande == bande }
        val i = tranche(elDeg)
        val ici = bilan(sat.filter { tranche(it.elDeg) == i })
        if (ici.predit) return Prediction(ici.meilleure, ici, i)
        if (ici.n >= MIN_MESURES) return null
        val tout = bilan(sat)
        return if (tout.predit) Prediction(tout.meilleure, tout, null) else null
    }

    /** One line of the table: a satellite in an elevation band. */
    data class Ligne(val catnum: Int, val satellite: String, val bande: Bande, val tranche: Int, val bilan: Bilan)

    fun tableau(mesures: List<Mesure>): List<Ligne> =
        mesures.groupBy { Triple(it.catnum, it.bande, tranche(it.elDeg)) }.map { (k, l) -> Ligne(k.first, l.last().satellite, k.second, k.third, bilan(l)) }
            .sortedWith(compareBy({ it.satellite }, { it.bande }, { it.tranche }))

    fun ligneFichier(m: Mesure): String = m.json().toString()

    fun litFichier(lignes: Sequence<String>): List<Mesure> =
        lignes.mapNotNull { l -> l.trim().takeIf { it.startsWith("{") }?.let { runCatching { Mesure.de(JSONObject(it)) }.getOrNull() } }.toList()
}
