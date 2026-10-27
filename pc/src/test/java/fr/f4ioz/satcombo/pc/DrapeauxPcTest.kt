/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.JournalFt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

/** The page's flags for the server's drawings: the atlas, the flags' colours, the codes without one, the FT panel's flags. */
class DrapeauxPcTest {

    private fun rgb(b: BufferedImage, fx: Double, fy: Double) = Color(b.getRGB((fx * (b.width - 1)).toInt(), (fy * (b.height - 1)).toInt()), true)
    private fun proche(c: Color, r: Int, g: Int, b: Int, tol: Int = 60) =
        Math.abs(c.red - r) <= tol && Math.abs(c.green - g) <= tol && Math.abs(c.blue - b) <= tol && c.alpha > 200
    private fun bleu(c: Color) = c.blue > 100 && c.blue > c.red + 40 && c.alpha > 200
    private fun rouge(c: Color) = c.red > 150 && c.green < 90 && c.blue < 90 && c.alpha > 200
    private fun blanc(c: Color) = c.red > 200 && c.green > 200 && c.blue > 200 && c.alpha > 200

    @Test
    fun l_atlas_se_charge() {
        assertTrue(DrapeauxPc.nombre > 250)
        for (c in listOf("fr", "de", "gb-sct", "gb-eng", "gb-wls", "us", "jp", "it")) {
            val i = DrapeauxPc.image(c)
            assertNotNull(c, i)
            assertTrue(c, i!!.height in 44..54 && i.width > i.height)
        }
        assertEquals(0.73f, DrapeauxPc.em.h, 0.05f)
    }

    @Test
    fun les_drapeaux_ont_leurs_couleurs() {
        // France: blue, white, red upright.
        val fr = DrapeauxPc.image("FR")!!
        assertTrue(bleu(rgb(fr, 0.2, 0.5))); assertTrue(blanc(rgb(fr, 0.5, 0.5))); assertTrue(rouge(rgb(fr, 0.8, 0.5)))
        // Germany: black, red, gold across.
        val de = DrapeauxPc.image("de")!!
        assertTrue(proche(rgb(de, 0.5, 0.2), 20, 20, 20)); assertTrue(rouge(rgb(de, 0.5, 0.5))); assertTrue(proche(rgb(de, 0.5, 0.8), 255, 205, 0))
        // Scotland: a white saltire on blue; England: a red cross on white.
        val sct = DrapeauxPc.image("SCT")!!
        assertTrue(blanc(rgb(sct, 0.5, 0.5))); assertTrue(bleu(rgb(sct, 0.5, 0.2))); assertTrue(bleu(rgb(sct, 0.15, 0.5)))
        assertTrue(DrapeauxPc.connu("gb-sct") && DrapeauxPc.connu("sct") && DrapeauxPc.connu("Sct"))
        val eng = DrapeauxPc.image("ENG")!!
        assertTrue(rouge(rgb(eng, 0.5, 0.5))); assertTrue(rouge(rgb(eng, 0.5, 0.15))); assertTrue(blanc(rgb(eng, 0.2, 0.25)))
        // The United States: the blue canton top left, red and white stripes.
        val us = DrapeauxPc.image("us")!!
        val canton = (5..35).flatMap { x -> (10..40).map { y -> rgb(us, x / 100.0, y / 100.0) } }
        assertTrue(canton.count { bleu(it) } > canton.size * 0.7)
        val bas = (0..40).map { rgb(us, 0.8, 0.5 + it / 100.0) }
        assertTrue(bas.any { rouge(it) } && bas.any { blanc(it) })
        // Transparent outside the flag's rounded corners.
        assertTrue(fr.getRGB(0, 0) ushr 24 < 60)
    }

    @Test
    fun un_code_sans_drapeau_ne_dessine_rien() {
        for (c in listOf("", "zz", "xx", "fra", null)) {
            assertFalse(DrapeauxPc.connu(c)); assertNull(DrapeauxPc.image(c)); assertEquals(0f, DrapeauxPc.largeur(c, 20f))
            val b = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            val g = b.createGraphics()
            assertEquals(0f, DrapeauxPc.dessine(g, c, 2f, 2f, 20f)); g.dispose()
            assertTrue((0 until 40).all { x -> (0 until 40).all { y -> b.getRGB(x, y) == 0 } })
        }
    }

    @Test
    fun un_drapeau_est_dessine_a_sa_place_et_sa_taille() {
        val b = BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB)
        val g = b.createGraphics()
        val w = DrapeauxPc.dessine(g, "fr", 10f, 20f, 15f); g.dispose()
        assertEquals(15f * DrapeauxPc.image("fr")!!.width / DrapeauxPc.image("fr")!!.height, w, 0.01f)
        assertTrue(bleu(Color(b.getRGB(10 + (w * 0.2f).toInt(), 27), true)))
        assertTrue(rouge(Color(b.getRGB(10 + (w * 0.8f).toInt(), 27), true)))
        assertEquals(0, b.getRGB(5, 27)); assertEquals(0, b.getRGB(50, 27)); assertEquals(0, b.getRGB(20, 10)); assertEquals(0, b.getRGB(20, 40))
    }

    @Test
    fun le_panneau_ft_montre_le_drapeau_de_chaque_ligne_a_droite() {
        assertEquals("DE", Dxcc.entite("DL3AB")?.iso); assertEquals("SCT", Dxcc.entite("MM0INH")?.iso); assertEquals("FR", Dxcc.entite("F5XYZ")?.iso)
        val t0 = 1_791_800_000_000L / 15_000L * 15_000L
        val l = listOf(
            JournalFt.Decode(t0, "FT8", -5, 0.1, 900, "CQ DL3AB JO62", "DL3AB", null, "JO62"),
            JournalFt.Decode(t0 + 15_000L, "FT8", -6, 0.1, 900, "CQ MM0INH IO85", "MM0INH", null, "IO85"),
            JournalFt.Decode(t0 + 30_000L, "FT8", -7, 0.1, 900, "CQ F5XYZ JN03", "F5XYZ", null, "JN03"),
            JournalFt.Decode(t0 + 45_000L, "FT8", -8, 0.1, 900, "CQ Q1ABC", "Q1ABC", null, null))
        val b = BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, 1000, 500)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        PanneauFtPc.dessine(g, l, JournalFt.Affichage(cadre = JournalFt.Cadre(0.1f, 0.2f, 0.4f, 0.5f), opacite = 100), t0 + 60_000L, 1000f, 500f, "F4IOZ", emptySet())
        g.dispose()
        // As the panel sets them: the rows under the title, the flag right-aligned at the margin, on the baseline.
        val m = JournalFt.mise(400f, 250f)
        val droite = 100f + 400f - m.marge
        val fh = DrapeauxPc.em.h * m.police
        fun ligne(k: Int): Pair<Float, Float> {
            val y = 100f + m.marge + m.titre + k * m.ligne
            val fm = b.createGraphics().apply { font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 1).deriveFont(m.police) }.fontMetrics
            val base = y + (m.ligne + fm.ascent - fm.descent) / 2
            return base - DrapeauxPc.em.haut * m.police to fh
        }
        fun px(code: String, k: Int, fx: Float, fy: Float): Color {
            val (haut, h) = ligne(k); val w = DrapeauxPc.largeur(code, h)
            return Color(b.getRGB((droite - w + fx * w).toInt(), (haut + fy * h).toInt()))
        }
        // Germany: black over red over gold.
        assertTrue(px("de", 0, 0.5f, 0.2f).let { it.red < 70 && it.green < 70 })
        assertTrue(rouge(px("de", 0, 0.5f, 0.5f)))
        assertTrue(px("de", 0, 0.5f, 0.82f).let { it.red > 180 && it.green > 140 && it.blue < 90 })
        // Scotland: blue, its white saltire's middle; France: blue, white, red.
        assertTrue(bleu(px("gb-sct", 1, 0.5f, 0.15f))); assertTrue(blanc(px("gb-sct", 1, 0.5f, 0.5f)))
        assertTrue(bleu(px("fr", 2, 0.2f, 0.5f))); assertTrue(rouge(px("fr", 2, 0.8f, 0.5f)))
        // No country for a Q callsign: neither a flag nor a code, the panel's background (210/255 over white) there.
        val (haut3, h3) = ligne(3)
        val fond = (0 until (2 * fh).toInt()).map { Color(b.getRGB((droite - it).toInt(), (haut3 + h3 / 2).toInt())) }
        assertTrue(fond.all { Math.abs(it.red - 49) <= 3 && Math.abs(it.blue - 59) <= 3 })
    }
}
