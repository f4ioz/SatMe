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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A complex mixer on interleaved 16-bit I/Q: the baseband moved by
 * [decalageHz] at [debit] samples per second, the phase carried from one
 * buffer to the next (a rotating phasor, renormalised each buffer).
 */
class Melangeur(val decalageHz: Double, val debit: Double) {
    private val pr = cos(2 * PI * decalageHz / debit)
    private val pi = sin(2 * PI * decalageHz / debit)
    private var c = 1.0
    private var s = 0.0

    /** [paires] I/Q pairs of [b] mixed in place, held within 16 bits. */
    fun applique(b: ShortArray, paires: Int = b.size / 2) {
        for (k in 0 until paires) {
            val i = b[2 * k].toDouble()
            val q = b[2 * k + 1].toDouble()
            b[2 * k] = (i * c - q * s).roundToInt().coerceIn(-32767, 32767).toShort()
            b[2 * k + 1] = (i * s + q * c).roundToInt().coerceIn(-32767, 32767).toShort()
            val nc = c * pr - s * pi
            s = c * pi + s * pr
            c = nc
        }
        val m = 1.0 / sqrt(c * c + s * s)
        c *= m; s *= m
    }
}
