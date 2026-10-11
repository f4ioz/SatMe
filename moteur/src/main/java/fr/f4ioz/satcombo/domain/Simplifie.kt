/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * Douglas-Peucker line simplification.
 *
 * Downloaded POTA park outlines arrive raw (hundreds of points for a dune)
 * and must be brought to the grain of the bundled ones, about ten metres.
 * Same algorithm that prepared the offline files.
 */
object Simplifie {

    /**
     * Simplifies an open polyline. [eps] is the tolerance in degrees
     * (0.0001 ≈ 11 m in latitude).
     */
    fun ligne(pts: List<DoubleArray>, eps: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val garde = BooleanArray(pts.size)
        garde[0] = true; garde[pts.size - 1] = true
        segment(pts, 0, pts.size - 1, eps, garde)
        return pts.filterIndexed { i, _ -> garde[i] }
    }

    /**
     * Simplifies a closed ring, split at the vertex farthest from the first
     * point: splitting anywhere could erase a real headland lying on the seam.
     */
    fun anneau(pts: List<DoubleArray>, eps: Double): List<DoubleArray> {
        val ouvert = if (pts.size > 1 &&
            pts.first()[0] == pts.last()[0] && pts.first()[1] == pts.last()[1])
            pts.dropLast(1) else pts
        if (ouvert.size < 4) return ouvert
        val x0 = ouvert[0][0]; val y0 = ouvert[0][1]
        var j = 0; var dm = -1.0
        for (i in ouvert.indices) {
            val d = (ouvert[i][0] - x0) * (ouvert[i][0] - x0) +
                    (ouvert[i][1] - y0) * (ouvert[i][1] - y0)
            if (d > dm) { dm = d; j = i }
        }
        val a = ligne(ouvert.subList(0, j + 1), eps)
        val b = ligne(ouvert.subList(j, ouvert.size) + listOf(ouvert[0]), eps)
        return a.dropLast(1) + b.dropLast(1)
    }

    private fun segment(
        pts: List<DoubleArray>, de: Int, a: Int, eps: Double, garde: BooleanArray
    ) {
        if (a <= de + 1) return
        val x1 = pts[de][0]; val y1 = pts[de][1]
        val x2 = pts[a][0]; val y2 = pts[a][1]
        val dx = x2 - x1; val dy = y2 - y1
        val n = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-12)
        var dm = -1.0; var im = de
        for (i in de + 1 until a) {
            val d = kotlin.math.abs(dy * pts[i][0] - dx * pts[i][1] + x2 * y1 - y2 * x1) / n
            if (d > dm) { dm = d; im = i }
        }
        if (dm > eps) {
            garde[im] = true
            segment(pts, de, im, eps, garde)
            segment(pts, im, a, eps, garde)
        }
    }
}
