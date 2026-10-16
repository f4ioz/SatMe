/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.pc.IncrustationDatvPc.Position
import fr.f4ioz.satcombo.pc.IncrustationDatvPc.Reglage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread

class IncrustationDatvPcTest {

    private val ts = 429_832L
    private val ms = 1_760_000_000_000L

    /** The opaque pixels' bounds (alpha > 0), or null when all transparent. */
    private fun cadre(im: BufferedImage): IntArray? {
        var x0 = im.width; var y0 = im.height; var x1 = -1; var y1 = -1
        for (y in 0 until im.height) for (x in 0 until im.width) if ((im.getRGB(x, y) ushr 24) != 0) {
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }

    @Test fun `le rendu a la taille de l'image et reste transparent hors de la boite`() {
        val r = Reglage(indicatif = true, locator = true, texte = "Essai QO-100", logo = true)
        val im = IncrustationDatvPc.rendu(640, 360, r, "f4ioz", "jn18", "image", ms)
        assertEquals(640, im.width); assertEquals(360, im.height)
        assertTrue(im.colorModel.hasAlpha())
        val c = cadre(im)!!
        // Bottom left by default, inside the margins; the rest of the picture untouched.
        assertTrue(c[0] in 5..40 && c[3] in 320..355 && c[2] < 600 && c[1] > 180)
        assertEquals(0, im.getRGB(630, 10) ushr 24)
        assertEquals(0, im.getRGB(320, 10) ushr 24)
        // The box: dark and half transparent.
        val a = im.getRGB(c[0] + 3, c[3] - 3) ushr 24
        assertTrue("alpha $a", a in 100..200)
    }

    @Test fun `chaque position va dans son coin`() {
        fun c(p: Position) = cadre(IncrustationDatvPc.rendu(960, 540, Reglage(indicatif = true, position = p), "F4IOZ", "", "video", ms))!!
        val hg = c(Position.HAUT_GAUCHE); val hd = c(Position.HAUT_DROITE); val bg = c(Position.BAS_GAUCHE); val bd = c(Position.BAS_DROITE)
        assertTrue(hg[0] < 480 && hg[1] < 270 && hg[3] < 270)
        assertTrue(hd[0] > 480 && hd[1] < 270)
        assertTrue(bg[0] < 480 && bg[1] > 270)
        assertTrue(bd[0] > 480 && bd[1] > 270)
        val b = c(Position.BANDEAU)
        // The strip: the whole width, at the bottom.
        assertEquals(0, b[0]); assertEquals(959, b[2]); assertTrue(b[1] > 400)
    }

    @Test fun `sans fond le texte garde un contour`() {
        val im = IncrustationDatvPc.rendu(640, 360, Reglage(texte = "73", fond = false), "", "", "image", ms)
        val c = cadre(im)!!
        // Small: the text only, no box.
        assertTrue(c[2] - c[0] < 120)
    }

    @Test fun `un texte trop long tient dans l'image`() {
        val r = Reglage(texte = "W".repeat(60), taille = IncrustationDatvPc.Taille.GRAND)
        val im = IncrustationDatvPc.rendu(426, 240, r, "", "", "image", ms)
        val c = cadre(im)!!
        assertTrue(c[0] >= 0 && c[2] < 426)
    }

    @Test fun `sur la mire, pas de doublon`() {
        val r = Reglage(indicatif = true, locator = true, logo = true)
        assertFalse(IncrustationDatvPc.actif(r, "mire", "F4IOZ", "JN18"))
        assertFalse(IncrustationDatvPc.actif(r, "animee", "F4IOZ", "JN18"))
        assertTrue(IncrustationDatvPc.actif(r, "camera", "F4IOZ", "JN18"))
        assertNull(cadre(IncrustationDatvPc.rendu(640, 360, r, "F4IOZ", "JN18", "mire", ms)))
        assertTrue(IncrustationDatvPc.actif(r.copy(texte = "x"), "mire", "F4IOZ", "JN18"))
        assertFalse(IncrustationDatvPc.actif(Reglage(), "image", "F4IOZ", "JN18"))
    }

    @Test fun `l'heure change chaque seconde`() {
        val r = Reglage(heure = true)
        val a = IncrustationDatvPc.rendu(640, 360, r, "", "", "image", ms)
        val b = IncrustationDatvPc.rendu(640, 360, r, "", "", "image", ms + 1000)
        assertNotEquals(a.getRGB(0, 0, 640, 360, null, 0, 640).toList(), b.getRGB(0, 0, 640, 360, null, 0, 640).toList())
        assertEquals("08:53:20 UTC", IncrustationDatvPc.heure(ms))
    }

    @Test fun `reglages gardes et nettoyes`() {
        val st = StockageMemoire()
        val i = IncrustationDatvPc(st) { ms }
        assertFalse(i.json().getBoolean("indicatif"))
        i.regle(mapOf("indicatif" to "1", "texte" to "  a\nb\tc  " + "x".repeat(100), "position" to "bandeau", "taille" to "grand", "fond" to "0"))
        val r = IncrustationDatvPc(st).reglage
        assertTrue(r.indicatif); assertEquals(Position.BANDEAU, r.position); assertFalse(r.fond)
        assertTrue(r.texte.startsWith("a b c x")); assertEquals(IncrustationDatvPc.TEXTE_MAX, r.texte.length)
        i.regle(mapOf("position" to "n'importe"))
        assertEquals(Position.BAS_GAUCHE, i.reglage.position)
    }

    @Test fun `sans incrustation la commande ne change pas, avec elle le debit reste`() {
        for (src in listOf("mire", "animee", "image", "video", "camera")) {
            val p = EmissionDatvPc.ParametresTs(ts, src, File("/m.png"), "/dev/video0", "F4IOZ", false, true)
            val sans = EmissionDatvPc.commande("ff", p)
            assertEquals(src, sans, EmissionDatvPc.commande("ff", p.copy(incrustation = null)))
            val inc = IncrustationDatvPc.IncrustationTs(File("/o.png"), false, { ByteArray(0) })
            val avec = EmissionDatvPc.commande("ff", p.copy(incrustation = inc))
            assertEquals(src, sans[sans.indexOf("-muxrate") + 1], avec[avec.indexOf("-muxrate") + 1])
            assertEquals(src, sans[sans.indexOf("-b:v") + 1], avec[avec.indexOf("-b:v") + 1])
            assertTrue(src, "/o.png" in avec && "-vf" !in avec && avec[avec.indexOf("-map") + 1] == "[v]")
            // The sound's input number unchanged: the overlay comes last.
            val son = sans.zipWithNext().filter { it.first == "-map" }.map { it.second }.last()
            assertEquals(src, son, avec.zipWithNext().filter { it.first == "-map" }.map { it.second }.last())
        }
        val direct = IncrustationDatvPc.IncrustationTs(File("/o.png"), true, { ByteArray(0) })
        val l = EmissionDatvPc.commande("ff", EmissionDatvPc.ParametresTs(ts, "image", File("/i.png"), "", "F4IOZ", false, true, direct))
        assertTrue(l.containsAll(listOf("image2pipe", "pipe:0")) && "[2:v]scale=640:360,format=rgba[o]" in l.first { it.contains("overlay") })
    }

    @Test fun `la commande d'apercu sort une image sans son`() {
        val l = EmissionDatvPc.commande("ff", EmissionDatvPc.ParametresTs(ts, "animee", File("/m.png"), "", "F4IOZ", false, true))
        val a = IncrustationDatvPc.commandeApercu(l, File("/a.jpg"))
        assertTrue("-re" !in a && "-c:v" !in a && "mpegts" !in a && a.last() == "/a.jpg" && "-frames:v" in a)
        assertTrue(a.zipWithNext().none { it.first == "-map" && ":a:" in it.second })
    }

    /** With ffmpeg here: 3 s of the real command, the time running over a still picture; video, sound and the rate kept. */
    @Test fun `ffmpeg avec incrustation`() {
        val ff = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue(ff != null && File("/usr/bin/ffprobe").canExecute())
        val dir = kotlin.io.path.createTempDirectory("satme-incrustation").toFile()
        try {
            val png = File(dir, "photo.png")
            run(listOf(ff!!, "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=800x600", "-frames:v", "1", png.path))
            val st = StockageMemoire()
            val horloge = { System.currentTimeMillis() }
            val inc = IncrustationDatvPc(st, horloge)
            inc.regle(mapOf("indicatif" to "1", "locator" to "1", "logo" to "1", "heure" to "1", "texte" to "Essai SatMe", "position" to "bas-droite"))
            for (direct in listOf(true, false)) {
                val o = inc.pour(ts, "image", "F4IOZ", "JN18", File(dir, "inc.png"), direct)!!
                assertEquals(direct, o.direct)
                val out = File(dir, "flux-$direct.ts")
                val c = EmissionDatvPc.commande(ff, EmissionDatvPc.ParametresTs(ts, "image", png, "", "F4IOZ", false, true, o))
                val p = ProcessBuilder(c.dropLast(1) + listOf("-t", "3", "-y", out.path)).redirectError(File(dir, "err-$direct.txt")).start()
                if (o.direct) thread(isDaemon = true) { o.alimente(p.outputStream) { p.isAlive } } else p.outputStream.close()
                assertTrue(File(dir, "err-$direct.txt").let { "ffmpeg : " + (if (it.isFile) it.readText() else "") }, p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
                val probe = ProcessBuilder("/usr/bin/ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height",
                    "-of", "csv=p=0", out.path).redirectErrorStream(true).start()
                val vu = probe.inputStream.bufferedReader().readText()
                assertTrue(vu, "h264,video,640,360" in vu && "aac,audio" in vu)
                // The transport stream at its rate: 3 s of it (the muxer pads with null packets).
                val kbps = out.length() * 8 / 3.0
                assertTrue("débit $kbps", kbps in ts * 0.85..ts * 1.15)
                // The overlay is in the picture: bottom right differs from the plain blue.
                val jpg = File(dir, "f-$direct.png")
                run(listOf(ff, "-v", "error", "-ss", "2", "-i", out.path, "-frames:v", "1", "-y", jpg.path))
                val im = ImageIO.read(jpg)
                val bleu = im.getRGB(100, 100)
                assertTrue((bleu and 0xFF) > 150)
                assertNotEquals(bleu, im.getRGB(600, 340))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `l'alimentation s'arrete quand ffmpeg part`() {
        val o = IncrustationDatvPc.IncrustationTs(File("/x.png"), true, { ByteArray(10) }, { ms })
        val sortie = object : java.io.OutputStream() {
            var n = 0
            override fun write(b: Int) { if (++n > 25) throw java.io.IOException("fermé") }
        }
        val t = thread { o.alimente(sortie) }
        t.join(5000)
        assertFalse(t.isAlive)
        assertNotNull(ImageIO.read(ByteArrayInputStream(IncrustationDatvPc.octets(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)))))
    }

    private fun run(c: List<String>) {
        val p = ProcessBuilder(c).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { c.joinToString(" ") }
    }
}
