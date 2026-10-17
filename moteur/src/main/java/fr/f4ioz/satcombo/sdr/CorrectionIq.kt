/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.plateforme.Stockage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A transmitter's I/Q correction: what cancels its LO leakage (a DC offset
 * added), its image (gain and phase imbalance pre-distorted) and its
 * crystal error (the LO moved). Applied to the samples just before they go
 * to the transmitter ([EtageIq]):
 *
 *     I' = (1 + g/2)·I + dcI
 *     Q' = (1 − g/2)·(Q·cos φ + I·sin φ) + dcQ
 *
 * Neutral by default: the samples left untouched, the LO as asked.
 */
data class ParametresIq(
    /** Added to I and Q, as a share of full scale (±1). */
    val dcI: Double = 0.0,
    val dcQ: Double = 0.0,
    /** Gain imbalance g: I × (1 + g/2), Q × (1 − g/2). */
    val gain: Double = 0.0,
    /** Phase skew φ (degrees): I mixed into Q by sin φ. */
    val phaseDeg: Double = 0.0,
    /** The transmitter's frequency error (ppm; + : it transmits high): its LO set lower by as much. */
    val ppm: Double = 0.0,
) {
    /** Nothing to do on the samples (the LO may still move). */
    val iqNeutre: Boolean get() = dcI == 0.0 && dcQ == 0.0 && gain == 0.0 && phaseDeg == 0.0
    val neutre: Boolean get() = iqNeutre && ppm == 0.0

    /** Within the bounds the page and the calibration may use; NaN as 0. */
    fun borne(): ParametresIq = ParametresIq(b(dcI, DC_MAX), b(dcQ, DC_MAX), b(gain, GAIN_MAX), b(phaseDeg, PHASE_MAX), b(ppm, PPM_MAX))

    fun texte(): String = listOf(dcI, dcQ, gain, phaseDeg, ppm).joinToString(";") { String.format(Locale.US, "%.8g", it) }

    companion object {
        val NEUTRE = ParametresIq()
        const val DC_MAX = 0.1
        const val GAIN_MAX = 0.2
        const val PHASE_MAX = 10.0
        const val PPM_MAX = 50.0

        private fun b(v: Double, m: Double) = if (v.isNaN()) 0.0 else v.coerceIn(-m, m)

        fun lis(s: String?): ParametresIq? {
            val v = s?.split(';')?.map { it.trim().toDoubleOrNull() } ?: return null
            if (v.size < 5 || v.any { it == null }) return null
            return ParametresIq(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!).borne()
        }
    }
}

/**
 * One transmitter's correction as it is now ([parametres], changed at any
 * time: the next buffer takes it), shared by whatever transmits with it.
 */
class CorrectionIq(val appareil: String, val serie: String, parametres: ParametresIq = ParametresIq.NEUTRE) {
    @Volatile var parametres: ParametresIq = parametres.borne()
        set(v) { field = v.borne() }
}

/**
 * The correction for one transmission: the LO moved by the crystal error
 * known at the start ([loHz]); a later change of it made by a digital mix
 * (the LO is not retuned while transmitting), then the gain and phase, then
 * the DC offset — last, being at the LO. Called on the producer's thread only.
 */
class EtageIq(private val source: CorrectionIq, val frequenceHz: Long, private val debit: Long) {
    private val ppmDepart = source.parametres.ppm
    /** Where to tune the LO for [frequenceHz]. */
    val loHz: Long = loCorrige(frequenceHz, ppmDepart)
    private var c = 1.0
    private var s = 0.0

    /** [n] I/Q pairs of [b] corrected in place; untouched when neutral. */
    fun applique(b: ShortArray, n: Int = b.size / 2) {
        val p = source.parametres
        val residuHz = -(p.ppm - ppmDepart) * 1e-6 * frequenceHz
        if (p.iqNeutre && residuHz == 0.0) return
        if (residuHz != 0.0 && debit > 0) {
            // The phase carried from buffer to buffer, its magnitude held at 1.
            val w = 2 * PI * residuHz / debit
            val cw = cos(w); val sw = sin(w)
            for (k in 0 until n) {
                val i = b[2 * k].toDouble(); val q = b[2 * k + 1].toDouble()
                b[2 * k] = sature(i * c - q * s)
                b[2 * k + 1] = sature(i * s + q * c)
                val nc = c * cw - s * sw
                s = c * sw + s * cw
                c = nc
            }
            val m = hypot(c, s)
            c /= m; s /= m
        }
        if (!p.iqNeutre) corrige(b, n, p)
    }

    companion object {
        const val PLEINE_ECHELLE = 32767.0

        /** The LO for [f] on a transmitter [ppm] off. */
        fun loCorrige(f: Long, ppm: Double): Long = if (ppm == 0.0) f else Math.round(f / (1.0 + ppm * 1e-6))

        /** The gain, phase and DC correction of [p] on [n] pairs of [b], in place. */
        fun corrige(b: ShortArray, n: Int, p: ParametresIq) {
            val a = 1 + p.gain / 2
            val d = 1 - p.gain / 2
            val phi = Math.toRadians(p.phaseDeg)
            val cp = d * cos(phi); val sp = d * sin(phi)
            val oi = p.dcI * PLEINE_ECHELLE; val oq = p.dcQ * PLEINE_ECHELLE
            for (k in 0 until n) {
                val i = b[2 * k].toDouble(); val q = b[2 * k + 1].toDouble()
                b[2 * k] = sature(a * i + oi)
                b[2 * k + 1] = sature(q * cp + i * sp + oq)
            }
        }

        private fun sature(v: Double): Short = Math.round(v).coerceIn(-32767L, 32767L).toInt().toShort()
    }
}

/**
 * The correction that undoes a known impairment: the transmitter's I/Q
 * made [m]·(I, Q) + [dc] (full scale 1). Its inverse split into a lower
 * triangle (what [ParametresIq] can do) times a rotation (harmless): the
 * output then a pure rotation and scale of what was asked.
 */
object CompensationIq {
    fun pour(m: Array<DoubleArray>, dc: DoubleArray, ppm: Double = 0.0): ParametresIq {
        val det = m[0][0] * m[1][1] - m[0][1] * m[1][0]
        require(det > 0) { "déséquilibre non inversible" }
        // N = M⁻¹, rows n0, n1.
        val n00 = m[1][1] / det; val n01 = -m[0][1] / det
        val n10 = -m[1][0] / det; val n11 = m[0][0] / det
        // N = L·Qo: L's first column from the rows' Gram–Schmidt.
        val a = hypot(n00, n01)
        val e0 = n00 / a; val e1 = n01 / a
        val c = n10 * e0 + n11 * e1
        val d = hypot(n10 - c * e0, n11 - c * e1)
        val e = hypot(c, d)
        val g = 2 * (a - e) / (a + e)
        val phi = Math.toDegrees(atan2(c, d))
        // The offset that the transmitter's own one cancels: −N·dc.
        val oi = -(n00 * dc[0] + n01 * dc[1])
        val oq = -(n10 * dc[0] + n11 * dc[1])
        // The correction's DC goes after its linear part: L·x/k + o; the scale k folded in.
        return ParametresIq(oi, oq, g, phi, ppm)
    }
}

/**
 * Every transmitter's correction, kept in the settings by its kind and its
 * serial number ([CLE] + "pluto_1044…"). One live [CorrectionIq] for each:
 * a change made while transmitting takes effect on the next buffer.
 */
class CorrectionsIq(private val stockage: Stockage) {
    private val vivantes = ConcurrentHashMap<String, CorrectionIq>()

    private fun cle(appareil: String, serie: String) = CLE + appareil + "_" + serie.lowercase()

    /** The correction for this board: the one kept, else neutral. */
    fun pour(appareil: String, serie: String): CorrectionIq =
        vivantes.computeIfAbsent(cle(appareil, serie)) {
            CorrectionIq(appareil, serie, ParametresIq.lis(stockage.getString(it, null)) ?: ParametresIq.NEUTRE)
        }

    /**
     * The correction to use when the transmitter's serial is [serie]: its
     * own; an empty one (the board not read) takes the only board of that
     * kind known here, else none.
     */
    fun aAppliquer(appareil: String, serie: String): CorrectionIq? {
        if (serie.isNotEmpty()) return pour(appareil, serie)
        val l = connues().filter { it.first == appareil }
        return if (l.size == 1) pour(appareil, l[0].second) else null
    }

    /** Kept, and applied to what transmits with it now. */
    fun enregistre(appareil: String, serie: String, p: ParametresIq) {
        val c = pour(appareil, serie)
        c.parametres = p
        val e = stockage.edit()
        val l = (stockage.getStringSet(CLE_LISTE, null) ?: emptySet()) + (appareil + "_" + serie.lowercase())
        e.putStringSet(CLE_LISTE, l)
        e.putString(cle(appareil, serie), c.parametres.texte())
        e.apply()
    }

    /** The boards with a correction kept: (kind, serial). */
    fun connues(): List<Pair<String, String>> = (stockage.getStringSet(CLE_LISTE, null) ?: emptySet()).sorted()
        .mapNotNull { s -> s.indexOf('_').takeIf { it > 0 }?.let { s.substring(0, it) to s.substring(it + 1) } }

    /** What the last calibration found, kept beside the correction (free text, for the page). */
    fun resultat(appareil: String, serie: String): String = stockage.getString(cle(appareil, serie) + "_res", "") ?: ""
    fun gardeResultat(appareil: String, serie: String, texte: String) { stockage.edit().putString(cle(appareil, serie) + "_res", texte).apply() }

    companion object {
        const val CLE = "iq_corr_"
        const val CLE_LISTE = "iq_corr_liste"
        const val PLUTO = "pluto"
        const val BLADERF = "bladerf"
        const val HACKRF = "hackrf"
    }
}
