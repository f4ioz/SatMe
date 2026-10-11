/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Simplifie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Douglas-Peucker: the outline loses points, never its shape. */
class SimplifieTest {

    @Test
    fun une_droite_bruitee_se_reduit_a_ses_extremites() {
        val pts = (0..100).map { doubleArrayOf(it / 100.0, (it % 2) * 0.00001) }
        val s = Simplifie.ligne(pts, 0.0001)
        assertEquals(2, s.size)
    }

    @Test
    fun un_cap_reel_survit_a_la_simplification() {
        // An L: the corner is well beyond tolerance and must survive.
        val pts = (0..50).map { doubleArrayOf(it / 50.0, 0.0) } +
            (1..50).map { doubleArrayOf(1.0, it / 50.0) }
        val s = Simplifie.ligne(pts, 0.001)
        assertEquals(3, s.size)
        assertEquals(1.0, s[1][0], 1e-9)
        assertEquals(0.0, s[1][1], 1e-9)
    }

    @Test
    fun un_anneau_ferme_reste_un_anneau_sans_doublon() {
        // A closed square (first == last), finely sampled.
        val cote = { a: DoubleArray, b: DoubleArray ->
            (0 until 25).map { i ->
                doubleArrayOf(a[0] + (b[0] - a[0]) * i / 25.0,
                              a[1] + (b[1] - a[1]) * i / 25.0)
            }
        }
        val c = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0),
            doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0))
        val pts = cote(c[0], c[1]) + cote(c[1], c[2]) + cote(c[2], c[3]) +
            cote(c[3], c[0]) + listOf(doubleArrayOf(0.0, 0.0))
        val s = Simplifie.anneau(pts, 0.001)
        assertEquals(4, s.size)
        // No duplicate point: the ring comes back open, ready to be closed.
        assertTrue(s.map { it[0] to it[1] }.toSet().size == 4)
    }
}
