/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.reglages.SectionsAccueil
import fr.f4ioz.site.securite.Jetons
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64

/** The home page's sky, its API and the light coastline, the SSTV wall and its viewer, the logbook's figures and map — on the whole site. */
class CielSiteTest {

    companion object {
        private lateinit var dossier: File
        private lateinit var site: Site
        private var port = 0
        private val jeton = Jetons.nouveau()
        private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        private var visiteur = 10

        @BeforeClass @JvmStatic fun demarre() {
            dossier = Files.createTempDirectory("site-ciel").toFile()
            File(dossier, "contenu").mkdirs()
            // The elements on disk, as the GP server would have left them (nothing is fetched: hors ligne).
            File(dossier, "donnees/cache/tle").mkdirs()
            File(dossier, "donnees/cache/tle/amsat_gp.txt").writeText(CielSiteTest::class.java.getResourceAsStream("/fixtures/elements-essai.tle")!!.bufferedReader().readText())
            site = Site(Config(urlBase = "https://f4ioz.example", ecoute = "127.0.0.1", port = 0, donnees = File(dossier, "donnees"),
                contenu = File(dossier, "contenu"), horsLigne = true, tleServeur = "", jetonSha256 = Jetons.sha256Hex(jeton),
                adminMotDePasse = Jetons.hacheMotDePasse("mot-de-passe-essai", 20_000), cookiesSecurises = false))
            port = site.demarre()
        }

        @AfterClass @JvmStatic fun arrete() { site.arrete(); dossier.deleteRecursively() }
    }

    private lateinit var ip: String

    @Before fun avant() { site.reglages.retablis(); ip = "198.51.100." + (visiteur++) }

    private fun get(chemin: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$chemin")).GET().header("X-Forwarded-For", ip).build(), HttpResponse.BodyHandlers.ofString())

    private fun publie(type: String, corps: ByteArray, meta: JSONObject): HttpResponse<String> {
        val m = Base64.getUrlEncoder().withoutPadding().encodeToString(meta.toString().toByteArray())
        return client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/publication/$type")).POST(HttpRequest.BodyPublishers.ofByteArray(corps))
            .header("X-Forwarded-For", ip).header("Authorization", "Bearer $jeton").header("X-SatMe-Meta", m).build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun `the home page carries the sky, its data and its script, off in the settings, it is gone`() {
        val b = get("/").body()
        assertTrue(b.contains("data-ciel"))
        assertTrue(b.contains("Au-dessus de JN18FS"))
        assertTrue(b.contains("<svg class=\"ciel-cadran\""))
        assertTrue(b.contains("id=\"ciel-donnees\""))
        assertTrue(b.contains("/static/js/ciel-station.js?v="))
        assertTrue(b.contains("/api/lib/satellite.js"))
        assertTrue(b.contains("style=\"--gc:1/8;--gr:1/4\" aria-labelledby=\"h-ciel\""))
        site.reglages.change { it.copy(accueil = SectionsAccueil(ciel = false)) }
        val sans = get("/").body()
        assertFalse(sans.contains("data-ciel"))
        assertFalse(sans.contains("ciel-station.js"))
    }

    @Test fun `the sky's API and its script, with SatMe's pure functions first`() {
        val r = get("/api/ciel")
        assertEquals(200, r.statusCode())
        val j = JSONObject(r.body())
        assertEquals("JN18FS", j.getString("locator"))
        assertEquals(setOf("ISS", "RS-44", "SO-50"), (0 until j.getJSONArray("sats").length()).map { j.getJSONArray("sats").getJSONObject(it).getString("nom") }.toSet())
        assertTrue(j.has("passages")); assertTrue(j.getJSONObject("soleil").has("el"))
        assertTrue(r.headers().firstValue("cache-control").orElse("").contains("max-age=60"))
        val js = get("/static/js/ciel-station.js").body()
        assertTrue(js.indexOf("function obSoleil") in 0 until js.indexOf("data-ciel"))
        assertFalse(js.contains("const Orbites = (function"))
    }

    @Test fun `the light coastline is a list of rings, far smaller than the whole one`() {
        val l = get("/api/carte/traits-legers")
        assertEquals(200, l.statusCode())
        val a = JSONArray(l.body())
        assertTrue(a.length() > 50)
        assertTrue(l.body().length * 2 < get("/api/carte/traits").body().length)
    }

    @Test fun `the SSTV wall groups the pictures by pass, the latest glowing, the viewer comes with the galleries`() {
        val t = 1_790_000_000_000L
        for ((k, dt) in listOf(0L, 120_000L, 240_000L, 6_000_000L).withIndex())
            assertEquals(201, publie("sstv-image", PublicationsTest.png(couleur = 0x102030 + k), JSONObject().put("satellite", "ISS").put("utc", t + dt).put("mode", "PD120")).statusCode())
        val b = get("/gallery/sstv").body()
        assertEquals(2, Regex("class=\"mur-passage\"").findAll(b).count())
        assertEquals(1, Regex("class=\"vign recente\"").findAll(b).count())
        assertTrue(b.contains("data-plein=\"/media/"))
        assertTrue(b.contains("/static/js/galerie.js?v="))
        assertTrue(get("/gallery").body().contains("href=\"/gallery/sstv\""))
        assertEquals("/gallery/sstv", get("/sstv").headers().firstValue("location").orElse(""))
        assertEquals(200, get("/static/js/galerie.js").statusCode())
        assertTrue(get("/sitemap.xml").body().contains("/gallery/sstv<"))
    }

    @Test fun `the logbook in figures and on a map, with the station's card`() {
        val adif = "essai\n<EOH>\n<CALL:5>F1ABC<QSO_DATE:8>20261001<TIME_ON:4>1200<BAND:2>2m<MODE:2>FM<SAT_NAME:5>RS-44<GRIDSQUARE:4>JN05<COUNTRY:6>France<EOR>\n" +
            "<CALL:5>EA1ZZ<QSO_DATE:8>20261002<TIME_ON:4>1300<BAND:3>20m<MODE:3>SSB<GRIDSQUARE:4>IN53<COUNTRY:5>Spain<EOR>\n"
        assertEquals(201, publie("qso", adif.toByteArray(), JSONObject()).statusCode())
        val b = get("/logbook").body()
        assertTrue(b.contains("class=\"carnet-chiffres\""))
        assertTrue(b.contains("data-compte=\"2\""))
        assertTrue(b.contains("id=\"carte-qso-donnees\""))
        assertTrue(b.contains("\"g\":\"JN05\""))
        assertTrue(b.contains("/static/js/carte-qso.js?v="))
        assertTrue(b.contains("class=\"carte-qsl\""))
        assertEquals(200, get("/static/js/carte-qso.js").statusCode())
    }
}
