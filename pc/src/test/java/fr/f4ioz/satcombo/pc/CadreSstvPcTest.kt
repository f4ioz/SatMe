/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File

/** The SSTV picture placed with the mouse on the journal page: kept, and where the files draw it. */
class CadreSstvPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-cadre-sstv-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val t0 = 1_791_800_000_000L

    private fun rouge(img: BufferedImage, x: Int, y: Int): Boolean {
        val c = img.getRGB(x, y)
        return (c shr 16 and 0xFF) > 200 && (c shr 8 and 0xFF) < 60 && (c and 0xFF) < 60
    }

    @Test
    fun le_placement_automatique_reste_celui_d_avant() {
        // Satellite on the right: top left, 8 dp in; on the left: top right. 42 % of the width at most.
        val a = RenduPc.placeSstv(null, 1080f, 1080f, 0.7f, 3f)
        assertEquals(24f, a.x, 0.01f); assertEquals(24f, a.y, 0.01f); assertEquals(453.6f, a.l, 0.01f); assertEquals(340.2f, a.h, 0.01f)
        val b = RenduPc.placeSstv(null, 1080f, 1080f, 0.2f, 3f)
        assertEquals(1080f - 453.6f - 24f, b.x, 0.01f)
        // Placed by hand: its shares, whatever the satellite.
        val c = RenduPc.placeSstv(CadreSstv(0.5f, 0.5f, 0.2f), 1000f, 800f, 0.9f, 3f)
        assertEquals(500f, c.x, 0.01f); assertEquals(400f, c.y, 0.01f); assertEquals(200f, c.l, 0.01f)
    }

    @Test
    fun la_page_le_garde_et_l_image_le_suit() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        val web = JournalWebPc(j, st, dossier)
        val e = JournalPassage.Entree(27607, "SO-50", t0, t0 + 600_000L, "JN18FS",
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, 100.0 + it * 10, 5.0 + it) })
        j.rangement.enregistre(e)
        // A red picture at 4 min.
        val image = SstvMeta.fileName("SO-50", t0 + 240_000L, "Robot 36", true)
        File(dossier, "sstv").mkdirs()
        val r = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 320, 240); dispose() } }
        javax.imageio.ImageIO.write(r, "png", File(dossier, "sstv/$image"))
        File(dossier, "sstv/" + SstvMeta.sidecarName(image)).writeText(
            SstvMeta.encode(SstvMeta.SstvShot(image, "SO-50", t0 + 240_000L, mode = "Robot 36", source = "live")))

        fun lit(route: String, p: Map<String, String> = emptyMap()) = String(web.sert(route) { p[it].orEmpty() }.corps)
        fun image(extra: Map<String, String>): BufferedImage {
            val m = JSONObject(lit("/passage", mapOf("id" to e.id))).getJSONArray("marques").getJSONObject(0)
            val f = JSONObject(lit("/fabrique", mapOf("id" to e.id, "quoi" to "image", "t" to "${m.getLong("a")}", "format" to "paysage") + extra))
            var tr: JSONObject
            do { Thread.sleep(50); tr = JSONObject(lit("/travail", mapOf("n" to f.getString("travail")))) } while (!tr.getBoolean("fini"))
            return javax.imageio.ImageIO.read(web.sert("/fichier") { mapOf("n" to f.getString("travail"))[it].orEmpty() }.fichier!!)
        }

        // By itself until placed.
        assertEquals("", JSONObject(lit("/reglages")).getString("cadreSstv"))
        // Placed on the page: kept by the station, given back to the page (clamped).
        assertTrue(JSONObject(lit("/cadreSstv", mapOf("v" to "0.05,0.5,0.3"))).getBoolean("ok"))
        assertEquals("0.0500,0.5000,0.3000", st.settings.journalCadreSstv)
        assertEquals("0.0500,0.5000,0.3000", JSONObject(lit("/reglages")).getString("cadreSstv"))

        // 16:9 at 1920×1080, its view 855 px high. The kept place: 576 px wide from (96, 427), centre (384, 643).
        val kept = image(emptyMap())
        assertEquals(1920, kept.width)
        assertTrue(rouge(kept, 384, 643)); assertFalse(rouge(kept, 1392, 522))
        // The page's own, sent with the file: 480 px from (1152, 342), centre (1392, 522).
        val sent = image(mapOf("cadreSstv" to "0.6,0.4,0.25"))
        assertTrue(rouge(sent, 1392, 522)); assertFalse(rouge(sent, 384, 643))
        // "auto": the corner away from the satellite, at the top, as before.
        val auto = image(mapOf("cadreSstv" to "auto"))
        assertFalse(rouge(auto, 1392, 522)); assertFalse(rouge(auto, 384, 643))
        assertTrue(rouge(auto, 300, 150) || rouge(auto, 1620, 150))

        // Back to by itself.
        lit("/cadreSstv", mapOf("v" to ""))
        assertEquals("", st.settings.journalCadreSstv)
    }
}
