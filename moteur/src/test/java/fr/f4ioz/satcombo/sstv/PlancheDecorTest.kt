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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

/** Backgrounds, patches, and the two built-in templates as SatMe exports them. */
class PlancheDecorTest {

    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    @Test
    fun une_image_couvre_la_planche_sans_etre_deformee() {
        // Wider than the sheet: the sides cut off, equally.
        val a = PlancheDecor.couvre(4000, 2000, 2400f, 1697f)
        assertEquals(2000, a.h); assertEquals(0, a.y)
        assertEquals(2400f / 1697f, a.w.toFloat() / a.h, 0.002f)
        assertEquals((4000 - a.w) / 2, a.x)
        // Taller: top and bottom cut off.
        val b = PlancheDecor.couvre(1000, 2000, 2400f, 1697f)
        assertEquals(1000, b.w); assertEquals(0, b.x)
        assertEquals(2400f / 1697f, b.w.toFloat() / b.h, 0.002f)
        assertEquals((2000 - b.h) / 2, b.y)
        // The same shape: the whole picture.
        assertEquals(PlancheDecor.Rect(0, 0, 2400, 1697), PlancheDecor.couvre(2400, 1697, 1200f, 848.5f))
    }

    /**
     * A round mission patch on a black surround, as exp75a.jpg (650×666):
     * a blue ring, inside a sky nearly as dark as the surround — kept, being
     * inside the ring. JPEG-like noise on the black.
     */
    private fun ecussonRond(w: Int = 130, h: Int = 133): IntArray {
        val r = Random(7)
        val cx = w / 2f; val cy = h / 2f; val rayon = 60f
        return IntArray(w * h) { i ->
            val x = i % w + 0.5f; val y = i / w + 0.5f
            val d = hypot(x - cx, y - cy)
            when {
                d > rayon -> { val v = r.nextInt(0, 18); (0xFF shl 24) or (v shl 16) or (v shl 8) or r.nextInt(0, 18) }
                d > rayon - 5 -> 0xFF1A5ABE.toInt()            // the blue ring
                y > cy + 25 -> 0xFF35B5E6.toInt()               // light blue mountains
                else -> 0xFF0E1622.toInt()                       // dark sky inside
            }
        }
    }

    @Test
    fun le_contour_noir_d_un_ecusson_rond_devient_transparent() {
        val w = 130; val h = 133
        val px = ecussonRond(w, h)
        val out = PlancheDecor.detoure(px, w, h)
        // The surround: gone, corners and just outside the ring.
        listOf(0, w - 1, (h - 1) * w, w * h - 1, (h / 2) * w + 2).forEach { assertEquals(0, alpha(out[it])) }
        // Inside: kept whole, the dark sky as much as the ring.
        val centre = (h / 2) * w + w / 2
        assertEquals(255, alpha(out[centre])); assertEquals(px[centre] and 0xFFFFFF, out[centre] and 0xFFFFFF)
        assertEquals(255, alpha(out[(h / 2) * w + (w / 2 - 57)]))
        assertEquals(255, alpha(out[(h / 2 + 40) * w + w / 2]))
        // A soft edge: some pixels between, on the circle.
        assertTrue(out.count { alpha(it) in 1..254 } > 50)
    }

    @Test
    fun un_ecusson_rectangulaire_garde_ses_coins() {
        val w = 100; val h = 80
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in 20 until 80 && y in 20 until 60) 0xFFC8102E.toInt() else 0xFFFDFDFD.toInt() }
        val out = PlancheDecor.detoure(px, w, h)
        assertEquals(0, alpha(out[5 * w + 5]))
        assertEquals(0, alpha(out[40 * w + 10]))
        // A corner of the patch: not rounded off.
        assertTrue(alpha(out[21 * w + 21]) == 255)
        assertTrue(alpha(out[20 * w + 20]) >= 96)
        assertEquals(255, alpha(out[40 * w + 50]))
    }

    @Test
    fun deja_transparent_ou_sans_bord_uni() {
        // Transparent all round already (a PNG): as it was.
        val w = 40; val h = 40
        val transparent = IntArray(w * h) { i -> if (i % w in 10..30 && i / w in 10..30) 0xFF336699.toInt() else 0 }
        assertTrue(transparent.contentEquals(PlancheDecor.detoure(transparent, w, h)))
        // A square photo, no plain edge: cut to its circle.
        val r = Random(3)
        val photo = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0, 0xFFFFFF) }
        val out = PlancheDecor.detoure(photo, w, h)
        assertEquals(0, alpha(out[0])); assertEquals(255, alpha(out[20 * w + 20]))
        // A wide one: left alone.
        val large = IntArray(80 * 20) { 0xFF000000.toInt() or r.nextInt(0, 0xFFFFFF) }
        assertTrue(PlancheDecor.detoure(large, 80, 20).all { alpha(it) == 255 })
    }

    @Test
    fun fond_et_ecussons_enregistres_avec_la_planche() {
        var m = Planche.genereGrille("p1", "ISS").copy(decor = "nasa_aurore")
        m = Planche.ajouteEcusson(m, "ecusson_a.png", 650, 666, 1.414f)
        m = m.copy(ecussons = m.ecussons.map { it.copy(sansContour = true) })
        m = Planche.ajouteEcusson(m, "ecusson_b.jpg", 400, 200, 1.414f)
        val texte = Planche.ecrit(m)
        assertTrue(texte.contains("decor=nasa_aurore\n"))
        assertTrue(texte.contains("ecusson=ecusson_a.png;"))
        val lu = Planche.lit("p1", texte)!!
        assertEquals("nasa_aurore", lu.decor)
        assertEquals(2, lu.ecussons.size)
        assertTrue(lu.ecussons[0].sansContour); assertFalse(lu.ecussons[1].sansContour)
        assertEquals(m.ecussons[0].zone, lu.ecussons[0].zone)
        // The patch keeps its picture's shape on the sheet.
        val z = lu.ecussons[1].zone
        assertEquals(2f, z.w * 1.414f / z.h, 0.01f)
        // A sheet without them is written as before; an older file reads with none.
        val avant = Planche.genereTour("p2", "Tour")
        assertFalse(Planche.ecrit(avant).contains("decor=")); assertFalse(Planche.ecrit(avant).contains("ecusson="))
        val vieux = Planche.lit("p2", Planche.ecrit(avant))!!
        assertEquals("", vieux.decor); assertTrue(vieux.ecussons.isEmpty())
        assertEquals(avant.cases, vieux.cases)
    }

    @Test
    fun trois_ecussons_au_plus_et_mis_a_l_echelle_autour_de_leur_centre() {
        var m = Planche.genereGrille("p", "p")
        repeat(5) { m = Planche.ajouteEcusson(m, "ecusson_$it.png", 100, 100) }
        assertEquals(Planche.ECUSSONS_MAX, m.ecussons.size)
        val z = m.ecussons[0].zone
        val g = Planche.echelle(z, 1.5f)
        assertEquals(z.x + z.w / 2, g.x + g.w / 2, 1e-4f); assertEquals(z.y + z.h / 2, g.y + g.h / 2, 1e-4f)
        assertEquals(z.w / z.h, g.w / g.h, 1e-4f)
        // Never larger than the sheet, never lost.
        val geant = Planche.echelle(z, 50f)
        assertTrue(geant.w <= 1f && geant.h <= 1f && geant.x >= 0f && geant.y >= 0f)
        assertEquals(z.w / z.h, geant.w / geant.h, 1e-3f)
        assertTrue(Planche.echelle(z, 0.001f).w >= 0.029f)
    }

    @Test
    fun ce_qu_une_page_envoie_est_verifie() {
        val m = Planche.genereGrille("p", "p").copy(decor = "../../reglages.properties",
            ecussons = listOf(Planche.Ecusson("ecusson_a.png", Planche.Zone(0.9f, 0.9f, 0.3f, 0.3f)),
                Planche.Ecusson("../x.png", Planche.Zone(0.1f, 0.1f, 0.1f, 0.1f)),
                Planche.Ecusson("ecusson_absent.png", Planche.Zone(0.1f, 0.1f, 0.1f, 0.1f))))
        val existe = { n: String -> n == "ecusson_a.png" || n == "decor_x.jpg" }
        val v = Planche.decorVerifie(m, existe)
        assertEquals("", v.decor)
        assertEquals(1, v.ecussons.size)
        val z = v.ecussons[0].zone
        assertTrue(z.x + z.w <= 1f + 1e-5f && z.y + z.h <= 1f + 1e-5f)
        assertEquals("decor_x.jpg", Planche.decorVerifie(m.copy(decor = "decor_x.jpg"), existe).decor)
        assertEquals("abysse", Planche.decorVerifie(m.copy(decor = "abysse"), existe).decor)
        assertFalse(PlancheDecor.nomFichier("a/b.png")); assertFalse(PlancheDecor.nomFichier(".."))
    }

    @Test
    fun les_fonds_integres_sont_la_et_legers() {
        assertNotNull(PlancheDecor.integre(""))
        assertNull(PlancheDecor.integre("decor_x.jpg"))
        val images = PlancheDecor.INTEGRES.filter { it.ressource != null }
        assertTrue(images.size in 3..10)
        images.forEach { d ->
            val o = PlancheDecor.ouvre(d)!!.use { it.readBytes() }
            assertTrue(d.id + ": " + o.size, o.size in 120_000..360_000)
            assertTrue(d.source.startsWith("NASA"))
        }
        PlancheDecor.INTEGRES.filter { it.ressource == null }.forEach { assertEquals(it.couleurs.size, it.positions.size) }
    }

    @Test
    fun le_rangement_efface_les_images_que_plus_rien_n_utilise() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-decor-" + System.nanoTime())
        try {
            val r = Planche.Rangement(d)
            val garde = File(d, "ecusson_garde.png").apply { writeText("x"); setLastModified(0) }
            val orphelin = File(d, "decor_vieux.jpg").apply { writeText("x"); setLastModified(0) }
            val neuf = File(d, "ecusson_neuf.png").apply { writeText("x") }
            val fond = File(d, "fond_p.jpg").apply { writeText("x"); setLastModified(0) }
            r.enregistre(Planche.ajouteEcusson(Planche.genereGrille("p", "p"), garde.name, 10, 10))
            r.nettoie()
            assertTrue(garde.exists()); assertFalse(orphelin.exists())
            // Just written (its sheet perhaps not saved yet), and a template's own background: left.
            assertTrue(neuf.exists()); assertTrue(fond.exists())
            assertNotNull(r.fichier(garde.name)); assertNull(r.fichier("../" + garde.name))
        } finally { d.deleteRecursively() }
    }

    /**
     * The two sheets Olivier exported (2400×1697, SatMe_Planche_ISS_… and
     * SatMe_Planche_SSTV_planche.png) are the reference: their white box
     * edges and light frames, measured in pixels, are where the built-in
     * templates put them.
     */
    @Test
    fun les_modeles_integres_suivent_les_planches_de_reference() {
        val w = 2400f; val h = 1697f
        fun px(z: Planche.Zone) = listOf(z.x * w, z.y * h, (z.x + z.w) * w, (z.y + z.h) * h)
        fun proche(attendu: List<Number>, z: Planche.Zone) =
            px(z).zip(attendu).forEach { (a, b) -> assertEquals(b.toFloat(), a, 2f) }
        // « ISS · SSTV »: a 3×4 grid on the right; strokes (6 px) centred on these edges.
        val g = Planche.genereGrille("g", "g")
        val colonnes = listOf(813 to 1290, 1333 to 1810, 1853 to 2330)
        val rangees = listOf(70 to 429, 469 to 828, 868 to 1227, 1267 to 1626)
        g.cases.forEachIndexed { i, z ->
            val (x0, x1) = colonnes[i % 3]; val (y0, y1) = rangees[i / 3]
            proche(listOf(x0, y0, x1, y1), z)
        }
        // Callsign and locator in their light frames, bottom left.
        proche(listOf(96, 934, 576, 1086), g.textes.first { it.champ == Planche.Champ.INDICATIF }.zone)
        proche(listOf(96, 1154, 696, 1306), g.textes.first { it.champ == Planche.Champ.NOM_LOCATOR }.zone)
        // « SSTV »: twelve boxes round the edge, 4 at the top, 2 a side, 4 at the bottom.
        val t = Planche.genereTour("t", "t")
        val cols = listOf(69 to 566, 657 to 1154, 1245 to 1742, 1833 to 2330)
        val rows = listOf(50 to 424, 457 to 832, 864 to 1239, 1272 to 1646)
        val ordre = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 3 to 1, 3 to 2, 3 to 3, 2 to 3, 1 to 3, 0 to 3, 0 to 2, 0 to 1)
        t.cases.forEachIndexed { i, z ->
            val (c, r) = ordre[i]
            proche(listOf(cols[c].first, rows[r].first, cols[c].second, rows[r].second), z)
        }
    }
}
