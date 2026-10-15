/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fine demodulation of one candidate, in complex baseband.
 *
 * The spectrogram finds signals to within half a symbol and half a tone:
 * good enough to know where to look, not to read. Here each candidate gets
 * its own narrow channel, cut from one transform of the whole slot: the bins
 * around the signal, shifted to zero and brought back to time at 32 samples
 * a symbol. In that channel, small and cheap, the start is searched to 1/32
 * of a symbol, the frequency to a fraction of a hertz and the drift (Hz/s)
 * over a few values — then the 8 (or 4) tones of every symbol are read
 * exactly where they are.
 *
 * The channel is band-limited to the signal: a strong neighbour 60 Hz away
 * no longer leaks into the tones through the sidelobes of a one-symbol
 * transform.
 */
object Ft8Fin {

    /** Baseband samples per symbol. */
    const val PAR_SYMBOLE = 32

    /** One transform of a whole block of audio, already at the mode's rate. */
    class Spectre(val re: FloatArray, val im: FloatArray, val mode: Ft8Signal.Mode) {
        val nfft: Int get() = re.size
        val binHz: Double get() = mode.cadenceHz / nfft
        /** Decimation down to [PAR_SYMBOLE] samples a symbol. */
        val decimation: Int get() = mode.parSymbole / PAR_SYMBOLE
        val nBase: Int get() = nfft / decimation
        val cadenceBase: Double get() = mode.cadenceHz / decimation
    }

    fun spectre(audio: FloatArray, mode: Ft8Signal.Mode): Spectre {
        val n = Ft8Signal.puissanceDeDeux(maxOf(audio.size, mode.parSymbole * 2))
        val re = FloatArray(n)
        val im = FloatArray(n)
        audio.copyInto(re, 0, 0, minOf(audio.size, n))
        Ft8Signal.fft(re, im)
        return Spectre(re, im, mode)
    }

    /** A complex channel: tone 0 near 0 Hz, [cadence] samples a second. */
    class Canal(val re: FloatArray, val im: FloatArray, val cadence: Double, /** Audio frequency of 0 Hz here. */ val origineHz: Double)

    /**
     * The channel around [f0Hz] (audio frequency of tone 0), keeping
     * [basHz]..[hautHz] relative to it, edges tapered over a few hertz so the
     * cut does not ring.
     */
    fun canal(sp: Spectre, f0Hz: Double, basHz: Double, hautHz: Double): Canal {
        val nb = sp.nBase
        val re = FloatArray(nb)
        val im = FloatArray(nb)
        val k0 = (f0Hz / sp.binHz).roundToInt()
        val pente = 4.0                              // taper width, Hz
        val kBas = ((basHz - pente) / sp.binHz).toInt() - 1
        val kHaut = ((hautHz + pente) / sp.binHz).toInt() + 1
        for (kp in maxOf(kBas, -nb / 2) until minOf(kHaut, nb / 2)) {
            val src = k0 + kp
            if (src <= 0 || src >= sp.nfft / 2) continue
            val f = kp * sp.binHz
            val w = when {
                f < basHz - pente || f > hautHz + pente -> 0.0
                f < basHz -> 0.5 - 0.5 * cos(PI * (f - basHz + pente) / pente)
                f > hautHz -> 0.5 - 0.5 * cos(PI * (hautHz + pente - f) / pente)
                else -> 1.0
            }.toFloat()
            if (w == 0f) continue
            val dst = (kp + nb) % nb
            re[dst] = sp.re[src] * w
            im[dst] = sp.im[src] * w
        }
        Ft8Signal.fftInverse(re, im)
        return Canal(re, im, sp.cadenceBase, k0 * sp.binHz)
    }

    // ------------------------------------------------------------ sync

    /** Where a candidate was found to sit, and its tones read there. */
    class Lecture(
        /** Channel sample where symbol 0 starts. */
        val debut: Int,
        /** Tone 0's offset from the channel's origin, Hz, at mid-transmission. */
        val decalageHz: Double,
        /** Drift found, Hz/s. */
        val penteHzS: Double,
        /** Sync energy over total energy at the sync symbols. */
        val score: Double,
        /** Power of each tone of each symbol, [symbol][tone]. */
        val puissances: Array<FloatArray>,
    )

    private val COS = Array(8) { t -> FloatArray(PAR_SYMBOLE) { n -> cos(2 * PI * t * n / PAR_SYMBOLE).toFloat() } }
    private val SIN = Array(8) { t -> FloatArray(PAR_SYMBOLE) { n -> sin(2 * PI * t * n / PAR_SYMBOLE).toFloat() } }

    /**
     * The channel with a frequency offset and a drift taken out, over
     * [debut] until [fin]: what is searched, then read.
     */
    private fun redresse(c: Canal, debut: Int, fin: Int, decalageHz: Double, penteHzS: Double, milieuS: Double,
                         re: FloatArray, im: FloatArray) {
        // The phase is quadratic: its rotation from one sample to the next
        // changes by a constant factor. Two complex multiplications a sample
        // instead of a cosine and a sine; exact values again every 256
        // samples so rounding does not pile up.
        val fs = c.cadence
        var cr = 0.0; var ci = 0.0      // exp(j·phi(n))
        var rr = 0.0; var ri = 0.0      // exp(j·(phi(n+1) − phi(n)))
        val dd = -2 * PI * penteHzS / (fs * fs)
        val qr = cos(dd); val qi = sin(dd)
        for (n in debut until fin) {
            val j = n - debut
            if ((j and 255) == 0) {
                val t = n / fs
                val u = t - milieuS
                val phi = -2 * PI * (decalageHz * t + 0.5 * penteHzS * u * u)
                val pas = -2 * PI * (decalageHz / fs + penteHzS * (u / fs + 0.5 / (fs * fs)))
                cr = cos(phi); ci = sin(phi); rr = cos(pas); ri = sin(pas)
            }
            if (n < 0 || n >= c.re.size) { re[j] = 0f; im[j] = 0f } else {
                val a = c.re[n]; val b = c.im[n]
                val co = cr.toFloat(); val si = ci.toFloat()
                re[j] = a * co - b * si
                im[j] = a * si + b * co
            }
            val nr = cr * rr - ci * ri; ci = cr * ri + ci * rr; cr = nr
            val mr = rr * qr - ri * qi; ri = rr * qi + ri * qr; rr = mr
        }
    }

    /** Power of [ton] over one symbol starting at [i] in a straightened span. */
    private fun puissance(re: FloatArray, im: FloatArray, i: Int, ton: Int): Float {
        val co = COS[ton]; val si = SIN[ton]
        var sr = 0f; var sj = 0f
        for (n in 0 until PAR_SYMBOLE) {
            val a = re[i + n]; val b = im[i + n]
            sr += a * co[n] + b * si[n]
            sj += b * co[n] - a * si[n]
        }
        return sr * sr + sj * sj
    }

    /**
     * Searches start, frequency and drift around a candidate.
     *
     * The start within ±[fenetre] channel samples of [debutApprox], the
     * frequency over [decalages] Hz, the drift over [pentes] Hz/s; the
     * score is the power of the expected sync tones over the total power at
     * those symbols, so a strong neighbour leaking in does not win. Then the
     * winner's every symbol is read.
     */
    fun synchronise(c: Canal, mode: Ft8Signal.Mode, debutApprox: Int, fenetre: Int,
                    decalages: DoubleArray, pentes: DoubleArray): Lecture {
        val longueur = mode.symboles * PAR_SYMBOLE
        val bas = debutApprox - fenetre
        val etendue = longueur + 2 * fenetre
        val re = FloatArray(etendue)
        val im = FloatArray(etendue)
        val milieuS = (debutApprox + longueur / 2.0) / c.cadence
        val synchro = mode.synchro

        var meilleur = -1.0
        var mDebut = debutApprox; var mDecalage = 0.0; var mPente = 0.0
        for (k in pentes) for (df in decalages) {
            redresse(c, bas, bas + etendue, df, k, milieuS, re, im)
            // Energy per channel sample, summed in a running window per symbol.
            var tau = 0
            while (tau <= 2 * fenetre) {
                var s = 0.0
                var e = 0.0
                for ((position, ton) in synchro) {
                    val i = tau + position * PAR_SYMBOLE
                    s += puissance(re, im, i, ton)
                    var en = 0f
                    for (n in i until i + PAR_SYMBOLE) en += re[n] * re[n] + im[n] * im[n]
                    e += en
                }
                val score = if (e > 0.0) s / e else 0.0
                if (score > meilleur) { meilleur = score; mDebut = bas + tau; mDecalage = df; mPente = k }
                tau += 2
            }
        }
        // The best start to the channel sample.
        redresse(c, bas, bas + etendue, mDecalage, mPente, milieuS, re, im)
        for (d in intArrayOf(-1, 1)) {
            val tau = mDebut - bas + d
            if (tau < 0 || tau > 2 * fenetre) continue
            var s = 0.0; var e = 0.0
            for ((position, ton) in synchro) {
                val i = tau + position * PAR_SYMBOLE
                s += puissance(re, im, i, ton)
                for (n in i until i + PAR_SYMBOLE) e += (re[n] * re[n] + im[n] * im[n]).toDouble()
            }
            val score = if (e > 0.0) s / e else 0.0
            if (score > meilleur) { meilleur = score; mDebut = bas + tau }
        }
        val t0 = mDebut - bas
        val p = Array(mode.symboles) { s ->
            FloatArray(mode.tons) { ton -> puissance(re, im, t0 + s * PAR_SYMBOLE, ton) }
        }
        return Lecture(mDebut, mDecalage, mPente, meilleur, p)
    }

    // ------------------------------------------------------------ reading

    /** Positions carrying data: not sync, and for FT4 not the two ramps. */
    fun positionsDonnees(mode: Ft8Signal.Mode): IntArray =
        (0 until mode.symboles).filter { s ->
            if (mode.tons == 4) !Ft4.estRepere(s) else mode.synchro.none { it.first == s }
        }.toIntArray()

    private val GRAY_INVERSE_8 = intArrayOf(0, 1, 3, 2, 6, 4, 5, 7)
    private val GRAY_INVERSE_4 = intArrayOf(0, 1, 3, 2)

    /** The strongest tone of every symbol. */
    fun tons(l: Lecture): IntArray = IntArray(l.puissances.size) { s ->
        val p = l.puissances[s]
        var m = 0
        for (t in p.indices) if (p[t] > p[m]) m = t
        m
    }

    /**
     * Per-bit log-likelihood ratios, positive for zero, as [Ldpc] expects.
     *
     * Max-log on amplitudes, then scaled to a unit spread: min-sum does not
     * care about the scale, but the a-priori check compares agreement
     * between candidates and needs one.
     */
    fun vraisemblances(l: Lecture, mode: Ft8Signal.Mode): FloatArray {
        val bitsParSymbole = if (mode.tons == 8) 3 else 2
        val inverse = if (mode.tons == 8) GRAY_INVERSE_8 else GRAY_INVERSE_4
        val positions = positionsDonnees(mode)
        val sortie = FloatArray(positions.size * bitsParSymbole)
        var i = 0
        for (s in positions) {
            val amp = FloatArray(mode.tons) { sqrt(l.puissances[s][it]) }
            for (b in 0 until bitsParSymbole) {
                var max0 = 0f; var max1 = 0f
                for (ton in 0 until mode.tons) {
                    val bit = (inverse[ton] shr (bitsParSymbole - 1 - b)) and 1
                    if (bit == 0) max0 = maxOf(max0, amp[ton]) else max1 = maxOf(max1, amp[ton])
                }
                sortie[i++] = max0 - max1
            }
        }
        var somme = 0.0
        for (v in sortie) somme += v * v.toDouble()
        val ecart = sqrt(somme / sortie.size).toFloat()
        if (ecart > 0f) for (k in sortie.indices) sortie[k] /= ecart
        return sortie
    }

    /**
     * SNR in 2500 Hz, as an FT8 report.
     *
     * Signal: the power of the tone actually sent, symbol by symbol. Noise:
     * the tones two or more away from it (the Gaussian shaping leaks into
     * the adjacent ones). One symbol's transform has a noise bandwidth of the
     * tone spacing, hence the constant.
     */
    fun rapportDb(l: Lecture, tons: IntArray, mode: Ft8Signal.Mode): Int {
        var signal = 0.0
        var bruit = 0.0
        var nBruit = 0
        for (s in tons.indices) {
            val p = l.puissances[s]
            signal += p[tons[s]]
            for (t in p.indices) if (abs(t - tons[s]) >= 2) { bruit += p[t]; nBruit++ }
        }
        if (nBruit == 0) return -99
        val n = bruit / nBruit
        val sg = signal / tons.size - n
        if (n <= 0.0 || sg <= 0.0) return 30
        val brut = 10.0 * log10(sg / n) - 10.0 * log10(2500.0 / mode.ecartHz)
        return brut.coerceIn(-30.0, 30.0).toInt()
    }
}
