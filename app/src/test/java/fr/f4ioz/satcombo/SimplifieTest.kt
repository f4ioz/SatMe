/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Simplifie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Douglas-Peucker : le tracé perd ses points, jamais sa forme. */
class SimplifieTest {

    @Test
    fun une_droite_bruitee_se_reduit_a_ses_extremites() {
        val pts = (0..100).map { doubleArrayOf(it / 100.0, (it % 2) * 0.00001) }
        val s = Simplifie.ligne(pts, 0.0001)
        assertEquals(2, s.size)
    }

    @Test
    fun un_cap_reel_survit_a_la_simplification() {
        // Un L : le coin dépasse largement la tolérance, il doit rester.
        val pts = (0..50).map { doubleArrayOf(it / 50.0, 0.0) } +
            (1..50).map { doubleArrayOf(1.0, it / 50.0) }
        val s = Simplifie.ligne(pts, 0.001)
        assertEquals(3, s.size)
        assertEquals(1.0, s[1][0], 1e-9)
        assertEquals(0.0, s[1][1], 1e-9)
    }

    @Test
    fun un_anneau_ferme_reste_un_anneau_sans_doublon() {
        // Un carré fermé (premier == dernier) échantillonné finement.
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
        // Aucun point en double : l'anneau est ouvert, prêt à être refermé.
        assertTrue(s.map { it[0] to it[1] }.toSet().size == 4)
    }
}
