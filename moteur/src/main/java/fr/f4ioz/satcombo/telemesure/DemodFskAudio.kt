/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * Two-tone FSK from a **USB receiver's audio** (the IC-9700 in USB-D, or
 * the SDR demodulating USB), receive only.
 *
 * A wide-shift FSK (SO-127's « GENESIS FSK »: 800 bit/s with 1600 Hz
 * between the tones, 200 bit/s with 1125 Hz) heard in SSB is two audio
 * tones [ecartHz] apart, somewhere in the passband as the Doppler left it:
 *
 * 1. **tone search**: every quarter of a second or so, the audio's spectrum
 *    (Hann, FFT), each bin smoothed over the bit rate; the pair of bins
 *    [ecartHz] apart with the most power in [basHz]..[hautHz] gives the
 *    centre; above the noise (median of the band) by [seuilDb] it is a
 *    signal. Small moves are followed smoothly (the Doppler's residue),
 *    a big one only when seen twice;
 * 2. **two matched filters**: the audio moved by each tone to 0 Hz and
 *    summed over one bit (integrate and dump, as a running sum): with a
 *    shift of two bit rates and more the tones are orthogonal and
 *    non-coherent detection is right; the soft decision is the energy
 *    difference over the sum (−1…+1, the level does not matter);
 * 3. **clock**: a Gardner loop on that soft stream, interpolated between
 *    samples, a decision a bit.
 *
 * Each block is searched before it is demodulated (a third of a second
 * late): the tones of a frame's first bits are known when they are read.
 *
 * Bits go to [surBit] as 0/1 (1: the upper tone), with the soft value.
 * Which tone means 1 on the air, and LSB against USB, flip them: the frame
 * detectors try both polarities.
 */
class DemodFskAudio(
    val fs: Double,
    val debit: Double,
    val ecartHz: Double,
    private val basHz: Double = 250.0,
    private val hautHz: Double = 3_400.0,
    private val seuilDb: Double = 6.0,
    private val surBit: (Int, Float) -> Unit,
) {
    /** Samples a bit. */
    val sps = fs / debit

    /** The tones' middle (Hz in the audio), as found; the default: the passband's middle. */
    @Volatile var centreHz = ((basHz + hautHz) / 2).coerceAtLeast(ecartHz / 2 + basHz); private set
    /** A pair of tones above the noise in the last spectrum. */
    @Volatile var presence = false; private set
    /** The pair's power over the noise, dB (last spectrum). */
    @Volatile var snrDb = 0.0; private set
    /** The audio's level, dB full scale. */
    @Volatile var niveauDb = -120.0; private set
    var bits = 0L; private set

    // ------------------------------------------------------------ tone search
    private val nFft = Integer.highestOneBit((fs * 0.3).toInt().coerceAtLeast(1024))
    private val tampon = DoubleArray(nFft)
    private var nTampon = 0
    private val fenetre = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / (nFft - 1)) }
    private val reF = DoubleArray(nFft); private val imF = DoubleArray(nFft)
    private var candidatHz = Double.NaN
    private var verrou = false

    // ------------------------------------------------------------ matched filters
    private val l = Math.round(sps).toInt().coerceAtLeast(2)
    private val hpR = DoubleArray(l); private val hpI = DoubleArray(l)
    private val hmR = DoubleArray(l); private val hmI = DoubleArray(l)
    private var sPR = 0.0; private var sPI = 0.0; private var sMR = 0.0; private var sMI = 0.0
    private var ih = 0
    // The two oscillators, by rotation (renormalised now and then).
    private var oPR = 1.0; private var oPI = 0.0; private var oMR = 1.0; private var oMI = 0.0
    private var pasPR = 1.0; private var pasPI = 0.0; private var pasMR = 1.0; private var pasMI = 0.0
    private var normalise = 0

    // ------------------------------------------------------------ clock
    private var phase = 0.0
    private var avant = 0.0
    private var milieu = 0.0
    private var milieuPris = false
    private var precedent = 0.0
    private val gain = 0.04

    // Level.
    private var energie = 0.0
    private var nEnergie = 0

    init { regleOscillateurs() }

    private fun regleOscillateurs() {
        val wp = 2 * PI * (centreHz + ecartHz / 2) / fs
        val wm = 2 * PI * (centreHz - ecartHz / 2) / fs
        pasPR = cos(wp); pasPI = -sin(wp); pasMR = cos(wm); pasMI = -sin(wm)
    }

    /** [n] audio samples (16-bit). */
    fun traite(pcm: ShortArray, n: Int) {
        for (k in 0 until n) echantillon(pcm[k] / 32768.0)
    }

    fun traite(x: FloatArray, n: Int) {
        for (k in 0 until n) echantillon(x[k].toDouble())
    }

    private fun echantillon(x: Double) {
        energie += x * x; nEnergie++
        if (nEnergie >= 4096) { niveauDb = 10 * log10(energie / nEnergie + 1e-12); energie = 0.0; nEnergie = 0 }
        tampon[nTampon++] = x
        // The block searched first, then demodulated with what was found: a frame's start is not lost to the search.
        if (nTampon == nFft) { cherche(); for (i in 0 until nFft) demodule(tampon[i]); nTampon = 0 }
    }

    private fun demodule(x: Double) {
        // Matched filters: x moved down by each tone, summed over a bit.
        val pr = x * oPR; val pi = x * oPI
        val mr = x * oMR; val mi = x * oMI
        sPR += pr - hpR[ih]; sPI += pi - hpI[ih]; sMR += mr - hmR[ih]; sMI += mi - hmI[ih]
        hpR[ih] = pr; hpI[ih] = pi; hmR[ih] = mr; hmI[ih] = mi
        ih = if (ih + 1 == l) 0 else ih + 1
        var t = oPR * pasPR - oPI * pasPI; oPI = oPR * pasPI + oPI * pasPR; oPR = t
        t = oMR * pasMR - oMI * pasMI; oMI = oMR * pasMI + oMI * pasMR; oMR = t
        if (++normalise >= 1024) {
            normalise = 0
            val a = hypot(oPR, oPI); oPR /= a; oPI /= a
            val b = hypot(oMR, oMI); oMR /= b; oMI /= b
            // The running sums' rounding drift, cleared.
            sPR = hpR.sum(); sPI = hpI.sum(); sMR = hmR.sum(); sMI = hmI.sum()
        }
        val ep = sPR * sPR + sPI * sPI
        val em = sMR * sMR + sMI * sMI
        val doux = (ep - em) / (ep + em + 1e-18)
        horloge(doux)
    }

    private fun horloge(s: Double) {
        val pas = 1.0 / sps
        val ancien = phase
        phase += pas
        if (!milieuPris && ancien < 0.5 && phase >= 0.5) {
            val f = (0.5 - ancien) / pas
            milieu = avant + (s - avant) * f
            milieuPris = true
        }
        if (phase >= 1.0) {
            val f = (1.0 - ancien) / pas
            val v = avant + (s - avant) * f
            // Gardner: the middle between two decisions should sit on the crossing.
            val e = (v - precedent) * milieu
            phase -= 1.0
            phase += (gain * e).coerceIn(-0.1, 0.1)
            precedent = v
            milieuPris = false
            bits++
            surBit(if (v > 0) 1 else 0, v.toFloat())
        }
        avant = s
    }

    /** The tones looked for in the last block's spectrum. */
    private fun cherche() {
        for (i in 0 until nFft) { reF[i] = tampon[i] * fenetre[i]; imF[i] = 0.0 }
        Fft.transform(reF, imF)
        val hzBin = fs / nFft
        val nb = nFft / 2
        val p = DoubleArray(nb) { reF[it] * reF[it] + imF[it] * imF[it] }
        // Each tone spreads over about the bit rate: smoothed over half of it either side.
        val demi = (debit / 2 / hzBin).toInt().coerceAtLeast(1)
        val cumul = DoubleArray(nb + 1)
        for (i in 0 until nb) cumul[i + 1] = cumul[i] + p[i]
        fun lisse(i: Int): Double {
            val a = (i - demi).coerceAtLeast(0); val b = (i + demi + 1).coerceAtMost(nb)
            return (cumul[b] - cumul[a]) / (b - a)
        }
        val ib = (basHz / hzBin).toInt().coerceAtLeast(1); val ih2 = (hautHz / hzBin).toInt().coerceAtMost(nb - 1)
        val dEcart = ecartHz / hzBin
        val dm = (dEcart / 2)
        val bande = (ib..ih2).map { p[it] }.sorted()
        val bruit = bande.getOrElse(bande.size / 2) { 1e-18 }.coerceAtLeast(1e-18)
        var meilleur = -1.0; var ic = -1
        val de = (ib + dm).toInt() + 1; val a = (ih2 - dm).toInt() - 1
        for (c in de..a) {
            val v = lisse(Math.round(c - dm).toInt()) + lisse(Math.round(c + dm).toInt())
            if (v > meilleur) { meilleur = v; ic = c }
        }
        if (ic < 0) { presence = false; return }
        // Finer: the score's peak by a parabola through its neighbours.
        fun score(c: Int) = lisse(Math.round(c - dm).toInt()) + lisse(Math.round(c + dm).toInt())
        val y0 = score(ic - 1); val y1 = meilleur; val y2 = score(ic + 1)
        val den = y0 - 2 * y1 + y2
        val dec = if (den < 0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        val f = (ic + dec) * hzBin
        snrDb = 10 * log10(meilleur / 2 / bruit)
        presence = snrDb >= seuilDb
        if (!presence) { candidatHz = Double.NaN; return }
        val ecart = f - centreHz
        when {
            // Near (the Doppler's residue): followed.
            verrou && abs(ecart) <= debit / 2 -> centreHz += 0.5 * ecart
            // Elsewhere: taken when seen twice at the same place, or at once when nothing was followed.
            !verrou || (!candidatHz.isNaN() && abs(candidatHz - f) <= debit / 2) -> { centreHz = f; verrou = true; candidatHz = Double.NaN }
            else -> candidatHz = f
        }
        regleOscillateurs()
    }

    /** A known centre (a test, or the operator's): the search starts from it. */
    fun centre(hz: Double) { centreHz = hz; verrou = true; regleOscillateurs() }

    fun remetAZero() { verrou = false; presence = false; candidatHz = Double.NaN }
}
