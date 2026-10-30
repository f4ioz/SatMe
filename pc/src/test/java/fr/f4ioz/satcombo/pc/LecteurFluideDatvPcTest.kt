/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** « Lecture fluide »: ffmpeg's command, its MP4 cut back into init and fragments, the key frames, the route. */
class LecteurFluideDatvPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `H264 copied, anything else re-encoded in H264 with a key frame each second, sound in AAC, fragmented MP4`() {
        val c = LecteurFluideDatvPc.commande("ffmpeg", copie = true, tolerant = false)
        assertTrue(c.joinToString(" ").contains("-i pipe:0 -map 0:v:0 -map 0:a:0? -c:v copy"))
        assertFalse(c.contains("libx264"))
        assertTrue(c.joinToString(" ").contains("-c:a aac"))
        assertEquals("frag_keyframe+empty_moov+default_base_moof", c[c.indexOf("-movflags") + 1])
        assertEquals("200000", c[c.indexOf("-frag_duration") + 1])
        assertEquals("pipe:1", c.last())
        val h = LecteurFluideDatvPc.commande("ffmpeg", copie = false, tolerant = true)
        assertTrue(h.contains("libx264")); assertTrue(h.contains("zerolatency")); assertEquals("veryfast", h[h.indexOf("-preset") + 1])
        assertEquals("25", h[h.indexOf("-g") + 1])
        assertTrue(h[h.indexOf("-vf") + 1].contains("min(720,ih)"))
        // « Décodage tolérant » as for the other players.
        assertTrue(h.contains("ignore_err"))
        assertTrue(LecteurFluideDatvPc.copie("avc1.640028")); assertFalse(LecteurFluideDatvPc.copie("hvc1.1.6.L120.90"))
        assertFalse(LecteurFluideDatvPc.copie("mp4v.61")); assertFalse(LecteurFluideDatvPc.copie(null))
    }

    @Test fun `the stream aligned on its packets`() {
        val ts = ByteArray(5 + 188 * 3 + 7) { 0 }
        for (k in 0 until 3) ts[5 + k * 188] = 0x47
        ts[2] = 0x47   // a false start: no packet 188 bytes further
        val a = LecteurFluideDatvPc.aligne(ts)
        assertEquals(188 * 3, a.size)
        assertEquals(0x47.toByte(), a[0]); assertEquals(0x47.toByte(), a[188])
        assertEquals(0, LecteurFluideDatvPc.aligne(ByteArray(300)).size)
    }

    @Test fun `boxes cut back whole whatever the reads' sizes`() {
        fun boite(type: String, n: Int) = ByteArray(8 + n).also { b ->
            val t = 8 + n; b[0] = (t shr 24).toByte(); b[1] = (t shr 16).toByte(); b[2] = (t shr 8).toByte(); b[3] = t.toByte()
            type.toByteArray().copyInto(b, 4)
        }
        val flux = boite("ftyp", 12) + boite("moov", 100) + boite("moof", 40) + boite("mdat", 3000) + boite("moof", 40) + boite("mdat", 10)
        val inits = ArrayList<ByteArray>(); val frags = ArrayList<ByteArray>()
        val d = LecteurFluideDatvPc.DecoupeMp4()
        var o = 0
        for (n in listOf(3, 50, 7, 1000, 2000, 500, 1000)) { val k = minOf(n, flux.size - o); if (k <= 0) break; d.ajoute(flux.copyOfRange(o, o + k), k, { inits += it }, { frags += it }); o += k }
        assertEquals(1, inits.size); assertEquals(20 + 108, inits[0].size)
        assertEquals(listOf(48 + 3008, 48 + 18), frags.map { it.size })
    }

    /** A few seconds of a DATV-like stream (H.264 or MPEG-2 and MP2 in a transport stream), made by ffmpeg. */
    private fun ts(video: String): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        val f = File(tmp.root, "essai-$video.ts")
        val p = ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i",
            "sine=frequency=600:sample_rate=48000", "-t", "4", "-c:v", video, "-g", "25", "-b:v", "800k", "-c:a", "mp2", "-b:a", "128k", "-f", "mpegts", f.path)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return f.takeIf { p.waitFor() == 0 && it.length() > 10_000 }
    }

    private fun passe(ts: File, copie: Boolean): Pair<ByteArray?, List<ByteArray>> {
        val ff = RenduPc.ffmpeg()!!
        val p = ProcessBuilder(LecteurFluideDatvPc.commande(ff, copie, false)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        Thread { p.outputStream.use { o -> o.write(ts.readBytes()) } }.start()
        var init: ByteArray? = null
        val frags = ArrayList<ByteArray>()
        val d = LecteurFluideDatvPc.DecoupeMp4()
        val b = ByteArray(4096)
        while (true) { val n = p.inputStream.read(b); if (n < 0) break; d.ajoute(b, n, { init = it }, { frags += it }) }
        p.waitFor()
        return init to frags
    }

    @Test fun `H264 copied - its codecs, fragments of 200 ms at most, the key frames found`() {
        val f = ts("libx264"); assumeTrue("ffmpeg with libx264", f != null)
        val (init, frags) = passe(f!!, copie = true)
        assertNotNull(init)
        val codecs = LecteurFluideDatvPc.codecsInit(init!!)!!
        assertTrue(codecs, Regex("^avc1\\.[0-9a-f]{6},mp4a\\.40\\.2$").matches(codecs))
        val piste = LecteurFluideDatvPc.pisteVideo(init)!!
        val defaut = LecteurFluideDatvPc.drapeauxTrex(init, piste)
        val cles = frags.map { LecteurFluideDatvPc.estCle(it, piste, defaut) }
        // 4 s at a key frame each second, cut every 200 ms: about 20 fragments, a key one at each key frame (a scene cut may add one), the first among them.
        assertTrue("${frags.size} fragments", frags.size >= 15)
        assertTrue(cles.first())
        assertTrue("${cles.count { it }} key fragments", cles.count { it } in 3..8)
        assertTrue(cles.toString(), cles.count { !it } >= 8)
    }

    @Test fun `MPEG-2 re-encoded in H264 Main`() {
        val f = ts("mpeg2video"); assumeTrue("ffmpeg", f != null)
        val (init, frags) = passe(f!!, copie = false)
        assertEquals("avc1.4d", LecteurFluideDatvPc.codecsInit(init!!)!!.take(7))
        assertTrue(frags.size >= 10)
    }

    @Test fun `the route - the stream with its codecs to this PC, its state, nothing to another machine without a session`() {
        val f = ts("libx264"); assumeTrue("ffmpeg with libx264", f != null)
        val octets = f!!.readBytes()
        val lecteur = LecteurFluideDatvPc()
        val sons = ArrayList<Pair<Boolean, Boolean>>()
        lecteur.sonNavigateur = { ici, page -> synchronized(sons) { sons += ici to page } }
        // A source playing the file in a loop, at about its pace, while taken.
        val vers = java.util.concurrent.atomic.AtomicReference<((ByteArray) -> Unit)?>(null)
        lecteur.source = object : VideoSitePc.SourceTs {
            override fun branche(f: ((ByteArray) -> Unit)?) { vers.set(f) }
            override fun verrouille() = true
            override fun service() = "essai"
            override fun kbps() = 1000
        }
        val fil = Thread {
            var o = 0
            while (!Thread.currentThread().isInterrupted) {
                val v = vers.get()
                if (v == null) { Thread.sleep(50); continue }
                val n = minOf(188 * 7 * 4, octets.size - o)
                v(octets.copyOfRange(o, o + n)); o = (o + n) % octets.size
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        lecteur.demarre()
        val st = StationPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), Catalogue(tmp.newFolder(), enLigne = false), PortsSeriePc(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val s = ServeurPc(st, 0)
        s.fluideDatv = lecteur
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val c = URL("$base/api/datv/fluide.mp4?id=essai&son=1").openConnection() as HttpURLConnection
            c.readTimeout = 20_000
            assertEquals(200, c.responseCode)
            assertEquals("video/mp4", c.contentType)
            assertTrue(c.getHeaderField("X-Satme-Codecs").startsWith("avc1."))
            assertEquals("copie", c.getHeaderField("X-Satme-Mode"))
            val e = c.inputStream
            val debut = ByteArray(8); var lu = 0
            while (lu < 8) lu += e.read(debut, lu, 8 - lu)
            assertEquals("ftyp", String(debut, 4, 4))
            // The data keeps coming (a few seconds' worth).
            val b = ByteArray(65536); var total = 0; val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 2500) { val n = e.read(b); if (n < 0) break; total += n }
            assertTrue("$total bytes", total > 50_000)
            val j = JSONObject(URL("$base/api/datv/fluide").readText())
            assertTrue(j.getBoolean("enMarche")); assertEquals(1, j.getInt("clients")); assertEquals("copie", j.getString("mode"))
            // A page on this PC playing the sound: the PC's own told to be quiet.
            assertTrue(synchronized(sons) { sons.contains(true to true) })
            c.disconnect()
            // Another machine, no session: refused like every route.
            val acces = AccesDistantPc(tmp.newFolder(), reseau = true, iterations = 1000)
            acces.regle("mot-de-passe-du-mat")
            s.acces = acces
            s.estLocal = { false }
            val r = URL("$base/api/datv/fluide.mp4").openConnection() as HttpURLConnection
            assertTrue(r.responseCode in listOf(401, 403))
        } finally {
            s.arrete(); fil.interrupt()
        }
    }

    @Test fun `a smaller level - its own ffmpeg in H264 Baseline at 360 lines, shared, Source untouched`() {
        val f = ts("libx264"); assumeTrue("ffmpeg with libx264", f != null)
        val octets = f!!.readBytes()
        val lecteur = LecteurFluideDatvPc()
        val vers = java.util.concurrent.atomic.AtomicReference<((ByteArray) -> Unit)?>(null)
        lecteur.source = object : VideoSitePc.SourceTs {
            override fun branche(f: ((ByteArray) -> Unit)?) { vers.set(f) }
            override fun verrouille() = true
            override fun service() = "essai"
            override fun kbps() = 1000
        }
        val fil = Thread {
            var o = 0
            while (!Thread.currentThread().isInterrupted) {
                val v = vers.get()
                if (v == null) { try { Thread.sleep(50) } catch (_: InterruptedException) { break }; continue }
                val n = minOf(188 * 7 * 4, octets.size - o)
                v(octets.copyOfRange(o, o + n)); o = (o + n) % octets.size
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        lecteur.demarre()
        val st = StationPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), Catalogue(tmp.newFolder(), enLigne = false), PortsSeriePc(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val s = ServeurPc(st, 0)
        s.fluideDatv = lecteur
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            fun page(q: String) = (URL("$base/api/datv/fluide.mp4?id=p$q&son=0&q=$q").openConnection() as HttpURLConnection).apply { readTimeout = 20_000 }
            val a = page("360"); val b = page("360")
            assertEquals(200, a.responseCode); assertEquals(200, b.responseCode)
            assertEquals("360", a.getHeaderField("X-Satme-Niveau")); assertEquals("h264", a.getHeaderField("X-Satme-Mode"))
            // H.264 Constrained Baseline.
            assertTrue(a.getHeaderField("X-Satme-Codecs"), a.getHeaderField("X-Satme-Codecs").startsWith("avc1.42"))
            val e = a.inputStream
            val buf = ByteArray(65536); var total = 0; val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 2000) { val n = e.read(buf); if (n < 0) break; total += n }
            assertTrue("$total bytes", total > 5_000)
            // One ffmpeg for both pages, and none for Source (nobody asked it).
            val j = JSONObject(URL("$base/api/datv/fluide").readText())
            val niveaux = j.getJSONArray("niveaux")
            assertEquals(1, niveaux.length()); assertEquals("360", niveaux.getJSONObject(0).getString("niveau"))
            assertEquals(2, niveaux.getJSONObject(0).getInt("clients"))
            assertEquals(setOf(NiveauDatvPc.P360), lecteur.partage.actifs(LecteurFluideDatvPc.SORTE))
            a.disconnect(); b.disconnect()
        } finally {
            s.arrete(); fil.interrupt()
        }
    }

    @Test fun `nobody watching - the source never taken`() {
        val lecteur = LecteurFluideDatvPc(horloge = System::currentTimeMillis)
        var pris = false
        lecteur.source = object : VideoSitePc.SourceTs {
            override fun branche(f: ((ByteArray) -> Unit)?) { pris = f != null }
            override fun verrouille() = false
            override fun service() = ""
            override fun kbps() = 0
        }
        lecteur.tic()
        assertFalse(pris)
        assertEquals(false, lecteur.json().getBoolean("flux"))
    }

    @Test fun `a page on this PC playing the sound - the PC's own quiet, the translation spoken in the browser, back after`() {
        val d = DatvPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), tmp.newFolder())
        d.regleSon(true)
        assertTrue("pc" in d.sorties()); assertFalse("navigateur" in d.sorties())
        d.sonNavigateur(ici = true, page = true)
        assertFalse("pc" in d.sorties()); assertTrue("navigateur" in d.sorties())
        // Another machine plays it: this PC's sound goes on.
        d.sonNavigateur(ici = false, page = true)
        assertTrue("pc" in d.sorties()); assertTrue("navigateur" in d.sorties())
        d.sonNavigateur(ici = false, page = false)
        assertEquals(setOf("pc", "pcFixe"), d.sorties())
    }
}
