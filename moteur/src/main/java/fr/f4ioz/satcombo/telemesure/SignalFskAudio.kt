/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Made-up two-tone FSK audio, as a USB receiver gives a direct FSK
 * satellite (SO-127's GENESIS FSK): for the test mode and the tests.
 * Never transmitted.
 */
object SignalFskAudio {

    /**
     * [bits] at [debit] bit/s, the tones [ecartHz] apart around [centreHz]
     * (1: the lower tone when [basEstUn], as GENESIS sends it), the centre
     * drifting by [deriveHzS] Hz a second (the Doppler's residue), white
     * noise for an Eb/N0 of [ebN0Db] (in the bit rate's bandwidth), [silenceS]
     * of noise either side; 16-bit samples at [fs], peak about a third of
     * full scale.
     */
    fun audio(bits: IntArray, fs: Int, debit: Double, ecartHz: Double, centreHz: Double, ebN0Db: Double,
              deriveHzS: Double = 0.0, basEstUn: Boolean = true, silenceS: Double = 0.5, graine: Long = 1L): ShortArray {
        val sps = fs / debit
        val avant = (silenceS * fs).toInt()
        val n = avant + (bits.size * sps).toInt() + avant
        val out = ShortArray(n)
        val r = java.util.Random(graine)
        // Signal power 1/2 (unit sine); noise over fs/2 such that Eb/N0 holds: N0 = Eb / 10^(x/10), Eb = 0.5 / debit.
        val n0 = 0.5 / debit / Math.pow(10.0, ebN0Db / 10)
        val sigma = sqrt(n0 * fs / 2)
        val a = 0.33 / (1 + 3 * sigma)
        var ph = 0.0
        for (i in 0 until n) {
            val t = (i - avant) / sps
            var x = sigma * r.nextGaussian()
            if (i >= avant && t < bits.size) {
                val b = bits[t.toInt()]
                val haut = if (basEstUn) b == 0 else b != 0
                val f = centreHz + deriveHzS * (i - avant) / fs + (if (haut) ecartHz / 2 else -ecartHz / 2)
                ph += 2 * PI * f / fs
                if (ph > 2 * PI) ph -= 2 * PI
                x += sin(ph)
            }
            out[i] = (a * x * 32767).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }
}
