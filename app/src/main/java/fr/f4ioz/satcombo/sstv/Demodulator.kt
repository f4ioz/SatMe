/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a stream of PCM samples into a stream of instantaneous frequencies.
 *
 * SSTV carries the picture as frequency alone (1500 Hz black … 2300 Hz white,
 * 1200 Hz sync), so the whole job is a clean FM discriminator. The audio is
 * mixed down against a 1900 Hz complex carrier, low-pass filtered to keep only
 * the ±800 Hz that matters, and the phase difference between consecutive
 * samples gives the frequency directly:
 *
 *     f[n] = fc + (fs / 2pi) * arg( z[n] * conj(z[n-1]) )
 *
 * This is deliberately sample-by-sample rather than FFT-based: the fastest mode
 * spends 0.19 ms on a pixel (about 8 samples at 44.1 kHz), which no practical
 * FFT window can resolve, and a discriminator has no window at all.
 *
 * Amplitude is tracked too, so the caller can tell a real signal from the hiss
 * between passes without running a second detector.
 */
class Demodulator(private val sampleRate: Int) {

    /** Local oscillator phase increment per sample. */
    private val dPhase = 2.0 * PI * SstvTone.CARRIER / sampleRate
    private var phase = 0.0

    // Short on purpose. The filter only has to kill the mixer image (which
    // lands at 3100-4200 Hz) — making it any sharper would smear the picture
    // horizontally, and the fastest mode spends 0.19 ms on a pixel.
    private val taps: DoubleArray = lowPass(
        n = (sampleRate / 850).coerceAtLeast(19).let { if (it % 2 == 0) it + 1 else it },
        cutoff = 1450.0, sampleRate = sampleRate)

    /** Samples on each side of the filter's centre: how far a tone bleeds. */
    val halfLength: Int get() = taps.size / 2

    /** Same for the narrow reading — also its extra delay over the wide one. */
    val halfLengthNarrow: Int get() = tapsN.size / 2

    private val delayI = DoubleArray(taps.size)
    private val delayQ = DoubleArray(taps.size)
    private var delayPos = 0

    private var prevI = 0.0
    private var prevQ = 0.0
    private var primed = false

    // **A second, narrow reading of the same signal**, for everything that has
    // to survive noise: header, sync pulses, tuning, and the pixels of a weak
    // picture: 2 ms, ±900 Hz around 1900. The wide one above stays for the
    // pixels of a good signal, where it is sharper; alone, it let full-band
    // noise (a phone microphone in a room) break the syncs of a PD 120 at
    // 10 dB SNR, a signal still readable.
    private val tapsN: DoubleArray = lowPass(
        n = (sampleRate / 500).coerceAtLeast(19).let { if (it % 2 == 0) it + 1 else it },
        cutoff = 900.0, sampleRate = sampleRate)
    private val delayNI = DoubleArray(tapsN.size)
    private val delayNQ = DoubleArray(tapsN.size)
    private var delayNPos = 0
    private var prevNI = 0.0
    private var prevNQ = 0.0

    private val hzPerRad = sampleRate / (2.0 * PI)

    /** Envelope of the filtered signal, so silence is not mistaken for sync. */
    var level: Double = 0.0
        private set

    fun reset() {
        phase = 0.0
        delayI.fill(0.0); delayQ.fill(0.0); delayPos = 0
        prevI = 0.0; prevQ = 0.0; primed = false; level = 0.0
        delayNI.fill(0.0); delayNQ.fill(0.0); delayNPos = 0
        prevNI = 0.0; prevNQ = 0.0
    }

    /**
     * Demodulates [count] samples of [pcm] into [out], which must hold at least
     * [count] entries. Returns the number of frequency samples written (always
     * [count] — the very first one after a reset is simply 1900 Hz).
     */
    fun process(pcm: ShortArray, count: Int, out: FloatArray, outNarrow: FloatArray? = null): Int {
        for (n in 0 until count) {
            val s = pcm[n] / 32768.0

            // Complex mix down to baseband.
            val c = cos(phase); val sn = sin(phase)
            phase += dPhase
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            // Push into the FIR delay lines.
            delayI[delayPos] = s * c
            delayQ[delayPos] = -s * sn
            if (outNarrow != null) {
                delayNI[delayNPos] = s * c
                delayNQ[delayNPos] = -s * sn
                delayNPos = if (delayNPos == 0) tapsN.size - 1 else delayNPos - 1
                var ni = 0.0; var nq = 0.0
                var kk = delayNPos + 1
                if (kk >= tapsN.size) kk = 0
                for (t in tapsN.indices) {
                    ni += tapsN[t] * delayNI[kk]
                    nq += tapsN[t] * delayNQ[kk]
                    kk++
                    if (kk >= tapsN.size) kk = 0
                }
                outNarrow[n] = if (!primed) SstvTone.CARRIER.toFloat() else {
                    val re = ni * prevNI + nq * prevNQ
                    val im = nq * prevNI - ni * prevNQ
                    val d = if (re == 0.0 && im == 0.0) 0.0 else atan2(im, re)
                    (SstvTone.CARRIER + d * hzPerRad).toFloat()
                }
                prevNI = ni; prevNQ = nq
            }
            delayPos = if (delayPos == 0) taps.size - 1 else delayPos - 1

            var i = 0.0; var q = 0.0
            var k = delayPos + 1
            if (k >= taps.size) k = 0
            for (t in taps.indices) {
                i += taps[t] * delayI[k]
                q += taps[t] * delayQ[k]
                k++
                if (k >= taps.size) k = 0
            }

            val mag = i * i + q * q
            level += (mag - level) * 0.001

            out[n] = if (!primed) {
                primed = true
                SstvTone.CARRIER.toFloat()
            } else {
                // arg(z[n] * conj(z[n-1]))
                val re = i * prevI + q * prevQ
                val im = q * prevI - i * prevQ
                val d = if (re == 0.0 && im == 0.0) 0.0 else atan2(im, re)
                (SstvTone.CARRIER + d * hzPerRad).toFloat()
            }
            prevI = i; prevQ = q
        }
        return count
    }

    companion object {
        /** Hamming-windowed sinc, unity gain at DC. */
        fun lowPass(n: Int, cutoff: Double, sampleRate: Int): DoubleArray {
            val h = DoubleArray(n)
            val mid = (n - 1) / 2.0
            val wc = 2.0 * PI * cutoff / sampleRate
            var sum = 0.0
            for (k in 0 until n) {
                val x = k - mid
                val sinc = if (x == 0.0) wc / PI else sin(wc * x) / (PI * x)
                val win = 0.54 - 0.46 * cos(2.0 * PI * k / (n - 1))
                h[k] = sinc * win
                sum += h[k]
            }
            for (k in 0 until n) h[k] /= sum
            return h
        }
    }
}
