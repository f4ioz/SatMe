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
 * The TX gain that was right on QO-100, remembered per symbol rate: the
 * closed loop ([BoucleNiveau]) said our signal sat between [ecartMinDb] and
 * the beacon's margin below the beacon for [tenueMs] at that gain. Only a
 * memory: it is offered to the operator, never applied by itself.
 */
class CalibrationGain(
    /** Further below the beacon than this (dB), the gain is not « right », just low. */
    val ecartMinDb: Double = -6.0,
    val tenueMs: Long = 10_000L,
) {
    data class Point(val srKs: Int, val gainDb: Double, val ecartDb: Double, val ms: Long)

    private val points = HashMap<Int, Point>()
    private var depuis: Point? = null

    @Synchronized fun de(srKs: Int): Point? = points[srKs]

    @Synchronized fun tous(): List<Point> = points.values.sortedBy { it.srKs }

    /**
     * Each loop measure while transmitting to QO-100 at [srKs] with [gainDb]:
     * [ecartDb] our level against the beacon (null: not measured),
     * [tropFort] the loop's verdict. True when a calibration was just noted.
     */
    @Synchronized
    fun mesure(srKs: Int, gainDb: Double, ecartDb: Double?, tropFort: Boolean, maintenant: Long): Boolean {
        if (ecartDb == null || tropFort || ecartDb < ecartMinDb || gainDb.isNaN()) { depuis = null; return false }
        val d = depuis
        if (d == null || d.srKs != srKs || d.gainDb != gainDb) { depuis = Point(srKs, gainDb, ecartDb, maintenant); return false }
        if (maintenant - d.ms < tenueMs) return false
        val p = Point(srKs, gainDb, ecartDb, maintenant)
        val avant = points[srKs]
        points[srKs] = p
        depuis = p
        return avant?.gainDb != p.gainDb
    }

    @Synchronized fun oublie(srKs: Int) { points.remove(srKs) }

    /** As kept (JSON). */
    @Synchronized
    fun texte(): String = JSONArray(points.values.map { JSONObject().put("sr", it.srKs).put("g", it.gainDb).put("e", it.ecartDb).put("t", it.ms) }).toString()

    /** Taken back from [texte]. */
    @Synchronized
    fun reprends(t: String?) {
        points.clear()
        val a = runCatching { JSONArray(t ?: return) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val sr = o.optInt("sr")
            val g = o.optDouble("g")
            if (sr !in Datv.DEBITS || g.isNaN() || g > 0.0 || g < ReglesEmission.GAIN_MIN_DB) continue
            points[sr] = Point(sr, g, o.optDouble("e", Double.NaN), o.optLong("t"))
        }
    }
}

/**
 * A gain moved step by step while transmitting, instead of at once: down
 * ([Sens.BAS], quick) or up ([Sens.HAUT], slow, towards [cibleDb]). Up stops
 * at once when the loop says too strong. Never above [plafondDb]: the caps
 * of [ReglesEmission] and of the transmitter still apply to each step.
 */
class RampeGain(
    val pasHautDb: Double = 1.0,
    val intervalleHautMs: Long = 2_000L,
    val pasBasDb: Double = 3.0,
    val intervalleBasMs: Long = 1_000L,
) {
    enum class Sens(val cle: String) { HAUT("haut"), BAS("bas") }

    data class Etat(val sens: Sens? = null, val cibleDb: Double = 0.0, val message: String = "")

    @Volatile var etat = Etat(); private set
    private var dernierMs = Long.MIN_VALUE / 2

    val active: Boolean get() = etat.sens != null

    /** Starts a ramp to [cibleDb], capped at [plafondDb]. */
    @Synchronized
    fun demarre(sens: Sens, cibleDb: Double, plafondDb: Double) {
        val c = minOf(cibleDb, plafondDb, 0.0).coerceAtLeast(ReglesEmission.GAIN_MIN_DB)
        etat = Etat(sens, c, if (sens == Sens.HAUT) "montée vers %.1f dB".format(java.util.Locale.FRANCE, c) else "descente vers %.1f dB".format(java.util.Locale.FRANCE, c))
        dernierMs = Long.MIN_VALUE / 2
    }

    @Synchronized
    fun arrete(pourquoi: String = "rampe arrêtée") { if (etat.sens != null) etat = Etat(message = pourquoi) }

    /**
     * Each second while transmitting: the gain applied now, the loop's
     * verdict; returns the next gain to apply, or null to leave it.
     */
    @Synchronized
    fun tic(gainDb: Double, tropFort: Boolean, maintenant: Long): Double? {
        val e = etat
        val s = e.sens ?: return null
        if (gainDb.isNaN()) { arrete("gain inconnu"); return null }
        if (s == Sens.HAUT && tropFort) { arrete("montée arrêtée : trop fort par rapport à la balise"); return null }
        // Within the AD936x's 0.25 dB step: reached.
        val fini = if (s == Sens.HAUT) gainDb >= e.cibleDb - 0.25 else gainDb <= e.cibleDb + 0.25
        if (fini) { etat = Etat(message = "atteint : %.1f dB".format(java.util.Locale.FRANCE, gainDb)); return null }
        val intervalle = if (s == Sens.HAUT) intervalleHautMs else intervalleBasMs
        if (maintenant - dernierMs < intervalle) return null
        dernierMs = maintenant
        return if (s == Sens.HAUT) minOf(gainDb + pasHautDb, e.cibleDb) else maxOf(gainDb - pasBasDb, e.cibleDb)
    }
}
