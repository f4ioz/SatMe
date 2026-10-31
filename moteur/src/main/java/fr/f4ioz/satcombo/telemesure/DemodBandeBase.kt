/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.sdr.Dsp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10

/**
 * The end of a two-level FSK demodulator, once the signal is a level that
 * follows the data (the FM discriminator's output): a low-pass at 0.6 ×
 * the bit rate, the threshold half-way between the envelope's top and
 * bottom (peaks held, let go slowly: right even when the data is not
 * balanced), then the clock — a phase counter at the bit rate pulled by
 * each zero crossing towards half-way between two decisions, the decision
 * the sign at the counter's wrap, interpolated between samples.
 *
 * Shared by [DemodFsk] (the SDR's I/Q, levels in Hz) and [DemodBandeBase]
 * (a rig's 9600 output on a sound card, levels in full scale);
 * [ampleur] is the envelope's start, about the signal's size; [coupure]
 * the low-pass's cut (× the bit rate) over [longueur] bits; the clock's
 * pull at each crossing, [gainLibre], and [gainVerrou] once flags are seen.
 */
class TrancheurFsk(fs: Double, private val debit: Int, ampleur: Double, private val surBit: (Int) -> Unit,
                   coupure: Double = 0.6, longueur: Double = 4.0, private val gainLibre: Double = 0.15, private val gainVerrou: Double = 0.06) {
    private val sps = fs / debit
    private val apres = Dsp.lowPass(((longueur * sps).toInt() or 1).coerceIn(15, 401), coupure * debit, fs)
    private val na = apres.size
    private val hD = FloatArray(2 * na)
    private var hd = 0

    private var haut = ampleur
    private var bas = -ampleur
    private val declin = 1.0 / (sps * 40)
    /** The threshold (the envelope's middle). */
    val dc: Double get() = (haut + bas) / 2
    /** The two levels, for the display and tests. */
    val niveaux: Pair<Double, Double> get() = haut to bas
    private var phase = 0.0
    private var avant = 0.0

    /** An outside sign of a signal (flags seen): the clock moves more gently. */
    @Volatile var verrouExterne = false
    var bits = 0L; private set

    /** Both levels moved by [d] (the AFC moved the mixer). */
    fun decale(d: Double) { haut -= d; bas -= d }

    fun pousse(x: Double) {
        // Post-detection filter.
        hD[hd] = x.toFloat(); hD[hd + na] = x.toFloat()
        hd = if (hd + 1 == na) 0 else hd + 1
        var y = 0.0
        for (t in 0 until na) y += apres[t] * hD[hd + t]
        // The envelope: peaks caught at once, let go slowly towards each other.
        val ecart = haut - bas
        if (y > haut) haut += (y - haut) * 0.3 else haut -= ecart * declin
        if (y < bas) bas += (y - bas) * 0.3 else bas += ecart * declin
        val v = y - dc
        // Clock: decide at the wrap; nudge at each crossing.
        val p0 = phase
        phase += 1.0 / sps
        if ((v >= 0) != (avant >= 0)) {
            // Where between the two samples it crossed, in bit units.
            val f = avant / (avant - v)
            val ici = p0 + f / sps
            var e = ici - 0.5
            if (e > 0.5) e -= 1.0 else if (e < -0.5) e += 1.0
            phase -= e * (if (verrouExterne) gainVerrou else gainLibre)
        }
        if (phase >= 1.0) {
            phase -= 1.0
            // The decision instant fell between the two samples: interpolated.
            val f = 1.0 - phase * sps
            val d = avant + (v - avant) * f.coerceIn(0.0, 1.0)
            surBit(if (d >= 0) 1 else 0)
            bits++
        }
        avant = v
    }
}

/**
 * Two-level FSK / GMSK at [debit] bit/s from a **rig's packet output in
 * 9600 mode** on a sound card (mono, [fs] = 44.1, 48 or 96 kHz): the FM
 * discriminator's output, flat (no de-emphasis), as a TS-2000 gives it on
 * ACC2 with its external TNC speed at 9600 bps. Receive only.
 *
 * The rig has already done the discriminator and its CAT the Doppler: no
 * mixer, no AFC here. What the Doppler left (the CAT moves the dial by
 * steps) is a DC offset, which the envelope's middle takes off. At 44.1 or
 * 48 kHz a 9600 bit/s bit is only 4.6 or 5 samples: the sound is first
 * brought to 8 samples a bit at least (zeros put between, the low-pass
 * after makes them the missing samples). The low-pass is wider and longer
 * than the SDR path's (0.75 × the bit rate over 8 bits) and the clock
 * calmer: tried on made-up signals, about 1 dB better here, where nothing
 * else (channel filter, discriminator) has smoothed the noise before.
 *
 * The polarity (the rig's discriminator, the card's input) is unknown: the
 * readers after this do not care (descrambled + NRZI, NRZI alone: see
 * [G3ruh]; Geoscan's sync word is sought in both polarities).
 *
 * Bits go to [surBit] as 0/1.
 */
class DemodBandeBase(val fs: Double, val debit: Int, private val surBit: (Int) -> Unit) {
    /** Zeros between samples: 8 samples a bit at least. */
    val facteur = maxOf(1, ceil(8.0 * debit / fs).toInt())
    private val fs2 = fs * facteur
    private val trancheur = TrancheurFsk(fs2, debit, 0.05, surBit, coupure = 0.75, longueur = 8.0, gainLibre = 0.05, gainVerrou = 0.03)

    /** The sound's level (RMS, dB full scale) and its peak (0..1) over the last 50 ms. */
    @Volatile var niveauDb = -120.0; private set
    @Volatile var crete = 0.0; private set
    /** The quietest 50 ms of the last few seconds (dB full scale): between packets, the discriminator's noise. */
    @Volatile var plancherDb = -120.0; private set
    val bits: Long get() = trancheur.bits
    var verrouExterne: Boolean
        get() = trancheur.verrouExterne
        set(v) { trancheur.verrouExterne = v }
    /** The threshold now, full scale: the Doppler's rest after the CAT's step, the card's own DC. */
    val dc: Double get() = trancheur.dc

    private var blocN = 0
    private var blocP = 0.0
    private var blocC = 0.0
    private val blocTaille = (fs * 0.05).toInt().coerceAtLeast(64)
    private var plancher = -1.0

    /** [n] samples, full scale ±1. */
    fun traite(x: FloatArray, n: Int) {
        val g = facteur.toDouble()
        for (k in 0 until n) {
            val v = x[k].toDouble()
            blocP += v * v; blocN++
            if (abs(v) > blocC) blocC = abs(v)
            if (blocN == blocTaille) bloc()
            trancheur.pousse(v * g)
            for (z in 1 until facteur) trancheur.pousse(0.0)
        }
    }

    /** 16-bit samples. */
    fun traite(pcm: ShortArray, n: Int) {
        val x = FloatArray(n) { pcm[it] / 32768f }
        traite(x, n)
    }

    private fun bloc() {
        val p = blocP / blocN
        crete = blocC
        blocN = 0; blocP = 0.0; blocC = 0.0
        niveauDb = if (p > 0) 10 * log10(p) else -120.0
        if (p <= 0) return
        plancher = if (plancher < 0 || p < plancher) p else plancher * 1.005
        plancherDb = 10 * log10(plancher)
    }
}
