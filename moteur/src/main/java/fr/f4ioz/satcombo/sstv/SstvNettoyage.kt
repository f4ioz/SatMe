/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs

/**
 * Cleans a decoded SSTV picture of what a satellite pass does to it.
 *
 * The sound needs nothing more: the discriminator reads frequency, not level
 * (a weak signal decodes like a strong one), keeps only the SSTV band, and
 * every sync pulse already re-aligns the line (slant) and recalibrates the
 * frequency offset. What is left is in the picture, along the lines — the
 * direction the picture is sent in:
 *  - a fade or a burst of noise ruins one line, or two (PD modes send two
 *    lines per block): the line is replaced by its neighbours;
 *  - shorter bursts leave dashes on a line: those pixels are replaced too.
 *
 * Only what clearly breaks from the lines above and below, while those two
 * agree, is touched: a clean picture comes out unchanged, and a detail one
 * pixel wide across the lines is kept. The rows not received (a picture cut
 * short by LOS) are left alone.
 */
object SstvNettoyage {

    /** Luma gap, 0–255, from which a pixel breaks from the lines around it. */
    const val SEUIL = 64

    fun nettoie(px: IntArray, w: Int, h: Int, lignes: Int = h): IntArray {
        val n = lignes.coerceIn(0, h)
        val out = px.copyOf()
        if (w <= 0 || n < 3) return out
        // Whole lines first, then dashes, both judged on the picture received.
        repareLignes(px, out, w, n)
        val etape = out.copyOf()
        repareTirets(etape, out, w, n)
        return out
    }

    private fun luma(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /** Each channel of [a] and [b] mixed, [b] weighing [pb] out of [tot]. */
    private fun mele(a: Int, b: Int, pb: Int, tot: Int): Int {
        var r = 0xFF shl 24
        for (s in intArrayOf(16, 8, 0)) {
            val va = (a shr s) and 0xFF; val vb = (b shr s) and 0xFF
            r = r or (((va * (tot - pb) + vb * pb + tot / 2) / tot) shl s)
        }
        return r
    }

    /** Mean luma gap between two rows. */
    private fun ecart(px: IntArray, w: Int, y1: Int, y2: Int): Int {
        var s = 0L
        for (x in 0 until w) s += abs(luma(px[y1 * w + x]) - luma(px[y2 * w + x]))
        return (s / w).toInt()
    }

    /**
     * One or two lines far from both their neighbours, while those resemble
     * each other: lost to noise, rebuilt from the neighbours.
     */
    private fun repareLignes(src: IntArray, out: IntArray, w: Int, n: Int) {
        var y = 1
        while (y < n - 1) {
            var fait = 0
            for (k in 1..2) {
                val bas = y + k
                if (bas >= n) break
                val voisins = ecart(src, w, y - 1, bas)
                val limite = 3 * voisins + SEUIL / 2
                val casse = (y until bas).all { r ->
                    ecart(src, w, r, y - 1) > limite && ecart(src, w, r, bas) > limite
                }
                if (casse) {
                    for (r in y until bas) for (x in 0 until w)
                        out[r * w + x] = mele(src[(y - 1) * w + x], src[bas * w + x], r - y + 1, k + 1)
                    fait = k
                    break
                }
            }
            y += if (fait > 0) fait + 1 else 1
        }
    }

    /**
     * Pixels (on one line, or two) far from the pixels above and below, on
     * the same side, while those two agree: a dash of noise.
     */
    private fun repareTirets(src: IntArray, out: IntArray, w: Int, n: Int) {
        for (x in 0 until w) {
            var y = 1
            while (y < n - 1) {
                var fait = 0
                for (k in 1..2) {
                    val bas = y + k
                    if (bas >= n) break
                    val h = luma(src[(y - 1) * w + x]); val b = luma(src[bas * w + x])
                    if (abs(h - b) >= SEUIL / 3) continue
                    val casse = (y until bas).all { r ->
                        val v = luma(src[r * w + x])
                        (v - h > SEUIL && v - b > SEUIL) || (h - v > SEUIL && b - v > SEUIL)
                    }
                    if (casse) {
                        for (r in y until bas)
                            out[r * w + x] = mele(src[(y - 1) * w + x], src[bas * w + x], r - y + 1, k + 1)
                        fait = k
                        break
                    }
                }
                y += if (fait > 0) fait + 1 else 1
            }
        }
    }
}
