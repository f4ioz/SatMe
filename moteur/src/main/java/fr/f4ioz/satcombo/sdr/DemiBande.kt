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

/**
 * The half-band low-pass (cut at a quarter of the rate) by which a
 * SatMe-Node brings its I/Q down by two at each step, and SatMe-PC up
 * again: every other tap is zero, the centre one is ½ — only [K] products
 * a side per sample, which a Raspberry Pi takes at a mega-sample.
 */
object DemiBande {
    /** Odd taps a side (31 taps in all). */
    const val K = 8

    /** h(1), h(3), … h(2K−1): a windowed sinc (Blackman), normalised so that the centre tap is ½ and the sum 1. */
    val PAIRS: FloatArray = run {
        val n = 4 * K - 1
        val milieu = (n - 1) / 2
        fun w(i: Int) = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
        val brut = DoubleArray(K) { k -> val m = 2 * k + 1; sin(PI * m / 2) / (PI * m) * w(milieu + m) }
        // Sum of all taps = ½ + 2·Σ odd: scaled to 1 (DC passes as it is).
        val s = 2 * brut.sum()
        FloatArray(K) { (brut[it] * 0.5 / s).toFloat() }
    }

    internal fun borne(v: Float): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
}

/**
 * Interleaved 16-bit I/Q down by 2^[etages] in half-band steps (none: copied
 * as it comes). The state is kept between blocks: a stream cut anywhere
 * gives the same samples.
 */
class DecimateurDemiBande(val etages: Int) {
    init { require(etages in 0..4) }
    val facteur: Int get() = 1 shl etages

    private class Etage {
        private val n = 4 * DemiBande.K - 1
        private val hI = FloatArray(2 * n); private val hQ = FloatArray(2 * n)
        private var pos = 0
        private var parite = 0

        /** One pair in; true when an output came ([sortie] filled). */
        fun pousse(i: Float, q: Float, sortie: FloatArray): Boolean {
            hI[pos] = i; hI[pos + n] = i; hQ[pos] = q; hQ[pos + n] = q
            pos = if (pos + 1 == n) 0 else pos + 1
            parite = parite xor 1
            if (parite == 0) return false
            // The window: the oldest at pos, the newest at pos + n − 1; the centre in the middle.
            val c = pos + 2 * DemiBande.K - 1
            var a = 0.5f * hI[c]; var b = 0.5f * hQ[c]
            val h = DemiBande.PAIRS
            for (k in 0 until DemiBande.K) {
                val d = 2 * k + 1
                a += h[k] * (hI[c - d] + hI[c + d]); b += h[k] * (hQ[c - d] + hQ[c + d])
            }
            sortie[0] = a; sortie[1] = b
            return true
        }
    }

    private val chaine = Array(etages) { Etage() }
    private val tmp = FloatArray(2)

    /** [n] shorts (pairs) of [entree] into [sortie] (n / [facteur] at most, plus one pair); returns the shorts written. */
    fun traite(entree: ShortArray, n: Int, sortie: ShortArray): Int {
        if (etages == 0) { System.arraycopy(entree, 0, sortie, 0, n and 1.inv()); return n and 1.inv() }
        var o = 0
        var k = 0
        while (k + 1 < n) {
            var i = entree[k].toFloat(); var q = entree[k + 1].toFloat()
            k += 2
            var sort = true
            for (e in chaine) {
                if (!e.pousse(i, q, tmp)) { sort = false; break }
                i = tmp[0]; q = tmp[1]
            }
            if (sort) { sortie[o++] = DemiBande.borne(i); sortie[o++] = DemiBande.borne(q) }
        }
        return o
    }
}

/**
 * Interleaved 16-bit I/Q up by 2^[etages] in half-band steps: zeros put in
 * between, the images beyond the old band filtered away. SatMe-PC takes
 * back a node's lighter stream at the rate its reception runs at.
 */
class InterpolateurDemiBande(val etages: Int) {
    init { require(etages in 0..4) }
    val facteur: Int get() = 1 shl etages

    private class Etage {
        private val n = 2 * DemiBande.K
        private val hI = FloatArray(2 * n); private val hQ = FloatArray(2 * n)
        private var pos = 0

        /** One pair in, two out ([sortie]: I0 Q0 I1 Q1), K pairs late. */
        fun pousse(i: Float, q: Float, sortie: FloatArray) {
            hI[pos] = i; hI[pos + n] = i; hQ[pos] = q; hQ[pos + n] = q
            pos = if (pos + 1 == n) 0 else pos + 1
            // x[j−K+1 … j+K] from pos to pos + n − 1; the even output is x[j] (index pos + K − 1).
            val j = pos + DemiBande.K - 1
            sortie[0] = hI[j]; sortie[1] = hQ[j]
            var a = 0f; var b = 0f
            val h = DemiBande.PAIRS
            for (k in 1..DemiBande.K) {
                // x[j + 1 − k] + x[j + k]
                a += h[k - 1] * (hI[j + 1 - k] + hI[j + k]); b += h[k - 1] * (hQ[j + 1 - k] + hQ[j + k])
            }
            sortie[2] = 2 * a; sortie[3] = 2 * b
        }
    }

    private val chaine = Array(etages) { Etage() }
    private val a = FloatArray(2 * (1 shl etages))
    private val b = FloatArray(2 * (1 shl etages))
    private val t = FloatArray(4)

    /** [n] shorts of [entree] into [sortie] (n × [facteur] shorts); returns the shorts written. */
    fun traite(entree: ShortArray, n: Int, sortie: ShortArray): Int {
        val m = n and 1.inv()
        if (etages == 0) { System.arraycopy(entree, 0, sortie, 0, m); return m }
        var o = 0
        var k = 0
        while (k < m) {
            a[0] = entree[k].toFloat(); a[1] = entree[k + 1].toFloat()
            k += 2
            var paires = 1
            var src = a; var dst = b
            for (e in chaine) {
                for (p in 0 until paires) {
                    e.pousse(src[2 * p], src[2 * p + 1], t)
                    dst[4 * p] = t[0]; dst[4 * p + 1] = t[1]; dst[4 * p + 2] = t[2]; dst[4 * p + 3] = t[3]
                }
                paires *= 2
                val x = src; src = dst; dst = x
            }
            for (p in 0 until 2 * paires) sortie[o++] = DemiBande.borne(src[p])
        }
        return o
    }
}
