/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CTCSS sub-tones heard in narrow FM: which of the 50 standard tones
 * (67.0 – 254.1 Hz) a transmitter sends under its voice. Receive only.
 *
 * Fed with the discriminator's output, before anything that could remove the
 * low frequencies:
 *
 *   44 100 S/s audio
 *     | two one-pole low-passes at 260 Hz  (voice lowered before decimating)
 *     | third-order CIC, decimate by 44     (≈ 1002 S/s)
 *   ≈ 1 kHz
 *     | Hann window of 0.64 s, one Goertzel per standard tone, every 0.128 s
 *
 * A 0.64 s window resolves the closest pair of the table (67.0 / 69.3 Hz)
 * by 15 dB. A tone is "heard" when it holds most of the power of the bank
 * (the tones within [VOISINS_HZ] aside: they share its main lobe) and stands
 * at a real deviation, three evaluations in a row: about 0.9 s after it
 * starts. It is lost after two evaluations without it (about 0.25 s).
 *
 * Cost: a few operations per audio sample plus 50 short Goertzels eight times
 * a second — nothing, even on a phone. [RxChain] runs it in NFM only, when asked.
 */
class DetecteurSousTon(
    /** Input audio rate, Hz. */
    sampleRate: Double = Dsp.AUDIO_RATE.toDouble(),
    /** Input units per Hz of deviation (the discriminator's scale), for [Etat.deviationHz]. */
    private val unitesParHz: Double = 26_000.0 / 5_000.0,
) {
    /** What is heard now. [hz] 0: no tone. [confiance] 0..1, of the best tone even when none is heard. */
    data class Etat(val hz: Double = 0.0, val confiance: Float = 0f, val deviationHz: Int = 0) {
        val present: Boolean get() = hz > 0.0
    }

    private val facteur = maxOf(1, (sampleRate / 1_000.0).roundToInt())
    private val debitBas = sampleRate / facteur

    // Pre-filter: two one-pole low-passes.
    private val aLp = exp(-2.0 * PI * COUPURE_HZ / sampleRate).toFloat()
    private var lp1 = 0f
    private var lp2 = 0f

    // CIC, order 3, integer: wraps harmlessly.
    private var i1 = 0L; private var i2 = 0L; private var i3 = 0L
    private var c1 = 0L; private var c2 = 0L; private var c3 = 0L
    private var phase = 0
    private val gainCic = facteur.toDouble() * facteur * facteur

    private val n = (FENETRE_S * debitBas).roundToInt()
    private val pas = (PAS_S * debitBas).roundToInt().coerceAtLeast(1)
    private val anneau = FloatArray(n)
    private var ecrit = 0
    private var remplis = 0
    private var depuis = 0
    private val hann = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    private val coefs = DoubleArray(TONS.size) { 2.0 * cos(2.0 * PI * TONS[it] / debitBas) }
    private val sinW = DoubleArray(TONS.size) { sin(2.0 * PI * TONS[it] / debitBas) }

    /** Each tone's amplitude scale: what the low-passes and the CIC left of it, and the window. */
    private val echelle = DoubleArray(TONS.size) { k ->
        val f = TONS[k]
        val w = 2.0 * PI * f / sampleRate
        val a = aLp.toDouble()
        val un = (1 - a) / sqrt(1 - 2 * a * cos(w) + a * a)
        val x = PI * f / sampleRate
        val cic = abs(sin(facteur * x) / (facteur * sin(x)))
        4.0 / n / (un * un * cic * cic * cic)
    }
    private val puissance = DoubleArray(TONS.size)
    private val court = (RECENT_S * debitBas).roundToInt().coerceIn(16, n)
    private val hannCourt = FloatArray(court) { (0.5 - 0.5 * cos(2.0 * PI * it / court)).toFloat() }
    private var moyenne = 0f
    private val fenetre = FloatArray(n)

    private var candidat = -1
    private var suite = 0
    private var tenu = -1
    private var rates = 0

    @Volatile var etat: Etat = Etat(); private set

    fun reset() {
        lp1 = 0f; lp2 = 0f; i1 = 0; i2 = 0; i3 = 0; c1 = 0; c2 = 0; c3 = 0; phase = 0
        anneau.fill(0f); ecrit = 0; remplis = 0; depuis = 0
        candidat = -1; suite = 0; tenu = -1; rates = 0
        etat = Etat()
    }

    /** [count] discriminator samples. */
    fun process(buf: ShortArray, count: Int) {
        val a = aLp
        val g = 1f - a
        for (k in 0 until count) {
            lp1 = a * lp1 + g * buf[k]
            lp2 = a * lp2 + g * lp1
            i1 += (lp2 * 16f).toLong(); i2 += i1; i3 += i2
            if (++phase < facteur) continue
            phase = 0
            val d1 = i3 - c1; c1 = i3
            val d2 = d1 - c2; c2 = d1
            val d3 = d2 - c3; c3 = d2
            pousse((d3 / (gainCic * 16.0)).toFloat())
        }
    }

    private fun pousse(x: Float) {
        anneau[ecrit] = x
        ecrit = (ecrit + 1) % n
        if (remplis < n) remplis++
        if (++depuis >= pas && remplis == n) { depuis = 0; evalue() }
    }

    private fun evalue() {
        // The window, oldest first, its mean removed (the mistuning's DC).
        var m = 0.0
        for (k in 0 until n) m += anneau[k]
        val moy = (m / n).toFloat()
        moyenne = moy
        for (k in 0 until n) fenetre[k] = (anneau[(ecrit + k) % n] - moy) * hann[k]
        var total = 0.0
        for (t in TONS.indices) {
            val c = coefs[t]
            var s1 = 0.0; var s2 = 0.0
            for (k in 0 until n) { val s0 = fenetre[k] + c * s1 - s2; s2 = s1; s1 = s0 }
            val re = s1 - s2 * c / 2.0
            val im = s2 * sinW[t]
            puissance[t] = re * re + im * im
            total += puissance[t]
        }
        if (total <= 0.0) { manque(); return }
        var best = 0
        for (t in TONS.indices) if (puissance[t] > puissance[best]) best = t
        val cBest = confiance(best)
        val ok = cBest >= SEUIL && deviation(best) >= DEVIATION_MIN_HZ
        if (tenu >= 0) {
            // Held: kept while it still clearly leads, even if the best moved for an instant.
            val c = confiance(tenu)
            if (c >= SEUIL_TENU && deviation(tenu) >= DEVIATION_MIN_HZ / 2 && deviationRecente(tenu) >= DEVIATION_MIN_HZ / 2) {
                rates = 0
                etat = Etat(TONS[tenu], c.toFloat(), deviation(tenu).roundToInt())
                return
            }
            if (++rates >= 2) { tenu = -1; candidat = -1; suite = 0 }
            else { etat = etat.copy(confiance = c.toFloat()); return }
        }
        if (ok) {
            if (best == candidat) suite++ else { candidat = best; suite = 1 }
            if (suite >= 3) { tenu = best; rates = 0; etat = Etat(TONS[best], cBest.toFloat(), deviation(best).roundToInt()); return }
        } else { candidat = -1; suite = 0 }
        etat = Etat(0.0, cBest.toFloat(), 0)
    }

    private fun manque() {
        if (tenu >= 0 && ++rates < 2) return
        tenu = -1; candidat = -1; suite = 0; etat = Etat()
    }

    /** [t]'s share of the bank's power, the tones sharing its main lobe left out. */
    private fun confiance(t: Int): Double {
        var autres = 0.0
        val f = TONS[t]
        for (u in TONS.indices) if (abs(TONS[u] - f) > VOISINS_HZ) autres += puissance[u]
        val p = puissance[t]
        return if (p + autres <= 0.0) 0.0 else p / (p + autres)
    }

    /**
     * [t]'s deviation over the last [RECENT_S] only: the long window still
     * holds a tone 0.6 s after it stopped; this one lets it go at once.
     */
    private fun deviationRecente(t: Int): Double {
        val c = coefs[t]
        var s1 = 0.0; var s2 = 0.0
        val debut = n - court
        for (k in 0 until court) {
            val x = (anneau[(ecrit + debut + k) % n] - moyenne) * hannCourt[k]
            val s0 = x + c * s1 - s2; s2 = s1; s1 = s0
        }
        val re = s1 - s2 * c / 2.0
        val im = s2 * sinW[t]
        return sqrt(re * re + im * im) * echelle[t] * n / court / unitesParHz
    }

    /** [t]'s deviation, Hz: its amplitude back through the filters, on the discriminator's scale. */
    private fun deviation(t: Int): Double = sqrt(puissance[t]) * echelle[t] / unitesParHz

    companion object {
        /** The 50 standard CTCSS tones (EIA), Hz. */
        val TONS = doubleArrayOf(
            67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5,
            94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
            131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
            171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
            203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1)

        /** The standard tone nearest to [hz] (within 0.3 Hz), or 0. */
        fun normalise(hz: Double): Double = TONS.firstOrNull { abs(it - hz) < 0.3 } ?: 0.0

        /** « 67,0 Hz ». */
        fun libelle(hz: Double): String = String.format(java.util.Locale.FRANCE, "%.1f Hz", hz)

        const val FENETRE_S = 0.64
        const val PAS_S = 0.128
        const val RECENT_S = 0.2
        const val COUPURE_HZ = 260.0
        /** Tones this close share the window's main lobe: not counted against each other. */
        const val VOISINS_HZ = 4.0
        /** Share of the bank's power to call a tone heard, then to keep it. */
        const val SEUIL = 0.6
        const val SEUIL_TENU = 0.35
        /** Below this deviation, no tone (the standard asks for 0.5 – 1 kHz; satellites sometimes far less). */
        const val DEVIATION_MIN_HZ = 50.0
    }
}
