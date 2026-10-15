/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Any rate to [sortie] (16 kHz for speech recognition), as a stream: a
 * windowed-sinc interpolator whose cut-off sits below the lower Nyquist
 * (8 % margin), so nothing above 7.4 kHz folds back into the voice.
 * The kernel is tabulated (256 phases per input sample, linear between).
 */
class Reechantillonneur(val entree: Int, val sortie: Int = 16_000) {

    private val pas = entree.toDouble() / sortie
    /** Cut-off, in cycles per input sample. */
    private val fc = 0.5 * min(1.0, sortie.toDouble() / entree) * 0.92
    /** Half the kernel, in input samples (12 zero crossings each side). */
    private val demi = Math.ceil(ZEROS / (2 * fc)).toInt()
    private val table: FloatArray = FloatArray(demi * PHASES + 2).also { t ->
        for (k in t.indices) {
            val x = k.toDouble() / PHASES
            if (x > demi) { t[k] = 0f; continue }
            val s = if (x == 0.0) 1.0 else sin(2 * PI * fc * x) / (2 * PI * fc * x)
            // Blackman window over ±demi.
            val w = 0.42 + 0.5 * cos(PI * x / demi) + 0.08 * cos(2 * PI * x / demi)
            t[k] = (2 * fc * s * w).toFloat()
        }
    }

    private var tampon = FloatArray(4096)
    private var n = 0
    /** Where the next output falls, in input samples from the buffer's start. */
    private var pos = 0.0

    init { require(entree > 0 && sortie > 0) }

    /** Feeds [x] (signed 16-bit), returns what came out at the new rate (−1…1). */
    fun pousse(x: ShortArray, nx: Int = x.size): FloatArray {
        val f = FloatArray(nx)
        for (i in 0 until nx) f[i] = x[i] / 32768f
        return pousse(f, nx)
    }

    fun pousse(x: FloatArray, nx: Int = x.size): FloatArray {
        if (entree == sortie) return x.copyOf(nx)
        if (n + nx > tampon.size) tampon = tampon.copyOf(maxOf(tampon.size * 2, n + nx))
        System.arraycopy(x, 0, tampon, n, nx)
        n += nx
        // The first outputs see zeros before the start: the buffer begins with demi zeros once.
        if (!amorce) { amorce = true; decale(-demi) }
        val estime = ((n - demi - pos) / pas).toInt().coerceAtLeast(0) + 2
        var out = FloatArray(estime)
        var k = 0
        while (true) {
            val centre = floor(pos).toInt()
            if (centre + demi >= n) break
            val frac = pos - centre
            var acc = 0.0
            val debut = centre - demi + 1
            for (j in debut..centre + demi) {
                val d = Math.abs(j - centre - frac) * PHASES
                val i0 = d.toInt()
                if (i0 >= table.size - 1) continue
                val h = table[i0] + (table[i0 + 1] - table[i0]) * (d - i0).toFloat()
                acc += tampon[j] * h
            }
            if (k == out.size) out = out.copyOf(out.size * 2)
            out[k++] = acc.toFloat()
            pos += pas
        }
        // Drop what no output needs any more.
        val garde = floor(pos).toInt() - demi
        if (garde > 0) decale(garde)
        return if (k == out.size) out else out.copyOf(k)
    }

    private var amorce = false

    /** Moves the buffer's start by [d] samples (negative: zeros put before). */
    private fun decale(d: Int) {
        if (d < 0) {
            val z = -d
            if (n + z > tampon.size) tampon = tampon.copyOf(n + z + 4096)
            System.arraycopy(tampon, 0, tampon, z, n)
            java.util.Arrays.fill(tampon, 0, z, 0f)
            n += z; pos += z
        } else {
            System.arraycopy(tampon, d, tampon, 0, n - d)
            n -= d; pos -= d
        }
    }

    companion object {
        private const val PHASES = 256
        private const val ZEROS = 12
    }
}
