/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The journal's closing card: its pictures near their own size, its plan in every format, what it says. */
class BilanPcTest {

    private val echantillons = File(System.getenv("SATME_SESSION") ?: "build/session-echantillons").apply { mkdirs() }
    private val formats = listOf(1280 to 720, 1920 to 1080, 854 to 480, 720 to 1280, 1080 to 1920, 480 to 854, 720 to 960)
    /** SSTV's sizes: Robot 36 / Martin, Scottie / PD 90, PD 120. */
    private val sstv = listOf(320 to 240, 320 to 256, 640 to 496)

    @Test
    fun les_images_jamais_agrandies_plus_de_1_5_ni_deformees_et_toujours_dans_la_zone() {
        for ((w, h) in formats) for (melange in 0..3) for (n in 1..12) {
            val tailles = (0 until n).map { if (melange == 3) sstv[it % sstv.size] else sstv[melange] }
            val p = BilanPc.plan(w, h, 6, avecPhotos = true)
            val z = p.photos!!
            val leg = 12 * p.u
            val l = BilanPc.mosaique(tailles, z.l, z.h, 10 * p.u, leg)
            assertEquals("$w×$h n=$n", n, l.size)
            l.forEachIndexed { k, pl ->
                val (iw, ih) = tailles[k]
                assertTrue("$w×$h n=$n ×${pl.echelle}", pl.echelle <= BilanPc.AGRANDI_MAX + 1e-6)
                assertTrue("$w×$h n=$n ${pl.l}/$iw", pl.l <= iw * BilanPc.AGRANDI_MAX + 1)
                // Its proportions kept (to the pixel of rounding).
                assertEquals("$w×$h n=$n", iw.toDouble() / ih, pl.l.toDouble() / pl.h, 2.0 * iw / ih / minOf(pl.l, pl.h))
                // In the pictures' area, its caption too; inside the frame.
                assertTrue("$w×$h n=$n $pl", BilanPc.Rect(0f, 0f, z.l, z.h).contient(BilanPc.Rect(pl.x, pl.y, pl.l, pl.h + leg)))
                assertTrue(BilanPc.Rect(0f, 0f, w.toFloat(), h.toFloat()).contient(BilanPc.Rect(z.x + pl.x, z.y + pl.y, pl.l, pl.h)))
                // No two overlapping.
                l.forEachIndexed { j, q -> if (j != k) assertTrue("$w×$h n=$n $k/$j", pl.x + pl.l <= q.x + 0.5f || q.x + q.l <= pl.x + 0.5f ||
                    pl.y + pl.h + leg <= q.y + 0.5f || q.y + q.h + leg <= pl.y + 0.5f) }
            }
            // Large enough to be seen: a single SSTV picture never smaller than its own size in a medium video.
            if (n == 1 && w * h >= 1280 * 720 && melange < 2) assertTrue("$w×$h ${l[0].echelle}", l[0].echelle >= 1f)
        }
    }

    @Test
    fun les_regles_d_echelle() {
        assertEquals(1.5f, BilanPc.echelle(4f), 0f)
        assertEquals(1f, BilanPc.echelle(1.1f), 0f)          // just above: its own size, pixel for pixel
        assertEquals(1.3f, BilanPc.echelle(1.3f), 0f)
        assertEquals(0.6f, BilanPc.echelle(0.6f), 0f)
        // One small picture in a big area: 1.5× at most, centred.
        val l = BilanPc.mosaique(listOf(320 to 256), 1000f, 800f, 10f, 20f).single()
        assertEquals(480f, l.l, 0.5f); assertEquals(384f, l.h, 0.5f)
        assertEquals(500f, l.x + l.l / 2, 1f)
        // Smaller when it must: never bigger than the area.
        val p = BilanPc.mosaique(listOf(640 to 496), 300f, 300f, 10f, 20f).single()
        assertTrue(p.l <= 300 && p.h + 20 <= 300); assertTrue(p.echelle < 1)
        assertTrue(BilanPc.mosaique(emptyList(), 300f, 300f, 10f, 20f).isEmpty())
        // Made smaller smoothly: the size asked, not one stretched pixel.
        val b = BufferedImage(640, 496, BufferedImage.TYPE_INT_RGB)
        val r = BilanPc.echelonne(b, 213, 165)
        assertEquals(213, r.width); assertEquals(165, r.height)
        assertTrue(BilanPc.echelonne(b, 640, 496) === b)
    }

    @Test
    fun le_plan_tient_dans_chaque_format() {
        for ((w, h) in formats) for (n in listOf(2, 5, 9)) for (photos in listOf(true, false)) {
            val p = BilanPc.plan(w, h, n, photos)
            val cadre = BilanPc.Rect(0f, 0f, w.toFloat(), h.toFloat())
            val zones = listOfNotNull(p.entete, p.chiffres, p.ciel, p.photos, p.pied)
            zones.forEach { assertTrue("$w×$h $n $photos $it", cadre.contient(it)) }
            // Nothing over anything else.
            for (a in zones) for (b in zones) if (a !== b) assertTrue("$w×$h $n $photos $a $b",
                a.droite <= b.x + 0.5f || b.droite <= a.x + 0.5f || a.bas <= b.y + 0.5f || b.bas <= a.y + 0.5f)
            if (photos) assertTrue("$w×$h", p.photos!!.l * p.photos!!.h > w * h * 0.25f) else assertNull(p.photos)
            p.ciel?.let { assertEquals(it.l, it.h, 0.01f) }
        }
    }

    private fun photo(n: Int, w: Int, h: Int): String {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        for (k in 0 until 8) { g.color = Color.getHSBColor((n * 0.21f + k * 0.1f) % 1f, 0.7f, 0.95f); g.fillRect(0, k * h / 8, w, h / 8 + 1) }
        g.color = Color.WHITE; g.font = g.font.deriveFont(w / 6f); g.drawString("SSTV $n", w / 8, h / 2); g.dispose()
        val f = File(echantillons, "bilan-photo-$n.png"); ImageIO.write(b, "png", f); return f.path
    }

    @Test
    fun le_bilan_dit_le_passage_et_se_dessine() {
        val de = 1_792_510_800_000L
        val e = JournalPassage.Entree(25544, "ISS", de, de + 600_000L, "JN18FS",
            points = (0..10).map { JournalPassage.Point(de + it * 60_000L, (300.0 + it * 12) % 360, if (it <= 5) 3.0 + it * 11 else 58.0 - (it - 5) * 11) })
        val marques = listOf(
            Marque(TypeMarque.SSTV, de + 60_000L, de + 180_000L, "PD120", fichier = photo(1, 640, 496)),
            Marque(TypeMarque.SSTV, de + 200_000L, de + 236_000L, "Robot 36", fichier = photo(2, 320, 240)),
            Marque(TypeMarque.SSTV, de + 300_000L, de + 410_000L, "PD90", fichier = photo(3, 320, 256)),
            Marque(TypeMarque.QSO, de + 250_000L, de + 250_000L, "F5XYZ"), Marque(TypeMarque.QSO, de + 260_000L, de + 260_000L, "EA4ABC"),
            Marque(TypeMarque.APRS, de + 270_000L, de + 270_000L, "DL1ABC-7", lat = 50.1, lon = 8.7))
        val fiches = mapOf("F5XYZ" to JournalPassage.Fiche("F5XYZ", locator = "JN03"), "EA4ABC" to JournalPassage.Fiche("EA4ABC", locator = "IN80"))
        val sc = RenduPc.Scene(e, emptyList(), marques, "F4IOZ", fiches = fiches, paysage = true)
        val d = BilanPc.donnees(listOf(sc), null)
        assertEquals("ISS", d.titre); assertEquals("F4IOZ · JN18FS", d.station)
        assertEquals(3, d.photos.size); assertEquals("1/3", d.photos[0].numero); assertEquals("PD120", d.photos[0].mode)
        assertTrue(d.photos[0].heure, d.photos[0].heure.endsWith("UTC"))
        val valeurs = d.chiffres.map { it.valeur }
        assertTrue(valeurs.toString(), "10 min 00 s" in valeurs && "58°" in valeurs && "3" in valeurs && "2" in valeurs)
        assertTrue(valeurs.toString(), valeurs.any { it.endsWith(" km") && it.contains('–') })
        assertEquals(1, d.traces.size); assertEquals(3, d.traces[0].sstv.size); assertEquals(2, d.traces[0].qsos.size)
        for ((w, h) in listOf(1280 to 720, 720 to 1280, 720 to 960)) {
            val img = BilanPc.image(w, h, d)
            assertEquals(w, img.width); assertEquals(h, img.height)
            ImageIO.write(img, "png", File(echantillons, "bilan-${w}x$h.png"))
        }
        // Without pictures nor track: the figures alone, still drawn.
        val vide = BilanPc.donnees(listOf(RenduPc.Scene(e.copy(points = emptyList()), emptyList(), emptyList(), "F4IOZ")), "Essai")
        assertEquals("Essai", vide.titre); assertEquals("ISS", vide.sousTitre); assertTrue(vide.photos.isEmpty())
        ImageIO.write(BilanPc.image(1280, 720, vide), "png", File(echantillons, "bilan-vide.png"))
    }
}
