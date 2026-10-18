/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoriqueNiveauTest {

    @Test fun `un point par seconde, dix minutes au plus, les anciens chassés`() {
        val h = HistoriqueNiveau()
        for (s in 0 until 700) h.ajoute(s * 1_000L, s.toDouble())
        val l = h.points(600_000L, 699_999L)
        // 600 finished seconds kept in the ring, plus the one under way; only the window's.
        assertTrue(l.size in 599..601)
        assertEquals(699.0, l.last().moy, 1e-9)
        assertTrue(l.first().t >= 99_999L)
        assertTrue(l.zipWithNext().all { (a, b) -> b.t > a.t })
    }

    @Test fun `la décimation garde la moyenne, le plus bas et le plus haut`() {
        val h = HistoriqueNiveau()
        // Ten seconds at -50 dB but one fade to -70 and one burst to -30.
        for (s in 0 until 10) h.ajoute(s * 1_000L, when (s) { 3 -> -70.0; 7 -> -30.0; else -> -50.0 })
        h.ajoute(10_000L, -50.0) // opens the next 10 s bucket
        val heure = h.points(3_600_000L, 10_500L)
        assertEquals(10_000L, h.palierPour(3_600_000L).pasMs)
        val p = heure.first()
        assertEquals(0L, p.t); assertEquals(10, p.n)
        assertEquals(-70.0, p.min, 1e-9); assertEquals(-30.0, p.max, 1e-9); assertEquals(-50.0, p.moy, 1e-9)
        // A day: a point a minute.
        assertEquals(60_000L, h.palierPour(86_400_000L).pasMs)
        assertEquals(1, h.points(86_400_000L, 10_500L).size)
    }

    @Test fun `un jour entier tient en 1440 minutes`() {
        val h = HistoriqueNiveau()
        var t = 0L
        while (t < 30L * 3_600_000L) { h.ajoute(t, (t / 60_000L % 7).toDouble()); t += 5_000L }
        val jour = h.points(86_400_000L, t)
        assertTrue(jour.size <= 1_441)
        assertTrue(jour.size >= 1_439)
        assertTrue(jour.first().t >= t - 86_400_000L - 60_000L)
    }

    @Test fun `statistiques pondérées et CSV`() {
        val pts = listOf(HistoriqueNiveau.Point(0, -10.0, -12.0, -8.0, 1), HistoriqueNiveau.Point(1_000, -20.0, -25.0, -15.0, 3))
        val s = HistoriqueNiveau.stats(pts)!!
        assertEquals(-25.0, s.min, 1e-9); assertEquals(-8.0, s.max, 1e-9); assertEquals(-17.5, s.moy, 1e-9)
        assertNull(HistoriqueNiveau.stats(emptyList()))
        val csv = HistoriqueNiveau.csv(listOf("canal" to pts, "balise" to listOf(HistoriqueNiveau.Point(1_000, 15.0, 14.0, 16.0, 1))))
        val lignes = csv.trim().lines()
        assertEquals("temps_utc,canal_moy_db,canal_min_db,canal_max_db,balise_moy_db,balise_min_db,balise_max_db", lignes[0])
        assertEquals("1970-01-01T00:00:00Z,-10.0,-12.0,-8.0,,,", lignes[1])
        assertEquals("1970-01-01T00:00:01Z,-20.0,-25.0,-15.0,15.0,14.0,16.0", lignes[2])
    }

    @Test fun `les mesures absurdes ou revenues en arrière sont ignorées`() {
        val h = HistoriqueNiveau()
        h.ajoute(5_000, Double.NaN); h.ajoute(5_000, Double.NEGATIVE_INFINITY)
        assertTrue(h.estVide())
        h.ajoute(5_000, -1.0); h.ajoute(2_000, -99.0)
        assertEquals(listOf(-1.0), h.points(600_000, 6_000).map { it.moy })
        h.vide(); assertTrue(h.estVide())
    }
}
