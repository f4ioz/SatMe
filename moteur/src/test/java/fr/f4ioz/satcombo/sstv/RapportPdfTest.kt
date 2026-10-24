/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The report laid out on A4 pages: every page headed, nothing off the page, no card cut, every number a number. */
class RapportPdfTest {

    /** A document that keeps what is drawn: texts and boxes, page by page. */
    private class Releve : RapportPdf.Document {
        val textes = ArrayList<MutableList<Triple<String, Float, Float>>>()
        val boites = ArrayList<MutableList<FloatArray>>()
        val images = ArrayList<FloatArray>()
        val nombres = ArrayList<Float>()
        override fun largeur(s: String, taille: Float, gras: Boolean) = s.length * taille * (if (gras) 0.58f else 0.52f)
        override fun taille(image: ByteArray) = runCatching { ImageIO.read(ByteArrayInputStream(image))?.let { it.width to it.height } }.getOrNull()
        override fun page(): RapportPdf.Toile {
            textes.add(mutableListOf()); boites.add(mutableListOf())
            return object : RapportPdf.Toile {
                override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) {
                    textes.last() += Triple(s, x, y); nombres += listOf(x, y, taille)
                }
                override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) {
                    nombres += xy.toList() + epaisseur
                }
                override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
                    boites.last() += floatArrayOf(x, y, w, h); nombres += listOf(x, y, w, h)
                }
                override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) { nombres += listOf(x, y, r) }
                override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) { images += floatArrayOf(x, y, w, h) }
            }
        }
    }

    @Test fun le_rapport_tient_sur_des_pages_a4_entetees_sans_rien_couper() {
        for (langue in listOf("fr", "en")) {
            I18n.apply(langue, langue)
            val r = RapportEssai.rapport()
            val d = Releve()
            val n = RapportPdf.dessine(r, d)
            assertEquals(n, d.textes.size)
            assertTrue("pages $n", n >= 2)
            // Each page: the event's name and « page i/N » above.
            d.textes.forEachIndexed { i, l ->
                assertTrue(l.any { it.first == "page ${i + 1}/$n" })
                assertTrue(l.any { it.first.contains("ARISS série 34") })
            }
            assertTrue(d.textes.flatten().any { it.first.contains("F4IOZ") })
            assertTrue(d.textes.flatten().any { it.first.contains("IC-9700") })
            // The sheet and each slide received drawn, inside the page.
            assertEquals(1 + 9, d.images.size)
            for (b in d.images + d.boites.flatten()) {
                assertTrue(b.joinToString(), b[0] >= 33f && b[0] + b[2] <= RapportPdf.LARGEUR - 33f)
                assertTrue(b.joinToString(), b[1] >= 0f && b[1] + b[3] <= RapportPdf.HAUTEUR - 34f)
            }
            for (l in d.textes) for ((s, x, y) in l) assertTrue("$s $x $y", y in 30f..RapportPdf.HAUTEUR - 30f && x >= 30f)
            assertTrue(d.nombres.all { it.isFinite() })
        }
        I18n.apply("fr", "fr")
    }

    @Test fun le_dessin_du_montage_tient_dans_sa_carte() {
        I18n.apply("fr", "fr")
        val r = RapportEssai.rapport(montage = true)
        val d = Releve()
        val n = RapportPdf.dessine(r, d)
        val tout = d.textes.flatten().map { it.first }
        assertTrue(tout.filter { it.contains("SSTV") || it.contains("MHz") || it.contains("Poste") }.toString(),
            tout.contains("SSTV réception") && tout.contains("25m · 437,396 MHz · N") && tout.contains("▸ Poste (CAT)"))
        // The logo in the header and on every page's running head, the rig's photo in the drawing.
        assertEquals(1 + 9 + 1 + n + 1, d.images.size)
        for (b in d.images + d.boites.flatten()) {
            assertTrue(b.joinToString(), b[0] >= 33f && b[0] + b[2] <= RapportPdf.LARGEUR - 33f)
            assertTrue(b.joinToString(), b[1] >= 0f && b[1] + b[3] <= RapportPdf.HAUTEUR - 34f)
        }
        assertTrue(d.nombres.all { it.isFinite() })
    }

    @Test fun un_evenement_sans_rien_fait_une_page() {
        val ev = EvenementSstv.Evenement("i:x", EvenementSstv.Genre.IMAGES, "Rien", "ISS", 0, RapportEssai.T0, RapportEssai.T0 + 86_400_000L)
        val vide = RapportEvenement.construit(ev, SerieSstv.Prevision(ev.serie(), emptyList(), null, null, emptySet(), 10.0, emptyList()),
            emptyList(), RapportEvenement.Station("", ""), 10.0, RapportEssai.T0)
        val d = Releve()
        assertEquals(1, RapportPdf.dessine(vide, d))
        assertTrue(d.textes[0].any { it.first == "0/12" })
        assertTrue(d.nombres.all { it.isFinite() })
        assertTrue(RapportEvenement.nomFichier(vide, "pdf").matches(Regex("SatMe_Rapport_Rien_\\d{8}\\.pdf")))
    }
}
