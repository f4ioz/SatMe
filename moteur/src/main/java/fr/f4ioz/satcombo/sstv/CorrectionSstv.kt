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
import kotlin.math.roundToInt

/**
 * One picture's noise taken off, nothing added. What a weak FM signal does
 * to Robot 36 (and the other colour modes) and what is done about it:
 *  - **specks of colour** (mostly green: a chroma reading thrown to black by
 *    a click): Robot 36 sends its colour at half the resolution, one chroma
 *    per line shared by two lines, so a colour unlike its 3×3 neighbourhood
 *    is noise, never detail: it takes its neighbourhood's median, the
 *    brightness kept;
 *  - **specks of brightness**: a pixel far from its neighbourhood's median,
 *    unlike the pixels above and below while those agree (the noise runs
 *    along the lines, a slide's own lines and letters are wider), takes
 *    their median;
 *  - **spoilt rows** (a burst: a row unlike both its neighbours while those
 *    agree) are rebuilt from the rows above and below.
 * A clean picture comes out unchanged (each step only touches what breaks
 * from its neighbourhood); no sharpening, nothing invented.
 */
object CorrectionSstv {

    class Options(
        val couleur: Boolean = true,
        /** Every pixel's colour the median of its 3×3 neighbourhood (Robot 36's colour is half as fine as its brightness): the fine colour noise of a weak signal. */
        val couleurDouce: Boolean = true,
        val lumiere: Boolean = true,
        val lignes: Boolean = true,
        /** Colour gap (Cb and Cr summed, 0–510) from which a pixel's colour is a speck. */
        val seuilCouleur: Int = 48,
        /** Brightness gap from which a pixel is a speck. */
        val seuilLumiere: Int = 56,
    )

    /** [px] ARGB, [w]×[h], its first [lignes] rows received (the rest left alone). */
    fun corrige(px: IntArray, w: Int, h: Int, lignes: Int = h, o: Options = Options()): IntArray {
        val n = lignes.coerceIn(0, h)
        if (w < 3 || n < 3) return px.copyOf()
        val tot = w * n
        val y = IntArray(tot); val cb = IntArray(tot); val cr = IntArray(tot)
        for (i in 0 until tot) {
            val c = px[i]; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            y[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
            cb[i] = 128 + ((-168736L * r - 331264L * g + 500000L * b) / 1_000_000L).toInt()
            cr[i] = 128 + ((500000L * r - 418688L * g - 81312L * b) / 1_000_000L).toInt()
        }
        val touche = BooleanArray(tot)
        val t1 = IntArray(9); val t2 = IntArray(9)
        fun med(c: IntArray, x: Int, yy: Int, t: IntArray): Int {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) t[k++] = c[(yy + dy).coerceIn(0, n - 1) * w + (x + dx).coerceIn(0, w - 1)]
            t.sort(); return t[4]
        }
        if (o.couleur) {
            val nb = cb.copyOf(); val nr = cr.copyOf()
            for (yy in 0 until n) for (x in 0 until w) {
                val i = yy * w + x
                val mb = med(cb, x, yy, t1); val mr = med(cr, x, yy, t2)
                // Softly: any colour off its neighbourhood (a rounding step or two left alone); else only the specks.
                val d = abs(cb[i] - mb) + abs(cr[i] - mr)
                if (d > (if (o.couleurDouce) 4 else o.seuilCouleur)) { nb[i] = mb; nr[i] = mr; touche[i] = true }
            }
            System.arraycopy(nb, 0, cb, 0, tot); System.arraycopy(nr, 0, cr, 0, tot)
        }
        if (o.lumiere) {
            val ny = y.copyOf()
            for (yy in 1 until n - 1) for (x in 0 until w) {
                val i = yy * w + x
                val m = med(y, x, yy, t1)
                val haut = y[i - w]; val bas = y[i + w]
                if (abs(y[i] - m) > o.seuilLumiere && abs(haut - bas) < o.seuilLumiere / 2 &&
                    abs(y[i] - haut) > o.seuilLumiere && abs(y[i] - bas) > o.seuilLumiere) { ny[i] = m; touche[i] = true }
            }
            System.arraycopy(ny, 0, y, 0, tot)
        }
        val out = px.copyOf()
        for (i in 0 until tot) if (touche[i]) out[i] = rgb(y[i], cb[i], cr[i])
        if (o.lignes) repareLignes(out, w, n)
        return out
    }

    private fun rgb(y: Int, cb: Int, cr: Int): Int {
        val u = cb - 128; val v = cr - 128
        val r = (y + 1.402 * v).roundToInt().coerceIn(0, 255)
        val g = (y - 0.344136 * u - 0.714136 * v).roundToInt().coerceIn(0, 255)
        val b = (y + 1.772 * u).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    private fun ecart(px: IntArray, w: Int, a: Int, b: Int): Int {
        var s = 0L
        for (x in 0 until w) s += abs(lum(px[a * w + x]) - lum(px[b * w + x]))
        return (s / w).toInt()
    }

    /** One or two rows far from both neighbours while those agree: rebuilt from them (as [SstvNettoyage], after the specks). */
    private fun repareLignes(px: IntArray, w: Int, n: Int) {
        val src = px.copyOf()
        var yy = 1
        while (yy < n - 1) {
            var fait = 0
            for (k in 1..2) {
                val bas = yy + k
                if (bas >= n) break
                val lim = 3 * ecart(src, w, yy - 1, bas) + 24
                if ((yy until bas).all { r -> ecart(src, w, r, yy - 1) > lim && ecart(src, w, r, bas) > lim }) {
                    for (r in yy until bas) for (x in 0 until w) {
                        val a = src[(yy - 1) * w + x]; val b = src[bas * w + x]; val p = r - yy + 1; val t = k + 1
                        var c = 0xFF shl 24
                        for (s in intArrayOf(16, 8, 0)) c = c or (((((a shr s) and 0xFF) * (t - p) + ((b shr s) and 0xFF) * p + t / 2) / t) shl s)
                        px[r * w + x] = c
                    }
                    fait = k; break
                }
            }
            yy += if (fait > 0) fait + 1 else 1
        }
    }
}
