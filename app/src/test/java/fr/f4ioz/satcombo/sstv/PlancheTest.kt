/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlancheTest {

    @Test
    fun une_grille_de_12_tient_dans_sa_zone_en_4_3() {
        val z = Planche.Zone(0.33f, 0.03f, 0.65f, 0.94f)
        val g = Planche.grille(12, z)
        assertEquals(12, g.size)
        g.forEach { c ->
            assertTrue(c.x >= z.x - 1e-4 && c.x + c.w <= z.x + z.w + 1e-4)
            assertTrue(c.y >= z.y - 1e-4 && c.y + c.h <= z.y + z.h + 1e-4)
            assertEquals(4f / 3f, c.w * 1.414f / c.h, 0.02f)
        }
        // Reading order: left to right, then the next row.
        assertTrue(g[0].x < g[1].x && g[0].y == g[1].y)
        assertEquals(12, Planche.tour().size)
    }

    /** A template like the ARISS one: a dark sky, 6 black boxes edged white, two light frames with lettering. */
    @Test
    fun les_cases_et_les_cadres_d_un_modele_sont_trouves_dans_l_ordre() {
        val w = 600; val h = 420
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x * 7 + y * 13) % 97 == 0) 0xFFFFFFFF.toInt() else (0xFF000000.toInt() or ((y / 6) shl 8) or (y / 3)) }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c }
        val boites = ArrayList<IntArray>()
        for (r in 0 until 2) for (c in 0 until 3) {
            val x0 = 230 + c * 120; val y0 = 40 + r * 180
            rect(x0 - 3, y0 - 3, x0 + 103, y0 + 78, 0xFFFFFFFF.toInt())
            rect(x0, y0, x0 + 100, y0 + 75, 0xFF000000.toInt())
            rect(x0 + 20, y0 + 30, x0 + 80, y0 + 40, 0xFFFF9900.toInt())   // "NO IMAGE"
            boites += intArrayOf(x0, y0)
        }
        // Two frames, lettering over half of them.
        for (y0 in listOf(260, 320)) {
            rect(40, y0, 170, y0 + 26, 0xFFF4EEE7.toInt())
            for (k in 0 until 10) rect(50 + k * 12, y0 + 5, 58 + k * 12, y0 + 21, 0xFF0B2D5B.toInt())
        }
        val c = Planche.detecteCases(px, w, h)
        assertEquals(6, c.size)
        c.forEachIndexed { i, z ->
            assertEquals(boites[i][0] / w.toFloat(), z.x, 0.01f)
            assertEquals(boites[i][1] / h.toFloat(), z.y, 0.01f)
        }
        val t = Planche.detecteCadresTexte(px, w, h)
        assertEquals(2, t.size)
        assertTrue(t[0].first.y < t[1].first.y)
        assertEquals(0xFFF4EEE7.toInt() and 0xF0F0F0, t[0].second and 0xF0F0F0)
        // The callsign goes into the first frame, the name into the second.
        val m = Planche.genereImporte("a", "ARISS", "fond.jpg", w / h.toFloat(), c, t)
        assertEquals(t[0].first, m.textes.first { it.champ == Planche.Champ.INDICATIF }.zone)
        assertEquals(t[1].first, m.textes.first { it.champ == Planche.Champ.NOM_LOCATOR }.zone)
    }

    @Test
    fun un_modele_se_garde_et_se_relit() {
        val m = Planche.genereGrille("p1", "Série 27 ; ARISS").copy(
            titre = "ARISS\nSeries 27", images = mapOf(0 to "a.png", 5 to "b.png"),
            textes = listOf(Planche.Texte(Planche.Champ.LIBRE, Planche.Zone(0.1f, 0.2f, 0.3f, 0.05f),
                Planche.JAUNE, Planche.VOILE_NOIR, "73 de F4IOZ")))
        val lu = Planche.lit("p1", Planche.ecrit(m))!!
        assertEquals(m.nom, lu.nom); assertEquals(m.titre, lu.titre)
        assertEquals(m.cases, lu.cases); assertEquals(m.images, lu.images)
        assertEquals(m.textes, lu.textes)
        assertEquals(m.legende, lu.legende); assertEquals(m.numeros, lu.numeros)
    }

    @Test
    fun remplissage_par_reception_complete_avant_partielle() {
        fun s(n: String, t: Long, c: Boolean = true) = SstvMeta.SstvShot(fileName = n, timeMs = t, complete = c)
        val im = listOf(s("c", 3000), s("a", 1000), s("p", 500, false), s("b", 2000))
        assertEquals(mapOf(0 to "a", 1 to "b", 2 to "c"), Planche.remplitParReception(3, im))
        assertEquals(mapOf(0 to "p", 1 to "a", 2 to "b", 3 to "c"), Planche.remplitParReception(12, im))
    }

    @Test
    fun dates_locator_et_legende() {
        val j1 = 1_746_403_200_000L  // 2025-05-05 00:00 UTC
        assertEquals("2025-05-05 – 2025-05-12", Planche.dates(listOf(j1 + 7 * 86_400_000L, j1 + 3600_000L)))
        assertEquals("2025-05-05", Planche.dates(listOf(j1, j1 + 3600_000L)))
        assertEquals("", Planche.dates(emptyList()))
        assertEquals("JN18FS", Planche.locatorDominant(listOf("jn18fs", "IN77UT", "JN18FS", "")))
        val l = Planche.legende(SstvMeta.SstvShot(fileName = "x", mode = "PD 120", timeMs = j1 + 20 * 3600_000L), "F4IOZ")
        assertEquals("PD 120 | 2025/05/05 20:00 UTC | F4IOZ", l)
        assertTrue(abs(Planche.Zone(0.95f, 0.5f, 0.2f, 0.1f).bornee().x - 0.8f) < 1e-5)
    }
}
