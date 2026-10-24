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
 * Copies of one slide put together into one picture: an ARISS series sends
 * each slide again and again, and the noise of a pass is never on the same
 * pixels twice. Each copy is placed on the first (a copy begun late is the
 * slide moved up; a sync slip moves it sideways), then each pixel is the
 * weighted median of the copies there: a speck or a spoilt row in one copy
 * is outvoted by the others, nothing is invented — every value comes from a
 * copy. Weights: each row's cleanliness in its copy (its specks, its
 * likeness to the rows around it), a pixel far from its own neighbourhood
 * counting little.
 *
 * Pure Kotlin: ARGB pixels in, ARGB pixels out.
 */
object FusionSstv {

    /** One copy: ARGB [px], [w]×[h], its first [lignes] rows received. */
    class Copie(val px: IntArray, val w: Int, val h: Int, val lignes: Int = h)

    /** Where a copy sits on the reference: its pixel (x+dx, y+dy) is the reference's (x, y). */
    data class Calage(val dx: Int, val dy: Int, /** Mean brightness gap where they overlap, 0–255. */ val ecart: Float, val communes: Int)

    class Resultat(
        val px: IntArray, val w: Int, val h: Int,
        /** Each copy's placing (null: not like the reference, left out). */
        val calages: List<Calage?>,
        /** Copies that had each row. */
        val parLigne: IntArray,
        /** Rows with at least one copy. */
        val lignes: Int,
    ) { val utilisees: Int get() = calages.count { it != null } }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /**
     * Each row's cleanliness, 0 spoilt .. 1 clean: the share of pixels far
     * from their 3×3 median (specks), and a row much unlike both neighbours.
     */
    fun qualiteLignes(c: Copie): FloatArray {
        val w = c.w; val n = c.lignes.coerceIn(0, c.h)
        val l = IntArray(w * maxOf(n, 1)) { if (it < w * n) lum(c.px[it]) else 0 }
        val q = FloatArray(c.h)
        val t = IntArray(9)
        for (y in 0 until n) {
            var imp = 0
            for (x in 0 until w) {
                var k = 0
                for (dy in -1..1) for (dx in -1..1) t[k++] = l[(y + dy).coerceIn(0, n - 1) * w + (x + dx).coerceIn(0, w - 1)]
                t.sort()
                if (abs(l[y * w + x] - t[4]) > 60) imp++
                // Green: a weak Robot 36 chroma.
                val p = c.px[y * w + x]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (g > r + 50 && g > b + 50) imp++
            }
            var saut = 0f
            if (y in 1 until n - 1) {
                var a = 0; var b = 0; var m = 0
                for (x in 0 until w) { val i = y * w + x; a += abs(l[i] - l[i - w]); b += abs(l[i] - l[i + w]); m += abs(l[i - w] - l[i + w]) }
                saut = ((minOf(a, b) - m / 2f) / w).coerceAtLeast(0f)
            }
            q[y] = (1f - imp / (0.35f * w) - (saut - 15f).coerceAtLeast(0f) / 30f).coerceIn(0f, 1f)
        }
        return q
    }

    /**
     * Where [c] sits on [ref]: shifts up to [dyMax] rows and [dxMax] pixels
     * tried, on brightness, over rows clean in both (a third of the
     * picture in common at least). Null when nothing fits.
     */
    fun cale(ref: Copie, c: Copie, dyMax: Int = ref.h / 4, dxMax: Int = 8,
             qRef: FloatArray = qualiteLignes(ref), qC: FloatArray = qualiteLignes(c)): Calage? {
        val w = minOf(ref.w, c.w)
        val la = IntArray(ref.w * ref.h) { lum(ref.px[it]) }
        val lb = IntArray(c.w * c.h) { lum(c.px[it]) }
        fun ecart(dx: Int, dy: Int, pas: Int): Pair<Float, Int> {
            var s = 0L; var m = 0L; var rangs = 0
            var y = 0
            while (y < ref.lignes) {
                val yb = y + dy
                if (yb in 0 until c.lignes && qRef[y] > 0.5f && qC[yb] > 0.5f) {
                    rangs++
                    var x = maxOf(0, -dx) + 8
                    val fin = minOf(w, w - dx) - 8
                    while (x < fin) { s += abs(la[y * ref.w + x] - lb[yb * c.w + x + dx]); m++; x += pas }
                }
                y += pas
            }
            return (if (m > 0) s / m.toFloat() else Float.MAX_VALUE) to rangs * pas
        }
        val minCommunes = ref.h / 3
        var best: Calage? = null
        for (dy in -dyMax..dyMax step 2) for (dx in -dxMax..dxMax step 2) {
            val (e, k) = ecart(dx, dy, 2)
            if (k >= minCommunes && (best == null || e < best.ecart)) best = Calage(dx, dy, e, k)
        }
        val b0 = best ?: return null
        var fin: Calage? = null
        for (dy in b0.dy - 2..b0.dy + 2) for (dx in b0.dx - 2..b0.dx + 2) {
            if (abs(dy) > dyMax || abs(dx) > dxMax) continue
            val (e, k) = ecart(dx, dy, 1)
            if (k >= minCommunes && (fin == null || e < fin.ecart)) fin = Calage(dx, dy, e, k)
        }
        return fin ?: b0
    }

    /** Below this gap two copies are one sound decoded twice. */
    const val DOUBLON = 2.5f

    /** The largest gap at which a copy is taken for the same slide (brightness, 0–255). */
    const val ECART_MAX = 40f

    /**
     * The copies put together, placed on [copies]' first (the best one, as
     * the gallery orders them). A copy that does not fit ([ECART_MAX]) is
     * left out. With one copy, it comes back as it is.
     */
    fun fusionne(copies: List<Copie>): Resultat {
        require(copies.isNotEmpty())
        val ref = copies[0]
        val w = ref.w; val h = ref.h
        val qs = copies.map { qualiteLignes(it) }
        val calages = ArrayList<Calage?>()
        for ((i, c) in copies.withIndex()) {
            calages += if (i == 0) Calage(0, 0, 0f, ref.lignes)
            else if (c.w != w) null
            else cale(ref, c, qRef = qs[0], qC = qs[i])?.takeIf { it.ecart <= ECART_MAX }
                // The same sound decoded twice (a picture and its « _r2 »): its noise once only.
                ?.takeIf { cg -> cg.ecart > DOUBLON && (1 until i).none { j -> calages[j] != null && (cale(copies[j], c, qRef = qs[j], qC = qs[i])?.ecart ?: 99f) <= DOUBLON } }
        }
        val out = IntArray(w * h) { 0xFF000000.toInt() }
        val parLigne = IntArray(h)
        val n = copies.size
        val vals = Array(3) { IntArray(n) }
        val poids = FloatArray(n)
        val ordre = Array(n) { 0 }
        var lignes = 0
        // Each copy's specks: a pixel far from its own 3×3 median counts little.
        val lums = copies.map { c -> IntArray(c.w * c.h) { lum(c.px[it]) } }
        val t = IntArray(9)
        for (y in 0 until h) {
            var avec = 0
            for (i in 0 until n) { val cg = calages[i] ?: continue; val yb = y + cg.dy; if (yb in 0 until copies[i].lignes) avec++ }
            parLigne[y] = avec
            if (avec == 0) continue
            lignes = y + 1
            for (x in 0 until w) {
                var m = 0
                for (i in 0 until n) {
                    val cg = calages[i] ?: continue
                    val c = copies[i]
                    val yb = y + cg.dy; val xb = (x + cg.dx).coerceIn(0, c.w - 1)
                    if (yb !in 0 until c.lignes) continue
                    val p = c.px[yb * c.w + xb]
                    val l = lums[i]
                    var k = 0
                    for (dy in -1..1) for (dx in -1..1) t[k++] = l[(yb + dy).coerceIn(0, c.lignes - 1) * c.w + (xb + dx).coerceIn(0, c.w - 1)]
                    t.sort()
                    val tache = abs(l[yb * c.w + xb] - t[4]) > 60
                    vals[0][m] = (p shr 16) and 0xFF; vals[1][m] = (p shr 8) and 0xFF; vals[2][m] = p and 0xFF
                    poids[m] = (0.05f + qs[i][yb]) * (if (tache) 0.2f else 1f)
                    m++
                }
                var r = 0
                for (ch in 0 until 3) r = (r shl 8) or medianePonderee(vals[ch], poids, m, ordre)
                out[y * w + x] = (0xFF shl 24) or r
            }
        }
        return Resultat(out, w, h, calages, parLigne, lignes)
    }

    /** The weighted median of [v]'s first [m] values (with two, the heavier one; even weights: their mean). */
    private fun medianePonderee(v: IntArray, p: FloatArray, m: Int, ordre: Array<Int>): Int {
        if (m == 1) return v[0]
        for (i in 0 until m) ordre[i] = i
        java.util.Arrays.sort(ordre, 0, m) { a, b -> v[a].compareTo(v[b]) }
        var tot = 0f
        for (i in 0 until m) tot += p[i]
        var acc = 0f
        for (k in 0 until m) {
            val i = ordre[k]
            acc += p[i]
            if (acc > tot / 2 + 1e-4f) return v[i]
            if (abs(acc - tot / 2) <= 1e-4f && k + 1 < m) return (v[i] + v[ordre[k + 1]] + 1) / 2
        }
        return v[ordre[m - 1]]
    }
}
