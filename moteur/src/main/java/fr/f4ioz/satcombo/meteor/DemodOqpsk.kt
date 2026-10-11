/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The METEOR-M LRPT demodulator: offset QPSK, 72 000 symbols a second, root
 * raised cosine 0.5, from complex baseband samples to soft symbols.
 *
 * - **Carrier, coarse**: squared, the signal shows a line at twice its
 *   offset; found by FFT twice a second, it sets the mixer (Doppler included).
 * - **Level**: a slow automatic gain.
 * - **Matched filter**: root raised cosine.
 * - **Offset**: in OQPSK the Q rail lags I by half a symbol; I is taken half a
 *   symbol later so that both are sampled at the same instant.
 * - **Timing**: Gardner detector, two strobes a symbol, cubic interpolation.
 * - **Carrier, fine**: a QPSK Costas loop on the symbols.
 *
 * Out: two signed bytes per symbol (I then Q), the phase still ambiguous by a
 * multiple of 90° — the deframer tries them all.
 */
class DemodOqpsk(
    val fs: Double,
    val debit: Double = 72_000.0,
    private val offset: Boolean = true
) {
    val sps = fs / debit

    // --- matched filter
    private val taps: DoubleArray = rrc(sps, 0.5, 8)
    private val histI = DoubleArray(taps.size)
    private val histQ = DoubleArray(taps.size)
    private var hpos = 0

    // --- mixer
    private var ncoPhase = 0.0
    /** Carrier offset being removed, Hz (coarse estimate + loop). */
    var frequenceHz = 0.0; private set

    // --- coarse estimate
    private val estN = Integer.highestOneBit((fs * 0.08).toInt()).coerceAtLeast(1024)
    private val estRe = DoubleArray(estN)
    private val estIm = DoubleArray(estN)
    private val estP = DoubleArray(estN)
    private var estK = 0
    private var estBlocs = 0
    /** How clear the carrier line was at the last estimate (peak / median). */
    var raieClarte = 0.0; private set

    // --- level
    private var puissance = 1e-6
    private var gain = 1.0

    // --- filtered samples kept for interpolation
    private val ring = 64
    private val bufI = DoubleArray(ring)
    private val bufQ = DoubleArray(ring)
    private var n = 0L                // samples filtered so far

    // --- timing
    private var omega = sps
    private var prochain = 8.0 + sps   // absolute time (in samples) of the next strobe
    private var milieu = false
    private var midI = 0.0; private var midQ = 0.0
    private var precI = 0.0; private var precQ = 0.0
    private val kp = 0.01
    private val ki = 0.00005

    // --- Costas
    private var phase = 0.0
    private var dfreq = 0.0
    private val alpha: Double
    private val beta: Double
    private var amplitude = 1.0

    var trace: ((String) -> Unit)? = null

    /** Symbols out so far. */
    var symboles = 0L; private set
    /** A measure of constellation quality: the mean |I| over its spread, in dB (≈ symbol SNR). */
    var qualiteDb = 0.0; private set
    private var mAbs = 0.0
    private var mVar = 0.0

    init {
        val bw = 0.005
        val damp = 0.707
        val den = 1 + 2 * damp * bw + bw * bw
        alpha = 4 * damp * bw / den
        beta = 4 * bw * bw / den
    }

    /**
     * Demodulates [count] complex samples ([re], [im]); soft symbols go to
     * [sortie] (I, Q, I, Q…) from [debut]. Returns the number of bytes written
     * (at most 2 × count / sps + 4).
     */
    fun traite(re: FloatArray, im: FloatArray, count: Int, sortie: ByteArray, debut: Int = 0): Int {
        var o = debut
        val dphi = -2 * PI * frequenceHz / fs
        var ph = ncoPhase
        val cph = cos(dphi); val sph = sin(dphi)
        var c = cos(ph); var s = sin(ph)
        for (k in 0 until count) {
            val x = re[k].toDouble(); val y = im[k].toDouble()
            // Mixer.
            var mi = x * c - y * s
            var mq = x * s + y * c
            val nc = c * cph - s * sph
            s = c * sph + s * cph
            c = nc
            // Coarse estimate on the raw samples (squared).
            estRe[estK] = x * x - y * y
            estIm[estK] = 2 * x * y
            if (++estK == estN) { estK = 0; estime() }
            // Level.
            puissance += (mi * mi + mq * mq - puissance) * 2e-5
            gain = 1.0 / sqrt(puissance + 1e-20)
            mi *= gain; mq *= gain
            // Matched filter.
            histI[hpos] = mi; histQ[hpos] = mq
            hpos = if (hpos + 1 == taps.size) 0 else hpos + 1
            var fi = 0.0; var fq = 0.0
            var j = hpos
            for (t in taps.indices) {
                fi += taps[t] * histI[j]; fq += taps[t] * histQ[j]
                j = if (j + 1 == taps.size) 0 else j + 1
            }
            // Carrier phase taken off here, before the half-symbol offset: on a rotated
            // signal each rail carries some of the other, and the offset would mix them.
            val cp = cos(phase); val sp = sin(phase)
            val r = (n % ring).toInt()
            bufI[r] = fi * cp + fq * sp
            bufQ[r] = -fi * sp + fq * cp
            phase += dfreq / sps
            if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
            n++
            o = strobes(sortie, o)
        }
        // Keep the mixer's phase from block to block (renormalised).
        ph = atan2(s, c)
        ncoPhase = ph
        return o - debut
    }

    private fun strobes(sortie: ByteArray, debut: Int): Int {
        var o = debut
        val retard = if (offset) sps / 2 else 0.0
        // Interpolation needs samples t-1 … t+2 of the latest filtered ones.
        while (prochain + 2 < n) {
            val t = prochain
            val vq = interp(bufQ, t)
            val vi = interp(bufI, t - retard)
            if (milieu) {
                midI = vi; midQ = vq
                prochain += omega / 2
            } else {
                // Gardner: the mid strobe sits on the transition between the last two symbols.
                val e = (midI * (precI - vi) + midQ * (precQ - vq)).coerceIn(-1.0, 1.0)
                precI = vi; precQ = vq
                omega += ki * e
                omega = omega.coerceIn(sps * 0.995, sps * 1.005)
                prochain += omega / 2 + kp * e
                o = costas(vi, vq, sortie, o)
            }
            milieu = !milieu
        }
        return o
    }

    private fun interp(buf: DoubleArray, t: Double): Double {
        val i = kotlin.math.floor(t).toLong()
        val mu = t - i
        val y0 = buf[Math.floorMod(i - 1, ring.toLong()).toInt()]
        val y1 = buf[Math.floorMod(i, ring.toLong()).toInt()]
        val y2 = buf[Math.floorMod(i + 1, ring.toLong()).toInt()]
        val y3 = buf[Math.floorMod(i + 2, ring.toLong()).toInt()]
        // Catmull-Rom cubic.
        val a = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3
        val b = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3
        val cc = -0.5 * y0 + 0.5 * y2
        return ((a * mu + b) * mu + cc) * mu + y1
    }

    private fun costas(vi: Double, vq: Double, sortie: ByteArray, debut: Int): Int {
        val i = vi
        val q = vq
        val e = (if (i >= 0) q else -q) - (if (q >= 0) i else -i)
        dfreq += beta * e
        dfreq = dfreq.coerceIn(-0.3, 0.3)
        phase += alpha * e
        // Soft symbols: the mean |I| maps to about 64.
        val m = (abs(i) + abs(q)) / 2
        amplitude += (m - amplitude) * 1e-3
        val k = 64.0 / (amplitude + 1e-12)
        sortie[debut] = (i * k).toInt().coerceIn(-127, 127).toByte()
        sortie[debut + 1] = (q * k).toInt().coerceIn(-127, 127).toByte()
        // Quality.
        mAbs += (abs(i) - mAbs) * 1e-3
        mVar += ((abs(i) - mAbs) * (abs(i) - mAbs) - mVar) * 1e-3
        symboles++
        if (symboles % 4096 == 0L) {
            qualiteDb = 20 * kotlin.math.log10(mAbs / (sqrt(mVar) + 1e-12))
            trace?.invoke("sym=$symboles omega=%.4f q=%.1f dfreqHz=%.1f amp=%.3f".format(omega - sps, qualiteDb, dfreq * debit / (2 * PI), amplitude))
        }
        return debut + 2
    }

    /** Twice the carrier offset is a line in the squared signal's spectrum. */
    private fun estime() {
        val re = estRe.copyOf(); val im = estIm.copyOf()
        // Hann window.
        for (i in 0 until estN) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / estN)
            re[i] *= w; im[i] *= w
        }
        Fft.transform(re, im)
        for (i in 0 until estN) estP[i] += hypot(re[i], im[i])
        if (++estBlocs < 6) return
        estBlocs = 0
        // Search within ±10 kHz of carrier offset (±20 kHz on the squared signal).
        val bin = fs / estN
        val lim = (20_000 / bin).toInt().coerceAtMost(estN / 2 - 1)
        var best = 0; var bv = -1.0
        for (k in -lim..lim) {
            val v = estP[Math.floorMod(k, estN)]
            if (v > bv) { bv = v; best = k }
        }
        val tri = estP.copyOf(); tri.sort()
        raieClarte = bv / (tri[estN / 2] + 1e-20)
        estP.fill(0.0)
        if (raieClarte < 15) return
        // The mixer takes the estimate; the Costas loop follows what is left.
        val f = best * bin / 2
        if (abs(f - frequenceHz) > 150) frequenceHz = f
    }

    companion object {
        /** Root raised cosine, unit energy, ±[span] symbols at [sps] samples a symbol. */
        fun rrc(sps: Double, alpha: Double, span: Int): DoubleArray {
            val half = kotlin.math.ceil(span * sps).toInt()
            val h = DoubleArray(2 * half + 1)
            for (i in h.indices) {
                val t = (i - half) / sps
                h[i] = when {
                    abs(t) < 1e-9 -> 1 - alpha + 4 * alpha / PI
                    abs(abs(t) - 1 / (4 * alpha)) < 1e-9 -> alpha / sqrt(2.0) *
                        ((1 + 2 / PI) * sin(PI / (4 * alpha)) + (1 - 2 / PI) * cos(PI / (4 * alpha)))
                    else -> (sin(PI * t * (1 - alpha)) + 4 * alpha * t * cos(PI * t * (1 + alpha))) /
                        (PI * t * (1 - (4 * alpha * t) * (4 * alpha * t)))
                }
            }
            val e = sqrt(h.sumOf { it * it })
            for (i in h.indices) h[i] /= e
            return h
        }
    }
}
