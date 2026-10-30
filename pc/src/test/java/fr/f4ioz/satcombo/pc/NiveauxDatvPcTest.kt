/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.OutputStream
import javax.imageio.ImageIO

/** « Qualité » of the DATV picture: the levels, their ffmpeg commands, the shared encoders' bookkeeping, the JPEG levels served. */
class NiveauxDatvPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the page's choice read, anything else is Source`() {
        assertEquals(NiveauDatvPc.P480, NiveauDatvPc.de("480"))
        assertEquals(NiveauDatvPc.P360, NiveauDatvPc.de("360p"))
        assertEquals(NiveauDatvPc.SOURCE, NiveauDatvPc.de("source"))
        assertEquals(NiveauDatvPc.SOURCE, NiveauDatvPc.de(null))
        assertEquals(NiveauDatvPc.SOURCE, NiveauDatvPc.de("1080"))
        // Smaller, lighter, never faster.
        val l = listOf(NiveauDatvPc.P720, NiveauDatvPc.P480, NiveauDatvPc.P360)
        assertEquals(l, l.sortedByDescending { it.lignes }); assertEquals(l, l.sortedByDescending { it.kbps })
        assertTrue(l.zipWithNext().all { (a, b) -> a.ipsJpeg >= b.ipsJpeg && a.qJpeg <= b.qJpeg })
    }

    @Test fun `ffmpeg per level - H264 re-encoded at its size, rate, bit rate and profile, a key frame each second`() {
        val s = LecteurFluideDatvPc.commande("ffmpeg", copie = true, tolerant = false, niveau = NiveauDatvPc.SOURCE)
        assertTrue(s.joinToString(" ").contains("-c:v copy")); assertFalse(s.contains("libx264"))
        for (n in listOf(NiveauDatvPc.P720, NiveauDatvPc.P480, NiveauDatvPc.P360)) {
            // Re-encoded even when the source is H.264 (copie asked).
            val c = LecteurFluideDatvPc.commande("ffmpeg", copie = true, tolerant = false, niveau = n)
            val j = c.joinToString(" ")
            assertFalse(j.contains("-c:v copy"))
            assertTrue(j, c[c.indexOf("-vf") + 1].contains("scale=-2:'min(${n.lignes},ih)'"))
            assertTrue(c[c.indexOf("-vf") + 1].startsWith("yadif=deint=interlaced"))
            assertEquals("25", c[c.indexOf("-fpsmax") + 1])
            assertEquals("libx264", c[c.indexOf("-c:v") + 1])
            assertEquals(n.preset, c[c.indexOf("-preset") + 1])
            assertEquals(n.profil, c[c.indexOf("-profile:v") + 1])
            assertEquals("${n.kbps}k", c[c.indexOf("-maxrate") + 1])
            assertEquals("${n.kbps}k", c[c.indexOf("-bufsize") + 1])
            assertEquals("25", c[c.indexOf("-g") + 1])
            assertTrue(j.contains("-tune zerolatency")); assertTrue(j.contains("-c:a aac"))
            assertEquals("pipe:1", c.last())
        }
        assertEquals("baseline", NiveauDatvPc.P360.profil); assertEquals("main", NiveauDatvPc.P480.profil)
        assertEquals(listOf(2500, 1200, 600), listOf(NiveauDatvPc.P720, NiveauDatvPc.P480, NiveauDatvPc.P360).map { it.kbps })
    }

    @Test fun `ffmpeg per level - JPEG pictures in, smaller and lighter ones out`() {
        val c = ImagesDatvPc.commande("ffmpeg", NiveauDatvPc.P480)
        val j = c.joinToString(" ")
        assertTrue(j, j.contains("-f image2pipe -c:v mjpeg -i pipe:0"))
        assertEquals("scale=-2:'min(480,ih)'", c[c.indexOf("-vf") + 1])
        assertEquals("8", c[c.indexOf("-q:v") + 1])
        assertEquals("pipe:1", c.last())
        assertEquals("10", ImagesDatvPc.commande("ffmpeg", NiveauDatvPc.P360).let { it[it.indexOf("-q:v") + 1] })
        assertEquals(15, NiveauDatvPc.P360.ipsJpeg)
    }

    @Test fun `shared encoders - started, shared, let go after a while, two levels at most, the nearest one beyond`() {
        var t = 1000L
        val p = PartageNiveauxDatvPc(max = 2, gardeMs = 10_000L, horloge = { t })
        // Source re-encodes nothing: never counted.
        assertEquals(NiveauDatvPc.SOURCE, p.prend("mp4", NiveauDatvPc.SOURCE))
        assertTrue(p.actifs("mp4").isEmpty())
        // Started; a second page shares it.
        assertEquals(NiveauDatvPc.P480, p.prend("mp4", NiveauDatvPc.P480))
        assertEquals(NiveauDatvPc.P480, p.prend("mp4", NiveauDatvPc.P480))
        assertEquals(setOf(NiveauDatvPc.P480), p.actifs("mp4"))
        assertEquals(2, p.pages("mp4", NiveauDatvPc.P480))
        // A second level, of the JPEG stream: two in all.
        assertEquals(NiveauDatvPc.P360, p.prend("jpeg", NiveauDatvPc.P360))
        // A third: refused, the nearest of its sort instead, and why.
        assertEquals(NiveauDatvPc.P480, p.prend("mp4", NiveauDatvPc.P720))
        assertEquals(NiveauDatvPc.P360, p.prend("jpeg", NiveauDatvPc.P720))
        assertTrue(p.raison(NiveauDatvPc.P720, NiveauDatvPc.P480).contains("480p"))
        assertEquals("", p.raison(NiveauDatvPc.P480, NiveauDatvPc.P480))
        assertEquals(3, p.pages("mp4", NiveauDatvPc.P480))
        // Pages leave: kept 10 s (a page reloading finds it), then to stop.
        repeat(3) { p.rend("mp4", NiveauDatvPc.P480) }
        t += 9_000L
        assertTrue(p.aArreter("mp4").isEmpty())
        // Taken again meanwhile: kept.
        assertEquals(NiveauDatvPc.P480, p.prend("mp4", NiveauDatvPc.P480))
        t += 20_000L
        assertTrue(p.aArreter("mp4").isEmpty())
        p.rend("mp4", NiveauDatvPc.P480)
        t += 10_000L
        assertEquals(listOf(NiveauDatvPc.P480), p.aArreter("mp4"))
        assertTrue(p.actifs("mp4").isEmpty())
        // Its place free: the 720p now possible; the JPEG 360p untouched (still used).
        assertEquals(NiveauDatvPc.P720, p.prend("mp4", NiveauDatvPc.P720))
        assertTrue(p.aArreter("jpeg").isEmpty())
        assertEquals(setOf(NiveauDatvPc.P360), p.actifs("jpeg"))
        // Nearest, the smaller of two as near.
        val q = PartageNiveauxDatvPc(max = 2, horloge = { t })
        q.prend("mp4", NiveauDatvPc.P720); q.prend("mp4", NiveauDatvPc.P360)
        assertEquals(NiveauDatvPc.P360, q.prend("mp4", NiveauDatvPc.P480))
        // None of its sort running: Source.
        assertEquals(NiveauDatvPc.SOURCE, q.prend("jpeg", NiveauDatvPc.P480))
        assertTrue(q.raison(NiveauDatvPc.P480, NiveauDatvPc.SOURCE).contains("la source"))
    }

    /** 720-line JPEG pictures made by ffmpeg (a test picture), as the receiver's decoder makes them. */
    private fun images(n: Int): List<ByteArray>? {
        val ff = RenduPc.ffmpeg() ?: return null
        val p = ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25", "-frames:v", n.toString(),
            "-q:v", "5", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val l = ArrayList<ByteArray>()
        val d = DatvPc.Decoupe()
        val b = ByteArray(65_536)
        while (true) { val k = p.inputStream.read(b); if (k < 0) break; d.ajoute(b, k) { l += it } }
        p.waitFor()
        return l.takeIf { it.size == n }
    }

    /** A page reading the stream: the pictures it got, until it has [n] (then it leaves). */
    private class Page(val n: Int) : OutputStream() {
        val images = ArrayList<ByteArray>()
        private val d = DatvPc.Decoupe()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (images.size >= n) throw java.io.IOException("assez")
            val c = b.copyOfRange(off, off + len)
            synchronized(images) { d.ajoute(c, c.size) { images += it } }
        }
    }

    @Test fun `JPEG levels really served - 480 lines, one encoder for two pages, stopped once nobody watches`() {
        val src = images(50); assumeTrue("ffmpeg", src != null)
        // The receiver's pictures, 25 a second, in a loop.
        val k = java.util.concurrent.atomic.AtomicInteger()
        val fil = Thread { while (!Thread.currentThread().isInterrupted) { try { Thread.sleep(40) } catch (_: InterruptedException) { break }; k.incrementAndGet() } }
            .apply { isDaemon = true; start() }
        val partage = PartageNiveauxDatvPc(gardeMs = 300L)
        val im = ImagesDatvPc({ src!![k.get() % src.size] }, { true }, { k.get().toLong() }, partage)
        try {
            val pages = (1..2).map { Page(15) }
            val fils = pages.mapIndexed { i, p -> Thread { im.sert(p, "page$i", NiveauDatvPc.P480) }.apply { start() } }
            Thread.sleep(300)
            assertEquals(2, partage.pages(ImagesDatvPc.SORTE, NiveauDatvPc.P480))
            assertEquals(setOf(NiveauDatvPc.P480), partage.actifs(ImagesDatvPc.SORTE))
            fils.forEach { it.join(20_000) }
            for (p in pages) {
                assertTrue("${p.images.size} images", p.images.size >= 15)
                val i = ImageIO.read(ByteArrayInputStream(p.images.last()))
                assertEquals(480, i.height); assertEquals(854, i.width)
                // Lighter than the source's.
                assertTrue(p.images.last().size < src!!.first().size)
            }
            val m = im.mesure("page0")
            assertEquals("480", m.getString("niveau")); assertTrue(m.getLong("octets") > 0); assertTrue(m.getBoolean("fini"))
            // Source: the pictures as they are, nothing started.
            val s = Page(3)
            im.sert(s, "src", NiveauDatvPc.SOURCE)
            assertEquals(720, ImageIO.read(ByteArrayInputStream(s.images.last())).height)
            assertEquals("source", im.mesure("src").getString("niveau"))
            // Nobody on 480 any more: stopped after its grace.
            Thread.sleep(400)
            im.menage()
            assertTrue(partage.actifs(ImagesDatvPc.SORTE).isEmpty())
        } finally { fil.interrupt() }
    }

    @Test fun `the route - the JPEG stream at the level asked, its measures by the page's id`() {
        val src = images(25); assumeTrue("ffmpeg", src != null)
        val k = java.util.concurrent.atomic.AtomicInteger()
        val fil = Thread { while (!Thread.currentThread().isInterrupted) { try { Thread.sleep(40) } catch (_: InterruptedException) { break }; k.incrementAndGet() } }
            .apply { isDaemon = true; start() }
        val st = StationPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), Catalogue(tmp.newFolder(), enLigne = false), PortsSeriePc(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val s = ServeurPc(st, 0, datv = DatvPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), tmp.newFolder()))
        s.imagesDatv = ImagesDatvPc({ src!![k.get() % src.size] }, { true }, { k.get().toLong() })
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val c = java.net.URL("$base/api/datv/video.mjpeg?q=360&id=tablette").openConnection() as java.net.HttpURLConnection
            c.readTimeout = 20_000
            assertEquals(200, c.responseCode)
            assertEquals("360", c.getHeaderField("X-Satme-Niveau"))
            val e = c.inputStream
            val l = ArrayList<ByteArray>(); val d = DatvPc.Decoupe(); val b = ByteArray(65536)
            while (l.size < 5) { val n = e.read(b); if (n < 0) break; d.ajoute(b, n) { l += it } }
            assertEquals(360, ImageIO.read(ByteArrayInputStream(l.last())).height)
            val m = org.json.JSONObject(java.net.URL("$base/api/datv/video/mesure?id=tablette").readText())
            assertEquals("360", m.getString("niveau")); assertEquals("360", m.getString("voulu"))
            assertTrue(m.getLong("images") >= 5); assertTrue(m.getLong("octets") > 0)
            assertFalse(org.json.JSONObject(java.net.URL("$base/api/datv/video/mesure?id=autre").readText()).getBoolean("ok"))
            c.disconnect()
        } finally { s.arrete(); fil.interrupt() }
    }
}
