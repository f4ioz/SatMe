/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Through the server: the appearance (theme, corners, density, simple or
 * complete interface) and the tabs' layouts, kept by SatMe-PC across a new
 * start, put back to their default, refused when they are not one.
 */
class ApparencePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-apparence-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun avecServeur(stockage: StockageMemoire, bloc: (get: (String) -> JSONObject, post: (String) -> JSONObject) -> Unit) {
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val s = ServeurPc(st, 0)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val get = { u: String -> JSONObject((URL(base + u).openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()) }
            val post = { u: String ->
                val c = URL(base + u).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                JSONObject(c.inputStream.bufferedReader().readText())
            }
            bloc(get, post)
        } finally { s.arrete() }
    }

    private fun enc(x: String) = URLEncoder.encode(x, "UTF-8")

    @Test
    fun les_themes_et_l_interface_sont_gardes() {
        val stockage = StockageMemoire()
        avecServeur(stockage) { get, post ->
            var j = get("/api/apparence")
            assertEquals("sombre", j.getString("theme")); assertEquals("arrondi", j.getString("forme"))
            assertEquals("normale", j.getString("densite")); assertEquals("complete", j.getString("interface"))
            assertEquals("Disposition par défaut", j.getJSONObject("textes").getString("ap_defaut"))
            j = post("/api/apparence?theme=aurore&forme=carre&densite=compacte&interface=simple")
            assertTrue(j.getBoolean("ok")); assertEquals("aurore", j.getString("theme")); assertEquals("simple", j.getString("interface"))
            // An unknown value: refused, nothing changed.
            j = post("/api/apparence?theme=rose")
            assertFalse(j.getBoolean("ok"))
            assertEquals("aurore", get("/api/apparence").getString("theme"))
        }
        // Kept: a new start reads them back.
        avecServeur(stockage) { get, _ ->
            val j = get("/api/apparence")
            assertEquals("aurore", j.getString("theme")); assertEquals("carre", j.getString("forme"))
            assertEquals("compacte", j.getString("densite")); assertEquals("simple", j.getString("interface"))
        }
    }

    @Test
    fun la_disposition_est_gardee_et_retablie() {
        val stockage = StockageMemoire()
        val d = """{"ordre":{"c1":["passages","materiel"],"c3":["frequences","ecoute"]},"retire":{"polar":1},"compact":{"frequences":true}}"""
        avecServeur(stockage) { get, post ->
            assertTrue(post("/api/apparence?dispo=pli-station&json=" + enc(d)).getBoolean("ok"))
            assertTrue(post("/api/apparence?dispo=pli-station.simple&json=" + enc("""{"retire":{"ptx":1}}""")).getBoolean("ok"))
            // Refused: not a layout, not a layout's name.
            assertFalse(post("/api/apparence?dispo=pli-station&json=" + enc("""{"ordre":{"c1":["<b>"]}}""")).getBoolean("ok"))
            assertFalse(post("/api/apparence?dispo=../reglages&json=" + enc(d)).getBoolean("ok"))
            val l = get("/api/apparence").getJSONObject("dispositions")
            assertEquals(listOf("passages", "materiel"), l.getJSONObject("pli-station").getJSONObject("ordre").getJSONArray("c1").toList())
            assertEquals(1, l.getJSONObject("pli-station.simple").getJSONObject("retire").getInt("ptx"))
        }
        avecServeur(stockage) { get, post ->
            assertTrue(get("/api/apparence").getJSONObject("dispositions").has("pli-station"))
            // One tab back to its default…
            assertFalse(post("/api/apparence?dispo=pli-station&json=").getJSONObject("dispositions").has("pli-station"))
            assertTrue(get("/api/apparence").getJSONObject("dispositions").has("pli-station.simple"))
            // … then every one.
            assertEquals(0, post("/api/apparence?defaut=1").getJSONObject("dispositions").length())
        }
        avecServeur(stockage) { get, _ -> assertEquals(0, get("/api/apparence").getJSONObject("dispositions").length()) }
    }
}
