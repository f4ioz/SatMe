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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A made-up telemetry signal as a receiver's audio, for the test mode and
 * the tests: the bits differentially encoded (a 1 turns the phase), plain
 * or Manchester, gently filtered, on a carrier at [porteuseHz] drifting by
 * [deriveHzS] Hz/s, with white noise for [ebN0Db].
 */
object SignalEssai {

    /** The beacon's text as a coded frame and its matching Phase 3 block. */
    fun texteBalise(texte: String, octets: Int): ByteArray {
        val b = ByteArray(octets) { ' '.code.toByte() }
        texte.toByteArray(Charsets.US_ASCII).copyInto(b, 0, 0, minOf(octets, texte.length))
        return b
    }

    /** A FUNcube-1 frame: id 2 (flight model), [type], the real-time values given as raw counts, payload [charge]. */
    fun trameFuncube(type: Int, champs: List<Pair<Int, Long>>, charge: ByteArray = ByteArray(200)): ByteArray {
        val d = ByteArray(256)
        var pos = 0
        fun ecrit(n: Int, v: Long) { for (k in n - 1 downTo 0) { if ((v shr k) and 1L == 1L) d[pos / 8] = (d[pos / 8].toInt() or (0x80 ushr (pos % 8))).toByte(); pos++ } }
        ecrit(2, 2); ecrit(6, type.toLong())
        for ((n, v) in champs) ecrit(n, v)
        charge.copyInto(d, 56, 0, minOf(200, charge.size))
        return d
    }

    fun audio(
        bits: IntArray,
        debit: Double,
        manchester: Boolean,
        fs: Int = 44_100,
        porteuseHz: Double = 1_500.0,
        deriveHzS: Double = 0.0,
        ebN0Db: Double = 20.0,
        avantS: Double = 1.5,
        apresS: Double = 0.5,
        graine: Long = 1L,
        amplitude: Double = 6_000.0,
    ): ShortArray {
        val rnd = java.util.Random(graine)
        // Differential: a 1 turns the phase.
        val niveaux = IntArray(bits.size)
        var e = 1
        for (i in bits.indices) { if (bits[i] == 1) e = -e; niveaux[i] = e }
        val spb = fs / debit
        val avant = (avantS * fs).toInt(); val apres = (apresS * fs).toInt()
        val n = avant + (bits.size * spb).toInt() + apres
        // Before and after the data: alternating ones (the beacon idles on a steady pattern too).
        val bb = DoubleArray(n)
        for (k in 0 until n) {
            val t = (k - avant) / spb
            val i = Math.floor(t).toInt()
            val niv = if (i in bits.indices) niveaux[i] else if (i < 0) (if (Math.floorMod(i, 2) == 0) 1 else -1) else niveaux.lastOrNull() ?: 1
            val demi = if (manchester && t - i >= 0.5) -1 else 1
            bb[k] = (niv * demi).toDouble()
        }
        // A gentle low-pass (one pole each way) for the band-limited transmitter.
        val a = 1 - kotlin.math.exp(-2 * PI * 2.5 * debit / fs)
        var y = 0.0
        for (k in 0 until n) { y += a * (bb[k] - y); bb[k] = y }
        for (k in n - 1 downTo 0) { y += a * (bb[k] - y); bb[k] = y }
        // Noise: Eb/N0 over the audio band (a real signal: N0/2 per Hz each side).
        val eb = amplitude * amplitude / 2 * spb / fs
        val n0 = eb / 10.0.pow(ebN0Db / 10)
        val sigma = sqrt(n0 * fs / 2)
        val out = ShortArray(n)
        var ph = 0.0
        for (k in 0 until n) {
            val f = porteuseHz + deriveHzS * k / fs
            ph += 2 * PI * f / fs
            if (ph > PI) ph -= 2 * PI
            val v = amplitude * bb[k] * cos(ph) + sigma * gauss(rnd)
            out[k] = v.coerceIn(-32_000.0, 32_000.0).toInt().toShort()
        }
        return out
    }

    private fun gauss(r: java.util.Random): Double {
        val u = r.nextDouble().coerceAtLeast(1e-12); val v = r.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }
}
