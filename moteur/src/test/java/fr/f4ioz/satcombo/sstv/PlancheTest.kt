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

    @Test
    fun le_suivi_dit_ce_qui_manque_a_la_serie() {
        val m = Planche.genereGrille("p", "s").copy(images = mapOf(0 to "a.png", 2 to "b.png", 3 to "perdue.png"))
        val (recues, manquent) = Planche.suivi(m) { it != "perdue.png" }
        assertEquals(2, recues)
        assertEquals(listOf(2) + (4..12).toList(), manquent)
        assertEquals(12 to emptyList<Int>(), Planche.suivi(m.copy(images = (0 until 12).associateWith { "x$it" })))
    }

    private fun Planche.Zone.dans(z: Planche.Zone) =
        x >= z.x - 1e-4f && y >= z.y - 1e-4f && x + w <= z.x + z.w + 1e-4f && y + h <= z.y + z.h + 1e-4f
    private fun chevauche(a: Planche.Zone, b: Planche.Zone) =
        a.x < b.x + b.w - 1e-4f && b.x < a.x + a.w - 1e-4f && a.y < b.y + b.h - 1e-4f && b.y < a.y + a.h - 1e-4f

    @Test
    fun la_grille_optimale_fait_les_images_les_plus_grandes_sans_case_vide() {
        val z = Planche.Zone(0.33f, 0.03f, 0.65f, 0.94f)
        for (n in 1..16) {
            val g = Planche.grilleOptimale(n, z)
            assertEquals(n, g.size)
            g.forEach { c ->
                assertTrue("$n $c", c.dans(z))
                assertEquals(4f / 3f, c.w * 1.414f / c.h, 0.01f)
            }
            for (i in g.indices) for (j in i + 1 until g.size) assertTrue("$n: $i/$j", !chevauche(g[i], g[j]))
            // Never smaller than the old grid's boxes.
            assertTrue("$n", g[0].w >= Planche.grille(n, z)[0].w - 1e-4f)
        }
        // Four in a near-square place: two by two. Two on a wide strip: side by side.
        val q = Planche.grilleOptimale(4, z)
        assertEquals(q[0].y, q[1].y, 1e-5f); assertTrue(q[2].y > q[0].y); assertEquals(q[0].x, q[2].x, 1e-5f)
        val b = Planche.grilleOptimale(2, Planche.Zone(0f, 0f, 1f, 0.4f))
        assertEquals(b[0].y, b[1].y, 1e-5f)
        // Five here: two, two, then one centred under them.
        val c = Planche.grilleOptimale(5, z)
        assertEquals(c[0].y, c[1].y, 1e-5f); assertTrue(c[4].y > c[2].y)
        assertEquals((c[0].x + c[1].x + c[1].w) / 2, c[4].x + c[4].w / 2, 1e-4f)
        // One alone: as large as the place allows.
        val u = Planche.grilleOptimale(1, z)[0]
        assertTrue(u.w > 0.6f * z.w || u.h > 0.9f * z.h)
    }

    @Test
    fun une_grille_ajustee_a_quatre_images_puis_rendue_au_modele() {
        val m = Planche.genereGrille("p", "s")
        val a = Planche.ajuste(m, listOf("a", "b", "c", "d"))
        assertTrue(a.ajustee)
        assertEquals(4, a.cases.size)
        assertEquals(mapOf(0 to "a", 1 to "b", 2 to "c", 3 to "d"), a.images)
        assertEquals(m.cases, a.origine)
        // The texts stay where they were, no picture on them, and the pictures larger than in the template.
        assertEquals(m.textes, a.textes)
        a.cases.forEach { c -> a.textes.forEach { t -> assertTrue(!chevauche(c, t.zone)) } }
        assertTrue(a.cases[0].w > m.cases[0].w * 1.3f)
        // Kept and read back as it is.
        val lu = Planche.lit("p", Planche.ecrit(a))!!
        assertEquals(a.cases, lu.cases); assertEquals(a.origine, lu.origine); assertTrue(lu.ajustee)
        // Back to the template: its twelve boxes, the four pictures in the first ones.
        val r = Planche.duModele(lu)
        assertEquals(m.cases, r.cases); assertTrue(!r.ajustee)
        assertEquals(mapOf(0 to "a", 1 to "b", 2 to "c", 3 to "d"), r.images)
        // No picture: the template's layout.
        assertEquals(m.cases, Planche.ajuste(a, emptyList()).cases)
    }

    @Test
    fun autour_du_titre_les_textes_s_ecartent_et_reviennent() {
        val m = Planche.genereTour("t", "s").copy(images = mapOf(0 to "a", 1 to "b", 2 to "c"))
        val a = Planche.ajuste(m, Planche.choisies(m))
        assertEquals(3, a.cases.size)
        a.cases.forEach { c ->
            assertTrue(c.dans(Planche.Zone(0f, 0f, 1f, 1f)))
            a.textes.forEach { t -> assertTrue("$c ${t.zone}", !chevauche(c, t.zone)) }
        }
        // Larger than the template's boxes: the title's place is used.
        assertTrue(a.cases[0].w > m.cases[0].w * 1.3f)
        // A colour changed meanwhile stays; the places come back.
        val rouge = a.copy(textes = a.textes.mapIndexed { i, t -> if (i == 0) t.copy(couleur = Planche.ROUGE) else t })
        val lu = Planche.lit("t", Planche.ecrit(rouge))!!
        val r = Planche.duModele(lu)
        assertEquals(m.textes.map { it.zone }, r.textes.map { it.zone })
        assertEquals(Planche.ROUGE, r.textes[0].couleur)
        assertEquals(m.cases, r.cases)
    }

    @Test
    fun un_modele_importe_garde_ses_images_dans_la_place_de_ses_cases() {
        val cases = Planche.grille(12, Planche.Zone(0.4f, 0.1f, 0.55f, 0.8f), 1.5f)
        val m = Planche.genereImporte("i", "ARISS", "fond_i.jpg", 1.5f, cases)
        val a = Planche.ajuste(m, listOf("a", "b"))
        val place = Planche.englobe(cases)!!
        assertEquals(2, a.cases.size)
        a.cases.forEach { assertTrue(it.dans(place)) }
        // The callsign frames printed on the template do not move.
        assertEquals(m.textes, a.textes); assertTrue(a.textesOrigine.isEmpty())
    }

    @Test
    fun cocher_decocher_echanger_et_choisir_le_nombre() {
        val m = Planche.ajuste(Planche.genereGrille("p", "s", n = 4), listOf("a"))
        val b = Planche.bascule(m, "b")
        assertEquals(listOf("a", "b"), Planche.choisies(b)); assertEquals(2, b.cases.size)
        assertEquals(listOf("b"), Planche.choisies(Planche.bascule(b, "a")))
        // Full at the template's count.
        val plein = Planche.bascule(Planche.bascule(b, "c"), "d")
        assertEquals(4, plein.cases.size)
        assertEquals(plein, Planche.bascule(plein, "e"))
        assertEquals(listOf("b", "a"), Planche.choisies(Planche.echange(b, 0, 1)))
        // A number: those there kept, the rest by reception, complete first.
        fun s(n: String, t: Long, c: Boolean = true) = SstvMeta.SstvShot(fileName = n, timeMs = t, complete = c)
        val g = listOf(s("x", 500, false), s("a", 1000), s("y", 2000), s("z", 3000))
        assertEquals(listOf("a", "y", "z"), Planche.choisies(Planche.avecNombre(m, 3, g)))
        assertEquals(listOf("a"), Planche.choisies(Planche.avecNombre(b, 1, g)))
        val modele = Planche.avecNombre(b, 0, g)
        assertTrue(!modele.ajustee); assertEquals(4, modele.cases.size)
        // Fill on a fitted sheet: as many as it holds, the best.
        assertEquals(listOf("a", "y"), Planche.choisies(Planche.remplit(b, g)))
    }

    /** A slide: a frame shared by the whole series, its own picture in the middle; noise spoils a few lines. */
    private fun diapo(graine: Int, bruitees: Set<Int> = emptySet()): IntArray {
        val w = 320; val h = 240; val r = java.util.Random(graine.toLong())
        val blocs = IntArray(64) { r.nextInt(256) }
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            // The decoder's noise: green speckle.
            if (y / 20 in bruitees) return@IntArray (0xFF shl 24) or (r.nextInt(80) shl 16) or ((150 + r.nextInt(100)) shl 8) or r.nextInt(80)
            val v = when {
                x in 60..259 && y in 40..199 -> blocs[((y - 40) / 20) * 8 + (x - 60) / 25]
                y < 40 -> 230
                else -> 20
            }
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test
    fun deux_copies_d_une_diapo_ne_font_qu_une_et_la_plus_propre_reste() {
        val propre = Planche.signature(diapo(1), 320, 240)
        val bruitee = Planche.signature(diapo(1, setOf(3, 7)), 320, 240)
        val autre = Planche.signature(diapo(2), 320, 240)
        assertTrue(Planche.memeImage(propre, bruitee))
        assertTrue(!Planche.memeImage(propre, autre))
        assertTrue(bruitee.bruit > propre.bruit)
        fun s(n: String, t: Long) = SstvMeta.SstvShot(fileName = n, timeMs = t)
        val sig = mapOf("b1" to bruitee, "p1" to propre, "a2" to autre)
        // The noisy copy came first; the clean one is kept, and no box gets the slide twice.
        assertEquals(mapOf(0 to "p1", 1 to "a2"),
            Planche.remplitParReception(12, listOf(s("b1", 1000), s("p1", 2000), s("a2", 3000)), sig))
        // Spoilt by noise, a picture comes last: one box, the clean one.
        val sale = Planche.signature(diapo(3, (0 until 12).toSet().filter { it % 3 == 0 }.toSet()), 320, 240)
        assertEquals(mapOf(0 to "a2"), Planche.remplitParReception(1, listOf(s("s3", 1000), s("a2", 3000)), sig + ("s3" to sale)))
    }

    @Test
    fun une_planche_d_avant_se_relit_telle_quelle() {
        val ancien = "planche=1\nnom=Série\nfond=\nratio=1.414\nlegende=1\nnumeros=0\ntitre=\n" +
            "case=0.1;0.1;0.2;0.2\nimage=0;a.png\nautre=chose\n"
        val m = Planche.lit("x", ancien)!!
        assertTrue(!m.ajustee); assertEquals(1, m.cases.size); assertEquals(mapOf(0 to "a.png"), m.images)
        assertEquals(m, Planche.duModele(m)); assertEquals(m, Planche.reajuste(m))
    }
}
