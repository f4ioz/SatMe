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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

class ClassementSstvTest {

    private val W = 320
    private val H = 240

    /** A slide like an ARISS one: dark sky, a title, a photo of its own (blocks drawn from [n]), the footer. */
    private fun diapo(n: Int): IntArray {
        val r = Random(n * 7919)
        val px = IntArray(W * H) { 0xFF060A14.toInt() }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = c }
        rect(110, 8, 210, 30, 0xFF6FA0E0.toInt())                     // « ARISS »
        val px0 = 20 + r.nextInt(60); val py0 = 40 + r.nextInt(20)
        for (k in 0 until 40) {                                           // the photo
            val x = px0 + r.nextInt(150); val y = py0 + r.nextInt(120)
            // Photo colours (no bright green: the decoder's noise is green, a slide seldom).
            val rr = r.nextInt(40, 255); val bb = r.nextInt(40, 255)
            val c = (0xFF shl 24) or (rr shl 16) or (r.nextInt(40, maxOf(rr, bb) + 1) shl 8) or bb
            rect(x, y, minOf(W, x + 10 + r.nextInt(30)), minOf(H, y + 8 + r.nextInt(25)), c)
        }
        rect(10, 215, 90, 235, 0xFF6FA0E0.toInt()); rect(230, 215, 310, 235, 0xFF6FA0E0.toInt())   // NA1SS, RS0ISS
        return px
    }

    private fun bruite(px: IntArray, part: Float, graine: Int): IntArray {
        val r = Random(graine); val o = px.copyOf()
        for (y in 0 until H) if (r.nextFloat() < part) for (x in 0 until W) if (r.nextFloat() < 0.5f)
            o[y * W + x] = (0xFF shl 24) or (r.nextInt(256) shl 16) or (r.nextInt(256) shl 8) or r.nextInt(256)
        return o
    }

    private fun bandeVerte(px: IntArray, y0: Int, y1: Int): IntArray {
        val o = px.copyOf(); val r = Random(y0)
        for (y in y0 until y1) for (x in 0 until W) o[y * W + x] = (0xFF shl 24) or (r.nextInt(30) shl 16) or (r.nextInt(120, 255) shl 8) or r.nextInt(40)
        return o
    }

    /** Stopped early: the rows after [lignes] not there (saved shorter). */
    private fun coupe(px: IntArray, lignes: Int) = px.copyOf(W * lignes)

    /** Begun late: the slide moved up by [dy] rows, the end noise. */
    private fun decale(px: IntArray, dy: Int, dx: Int = 0): IntArray {
        val o = bruite(IntArray(W * H) { 0xFF000000.toInt() }, 1f, dy)
        for (y in 0 until H - dy) for (x in 0 until W) o[y * W + x] = px[(y + dy) * W + (x - dx).coerceIn(0, W - 1)]
        return o
    }

    private fun a(px: IntArray, h: Int = H, complete: Boolean = true) = ClassementSstv.analyse(px, W, h, 240, complete)

    private fun shot(n: String, t: Long, complete: Boolean = true) = SstvMeta.SstvShot(fileName = n, satName = "ISS", timeMs = t, mode = "Robot36", complete = complete)

    @Test fun les_copies_d_une_diapo_vont_ensemble_meme_abimees_coupees_ou_decalees() {
        val d1 = diapo(1); val d2 = diapo(2); val d3 = diapo(3)
        val im = linkedMapOf(
            "a1" to a(d1), "a1b" to a(bruite(d1, 0.3f, 5)), "a1c" to a(coupe(d1, 130), 130, false), "a1d" to a(decale(d1, 40, 3)),
            "a1v" to a(bandeVerte(d1, 60, 110)),
            "b2" to a(d2), "b2b" to a(bruite(d2, 0.25f, 9)), "c3" to a(d3))
        val shots = im.keys.mapIndexed { i, n -> shot(n, 1000L + i, n != "a1c") }
        val g = ClassementSstv.groupe(shots, im)
        val par = g.associate { gr -> gr.images.first().fileName to gr.images.map { it.fileName }.toSet() }
        assertEquals(3, g.size)
        assertEquals(setOf("a1", "a1b", "a1c", "a1d", "a1v"), par["a1"])
        assertEquals(setOf("b2", "b2b"), par["b2"])
        assertEquals(setOf("c3"), par["c3"])
        // Two slides are not alike, a copy is.
        assertTrue(ClassementSstv.ressemblance(im["a1"]!!, im["a1b"]!!)!! > ClassementSstv.SEUIL)
        assertTrue(ClassementSstv.ressemblance(im["a1"]!!, im["b2"]!!)!! < ClassementSstv.SEUIL)
    }

    @Test fun la_note_range_propre_puis_mouchetee_puis_bande_puis_coupee() {
        val d = diapo(4)
        val propre = a(d).note
        val mouchetee = a(bruite(d, 0.15f, 3)).note
        val bande = a(bandeVerte(d, 40, 140)).note
        val coupee = a(coupe(d, 80), 80, false).note
        assertTrue("propre $propre", propre >= 95)
        assertTrue("$propre > $mouchetee", propre > mouchetee)
        assertTrue("$mouchetee > $bande", mouchetee > bande)
        assertTrue("$bande > $coupee", bande > coupee)
        assertTrue(coupee in 20..45)
        // In its group, a picture begun late loses the top it lacks.
        val im = mapOf("p" to a(d), "q" to a(d), "late" to a(decale(d, 48)))
        val g = ClassementSstv.groupe(listOf(shot("p", 1), shot("q", 2), shot("late", 3)), im).single()
        assertEquals("late", g.images.last().fileName)
        assertTrue(g.notes["late"]!! <= im["late"]!!.note - 15)
    }

    @Test fun la_main_garde_ses_groupes_et_son_numero() {
        val d1 = diapo(1); val d2 = diapo(2)
        val im = mapOf("a" to a(d1), "b" to a(d1), "c" to a(d2))
        val shots = listOf(shot("a", 1), shot("b", 2).copy(groupe = "seule-b"), shot("c", 3).copy(diapo = 7, diapos = 12))
        val g = ClassementSstv.groupe(shots, im)
        assertEquals(3, g.size)
        val c = g.first { it.images.any { s -> s.fileName == "c" } }
        assertEquals(7, c.diapo); assertEquals(ClassementSstv.Origine.MAIN, c.origine)
    }

    // ------------------------------------------------------------ real pictures

    private fun reel(n: String): Pair<IntArray, Pair<Int, Int>> {
        val f = File(javaClass.getResource("/sstv-reel/$n")!!.toURI())
        val b = ImageIO.read(f)
        return b.getRGB(0, 0, b.width, b.height, null, 0, b.width) to (b.width to b.height)
    }

    /** Six pictures of ARISS series 34 (23–24 October 2026, F4IOZ), reduced to 256 colours. */
    @Test fun de_vraies_images_de_la_serie_34() {
        val noms = mapOf(
            "SatMe_SSTV_ISS_20261023_061205Z_Robot36.png" to 9, "SatMe_SSTV_ISS_20261023_030103Z_Robot36.png" to 9,
            "SatMe_SSTV_ISS_20261023_074609Z_Robot36.png" to 8, "SatMe_SSTV_ISS_20261023_043401Z_Robot36.png" to 8,
            "SatMe_SSTV_ISS_20261024_052521Z_Robot36.png" to 2, "SatMe_SSTV_ISS_20261024_052759Z_Robot36.png" to 3)
        val an = noms.keys.associateWith { n ->
            val (px, wh) = reel(n)
            ClassementSstv.avecLu(ClassementSstv.analyse(px, wh.first, wh.second, 240, true), LectureDiapo.lit(px, wh.first, wh.second))
        }
        val shots = noms.keys.map { SstvMeta.parseName(it).copy(satName = "ISS") }
        val g = ClassementSstv.groupe(shots, an)
        // One group per slide, nothing mixed (2 and 3 look much alike: same frame, dark sky).
        assertEquals(4, g.size)
        for (gr in g) assertEquals(1, gr.images.map { noms[it.fileName] }.toSet().size)
        // The clean copy first; the one begun late (its top lost) last.
        val g9 = g.first { it.images.any { s -> noms[s.fileName] == 9 } }
        assertEquals("SatMe_SSTV_ISS_20261023_061205Z_Robot36.png", g9.meilleure.fileName)
        val g8 = g.first { it.images.any { s -> noms[s.fileName] == 8 } }
        assertEquals("SatMe_SSTV_ISS_20261023_074609Z_Robot36.png", g8.meilleure.fileName)
        assertTrue(g8.notes["SatMe_SSTV_ISS_20261023_043401Z_Robot36.png"]!! < 75)
        // The footer read where it is read: never a wrong number.
        for ((n, a) in an) assertTrue("$n lu ${a.lu}", a.lu == 0 || a.lu == noms[n])
        assertTrue(an.values.count { it.lu > 0 } >= 4)
        // Each group numbered by what is read.
        for (gr in g) assertEquals(noms[gr.meilleure.fileName], gr.diapo)
    }

    @Test fun une_diapo_sans_son_motif_n_est_pas_lue() {
        // As 5 and 6 (no pattern): the number's own pattern taken away, nothing read rather than another number.
        for ((n, d) in listOf("SatMe_SSTV_ISS_20261023_061205Z_Robot36.png" to 9, "SatMe_SSTV_ISS_20261024_052759Z_Robot36.png" to 3)) {
            val (px, wh) = reel(n)
            val g = LectureDiapo.gris(px, wh.first, wh.second)
            assertEquals(d, LectureDiapo.lit(g))
            assertEquals(0, LectureDiapo.lit(g, LectureDiapo.NOMBRES - d))
        }
        // A picture of something else: nothing.
        assertEquals(0, LectureDiapo.lit(diapo(5), W, H))
    }

    // ------------------------------------------------------------ sidecar

    @Test fun le_numero_et_le_groupe_vont_dans_le_fichier_compagnon() {
        val s = SstvMeta.SstvShot(fileName = "SatMe_SSTV_ISS_20261023_061205Z_Robot36.png", satName = "ISS", diapo = 9, diapos = 12, groupe = "g1")
        val e = SstvMeta.encode(s)
        assertTrue(e.contains("slide=9/12")); assertTrue(e.contains("group=g1"))
        val l = SstvMeta.decode(s.fileName, e)
        assertEquals(9, l.diapo); assertEquals(12, l.diapos); assertEquals("g1", l.groupe)
        // Nothing set: no line (an older sidecar stays as it was).
        assertFalse(SstvMeta.encode(s.copy(diapo = 0, groupe = "")).contains("slide"))
        assertEquals(0, SstvMeta.decode(s.fileName, "slide=abc\n").diapo)
    }

    @Test fun la_galerie_numerote_le_groupe_et_deplace_une_image() {
        val dir = File(System.getProperty("java.io.tmpdir"), "satme-classement-" + System.nanoTime()).apply { mkdirs() }
        try {
            val d1 = diapo(1); val d2 = diapo(2)
            val pix = mapOf("SatMe_SSTV_ISS_20261023_010000Z_Robot36.png" to d1, "SatMe_SSTV_ISS_20261023_020000Z_Robot36.png" to bruite(d1, 0.2f, 1),
                "SatMe_SSTV_ISS_20261023_030000Z_Robot36.png" to d2)
            for ((n, px) in pix) {
                val b = java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB); b.setRGB(0, 0, W, H, px, 0, W)
                ImageIO.write(b, "png", File(dir, n))
            }
            fun shots() = pix.keys.map { n -> SstvMeta.decode(n, File(dir, SstvMeta.sidecarName(n)).takeIf { it.isFile }?.readText()) }
            val gal = ClassementGalerie { f -> ImageIO.read(f)?.let { b -> ClassementGalerie.Pixels(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height) } }
            val e0 = gal.etat(dir, shots(), attendre = true)
            val an = e0.analyses.values.toList()
            assertEquals("${an.map { it.note }} ${ClassementSstv.ressemblance(an[0], an[1])} ${ClassementSstv.ressemblance(an[0], an[2])}", 2, e0.groupes.size)
            assertTrue(gal.numerote(dir, shots(), "SatMe_SSTV_ISS_20261023_020000Z_Robot36.png", 7))
            val e = gal.etat(dir, shots(), attendre = true)
            val g7 = e.groupes.first { it.diapo == 7 }
            assertEquals(2, g7.images.size); assertEquals(ClassementSstv.Origine.MAIN, g7.origine)
            // Moved into the other group, then on its own.
            val autre = e.groupes.first { it !== g7 }
            assertTrue(gal.deplace(dir, shots(), "SatMe_SSTV_ISS_20261023_010000Z_Robot36.png", autre.id))
            val e2 = gal.etat(dir, shots(), attendre = true)
            assertEquals(2, e2.groupes.first { gr -> gr.images.any { it.fileName == "SatMe_SSTV_ISS_20261023_030000Z_Robot36.png" } }.images.size)
            assertTrue(gal.deplace(dir, shots(), "SatMe_SSTV_ISS_20261023_010000Z_Robot36.png", ClassementGalerie.SEULE))
            assertEquals(3, gal.etat(dir, shots(), attendre = true).groupes.size)
            // The sheet's signatures carry the groups: one copy per group.
            val sig = gal.signatures(pix.mapValues { (_, px) -> Planche.signature(px, W, H) }, gal.etat(dir, shots(), attendre = true))
            assertNotNull(sig.values.firstOrNull { it.groupe.isNotEmpty() && it.note >= 0 })
        } finally { dir.deleteRecursively() }
    }

    @Test fun remplir_prend_la_mieux_notee_de_chaque_groupe() {
        val d1 = diapo(1); val d2 = diapo(2)
        val sig = mapOf(
            "a" to Planche.signature(d1, W, H).avec("g1", 60), "b" to Planche.signature(d1, W, H).avec("g1", 95),
            "c" to Planche.signature(d2, W, H).avec("g2", 80), "d" to Planche.signature(d1, W, H).avec("g3", 30))
        val im = listOf(shot("a", 1000), shot("b", 2000), shot("c", 3000), shot("d", 4000))
        // One per group (g3 looks like g1 but the hand split it), the best of g1, the poor one last.
        assertEquals(mapOf(0 to "b", 1 to "c", 2 to "d"), Planche.remplitParReception(12, im, sig))
        assertEquals(mapOf(0 to "b"), Planche.remplitParReception(1, im, sig))
    }
}
