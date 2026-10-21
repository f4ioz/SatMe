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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Locks the whole I/Q stream on a QO-100 beacon: what the LNB's oscillator
 * does to the beacon it does to every station, so the beacon's quick
 * wanderings, followed here sample by sample, are taken out of everything.
 *
 * Why: a new LNB (21 October) made every station wander ±25 Hz within a
 * second, around a slow drift. FT8's tones are 6.25 Hz apart: nothing
 * decoded. The calibration from the panorama (a measure a second, steps)
 * cannot follow that; this can.
 *
 *  - The beacon's channel: the raw stream turned so the beacon's expected
 *    place ([decalageHz], Hz from the stream's centre) is at zero, summed
 *    by blocks down to about 44 kHz, then low-passed (±1.5 kHz) and
 *    decimated by 10: about 4.4 kHz.
 *  - Squared: the middle beacon is BPSK (its phase jumps by π), squaring
 *    leaves a plain carrier at twice its offset. A CW carrier squares the
 *    same way.
 *  - Found by a transform of half a second, then followed by a second-order
 *    phase loop ([bandeHz], about 15 Hz): phase and frequency, sample by
 *    sample.
 *  - The beacon's offset minus its slow mean (20 s) is the quick part: the
 *    stream is turned the other way by it, continuously (no step). The
 *    slow part — the absolute error — stays the calibration's business, so
 *    the panorama still measures it.
 *  - Lost (the loop's agreement falls), the correction fades out over a few
 *    seconds and the beacon is searched again.
 *
 * Receive only. Off unless [actif].
 */
class VerrouBalise(private val rate: Double, private val bandeHz: Double = 25.0) {

    /** On. */
    @Volatile var actif = false

    /** Where the beacon should be, Hz from the stream's centre (the slow calibration's view). */
    @Volatile var decalageHz = 0.0

    /** Locked on the beacon. */
    @Volatile var verrouille = false
        private set

    /** The quick part taken out lately, Hz rms (10 s). */
    @Volatile var gigueHz = 0.0
        private set

    /** The beacon's offset from [decalageHz], slow mean, Hz (what the calibration should still take). */
    @Volatile var ecartLentHz = 0.0
        private set

    /** The correction applied now, Hz. */
    @Volatile var correctionHz = 0.0
        private set

    private val d1 = maxOf(1, (rate / 44_100.0).roundToInt())
    private val rate1 = rate / d1
    private val d2 = 10
    private val fs = rate1 / d2
    private val taps = Dsp.lowPass(63, 1_500.0, rate1)
    private val bufI = FloatArray(taps.size)
    private val bufQ = FloatArray(taps.size)
    private var pos = 0
    private var phase2 = 0

    // The beacon channel's mixer.
    private var mixHz = Double.NaN
    private var mr = 1.0; private var mi = 0.0
    private var pr = 1.0; private var pi = 0.0
    private var compteMix = 0

    // Block sum (first decimation).
    private var si = 0.0; private var sq = 0.0; private var dans = 0

    // Acquisition.
    private val nAcq = 2048
    private val acqI = FloatArray(nAcq)
    private val acqQ = FloatArray(nAcq)
    private var nAcqPleins = 0

    // Phase loop on the squared beacon (rad, rad/sample at fs).
    private var theta2 = 0.0
    private var omega2 = 0.0
    private val k1: Double
    private val k2: Double
    private var accord = 0.0
    private var basDepuis = 0

    // The quick correction: phase, Hz.
    private var lentHz = 0.0
    private var rapideHz = 0.0
    private var rapideLisse = 0.0
    private var depuisVerrou = 0L
    private var dejaVerrouille = false
    private var cr = 1.0; private var ci = 0.0
    private var compteCor = 0
    private var gigue2 = 0.0

    init {
        val zeta = 0.707
        val wn = 2.0 * bandeHz / (zeta + 1.0 / (4.0 * zeta))
        val t = 1.0 / fs
        k1 = 2.0 * zeta * wn * t
        k2 = wn * t * wn * t
    }

    fun reset() {
        verrouille = false; nAcqPleins = 0; accord = 0.0; basDepuis = 0; dejaVerrouille = false
        rapideHz = 0.0; correctionHz = 0.0; gigue2 = 0.0; gigueHz = 0.0
        cr = 1.0; ci = 0.0; si = 0.0; sq = 0.0; dans = 0
        bufI.fill(0f); bufQ.fill(0f); pos = 0; phase2 = 0
    }

    /** [n] raw samples, turned in place by the quick correction. */
    fun process(re: FloatArray, im: FloatArray, n: Int) {
        if (!actif) return
        if (mixHz != decalageHz) {
            mixHz = decalageHz
            val w = -2.0 * PI * mixHz / rate
            pr = cos(w); pi = sin(w)
        }
        // Correction step per raw sample, refreshed at each beacon sample.
        var wr = cos(-2.0 * PI * rapideHz / rate); var wi = sin(-2.0 * PI * rapideHz / rate)
        for (k in 0 until n) {
            val a = re[k].toDouble(); val b = im[k].toDouble()
            // The beacon's channel, from the raw (uncorrected) sample.
            si += a * mr - b * mi
            sq += a * mi + b * mr
            val nr = mr * pr - mi * pi; mi = mr * pi + mi * pr; mr = nr
            if (++compteMix == 4096) {
                compteMix = 0
                val m = sqrt(mr * mr + mi * mi); mr /= m; mi /= m
            }
            if (++dans == d1) {
                bufI[pos] = (si / d1).toFloat(); bufQ[pos] = (sq / d1).toFloat()
                pos = (pos + 1) % taps.size
                si = 0.0; sq = 0.0; dans = 0
                if (++phase2 == d2) {
                    phase2 = 0
                    var zi = 0.0; var zq = 0.0
                    var p = pos
                    for (t in taps.indices) {
                        zi += taps[t] * bufI[p]; zq += taps[t] * bufQ[p]
                        p = (p + 1) % taps.size
                    }
                    echantillon(zi, zq)
                    wr = cos(-2.0 * PI * rapideHz / rate); wi = sin(-2.0 * PI * rapideHz / rate)
                }
            }
            // The stream turned by the quick part.
            re[k] = (a * cr - b * ci).toFloat()
            im[k] = (a * ci + b * cr).toFloat()
            val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
            if (++compteCor == 4096) {
                compteCor = 0
                val m = sqrt(cr * cr + ci * ci); cr /= m; ci /= m
            }
        }
        correctionHz = rapideHz
        gigueHz = sqrt(gigue2)
    }

    /** One sample of the beacon's channel (at [fs]). */
    private fun echantillon(zi: Double, zq: Double) {
        // Squared: BPSK's jumps by π gone.
        val wi = zi * zi - zq * zq
        val wq = 2 * zi * zq
        if (!verrouille) {
            // Fading out what was taken while locked.
            rapideHz *= 1.0 - 1.0 / (2.0 * fs)
            acqI[nAcqPleins] = wi.toFloat(); acqQ[nAcqPleins] = wq.toFloat()
            if (++nAcqPleins == nAcq) { nAcqPleins = 0; acquiert() }
            return
        }
        val c = cos(theta2); val s = sin(theta2)
        // Phase error: the squared beacon against the loop's.
        val er = wi * c + wq * s
        val eq = wq * c - wi * s
        val e = atan2(eq, er)
        val avant = theta2
        theta2 += omega2 + k1 * e
        omega2 += k2 * e
        if (theta2 > PI) theta2 -= 2 * PI else if (theta2 < -PI) theta2 += 2 * PI
        var d = theta2 - avant
        if (d > PI) d -= 2 * PI else if (d < -PI) d += 2 * PI
        // The beacon's frequency now (half the squared one's), Hz.
        val hz = d / 2.0 * fs / (2 * PI)
        // The slow mean over 20 s, over what there is of it just after locking.
        depuisVerrou++
        lentHz += (hz - lentHz) / minOf(20.0 * fs, maxOf(fs, depuisVerrou.toDouble()))
        rapideHz = hz - lentHz
        ecartLentHz = lentHz
        // Measured on the quick part smoothed over 50 ms: the loop's own noise left out.
        rapideLisse += (rapideHz - rapideLisse) / (0.05 * fs)
        gigue2 += (rapideLisse * rapideLisse - gigue2) / (10.0 * fs)
        // Agreement: cos of the error, smoothed over half a second.
        accord += (cos(e) - accord) / (0.5 * fs)
        if (accord < 0.3) {
            if (++basDepuis > 2 * fs) { verrouille = false; basDepuis = 0; nAcqPleins = 0 }
        } else basDepuis = 0
    }

    /** The squared beacon's line in half a second of channel: found, the loop starts on it. */
    private fun acquiert() {
        val re = acqI.copyOf(); val im = acqQ.copyOf()
        fr.f4ioz.satcombo.domain.Ft8Signal.fft(re, im)
        var meilleur = 0; var haut = 0.0; var total = 0.0
        for (k in 0 until nAcq) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += p
            if (p > haut) { haut = p; meilleur = k }
        }
        val moyenne = total / nAcq
        // A line far above the rest (≈ 15 dB), else nothing to lock on.
        if (moyenne <= 0.0 || haut < 30.0 * moyenne) return
        val k = if (meilleur > nAcq / 2) meilleur - nAcq else meilleur
        // Refined between bins (parabola on the powers around it).
        fun pw(i: Int): Double { val j = ((i % nAcq) + nAcq) % nAcq; return re[j].toDouble() * re[j] + im[j].toDouble() * im[j] }
        val a = pw(meilleur - 1); val b = pw(meilleur); val c = pw(meilleur + 1)
        val den = a - 2 * b + c
        val fin = k + if (den != 0.0) 0.5 * (a - c) / den else 0.0
        omega2 = 2 * PI * fin / nAcq
        // Its phase at the last sample of the block.
        theta2 = atan2(acqQ[nAcq - 1].toDouble(), acqI[nAcq - 1].toDouble())
        val hz = omega2 / 2.0 * fs / (2 * PI)
        // A first lock starts with no quick part (the slow mean is where the beacon is); found again
        // after a short loss, the slow mean is kept.
        if (!dejaVerrouille || abs(hz - lentHz) > 200.0) { lentHz = hz; depuisVerrou = 0 }
        rapideHz = hz - lentHz; accord = 0.6; basDepuis = 0; dejaVerrouille = true
        verrouille = true
    }

    /** The beacon channel's rate (for tests). */
    val cadenceBalise: Double get() = fs
}
