/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The detailed journal's spectrum lines: the window, the averaging, the ring, the file, the picture. */
class SpectrePassageTest {

    /** A 64-bin spectrum: −100 dB, a peak of [pic] dB at bin [k]. */
    private fun sp(k: Int, pic: Float = -40f) = FloatArray(64) { if (it == k) pic else -100f }

    @Test
    fun la_fenetre_reechantillonne_et_refuse_ce_qui_deborde() {
        val s = FloatArray(64) { it.toFloat() }
        val f = SpectrePassage.fenetre(s, 32.0, 8.0, 16)!!
        assertEquals(16, f.size)
        // Linear bins: the columns walk from 24 to 40.
        assertEquals(24.5f, f.first(), 0.01f); assertEquals(39.5f, f.last(), 0.01f)
        assertNull(SpectrePassage.fenetre(s, 4.0, 8.0, 16))
        assertNull(SpectrePassage.fenetre(s, 60.0, 8.0, 16))
    }

    @Test
    fun une_ligne_moyenne_toutes_les_periodes() {
        val e = SpectrePassage.Enregistreur(bins = 8, periodeMs = 2_000L, capacite = 100)
        // Four spectra a second apart → two lines; the third second's peak on the second line.
        for (i in 0 until 4) assertTrue(e.ajoute(1_000L + i * 1_000L, if (i == 2) sp(32, -20f) else sp(32), 32.5, 4.0))
        e.termine()
        val l = e.lignes()
        assertEquals(2, l.size)
        assertEquals(1_500L, l[0].tMs)
        // The peak column: the second line's louder (one loud second averaged in).
        assertTrue(l[1].db.max() > l[0].db.max() + 5f)
        assertTrue(l[0].db.min() < -90f)
    }

    @Test
    fun l_anneau_plein_garde_tout_le_passage_en_decimant() {
        val e = SpectrePassage.Enregistreur(bins = 8, periodeMs = 1_000L, capacite = 10)
        // 40 s of spectra, one a second: more than ten lines of 1 s.
        for (i in 0..40) e.ajoute(i * 1_000L, sp(32, -40f + i), 32.5, 4.0)
        e.termine()
        val l = e.lignes()
        assertTrue("toujours borné : ${l.size}", l.size < 10)
        assertTrue(e.periodeMs >= 4_000L)
        // The whole span is still there, in order.
        assertTrue("${l.map { it.tMs }}", l.first().tMs < 4_000L && l.last().tMs in 34_000L..40_000L)
        assertEquals(l.map { it.tMs }.sorted(), l.map { it.tMs })
        // The peak still rises from line to line (pairs averaged, not dropped).
        assertTrue(l.zipWithNext().all { (a, b) -> b.db.max() > a.db.max() })
    }

    @Test
    fun le_fichier_relu_rend_les_lignes_au_demi_db() {
        val l = listOf(SpectrePassage.Ligne(1000L, floatArrayOf(-100f, -80.2f, -40f)), SpectrePassage.Ligne(3000L, floatArrayOf(-90f, -90f, -10f)))
        val b = SpectrePassage.Bande(25544, "ISS", 25_000.0, l)
        val o = ByteArrayOutputStream(); SpectrePassage.ecrit(b, o)
        val r = SpectrePassage.lit(ByteArrayInputStream(o.toByteArray()))
        assertEquals(25544, r.catnum); assertEquals("ISS", r.satellite); assertEquals(25_000.0, r.largeurHz, 0.0)
        assertEquals(listOf(1000L, 3000L), r.lignes.map { it.tMs })
        assertArrayEquals(floatArrayOf(-100f, -80f, -40f), r.lignes[0].db, 0.26f)
        assertArrayEquals(floatArrayOf(-90f, -90f, -10f), r.lignes[1].db, 0.26f)
    }

    @Test
    fun les_rapports_signal_bruit_et_l_image() {
        val l = (0 until 20).map { i -> SpectrePassage.Ligne(i * 2_000L, FloatArray(32) { k -> if (i in 5..14 && k == 16) -70f else -100f }) }
        val b = SpectrePassage.Bande(1, "X", 25_000.0, l)
        val r = SpectrePassage.rapports(b)
        assertEquals(10, r.count { it.second >= 6.0 })
        assertEquals(30.0, r.maxOf { it.second }, 0.01)
        val png = Png.encode(SpectrePassage.image(b, 40))
        val img = javax.imageio.ImageIO.read(ByteArrayInputStream(png))
        assertEquals(40, img.width); assertEquals(32, img.height)
        // The signal's row is brighter in the middle of the pass than at its start.
        val lum = { x: Int, y: Int -> img.getRGB(x, y).let { (it shr 16 and 255) + (it shr 8 and 255) + (it and 255) } }
        assertTrue(lum(20, 31 - 16) > lum(1, 31 - 16) + 200)
    }
}
