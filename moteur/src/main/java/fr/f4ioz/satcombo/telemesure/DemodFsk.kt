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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Two-level FSK / GMSK at [debit] bit/s from complex baseband at [fs]
 * (the satellite near 0 Hz, its Doppler followed by the tracking):
 *
 * 1. **residual AFC** — a mixer moved by the middle of the signal's
 *    envelope (step 4) while a signal stands above the noise: that middle
 *    is the carrier's offset; within ±[afcMaxHz];
 * 2. a channel low-pass wide enough for ±3 kHz of deviation plus the
 *    symbol rate;
 * 3. the **FM discriminator** (phase step between two samples);
 * 4. a low-pass at 0.6 × the bit rate; the threshold half-way between the
 *    envelope's top and bottom (peaks held, let go slowly);
 * 5. **clock recovery**: a phase counter at the bit rate pulled by each
 *    zero crossing towards half-way between two decisions; the decision is
 *    the sign at the counter's wrap, interpolated between samples.
 *
 * Bits go to [surBit] as 0/1 (the line's level, before NRZI and the
 * descrambler).
 */
class DemodFsk(val fs: Double, val debit: Int, private val afcMaxHz: Double = 12_000.0, private val surBit: (Int) -> Unit) {

    /** Slower rates cut further first: about nine samples a bit after the channel filter. */
    private val facteur = maxOf(1, (fs / (debit * 9.0)).toInt())
    private val fs2 = fs / facteur
    private var phaseDecim = 0
    private val canal = Dsp.lowPass(63 * facteur, 0.6 * debit + minOf(3_000.0, 1.0 * debit), fs)
    private val nc = canal.size
    private val hI = FloatArray(2 * nc); private val hQ = FloatArray(2 * nc)
    private var hp = 0

    /** Steps 4 and 5, shared with [DemodBandeBase] (the levels in Hz here). */
    private val trancheur = TrancheurFsk(fs2, debit, 1_000.0, surBit)
    /** The two levels (Hz from the AFC's frequency), for the display and tests. */
    val niveaux: Pair<Double, Double> get() = trancheur.niveaux
    private val dc: Double get() = trancheur.dc
    private var ncoPhase = 0.0
    private var prevI = 0f; private var prevQ = 0f

    /** The carrier's offset the AFC has found (Hz, positive: above). */
    @Volatile var afcHz = 0.0; private set
    /** A signal above the noise now. */
    @Volatile var presence = false; private set
    /** The channel's level and the noise floor's estimate, dB. */
    @Volatile var niveauDb = -120.0; private set
    @Volatile var plancherDb = -120.0; private set
    val bits: Long get() = trancheur.bits

    // The AFC's block: power.
    private var blocN = 0
    private var blocP = 0.0
    private val blocTaille = (fs2 * 0.01).toInt().coerceAtLeast(32)
    private var plancher = -1.0
    /** An outside sign of a signal (flags seen): the AFC may follow even near the noise. */
    var verrouExterne: Boolean
        get() = trancheur.verrouExterne
        set(v) { trancheur.verrouExterne = v }

    fun traite(re: FloatArray, im: FloatArray, n: Int) {
        val hzParRad = fs2 / (2 * PI)
        for (k in 0 until n) {
            // 1. AFC mixer.
            val c = cos(ncoPhase); val s = sin(ncoPhase)
            ncoPhase -= 2 * PI * afcHz / fs
            if (ncoPhase < -PI) ncoPhase += 2 * PI else if (ncoPhase > PI) ncoPhase -= 2 * PI
            val xi = (re[k] * c - im[k] * s).toFloat()
            val xq = (re[k] * s + im[k] * c).toFloat()
            // 2. Channel filter (a doubled ring: one contiguous window).
            hI[hp] = xi; hI[hp + nc] = xi; hQ[hp] = xq; hQ[hp + nc] = xq
            hp = if (hp + 1 == nc) 0 else hp + 1
            if (++phaseDecim < facteur) continue
            phaseDecim = 0
            var fi = 0f; var fq = 0f
            for (t in 0 until nc) { val w = canal[t]; fi += w * hI[hp + t]; fq += w * hQ[hp + t] }
            // 3. Discriminator.
            val a = fi * prevI + fq * prevQ
            val b = fq * prevI - fi * prevQ
            prevI = fi; prevQ = fq
            val hz = atan2(b.toDouble(), a.toDouble()) * hzParRad
            blocP += (fi * fi + fq * fq).toDouble(); blocN++
            if (blocN == blocTaille) bloc()
            // 4. and 5.
            trancheur.pousse(hz)
        }
    }

    private fun bloc() {
        val p = blocP / blocN
        blocN = 0; blocP = 0.0
        if (p <= 0) return
        // The floor follows dips at once and rises slowly (about 1 dB a second).
        plancher = if (plancher < 0 || p < plancher) p else plancher * 1.0023
        niveauDb = 10 * log10(p)
        plancherDb = 10 * log10(plancher)
        presence = p > 2.5 * plancher
        // Only on a signal: the discriminator's mean over noise alone wanders by hundreds of Hz.
        // The levels' middle is the carrier's offset still left; once moved, they move with it.
        if (presence) {
            val av = afcHz
            afcHz = (afcHz + (if (verrouExterne) 0.08 else 0.2) * dc).coerceIn(-afcMaxHz, afcMaxHz)
            trancheur.decale(afcHz - av)
        } else if (abs(afcHz) > 0) {
            // Nothing heard: slowly back to the tracking's frequency.
            afcHz *= 0.998
        }
    }

    fun remetAZero() { afcHz = 0.0; plancher = -1.0; presence = false }
}
