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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** « Re-décoder chaque image à la fin » ([RedecodageAuto], [CorrectionsGalerie.corrigeSiMieux]). */
class RedecodageAutoTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-auto-" + System.nanoTime()).also { it.mkdirs() }
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

    private fun mire(): IntArray = IntArray(320 * 240) { i -> val x = i % 320; val y = i / 320
        when {
            y in 40..150 && x in 40..240 -> { val r = 80 + (x - 40) / 2; val g = 60 + (y - 40); val b = 120 - (x - 40) / 4; (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
            y in 170..185 && x in 30..290 && (x / 6) % 3 != 2 -> 0xFFF0F0F0.toInt()
            else -> 0xFF0A0A18.toInt()
        } }

    /** A picture saved live: its PNG ([abimee]: rows shifted and specks, as a trapped live decoder made it; else already as good as decoding it again makes it), its sidecar and its sound. */
    private fun recue(nom: String, abimee: Boolean, graine: Long): SstvMeta.SstvShot {
        val img = mire()
        val pcm = SstvEncoder.encode(r36, img, 12_000)
        val rnd = java.util.Random(graine)
        val son = ShortArray(pcm.size) { (pcm[it] + rnd.nextGaussian() * 600).roundToInt().coerceIn(-32768, 32767).toShort() }
        val px = if (abimee) img.copyOf().also { a ->
            for (y in 60 until 240) { val dx = if (y in 100..180) 23 else 0; for (x in 0 until 320) a[y * 320 + x] = img[y * 320 + (x + dx) % 320] }
            for (i in a.indices) if (rnd.nextDouble() < 0.04) a[i] = 0xFF10C020.toInt()
        } else RedecodageSstv.decode(son, 12_000, r36)!!.let { r -> CorrectionSstv.corrige(SstvNettoyage.nettoie(r.pixels, 320, 240, r.lignes), 320, 240, r.lignes) }
        ecrit(File(d, nom), px, 320, 240)
        val s = SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = 1_790_000_000_000L + graine * 300_000L, mode = r36.name, complete = true, source = "live")
        File(d, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(s))
        SstvSon.ecritWav(SstvSon.fichier(File(d, nom)), son, 12_000)
        return s
    }

    @Test fun ne_bloque_pas_et_garde_l_originale() {
        val s = recue("SatMe_SSTV_ISS_20261023_012254Z_Robot36.png", abimee = true, graine = 1)
        val avant = File(d, s.fileName).readBytes()
        // The worker held until the caller has returned: handing a picture over never waits for its decoding.
        val feu = CountDownLatch(1); val fait = CountDownLatch(1)
        var issue: CorrectionsGalerie.Issue? = null
        val a = RedecodageAuto(c, { true }, lance = { r -> Thread { feu.await(); r.run() }.start() }, fini = { _, i -> issue = i; fait.countDown() })
        val t0 = System.nanoTime()
        assertTrue(a.demande(d, s))
        assertTrue("rendu en ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) < 200_000_000L)
        assertEquals(1, a.enAttente)
        assertNull("rien avant son tour", c.corrigee(d, s.fileName))
        feu.countDown()
        assertTrue(fait.await(60, TimeUnit.SECONDS))
        val f = issue!!.fiche
        assertNotNull(issue!!.erreur, f)
        assertEquals(CorrectionsGalerie.Methode.SON, f!!.methode)
        assertTrue(f.auto)
        assertTrue("${f.noteAvant} → ${f.noteApres}", f.noteApres > f.noteAvant)
        // The picture as received untouched; the best version the sheets take is the corrected one.
        assertArrayEquals(avant, File(d, s.fileName).readBytes())
        assertEquals(f.fichier, c.pourPlanche(d, s.fileName, f.noteAvant, null)!!.first.name)
        assertEquals(0, a.enAttente)
    }

    @Test fun ne_remplace_que_si_c_est_mieux() {
        // Saved as good as it gets: decoded again, it marks no better, nothing is kept.
        val s = recue("SatMe_SSTV_ISS_20261023_061445Z_Robot36.png", abimee = false, graine = 2)
        assertEquals(CorrectionsGalerie.PAS_MIEUX, c.corrigeSiMieux(d, s).erreur)
        assertNull(c.corrigee(d, s.fileName))
        // A corrected version made by hand stays.
        val t = recue("SatMe_SSTV_ISS_20261023_043914Z_Robot36.png", abimee = true, graine = 3)
        val main = c.corrige(d, t, CorrectionsGalerie.Methode.IMAGE).fiche!!
        assertEquals(CorrectionsGalerie.DEJA, c.corrigeSiMieux(d, t).erreur)
        assertEquals(main, c.corrigee(d, t.fileName))
        assertFalse(main.auto)
    }

    @Test fun option_coupee_ou_sans_son_rien() {
        val s = recue("SatMe_SSTV_ISS_20261023_074848Z_Robot36.png", abimee = true, graine = 4)
        var lances = 0
        assertFalse(RedecodageAuto(c, { false }, lance = { lances++ }).demande(d, s))
        SstvSon.fichier(File(d, s.fileName)).delete()
        assertFalse(RedecodageAuto(c, { true }, lance = { lances++ }).demande(d, s))
        assertEquals(0, lances)
        // Switched off before its turn: not done.
        val t = recue("SatMe_SSTV_ISS_20261023_075127Z_Robot36.png", abimee = true, graine = 5)
        var on = true; var issue: CorrectionsGalerie.Issue? = null; var job: Runnable? = null
        val a = RedecodageAuto(c, { on }, lance = { job = it }, fini = { _, i -> issue = i })
        assertTrue(a.demande(d, t))
        on = false; job!!.run()
        assertEquals(RedecodageAuto.COUPE, issue!!.erreur)
        assertNull(c.corrigee(d, t.fileName))
    }

    @Test fun le_fil_par_defaut_est_bas() {
        val l = CountDownLatch(1); var prio = -1; var demon = false
        RedecodageAuto.filBas()(Runnable { prio = Thread.currentThread().priority; demon = Thread.currentThread().isDaemon; l.countDown() })
        assertTrue(l.await(10, TimeUnit.SECONDS))
        assertEquals(Thread.MIN_PRIORITY, prio); assertTrue(demon)
    }
}
