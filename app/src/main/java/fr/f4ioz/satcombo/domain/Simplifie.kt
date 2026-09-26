/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Simplification de tracés — Douglas-Peucker.
 *
 * Sortie du domaine pour être partagée : les contours de parcs POTA
 * téléchargés arrivent bruts (des centaines de points pour une dune) et
 * doivent être ramenés au même grain que ceux embarqués, une dizaine de
 * mètres. Le calcul est le même que celui qui a préparé les fichiers hors
 * ligne — un seul algorithme, deux usages.
 */
object Simplifie {

    /**
     * Simplifie une polyligne ouverte. [eps] est la tolérance, en degrés
     * (0.0001 ≈ 11 m en latitude).
     */
    fun ligne(pts: List<DoubleArray>, eps: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val garde = BooleanArray(pts.size)
        garde[0] = true; garde[pts.size - 1] = true
        segment(pts, 0, pts.size - 1, eps, garde)
        return pts.filterIndexed { i, _ -> garde[i] }
    }

    /**
     * Simplifie un anneau fermé. L'anneau est coupé au sommet le plus éloigné
     * du premier point — couper n'importe où pourrait effacer un cap réel qui
     * tombe sur la couture.
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
