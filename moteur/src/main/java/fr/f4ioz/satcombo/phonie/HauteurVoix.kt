/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pitch of a voice, 100 times a second: the cumulative mean normalised
 * difference of each 30 ms frame with itself shifted (de Cheveigné and
 * Kawahara's method, 2002), the first dip under a threshold taken as the
 * period, refined by a parabola. Works without the fundamental (an SSB
 * passband starting at 300 Hz): the period is the waveform's, not a peak's.
 *
 * Above 12 kHz the sound is first brought down (low-pass, then one sample
 * in two or three): the period needs no more, and it is four times faster.
 */
object HauteurVoix {

    /** A pitch track: [f0] in Hz per frame (0 where unvoiced), [niveauDb] each frame's RMS (dBFS). */
    class Courbe(val pasS: Double, val f0: FloatArray, val niveauDb: FloatArray) {
        val n: Int get() = f0.size
        /** The pitch at [t] seconds (0 when unvoiced), the nearest frame. */
        fun a(t: Double): Float = f0.getOrElse(Math.round(t / pasS).toInt().coerceIn(0, maxOf(0, n - 1))) { 0f }
        fun voisees(): List<Float> = f0.filter { it > 0f }
    }

    /** A voice in a few numbers: its mean pitch, its spread, how much of it is voiced, its level. */
    data class Profil(
        /** Geometric mean of the voiced frames (Hz); 0 when nothing is voiced. */
        val f0Hz: Double,
        /** From the 10th to the 90th percentile, in semitones. */
        val etendueDt: Double,
        val fractionVoisee: Double,
        /** Mean level of the voiced frames (dBFS). */
        val niveauDb: Double,
    ) {
        val voisee: Boolean get() = f0Hz > 0
    }

    const val PAS_S = 0.01
    private const val TRAME_S = 0.03
    private const val SEUIL = 0.15
    private const val SEUIL_MAX = 0.4

    fun suit(pcm: FloatArray, rate: Int, fminHz: Double = 60.0, fmaxHz: Double = 450.0): Courbe {
        val k = if (rate > 12_000) rate / 8_000 else 1
        val x = if (k > 1) decime(pcm, k) else pcm
        val sr = rate.toDouble() / k
        val pas = Math.round(sr * PAS_S).toInt().coerceAtLeast(1)
        val w = Math.round(sr * TRAME_S).toInt()
        val tauMin = maxOf(2, (sr / fmaxHz).toInt())
        val tauMax = (sr / fminHz).toInt() + 1
        val n = maxOf(0, (pcm.size.toDouble() / rate / PAS_S).toInt())
        val f0 = FloatArray(n)
        val niv = FloatArray(n)
        // Silence: below 35 dB under the loudest frame, or below −70 dBFS.
        for (i in 0 until n) niv[i] = rmsDb(x, i * pas - w / 2, w)
        val plancher = maxOf(-70f, (niv.maxOrNull() ?: -120f) - 35f)
        val d = DoubleArray(tauMax + 1)
        for (i in 0 until n) {
            if (niv[i] < plancher) continue
            val debut = i * pas - w / 2
            if (debut < 0 || debut + w + tauMax >= x.size) continue
            // The difference function, then its cumulative mean normalised form.
            var somme = 0.0
            d[0] = 1.0
            for (tau in 1..tauMax) {
                var s = 0.0
                for (j in debut until debut + w) { val e = x[j] - x[j + tau]; s += e * e }
                somme += s
                d[tau] = if (somme > 0) s * tau / somme else 1.0
            }
            var t = -1
            var tau = tauMin
            while (tau < tauMax) {
                if (d[tau] < SEUIL) {
                    while (tau + 1 < tauMax && d[tau + 1] < d[tau]) tau++
                    t = tau; break
                }
                tau++
            }
            if (t < 0) {
                var m = tauMin
                for (u in tauMin until tauMax) if (d[u] < d[m]) m = u
                if (d[m] < SEUIL_MAX) t = m
            }
            if (t <= tauMin - 1 || t >= tauMax - 1) continue
            val a = d[t - 1]; val b = d[t]; val c = d[t + 1]
            val den = a - 2 * b + c
            val fin = if (den > 1e-12) t + 0.5 * (a - c) / den else t.toDouble()
            f0[i] = (sr / fin).toFloat()
        }
        nettoie(f0)
        return Courbe(PAS_S, f0, niv)
    }

    /** Lone frames out, octave slips put back, then a 5-frame median along each voiced run. */
    private fun nettoie(f0: FloatArray) {
        val n = f0.size
        for (i in 0 until n) if (f0[i] > 0 && (i == 0 || f0[i - 1] == 0f) && (i == n - 1 || f0[i + 1] == 0f)) f0[i] = 0f
        // Against the median of the 300 ms around: a short slip is corrected, a real leap (most of the window) is not.
        val avant = f0.copyOf()
        for (i in 0 until n) {
            val f = avant[i]
            if (f <= 0) continue
            val l = ArrayList<Float>(31)
            for (j in i - 15..i + 15) if (j in 0 until n && avant[j] > 0) l += avant[j]
            if (l.size < 5) continue
            l.sort()
            val med = l[l.size / 2]
            if (f > med * 1.8f && abs(f / 2 - med) < abs(f - med)) f0[i] = f / 2
            else if (f < med / 1.8f && abs(f * 2 - med) < abs(f - med)) f0[i] = f * 2
        }
        val c = f0.copyOf()
        for (i in 0 until n) {
            if (c[i] <= 0) continue
            val l = ArrayList<Float>(5)
            for (j in i - 2..i + 2) if (j in 0 until n && c[j] > 0) l += c[j]
            l.sort()
            f0[i] = l[l.size / 2]
        }
    }

    fun profil(c: Courbe, debutS: Double = 0.0, finS: Double = Double.MAX_VALUE): Profil {
        val i0 = (debutS / c.pasS).toInt().coerceAtLeast(0)
        val i1 = minOf(c.n, Math.ceil(finS.coerceAtMost(c.n * c.pasS) / c.pasS).toInt())
        val v = ArrayList<Double>()
        var niveaux = 0.0
        var sonores = 0
        for (i in i0 until i1) {
            if (c.niveauDb[i] > -70f) sonores++
            if (c.f0[i] > 0) { v += demiTons(c.f0[i].toDouble()); niveaux += Math.pow(10.0, c.niveauDb[i] / 10.0) }
        }
        if (v.isEmpty()) return Profil(0.0, 0.0, 0.0, -120.0)
        v.sort()
        val moyen = v.average()
        return Profil(hz(moyen), quantile(v, 0.9) - quantile(v, 0.1), v.size.toDouble() / maxOf(1, sonores),
            10 * log10(niveaux / v.size))
    }

    /** Semitones above 100 Hz. */
    fun demiTons(hz: Double): Double = 12.0 * ln(hz / 100.0) / ln(2.0)
    fun hz(demiTons: Double): Double = 100.0 * exp(demiTons / 12.0 * ln(2.0))

    fun quantile(trie: List<Double>, q: Double): Double {
        if (trie.isEmpty()) return 0.0
        val p = q * (trie.size - 1)
        val i = p.toInt().coerceIn(0, trie.size - 1)
        val j = minOf(i + 1, trie.size - 1)
        return trie[i] + (trie[j] - trie[i]) * (p - i)
    }

    private fun rmsDb(x: FloatArray, debut: Int, w: Int): Float {
        var s = 0.0; var m = 0
        for (j in maxOf(0, debut) until minOf(x.size, debut + w)) { s += x[j].toDouble() * x[j]; m++ }
        return if (m == 0 || s <= 0) -120f else (10 * log10(s / m)).toFloat()
    }

    /** Low-pass at 0.4 of the new rate (a 63-tap windowed sinc), then one sample in [k]. */
    fun decime(x: FloatArray, k: Int): FloatArray {
        val m = 31
        val fc = 0.4 / k
        val h = DoubleArray(2 * m + 1) { i ->
            val t = (i - m).toDouble()
            val s = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
            s * (0.54 + 0.46 * cos(PI * t / (m + 1)))
        }
        val g = h.sum()
        val y = FloatArray(x.size / k)
        for (i in y.indices) {
            val c = i * k
            var s = 0.0
            for (j in -m..m) { val p = c + j; if (p >= 0 && p < x.size) s += h[j + m] * x[p] }
            y[i] = (s / g).toFloat()
        }
        return y
    }

    fun rms(x: FloatArray): Double = if (x.isEmpty()) 0.0 else sqrt(x.sumOf { it.toDouble() * it } / x.size)
}
