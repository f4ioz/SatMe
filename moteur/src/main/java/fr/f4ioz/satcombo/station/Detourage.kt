/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A photo of a piece of hardware cut out of its background, for the
 * « Montage » drawing: the background's colour read on the border, the
 * background reached from the border (flood fill within a colour distance),
 * the large enclosed patches of it removed too (a hole through a handle) but
 * never the small light areas inside the object (a white label), a soft
 * edge of one or two pixels, the near-background halo faded, the object
 * cropped with a small margin.
 *
 * Pixels are ARGB ints, row by row. Pure: the PC decodes and encodes.
 */
object Detourage {

    /**
     * The cut-out: [pixels] ([l]×[h], alpha set), where it was in the photo
     * ([x0], [y0]), whether the border was one colour ([uniforme]; if not,
     * the result is only approximate), the [tolerance] used, the background's colour.
     */
    class Resultat(val pixels: IntArray, val l: Int, val h: Int, val x0: Int, val y0: Int,
                   val uniforme: Boolean, val tolerance: Int, val fond: Int, val opaques: Int)

    const val TOLERANCE_MIN = 4
    const val TOLERANCE_MAX = 120
    /** An enclosed background patch goes when it is at least this share of the photo. */
    const val ILOT_MIN = 0.02
    const val MARGE = 4

    private fun r(p: Int) = (p ushr 16) and 0xFF
    private fun g(p: Int) = (p ushr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** The colour distance: the largest difference of the three channels. */
    fun distance(p: Int, q: Int): Int = maxOf(abs(r(p) - r(q)), abs(g(p) - g(q)), abs(b(p) - b(q)))

    /** The border's pixels (two deep). */
    private fun bord(l: Int, h: Int): IntArray {
        val s = LinkedHashSet<Int>()
        for (k in 0 until minOf(2, l, h)) {
            for (x in 0 until l) { s += k * l + x; s += (h - 1 - k) * l + x }
            for (y in 0 until h) { s += y * l + k; s += y * l + (l - 1 - k) }
        }
        return s.toIntArray()
    }

    /** The background's colour: each channel's median on the border. */
    fun fond(px: IntArray, l: Int, h: Int): Int {
        val bo = bord(l, h)
        fun med(f: (Int) -> Int): Int { val v = bo.map { f(px[it]) }.sorted(); return v[v.size / 2] }
        return (0xFF shl 24) or (med(::r) shl 16) or (med(::g) shl 8) or med(::b)
    }

    /** The tolerance from the border's spread, and whether the border is one colour. */
    fun estime(px: IntArray, l: Int, h: Int, f: Int = fond(px, l, h)): Pair<Int, Boolean> {
        val d = bord(l, h).map { distance(px[it], f) }.sorted()
        val p90 = d[(d.size * 9) / 10]
        val uniforme = d[(d.size * 8) / 10] <= 24
        return (p90 * 3 / 2 + 14).coerceIn(12, 60) to uniforme
    }

    /** [px] ([l]×[h]) cut out; [tolerance] null: from the border. */
    fun detoure(px: IntArray, l: Int, h: Int, tolerance: Int? = null): Resultat {
        require(l > 2 && h > 2 && px.size == l * h)
        val f = fond(px, l, h)
        val (auto, uniforme) = estime(px, l, h, f)
        val tol = (tolerance ?: auto).coerceIn(TOLERANCE_MIN, TOLERANCE_MAX)
        val n = l * h
        val d = IntArray(n) { distance(px[it], f) }
        // 0: object, 1: background reached from the border.
        val fondM = ByteArray(n)
        val file = IntArray(n); var tete = 0; var queue = 0
        for (i in bord(l, h)) if (d[i] <= tol && fondM[i] == 0.toByte()) { fondM[i] = 1; file[queue++] = i }
        fun voisins(i: Int, a: (Int) -> Unit) {
            val x = i % l; val y = i / l
            if (x > 0) a(i - 1); if (x < l - 1) a(i + 1); if (y > 0) a(i - l); if (y < h - 1) a(i + l)
        }
        while (tete < queue) {
            val i = file[tete++]
            voisins(i) { j -> if (fondM[j] == 0.toByte() && d[j] <= tol) { fondM[j] = 1; file[queue++] = j } }
        }
        // The enclosed patches of background: only the large ones, close to the background's colour throughout.
        val vu = ByteArray(n)
        for (s in 0 until n) {
            if (fondM[s] != 0.toByte() || vu[s] != 0.toByte() || d[s] > tol) continue
            tete = 0; queue = 0; file[queue++] = s; vu[s] = 1
            var somme = 0L
            while (tete < queue) {
                val i = file[tete++]; somme += d[i]
                voisins(i) { j -> if (fondM[j] == 0.toByte() && vu[j] == 0.toByte() && d[j] <= tol) { vu[j] = 1; file[queue++] = j } }
            }
            if (queue >= ILOT_MIN * n && somme / queue <= tol / 2) for (k in 0 until queue) fondM[file[k]] = 1
        }
        // How far each object pixel is from the background (1, 2, more): the soft edge.
        val anneau = IntArray(n) { if (fondM[it] == 1.toByte()) 0 else 99 }
        for (passe in 1..2) {
            for (i in 0 until n) {
                if (anneau[i] != 99) continue
                var pres = false
                val x = i % l; val y = i / l
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= l || yy >= h) continue
                    if (anneau[yy * l + xx] == passe - 1) { pres = true; break@loop }
                }
                if (pres) anneau[i] = passe
            }
        }
        val alpha = IntArray(n)
        var x0 = l; var y0 = h; var x1 = -1; var y1 = -1; var opaques = 0
        for (i in 0 until n) {
            val a = when (anneau[i]) {
                0 -> 0.0
                1, 2 -> {
                    // Near the background's colour: faded (the halo, a shadow); far from it: the edge only softened.
                    val c = ((d[i] - tol).toDouble() / (2.0 * tol)).coerceIn(0.0, 1.0)
                    if (anneau[i] == 1) minOf(0.9, c) else 0.4 + 0.6 * c
                }
                else -> 1.0
            }
            val ai = (a * 255).roundToInt().coerceIn(0, 255)
            alpha[i] = ai
            if (ai > 12) {
                val x = i % l; val y = i / l
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (ai == 255) opaques++
        }
        if (x1 < 0) { x0 = 0; y0 = 0; x1 = l - 1; y1 = h - 1 }
        x0 = maxOf(0, x0 - MARGE); y0 = maxOf(0, y0 - MARGE); x1 = minOf(l - 1, x1 + MARGE); y1 = minOf(h - 1, y1 + MARGE)
        val lo = x1 - x0 + 1; val ho = y1 - y0 + 1
        val sortie = IntArray(lo * ho)
        for (y in 0 until ho) for (x in 0 until lo) {
            val i = (y + y0) * l + (x + x0)
            sortie[y * lo + x] = (alpha[i] shl 24) or (px[i] and 0xFFFFFF)
        }
        return Resultat(sortie, lo, ho, x0, y0, uniforme, tol, f, opaques)
    }
}
