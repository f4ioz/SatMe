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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** « Spectre rapide »: the stream's address, its pace, its reconnections, its fallback, and nothing while off. */
class SpectreRapidePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private var t = 1_800_000_000_000L
    private val posts = ArrayList<Pair<String, JSONObject>>()
    private val ouvertures = ArrayList<Pair<String, Map<String, String>>>()
    private var refus: Exception? = null
    private val lignes = ArrayList<String>()
    private var trame = 0L

    private inner class Faux : SpectreRapidePc.Canal {
        var ok = true
        override fun envoie(texte: String) { if (!ok) throw IOException("coupé"); lignes += texte }
        override fun ping() {}
        override fun ferme() { ok = false }
        override val ouvert: Boolean get() = ok
    }
    private var canal: Faux? = null

    private fun rapide(): SpectreRapidePc {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { m, u, _, c ->
            if (u.endsWith("/ping")) return@Transport 200 to "{\"ok\":true}"
            posts += u to JSONObject(String(c!!.octets())); 200 to "{\"ok\":true}" })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        posts.clear()
        val r = SpectreRapidePc(st, site, { t }) { u, e -> ouvertures += u to e; refus?.let { throw it }; Faux().also { canal = it } }
        r.panorama = { DirectSitePc.Panorama(FloatArray(4096) { -80f + (it % 50) }, 10_489_750_000.0, 1_058_400.0) }
        r.trames = { ++trame }
        return r
    }

    /** Runs the loop for [ms] of the fake clock, stepping as it asks. */
    private fun tourne(r: SpectreRapidePc, ms: Long) {
        val fin = t + ms
        while (t < fin) { val w = r.tic(); t += w.coerceIn(1L, 1000L) }
    }

    @Test fun `the stream's address is the site's host, wss for https`() {
        assertEquals("wss://example.org/ws/direct/station", SpectreRapidePc.urlFlux("https://example.org/api/publication/{type}"))
        assertEquals("wss://f4ioz.fr:8443/ws/direct/station", SpectreRapidePc.urlFlux("https://f4ioz.fr:8443/recevoir.php"))
        assertEquals("ws://127.0.0.1:8080/ws/direct/station", SpectreRapidePc.urlFlux("http://127.0.0.1:8080/api/publication/{type}"))
        assertNull(SpectreRapidePc.urlFlux(""))
        assertNull(SpectreRapidePc.urlFlux("/home/moi/site"))
    }

    @Test fun `off by default - nothing opened, nothing sent`() {
        val r = rapide()
        assertFalse(r.on)
        tourne(r, 10_000L)
        assertTrue(ouvertures.isEmpty()); assertTrue(lignes.isEmpty()); assertTrue(posts.isEmpty())
    }

    @Test fun `on - the token in the handshake, lines at the chosen pace`() {
        val r = rapide()
        r.on = true
        assertEquals(5, r.cadence)
        tourne(r, 10_000L)
        assertEquals(1, ouvertures.size)
        assertEquals("wss://example.org/ws/direct/station", ouvertures[0].first)
        assertEquals("Bearer le-jeton", ouvertures[0].second["Authorization"])
        assertTrue("${lignes.size} lines in 10 s at 5/s", lignes.size in 48..51)
        val l = JSONObject(lignes.last())
        assertEquals(setOf("centreHz", "largeurHz", "minDb", "maxDb", "bins", "t"), l.keySet())
        assertEquals(1024, java.util.Base64.getDecoder().decode(l.getString("bins")).size)
        lignes.clear(); r.cadence = 20; r.points = 2048
        tourne(r, 5_000L)
        assertTrue("${lignes.size} lines in 5 s at 20/s", lignes.size in 96..101)
        assertEquals(2048, java.util.Base64.getDecoder().decode(JSONObject(lignes.last()).getString("bins")).size)
        // A rate not offered is brought to the nearest one.
        r.cadence = 7; assertEquals(5, r.cadence)
        r.points = 3000; assertEquals(2048, r.points)
    }

    @Test fun `no new panorama, no line`() {
        val r = rapide()
        r.on = true
        r.trames = { 42L }
        tourne(r, 3_000L)
        assertEquals(1, lignes.size)
    }

    @Test fun `a cut connection is opened again after a growing wait`() {
        val r = rapide()
        r.on = true
        tourne(r, 1_000L)
        canal!!.ok = false
        refus = IOException("connexion refusée")
        tourne(r, 5_000L)
        val n = ouvertures.size
        assertTrue("tries with backoff: $n", n in 3..4)
        refus = null
        tourne(r, 40_000L)
        assertTrue(canal!!.ok)
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L), (1..7).map { SpectreRapidePc.attenteMs(it) })
    }

    @Test fun `a refused token waits ten minutes`() {
        val r = rapide()
        refus = ClientWebSocketPc.Refus(401)
        r.on = true
        tourne(r, 5 * 60_000L)
        assertEquals(1, ouvertures.size)
        assertTrue(r.json().getString("erreur").contains("jeton"))
        tourne(r, 6 * 60_000L)
        assertEquals(2, ouvertures.size)
    }

    @Test fun `no WebSocket on the site - the lines go by HTTP, a second at a time`() {
        val r = rapide()
        refus = ClientWebSocketPc.Refus(404)
        r.on = true
        tourne(r, 5_000L)
        assertEquals(1, ouvertures.size)
        assertTrue(posts.size in 4..6)
        val p = posts.last()
        assertEquals("https://example.org/api/publication/direct-spectre", p.first)
        val n = p.second.getJSONArray("lignes").length()
        assertTrue("$n lines in a batch", n in 4..6)
        assertEquals("http", r.json().getString("mode"))
        // The WebSocket asked again after 5 minutes.
        refus = null
        tourne(r, SpectreRapidePc.WS_DE_NOUVEAU_MS)
        assertEquals("ws", r.json().getString("mode"))
    }

    @Test fun `switched off - closed, and nothing more`() {
        val r = rapide()
        r.on = true
        tourne(r, 2_000L)
        r.regle { k -> if (k == "rapide") "0" else "" }
        val n = lignes.size
        tourne(r, 5_000L)
        assertEquals(n, lignes.size)
        assertFalse(canal!!.ok)
    }

    @Test fun `the pace never bursts after a delay`() {
        assertEquals(1200L, SpectreRapidePc.prochaine(1000L, 1001L, 200L))
        assertEquals(5200L, SpectreRapidePc.prochaine(1000L, 5000L, 200L))
        assertEquals(50L, SpectreRapidePc.periodeMs(20)); assertEquals(1000L, SpectreRapidePc.periodeMs(1))
        assertTrue(SpectreRapidePc.debitKbps(5, 1024) in 50.0..65.0)
    }
}
