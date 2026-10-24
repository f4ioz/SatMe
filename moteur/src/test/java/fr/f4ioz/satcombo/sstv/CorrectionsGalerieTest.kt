/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Corrected pictures kept apart ([CorrectionsGalerie]): the received ones never touched, never listed with them. */
class CorrectionsGalerieTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-corrections-" + System.nanoTime()).also { it.mkdirs() }
    @After fun nettoie() { d.deleteRecursively() }

    private fun charge(f: File): ClassementGalerie.Pixels? = runCatching {
        val b = ImageIO.read(f) ?: return null
        ClassementGalerie.Pixels(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
    }.getOrNull()
    private fun ecrit(f: File, px: IntArray, w: Int, h: Int) = runCatching {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, px, 0, w) }, "png", f)
    }.getOrDefault(false)
    private val c = CorrectionsGalerie(::charge, ::ecrit) { 1_800_000_000_000L }
    private val r36 = SstvMode.byName("Robot 36")!!

    /** A slide: dark sky, a photo-like block, white letters, a red title. */
    private fun mire(): IntArray = IntArray(320 * 240) { i -> val x = i % 320; val y = i / 320
        when {
            y in 40..150 && x in 40..240 -> { val r = 80 + (x - 40) / 2; val g = 60 + (y - 40); val b = 120 - (x - 40) / 4; (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
            y in 170..185 && x in 30..290 && (x / 6) % 3 != 2 -> 0xFFF0F0F0.toInt()
            y in 200..215 && x in 60..250 && (x / 5) % 2 == 0 -> 0xFFD02020.toInt()
            else -> 0xFF0A0A18.toInt()
        } }

    /** A received picture: PNG (spoilt as a bad live decoding would: rows shifted), its sidecar, and its sound when [son]. */
    private fun recue(nom: String, son: Boolean, graine: Long): SstvMeta.SstvShot {
        val img = mire()
        val abime = img.copyOf()
        val rnd = java.util.Random(graine)
        for (y in 60 until 240) { val dx = if (y in 100..180) 23 else 0; for (x in 0 until 320) abime[y * 320 + x] = img[y * 320 + (x + dx) % 320] }
        for (i in abime.indices) if (rnd.nextDouble() < 0.04) abime[i] = 0xFF10C020.toInt()
        ecrit(File(d, nom), abime, 320, 240)
        val s = SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = 1_790_000_000_000L + graine * 300_000L, mode = r36.name, source = "live")
        File(d, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(s))
        if (son) {
            val pcm = SstvEncoder.encode(r36, img, 12_000)
            val b = ShortArray(pcm.size) { (pcm[it] + rnd.nextGaussian() * 1200).roundToInt().coerceIn(-32768, 32767).toShort() }
            SstvSon.ecritWav(SstvSon.fichier(File(d, nom)), b, 12_000)
        }
        return s
    }

    @Test fun corrigee_du_son_a_cote_l_originale_intacte() {
        val s = recue("SatMe_SSTV_ISS_20261023_074848Z_Robot36.png", son = true, graine = 1)
        val avant = File(d, s.fileName).readBytes()
        val i = c.corrige(d, s)
        val f = i.fiche
        assertNotNull(i.erreur, f)
        assertEquals(CorrectionsGalerie.Methode.SON, f!!.methode)
        assertTrue("${f.noteAvant} → ${f.noteApres}", f.noteApres > f.noteAvant)
        assertEquals("SatMe_SSTV_ISS_20261023_074848Z_Robot36_corrigee.png", f.fichier)
        assertArrayEquals(avant, File(d, s.fileName).readBytes())
        // Apart: the gallery lists *.png of its own folder only.
        assertEquals(listOf(s.fileName), d.listFiles { x -> x.name.endsWith(".png") }!!.map { it.name })
        assertEquals(f, c.corrigee(d, s.fileName))
        assertNotNull(c.fichier(d, f.fichier))
        assertNull(c.fichier(d, "../" + s.fileName)); assertNull(c.fichier(d, s.fileName))
        c.oublie(d, s.fileName)
        assertNull(c.corrigee(d, s.fileName))
        assertArrayEquals(avant, File(d, s.fileName).readBytes())
    }

    @Test fun sans_son_l_image_seule() {
        val s = recue("SatMe_SSTV_ISS_20261023_075127Z_Robot36.png", son = false, graine = 2)
        assertEquals(CorrectionsGalerie.SANS_SON, c.corrige(d, s, CorrectionsGalerie.Methode.SON).erreur)
        val f = c.corrige(d, s).fiche!!
        assertEquals(CorrectionsGalerie.Methode.IMAGE, f.methode)
        assertTrue(f.noteApres >= f.noteAvant)
    }

    @Test fun fusion_d_un_groupe() {
        val l = listOf(recue("SatMe_SSTV_ISS_20261023_012016Z_Robot36.png", true, 3), recue("SatMe_SSTV_ISS_20261023_025545Z_Robot36.png", false, 4),
            recue("SatMe_SSTV_ISS_20261023_043121Z_Robot36.png", false, 5))
        val g = ClassementSstv.Groupe(l[0].fileName, l, 7, 12, ClassementSstv.Origine.MAIN, l.associate { it.fileName to 60 })
        val f = c.fusionne(d, g).fiche!!
        assertEquals(CorrectionsGalerie.Methode.FUSION, f.methode)
        assertEquals(3, f.copies.size); assertEquals(1, f.duSon)
        assertEquals(60, f.noteAvant)
        assertTrue("${f.noteApres}", f.noteApres > 60)
        assertEquals(f, c.fusion(d, g.id))
        // A sheet takes the fusion for a picture of the group, the corrected one else.
        assertEquals(f.fichier, c.pourPlanche(d, l[1].fileName, g.id)!!.first.name)
        assertNull(c.pourPlanche(d, l[1].fileName, null))
        assertEquals(CorrectionsGalerie.UNE_COPIE, c.fusionne(d, g.copy(images = l.take(1))).erreur)
    }

    @Test fun la_fiche_se_relit() {
        val f = CorrectionsGalerie.Fiche("a.png", "a_corrigee.png", CorrectionsGalerie.Methode.SON, 49, 79, 123L, derivePpm = -100, bruitHz = 21, gain = 0.92)
        assertEquals(f, CorrectionsGalerie.decode("a_corrigee.png", CorrectionsGalerie.encode(f)))
        assertNull(CorrectionsGalerie.decode("x.png", "rien=1"))
        assertFalse(CorrectionsGalerie.nomValide("SatMe_SSTV_ISS_20261023_074848Z_Robot36.png"))
        assertTrue(CorrectionsGalerie.nomValide(CorrectionsGalerie.nomFusion("seule-x/../y")))
        // Never taken for a reception (the ARISS pack).
        assertTrue(CorrectionsGalerie.estTraitee(File(File("sstv", "corrigees"), "a.png")))
        assertTrue(CorrectionsGalerie.estTraitee(File("sstv", "a_corrigee.png")))
        assertFalse(CorrectionsGalerie.estTraitee(File("sstv", "SatMe_SSTV_ISS_20261023_074848Z_Robot36.png")))
    }
}
