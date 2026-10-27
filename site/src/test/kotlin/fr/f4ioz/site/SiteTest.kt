/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.securite.Jetons
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64

/** The whole site on a free port, its data in a temporary folder, nothing fetched from the Internet. */
class SiteTest {

    companion object {
        private lateinit var dossier: File
        private lateinit var site: Site
        private var port = 0
        private val jeton = Jetons.nouveau()
        private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

        @BeforeClass @JvmStatic fun demarre() {
            dossier = Files.createTempDirectory("site-test").toFile()
            File(dossier, "contenu/articles/2026").mkdirs()
            File(dossier, "contenu/articles/2026/essai.md").writeText("---\ntitle: \"Un essai\"\ndate: 2026-10-01\ntags: [essai, sstv]\nsummary: \"Résumé\"\n---\n\nTexte **gras**.\n\n<script>alert(1)</script>\n")
            File(dossier, "contenu/pages").mkdirs()
            File(dossier, "contenu/pages/a-propos.md").writeText("---\ntitle: À propos\n---\nQui je suis.\n")
            site = Site(Config(urlBase = "https://f4ioz.example", ecoute = "127.0.0.1", port = 0, donnees = File(dossier, "donnees"),
                contenu = File(dossier, "contenu"), horsLigne = true, jetonSha256 = Jetons.sha256Hex(jeton),
                adminMotDePasse = Jetons.hacheMotDePasse("mot-de-passe-essai", 20_000), cookiesSecurises = false))
            port = site.demarre()
        }

        @AfterClass @JvmStatic fun arrete() { site.arrete(); dossier.deleteRecursively() }

        fun get(chemin: String, entetes: Map<String, String> = emptyMap()): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$chemin")).GET()
            entetes.forEach { (k, v) -> b.header(k, v) }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun envoie(methode: String, chemin: String, corps: ByteArray, entetes: Map<String, String> = emptyMap()): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$chemin")).method(methode, HttpRequest.BodyPublishers.ofByteArray(corps))
            entetes.forEach { (k, v) -> b.header(k, v) }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun meta(o: JSONObject): String = Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    @Test fun `every page of the menu answers, with its security headers`() {
        for (p in listOf("/", "/articles", "/articles/2026/essai", "/articles/feed.xml", "/gallery", "/logbook", "/satme", "/sat/pass", "/sat/sked",
                "/sat/orbites", "/sat/radar", "/sat/tle", "/grid", "/grid?a=JN18FS&b=IO91WM", "/a-propos", "/robots.txt", "/sitemap.xml", "/admin",
                "/static/css/site.css", "/static/js/orbites.js", "/static/js/sked.js", "/api/orbites", "/api/satellites")) {
            val r = get(p)
            assertEquals(p, 200, r.statusCode())
            assertEquals(p, "nosniff", r.headers().firstValue("x-content-type-options").orElse(""))
            assertTrue(p, r.headers().firstValue("content-security-policy").orElse("").contains("frame-ancestors 'self'"))
        }
        assertEquals(404, get("/nexiste-pas").statusCode())
        assertEquals(410, get("/tm60louv").statusCode())
        assertEquals("/sat/sked", get("/sat/passes").headers().firstValue("location").orElse(""))
        assertEquals(400, get("/static/%2e%2e/%2e%2e/etc/passwd").statusCode())
    }

    @Test fun `an article is rendered safely, its raw HTML left out`() {
        val r = get("/articles/2026/essai")
        assertTrue(r.body().contains("<b>gras</b>"))
        assertFalse(r.body().contains("<script>alert(1)"))
        assertTrue(r.body().contains("\"@type\":\"Article\""))
        assertTrue(r.body().contains("<link rel=\"canonical\" href=\"https://f4ioz.example/articles/2026/essai\">"))
        // The content pages' scripts run under a nonce only.
        assertTrue(r.headers().firstValue("content-security-policy").get().contains("script-src 'self' 'nonce-"))
        assertTrue(get("/sitemap.xml").body().contains("https://f4ioz.example/articles/2026/essai"))
    }

    @Test fun `HTTPS and the visitor's address come from the trusted proxy`() {
        val r = get("/", mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-For" to "203.0.113.5"))
        assertTrue(r.headers().firstValue("strict-transport-security").isPresent)
        assertFalse(get("/").headers().firstValue("strict-transport-security").isPresent)
    }

    @Test fun `the publication API wants the token`() {
        assertEquals(401, get("/api/publication/ping").statusCode())
        assertEquals(401, get("/api/publication/ping", mapOf("Authorization" to "Bearer faux")).statusCode())
        val ok = get("/api/publication/ping", mapOf("Authorization" to "Bearer $jeton"))
        assertEquals(200, ok.statusCode())
        assertEquals("F4IOZ", JSONObject(ok.body()).getString("indicatif"))
    }

    @Test fun `publishing is idempotent, a picture shows in the gallery, then is withdrawn`() {
        val auth = mapOf("Authorization" to "Bearer $jeton")
        val png = PublicationsTest.png(couleur = 0x123456)
        val m = meta(JSONObject().put("satellite", "ISS").put("utc", "2026-10-03T13:51:19Z").put("mode", "PD120").put("evenement", "ARISS octobre 2026"))
        val r1 = envoie("POST", "/api/publication/sstv-image", png, auth + ("X-SatMe-Meta" to m) + ("Content-Type" to "image/png"))
        assertEquals(r1.body(), 201, r1.statusCode())
        val id = JSONObject(r1.body()).getString("id")
        val r2 = envoie("POST", "/api/publication/sstv-image", png, auth + ("X-SatMe-Meta" to m))
        assertEquals(200, r2.statusCode())
        assertTrue(JSONObject(r2.body()).getBoolean("deja"))
        assertEquals(id, JSONObject(r2.body()).getString("id"))
        assertEquals(200, get("/gallery/item/$id").statusCode())
        assertTrue(get("/gallery").body().contains("ARISS octobre 2026"))
        assertEquals(200, get("/gallery/sstv/ariss-octobre-2026").statusCode())
        assertEquals(200, get("/media/$id/fichier.png").statusCode())
        assertEquals(200, get("/media/$id/vignette.jpg").statusCode())
        assertEquals(404, get("/media/$id/meta.json").statusCode())
        assertEquals(400, get("/media/$id/..%2Fmeta.json").statusCode())
        assertTrue(get("/").body().contains("/media/$id/vignette.jpg"))
        val d = envoie("DELETE", "/api/publication/$id", ByteArray(0), auth)
        assertEquals(200, d.statusCode())
        assertEquals(404, get("/gallery/item/$id").statusCode())
        assertEquals(404, envoie("DELETE", "/api/publication/$id", ByteArray(0), auth).statusCode())
    }

    @Test fun `too large, or not what it says, is refused`() {
        val auth = mapOf("Authorization" to "Bearer $jeton")
        assertEquals(413, envoie("POST", "/api/publication/qso", ByteArray((1 shl 20) + 10) { 'a'.code.toByte() }, auth).statusCode())
        assertEquals(415, envoie("POST", "/api/publication/sstv-image", "<html></html>".toByteArray(), auth).statusCode())
        assertEquals(404, envoie("POST", "/api/publication/autre", "x".toByteArray(), auth).statusCode())
        assertEquals(400, envoie("POST", "/api/publication/sstv-image", PublicationsTest.png(), auth + ("X-SatMe-Meta" to "%%%")).statusCode())
    }

    @Test fun `a contact published shows in the logbook`() {
        val auth = mapOf("Authorization" to "Bearer $jeton")
        val r = envoie("POST", "/api/publication/qso", """{"indicatif":"EA4ABC","utc":"2026-10-03T13:51:00Z","satellite":"SO-50","mode":"FM","locator":"IN80DD"}""".toByteArray(),
            auth + ("Content-Type" to "application/json"))
        assertEquals(201, r.statusCode())
        val l = get("/logbook?sat=SO-50").body()
        assertTrue(l.contains("EA4ABC"))
        assertTrue(l.contains("IN80"))
        assertFalse(l.contains("IN80DD"))
    }

    @Test fun `the admin page wants its password, its forms their token`() {
        assertFalse(get("/admin").body().contains("Relire Wavelog"))
        val mauvais = envoie("POST", "/admin/connexion", "mot_de_passe=non".toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        assertEquals(401, mauvais.statusCode())
        val bon = envoie("POST", "/admin/connexion", "mot_de_passe=mot-de-passe-essai".toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        assertEquals(303, bon.statusCode())
        val cookie = bon.headers().firstValue("set-cookie").get()
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict"))
        val c = cookie.substringBefore(';')
        val page = get("/admin", mapOf("Cookie" to c))
        assertTrue(page.body().contains("Relire Wavelog"))
        assertEquals("no-store", page.headers().firstValue("cache-control").orElse(""))
        // A form without its token: refused.
        assertEquals(403, envoie("POST", "/admin/rafraichir/tle", "csrf=faux".toByteArray(), mapOf("Cookie" to c, "Content-Type" to "application/x-www-form-urlencoded")).statusCode())
        val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page.body())!!.groupValues[1]
        assertNotNull(csrf)
        assertEquals(303, envoie("POST", "/admin/rafraichir/tle", "csrf=$csrf".toByteArray(), mapOf("Cookie" to c, "Content-Type" to "application/x-www-form-urlencoded")).statusCode())
    }
}
