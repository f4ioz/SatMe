/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.BullePhonie
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** « Vidéo DATV en direct »: ffmpeg's command, the playlists read, the cues, the estimates, and nothing while off. */
class VideoSitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private var t = 1_800_000_000_000L
    private val envois = ArrayList<String>()

    private fun video(): Pair<VideoSitePc, StockageFichier> {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { m, u, _, _ ->
            if (u.endsWith("/ping")) return@Transport 200 to "{\"ok\":true}"
            envois += "$m $u"; 200 to "{\"ok\":true}" })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "j"; else -> "" } }
        envois.clear()
        return VideoSitePc(st, site, { t }, { "/usr/bin/ffmpeg" }, { false }) to st
    }

    private fun Iterable<String>.apres(k: String): String = toList().let { it[it.indexOf(k) + 1] }

    @Test fun `the copy rendition copies the picture, the others are scaled and encoded with key frames at each segment`() {
        val d = File("/tmp/hls")
        val c = VideoSitePc.commande("ffmpeg", listOf(VideoSitePc.Rendu.SOURCE, VideoSitePc.Rendu.P480), 2, "veryfast", d, "non", null)
        assertEquals("pipe:0", c.apres("-i"))
        assertEquals("mpegts", c.apres("-f"))
        assertFalse(c.contains("-filter_complex"))
        // Two outputs: the copy first, its sound to AAC.
        val src = c.subList(c.indexOf("-map"), c.indexOf(File(d, "source/index.m3u8").path) + 1)
        assertEquals("copy", src.apres("-c:v")); assertEquals("aac", src.apres("-c:a"))
        assertTrue(src.contains("0:a:0?"))
        assertEquals(File(d, "source/s%d.ts").path, src.apres("-hls_segment_filename"))
        val p480 = c.subList(c.indexOf(File(d, "source/index.m3u8").path) + 1, c.size)
        assertEquals("libx264", p480.apres("-c:v"))
        assertEquals("veryfast", p480.apres("-preset")); assertEquals("zerolatency", p480.apres("-tune"))
        assertTrue(p480.apres("-vf").startsWith("scale=-2:'min(480,ih)'"))
        assertEquals("1000k", p480.apres("-b:v"))
        assertEquals("expr:gte(t,n_forced*2)", p480.apres("-force_key_frames"))
        assertEquals("2", p480.apres("-hls_time"))
        assertTrue(p480.apres("-hls_flags").contains("delete_segments"))
        assertEquals(File(d, "480p/index.m3u8").path, p480.last())
    }

    @Test fun `ultrafast and one-second segments, as chosen`() {
        val c = VideoSitePc.commande("ffmpeg", listOf(VideoSitePc.Rendu.P360), 1, "ultrafast", File("/x"), "non", null)
        assertEquals("ultrafast", c.apres("-preset")); assertEquals("1", c.apres("-hls_time"))
        assertEquals("expr:gte(t,n_forced*1)", c.apres("-force_key_frames")); assertEquals("450k", c.apres("-b:v"))
    }

    @Test fun `the translated voice comes on a local port and makes the trad track`() {
        val d = File("/tmp/hls")
        val c = VideoSitePc.commande("ffmpeg", listOf(VideoSitePc.Rendu.P720), 2, "veryfast", d, "mix", 45678)
        assertTrue(c.contains("tcp://127.0.0.1:45678"))
        assertEquals("s16le", c[c.indexOf("tcp://127.0.0.1:45678") - 6])
        val f = c.apres("-filter_complex")
        assertTrue(f.contains("sidechaincompress") && f.contains("amix") && f.endsWith("[trad]"))
        assertEquals("[trad]", c[c.lastIndexOf("-map") + 1])
        assertEquals(File(d, "trad/index.m3u8").path, c.last())
        assertFalse(VideoSitePc.filtreVoix("seule").contains("0:a"))
        // Without a port, no voice.
        assertFalse(VideoSitePc.commande("ffmpeg", listOf(VideoSitePc.Rendu.P720), 2, "veryfast", d, "mix", null).contains("-filter_complex"))
    }

    @Test fun `the upload estimate adds up the renditions`() {
        val r = VideoSitePc.Rendu.values().toList()
        assertEquals(5832L, VideoSitePc.debitEstimeKbps(r, 0, false))
        assertEquals(VideoSitePc.debitEstimeKbps(listOf(VideoSitePc.Rendu.P480), 0, false) + 106, VideoSitePc.debitEstimeKbps(listOf(VideoSitePc.Rendu.P480), 0, true))
        assertEquals((800 + 96) * 1100L, VideoSitePc.debitBits(VideoSitePc.Rendu.SOURCE, 800))
        assertEquals("avc1.4d4020,mp4a.40.2", VideoSitePc.codecs(VideoSitePc.Rendu.P720)); assertEquals("", VideoSitePc.codecs(VideoSitePc.Rendu.SOURCE))
    }

    @Test fun `ffmpeg's playlist read - segments and durations, nothing else`() {
        val m = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:4\n#EXTINF:2.000000,\ns4.ts\n#EXTINF:1.960000,\ns5.ts\n#EXTINF:2.0,\n../../etc/passwd\n"
        assertEquals(listOf("s4.ts" to 2.0, "s5.ts" to 1.96), VideoSitePc.lisListe(m))
        assertEquals(12, VideoSitePc.numero("s12.ts")); assertNull(VideoSitePc.numero("s12.ts.tmp")); assertNull(VideoSitePc.numero("x.ts"))
    }

    @Test fun `a cue is plain text, bounded, with its languages and times`() {
        val b = BullePhonie(7, t, 3.5, "datv", null, "QO-100", "EN", 0.9, "Hello <b>world</b>\u0007 " + "x".repeat(800), "Bonjour le monde", 0.8, 0.4, emptyList(), "fr")
        val o = VideoSitePc.repliqueJson(b)!!
        assertEquals(7L, o.getLong("id")); assertEquals(t, o.getLong("debut")); assertEquals(t + 3500L, o.getLong("fin"))
        assertFalse(o.getString("texte").contains("<")); assertFalse(o.getString("texte").contains("\u0007"))
        assertEquals(500, o.getString("texte").length)
        assertEquals("en", o.getString("langue")); assertEquals("fr", o.getString("langueCible")); assertEquals("Bonjour le monde", o.getString("traduction"))
        assertNull(VideoSitePc.repliqueJson(b.copy(texte = "  ")))
        val sans = VideoSitePc.repliqueJson(b.copy(traduction = "", langue = "klingon", dureeS = 300.0))!!
        assertFalse(sans.has("traduction")); assertEquals("", sans.getString("langue")); assertEquals(t + 30_000L, sans.getLong("fin"))
    }

    @Test fun `off by default - nothing sent, the source never taken`() {
        val (v, _) = video()
        var branchee = false
        v.source = object : VideoSitePc.SourceTs {
            override fun branche(f: ((ByteArray) -> Unit)?) { if (f != null) branchee = true }
            override fun verrouille() = true
            override fun service() = "F4XYZ"
            override fun kbps() = 900
        }
        v.repliques = { listOf(BullePhonie(1, t, 2.0, "datv", null, "", "en", 1.0, "hi", "", 1.0, 0.1, emptyList())) }
        assertFalse(v.on); assertFalse(v.sousTitres); assertEquals("non", v.audioTraduit)
        repeat(10) { v.tic(); t += 500 }
        assertTrue(envois.toString(), envois.isEmpty())
        assertFalse(branchee)
    }

    @Test fun `the state message - renditions, tracks, never a setting`() {
        val (v, _) = video()
        v.sousTitres = true
        v.traductionDatv = { "fr" }
        v.paroleDatv = { "fr" }
        val o = v.etatJson(listOf(VideoSitePc.Rendu.SOURCE, VideoSitePc.Rendu.P720), "mix", t, null)
        assertEquals(setOf("t", "actif", "segmentS", "service", "rendus", "audio", "soustitres"), o.keySet())
        val r = o.getJSONArray("rendus")
        assertEquals("source", r.getJSONObject(0).getString("nom")); assertFalse(r.getJSONObject(0).has("hauteur"))
        assertEquals(720, r.getJSONObject(1).getInt("hauteur")); assertEquals(1280, r.getJSONObject(1).getInt("largeur"))
        assertEquals("trad", o.getJSONArray("audio").getJSONObject(0).getString("nom"))
        assertEquals(listOf("orig", "trad"), (0 until 2).map { o.getJSONArray("soustitres").getJSONObject(it).getString("nom") })
    }

    @Test fun `settings - renditions, segment, preset, sound, as the page sends them`() {
        val (v, _) = video()
        val p = mapOf("video_rendus" to "720p,source,bogus", "video_segment" to "1", "video_preset" to "ultrafast", "video_audio" to "seule", "video_st" to "1", "video" to "1")
        v.regle { p[it].orEmpty() }
        assertTrue(v.on); assertTrue(v.sousTitres)
        assertEquals(listOf(VideoSitePc.Rendu.SOURCE, VideoSitePc.Rendu.P720), v.rendus)
        assertEquals(1, v.segmentS); assertEquals("ultrafast", v.preset); assertEquals("seule", v.audioTraduit)
        v.regle { k -> when (k) { "video_preset" -> "placebo"; "video_audio" -> "fort"; else -> "" } }
        assertEquals("ultrafast", v.preset); assertEquals("seule", v.audioTraduit)
        val j = v.json()
        assertEquals(1, j.getInt("reencodages")); assertTrue(j.getLong("debitEstimeKbps") > 2000)
    }

    /** A transport stream's PAT and PMT (program 1, PMT on PID 0x100) announcing [types] (stream type, PID). */
    private fun ts(vararg types: Int): ByteArray {
        fun paquet(pid: Int, section: List<Int>): ByteArray {
            val b = ByteArray(188) { 0xFF.toByte() }
            b[0] = 0x47; b[1] = (0x40 or (pid shr 8)).toByte(); b[2] = (pid and 0xFF).toByte(); b[3] = 0x10; b[4] = 0
            section.forEachIndexed { i, v -> b[5 + i] = v.toByte() }
            return b
        }
        val pat = listOf(0x00, 0xB0, 13, 0, 1, 0xC1, 0, 0, 0, 1, 0xE1, 0x00, 0, 0, 0, 0)
        val es = types.flatMap { listOf(it, 0xE1, 0x01, 0xF0, 0) }
        val pmt = listOf(0x02, 0xB0, 13 + es.size, 0, 1, 0xC1, 0, 0, 0xE1, 0x01, 0xF0, 0) + es + listOf(0, 0, 0, 0)
        return paquet(0, pat) + paquet(0x100, pmt)
    }

    @Test fun `the copy's codecs read from its stream types`() {
        assertEquals("avc1.640028,mp4a.40.2", VideoSitePc.codecsTs(ts(0x03, 0x1B)))
        assertEquals("hvc1.1.6.L120.90,mp4a.40.2", VideoSitePc.codecsTs(ts(0x24, 0x0F)))
        assertNull(VideoSitePc.codecsTs(ts(0x03)))
        assertNull(VideoSitePc.codecsTs(ByteArray(188 * 3)))
    }

    @Test fun `the voice resampled to 24 kHz`() {
        val r = VideoSitePc.reechantillonne(ShortArray(22_050) { 1000 }, 22_050, 24_000)
        assertEquals(24_000, r.size); assertEquals(1000, r[12_000].toInt())
    }
}
