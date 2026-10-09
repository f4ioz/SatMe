/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * From the dongle's raw samples (unsigned bytes I, Q at [fsEntree]) to
 * complex samples [facteur] times slower, the satellite moved to the middle:
 * a mixer by [decalageHz], a windowed-sinc low-pass, one output in [facteur].
 *
 * METEOR is 120 kHz wide: at 1 058 400 S/s from the dongle, a fourth
 * (264 600 S/s, 3.7 samples a symbol) keeps it whole and costs the
 * demodulator four times less.
 */
class Decimateur(val fsEntree: Double, val facteur: Int = 4, coupureHz: Double = 100_000.0, ntaps: Int = 48) {
    val fsSortie = fsEntree / facteur

    private val taps = DoubleArray(ntaps) { i ->
        val m = i - (ntaps - 1) / 2.0
        val x = 2 * coupureHz / fsEntree
        val sinc = if (m == 0.0) x else sin(PI * x * m) / (PI * m)
        val w = 0.54 - 0.46 * cos(2 * PI * i / (ntaps - 1))   // Hamming
        sinc * w
    }.let { t -> val s = t.sum(); DoubleArray(t.size) { t[it] / s } }

    private val histI = DoubleArray(ntaps)
    private val histQ = DoubleArray(ntaps)
    private var pos = 0
    private var phase = 0.0
    private var compte = 0

    /**
     * Unsigned-byte pairs ([iq], [n] bytes) to [re]/[im]; returns how many
     * samples came out (at most n / 2 / facteur + 1).
     */
    fun traiteU8(iq: ByteArray, n: Int, decalageHz: Double, re: FloatArray, im: FloatArray): Int {
        var o = 0
        val dphi = -2 * PI * decalageHz / fsEntree
        val cd = cos(dphi); val sd = sin(dphi)
        var c = cos(phase); var s = sin(phase)
        var k = 0
        while (k + 1 < n) {
            val x = ((iq[k].toInt() and 0xFF) - 127.5) / 127.5
            val y = ((iq[k + 1].toInt() and 0xFF) - 127.5) / 127.5
            k += 2
            histI[pos] = x * c - y * s
            histQ[pos] = x * s + y * c
            pos = if (pos + 1 == taps.size) 0 else pos + 1
            val nc = c * cd - s * sd
            s = c * sd + s * cd
            c = nc
            if (++compte == facteur) {
                compte = 0
                var a = 0.0; var b = 0.0
                var j = pos
                for (t in taps.indices) {
                    a += taps[t] * histI[j]; b += taps[t] * histQ[j]
                    j = if (j + 1 == taps.size) 0 else j + 1
                }
                re[o] = a.toFloat(); im[o] = b.toFloat()
                o++
            }
        }
        phase = kotlin.math.atan2(s, c)
        return o
    }
}
