/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.reglages.Direct
import fr.f4ioz.site.reglages.ReglageModule
import fr.f4ioz.site.reglages.ReglagePublication
import fr.f4ioz.site.reglages.Reglages
import fr.f4ioz.site.securite.Jetons
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** « Admin › Paramètres » on the whole site: hidden pages, refused kinds, the live block and its brakes. */
class ParametresSiteTest {

    companion object {
        private lateinit var dossier: File
        private lateinit var site: Site
        private var port = 0
        private val jeton = Jetons.nouveau()
        private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        private var visiteur = 10

        @BeforeClass @JvmStatic fun demarre() {
            dossier = Files.createTempDirectory("site-params").toFile()
            File(dossier, "contenu").mkdirs()
            site = Site(Config(urlBase = "https://f4ioz.example", ecoute = "127.0.0.1", port = 0, donnees = File(dossier, "donnees"),
                contenu = File(dossier, "contenu"), horsLigne = true, tleServeur = "", jetonSha256 = Jetons.sha256Hex(jeton),
                adminMotDePasse = Jetons.hacheMotDePasse("mot-de-passe-essai", 20_000), cookiesSecurises = false))
            port = site.demarre()
        }

        @AfterClass @JvmStatic fun arrete() { site.arrete(); dossier.deleteRecursively() }
    }

    /** Each test its own visitor: the server's brake is per address. */
    private lateinit var ip: String

    @Before fun avant() {
        site.reglages.retablis(); site.direct.efface()
        ip = "192.0.2." + (visiteur++)
    }

    private fun get(chemin: String, entetes: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$chemin")).GET().header("X-Forwarded-For", ip)
        entetes.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun envoie(methode: String, chemin: String, corps: ByteArray, entetes: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$chemin")).method(methode, HttpRequest.BodyPublishers.ofByteArray(corps)).header("X-Forwarded-For", ip)
        entetes.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private val auth get() = mapOf("Authorization" to "Bearer $jeton")
    private fun json(corps: JSONObject, chemin: String = "/api/publication/direct", entetes: Map<String, String> = auth) =
        envoie("POST", chemin, corps.toString().toByteArray(), entetes + ("Content-Type" to "application/json"))

    @Test fun `a hidden page answers 404 and leaves the menu, the sitemap and the home page`() {
        assertEquals(200, get("/grid").statusCode())
        assertTrue(get("/").body().contains("href=\"/grid\""))
        site.reglages.change { it.copy(modules = mapOf("/grid" to ReglageModule(visible = false))) }
        assertEquals(404, get("/grid").statusCode())
        assertEquals(404, get("/grid?a=JN18FS&b=IO91WM").statusCode())
        assertFalse(get("/").body().contains("href=\"/grid\""))
        assertFalse(get("/articles").body().contains("href=\"/grid\""))
        assertFalse(get("/sitemap.xml").body().contains("/grid<"))
        assertTrue(get("/sitemap.xml").body().contains("/sat/pass<"))
        // The base and the admin stay.
        assertEquals(200, get("/robots.txt").statusCode())
        assertEquals(200, get("/admin").statusCode())
        site.reglages.change { it.copy(modules = emptyMap()) }
        assertEquals(200, get("/grid").statusCode())
    }

    @Test fun `a page renamed and moved in the menu`() {
        site.reglages.change { it.copy(modules = mapOf("/grid" to ReglageModule(true, "Locators", 1))) }
        val b = get("/articles").body()
        assertTrue(b.contains("<span class=\"lbl\">Locators</span>"))
        assertTrue(b.indexOf("href=\"/grid\"") < b.indexOf("href=\"/sat/pass\""))
    }

    @Test fun `a refused kind is said to SatMe-PC, a hidden kind leaves the public pages`() {
        site.reglages.change { it.copy(publications = mapOf(TypePublication.SSTV to ReglagePublication(accepte = false))) }
        val meta = Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("satellite", "ISS").toString().toByteArray())
        val r = envoie("POST", "/api/publication/sstv-image", PublicationsTest.png(), auth + ("X-SatMe-Meta" to meta))
        assertEquals(403, r.statusCode())
        assertTrue(JSONObject(r.body()).getBoolean("refuse"))
        assertTrue(JSONObject(r.body()).getString("erreur").contains("Image SSTV"))
        assertTrue(JSONObject(get("/api/publication/ping", auth).body()).getJSONArray("refuses").toList().contains("sstv-image"))
        site.reglages.retablis()
        val ok = envoie("POST", "/api/publication/sstv-image", PublicationsTest.png(couleur = 0x123456), auth + ("X-SatMe-Meta" to meta))
        assertEquals(201, ok.statusCode())
        val id = JSONObject(ok.body()).getString("id")
        assertEquals(200, get("/gallery/item/$id").statusCode())
        site.reglages.change { it.copy(publications = mapOf(TypePublication.SSTV to ReglagePublication(accepte = true, publique = false))) }
        assertEquals(404, get("/gallery/item/$id").statusCode())
        assertEquals(404, get("/media/$id/vignette.jpg").statusCode())
        assertFalse(get("/gallery").body().contains(id))
        // Kept: shown again when the kind is.
        site.reglages.retablis()
        assertEquals(200, get("/gallery/item/$id").statusCode())
    }

    @Test fun `the live pushes want the token, the settings and small bodies`() {
        val pilote = JSONObject().put("t", System.currentTimeMillis()).put("pilote", JSONObject().put("prochain",
            JSONObject().put("sat", "ISS").put("campagne", "ARISS").put("aos", System.currentTimeMillis() + 3_600_000L).put("los", System.currentTimeMillis() + 4_000_000L)))
        assertEquals(401, json(pilote, entetes = emptyMap()).statusCode())
        // Nothing shown: refused, and SatMe-PC told why.
        val refus = json(pilote)
        assertEquals(403, refus.statusCode())
        assertTrue(JSONObject(refus.body()).getBoolean("refuse"))
        assertEquals(404, get("/api/direct/etat").statusCode())
        site.reglages.change { it.copy(bandeau = it.bandeau.copy(actif = true), direct = Direct(actif = true, source = "station")) }
        assertEquals(200, json(pilote).statusCode())
        val e = JSONObject(get("/api/direct/etat").body())
        assertEquals("ISS", e.getJSONObject("bandeau").getJSONObject("prochain").getString("sat"))
        assertEquals(413, envoie("POST", "/api/publication/direct", ByteArray(9000) { ' '.code.toByte() }, auth).statusCode())
        assertEquals(400, envoie("POST", "/api/publication/direct", "pas du json".toByteArray(), auth).statusCode())
        assertEquals(413, envoie("POST", "/api/publication/direct/datv-image", ByteArray(300 * 1024), auth).statusCode())
        assertEquals(415, envoie("POST", "/api/publication/direct/datv-image", PublicationsTest.png(), auth).statusCode())
        val ligne = JSONObject().put("centreHz", 10_489_750_000L).put("largeurHz", 1_058_400L).put("minDb", -100).put("maxDb", -40)
            .put("bins", Base64.getEncoder().encodeToString(ByteArray(1024) { 40 }))
        assertEquals(200, json(ligne, "/api/publication/direct/spectre").statusCode())
        val sp = JSONObject(get("/api/direct/spectre?depuis=0").body())
        assertEquals(1, sp.getJSONArray("lignes").length())
        assertTrue(JSONObject(get("/api/direct/etat").body()).getJSONObject("direct").getBoolean("spectre"))
        // The spectrum switched off on the site: refused again.
        site.reglages.change { it.copy(direct = it.direct.copy(spectre = false)) }
        assertEquals(403, json(ligne, "/api/publication/direct/spectre").statusCode())
        assertEquals(200, envoie("DELETE", "/api/publication/direct", ByteArray(0), auth).statusCode())
        assertFalse(JSONObject(get("/api/direct/etat").body()).getJSONObject("bandeau").has("prochain"))
    }

    @Test fun `the public live endpoints are braked per address`() {
        site.reglages.change { it.copy(direct = Direct(actif = true)) }
        val codes = (1..45).map { get("/api/direct/etat").statusCode() }
        assertEquals(200, codes.first())
        assertEquals(429, codes.last())
        assertEquals(40, codes.count { it == 200 })
    }

    @Test fun `the banner, the block and the page « En direct » follow the settings`() {
        assertFalse(get("/").body().contains("data-bandeau"))
        assertFalse(get("/").body().contains("data-direct"))
        assertEquals(404, get("/direct").statusCode())
        site.reglages.change { it.copy(bandeau = it.bandeau.copy(actif = true), direct = Direct(actif = true, page = true, accueil = true)) }
        val a = get("/").body()
        assertTrue(a.contains("data-bandeau"))
        assertTrue(a.contains("data-direct "))
        assertTrue(a.contains("eshail.batc.org.uk"))
        assertTrue(a.contains("js/direct.js"))
        assertEquals(200, get("/direct").statusCode())
        assertTrue(get("/articles").body().contains("href=\"/direct\""))
        assertTrue(get("/sitemap.xml").body().contains("/direct<"))
        // The cards of the home page, one by one.
        site.reglages.change { it.copy(accueil = it.accueil.copy(outils = false, satme = false)) }
        val b = get("/").body()
        assertFalse(b.contains("id=\"h-tools\""))
        assertFalse(b.contains("id=\"h-satme\""))
        assertTrue(b.contains("id=\"h-passes\""))
    }

    @Test fun `the settings page wants the session and its token`() {
        assertEquals(303, get("/admin/parametres").statusCode())
        val bon = envoie("POST", "/admin/connexion", "mot_de_passe=mot-de-passe-essai".toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        val c = bon.headers().firstValue("set-cookie").get().substringBefore(';')
        val page = get("/admin/parametres", mapOf("Cookie" to c))
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Rétablir les valeurs d'origine"))
        val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page.body())!!.groupValues[1]
        val form = mapOf("Cookie" to c, "Content-Type" to "application/x-www-form-urlencoded")
        assertEquals(403, envoie("POST", "/admin/parametres", "csrf=faux&b_actif=1".toByteArray(), form).statusCode())
        assertFalse(site.reglages.courant.bandeau.actif)
        // Everything on but « /dx », the videos refused, two satellites.
        val champs = ArrayList<String>()
        champs += "csrf=$csrf"
        site.modules.mapNotNull { it.idReglage }.filter { it != "/dx" }.forEach { champs += "mv:" + java.net.URLEncoder.encode(it, Charsets.UTF_8) + "=1" }
        TypePublication.values().forEach { t -> champs += "pp:${t.nom}=1"; if (t != TypePublication.VIDEO) champs += "pa:${t.nom}=1" }
        champs += listOf("b_actif=1", "b_prochain=1", "d_actif=1", "d_source=batc", "a_passages=1", "satellites=ISS%2C+SO-50")
        assertEquals(303, envoie("POST", "/admin/parametres", champs.joinToString("&").toByteArray(), form).statusCode())
        val r = site.reglages.courant
        assertFalse(r.module("/dx").visible)
        assertTrue(r.module("/grid").visible)
        assertFalse(r.publication(TypePublication.VIDEO).accepte)
        assertTrue(r.publication(TypePublication.SSTV).accepte)
        assertTrue(r.bandeau.actif && r.bandeau.prochainPassage && !r.bandeau.derniereImage)
        assertEquals("batc", r.direct.source)
        assertEquals(listOf("ISS", "SO-50"), r.satellitesAccueil)
        assertFalse(r.accueil.qso)
        assertEquals(404, get("/dx").statusCode())
        assertEquals(303, envoie("POST", "/admin/parametres/retablir", "csrf=$csrf".toByteArray(), form).statusCode())
        assertEquals(Reglages().json().toString(), site.reglages.courant.json().toString())
    }

    @Test fun `an auto-pilot pass takes over the live block, with its badge`() {
        site.reglages.change { it.copy(bandeau = it.bandeau.copy(actif = true), direct = Direct(actif = true)) }
        val t = System.currentTimeMillis()
        val pilote = JSONObject().put("t", t).put("pilote", JSONObject().put("enCours",
            JSONObject().put("sat", "ISS").put("phase", "EN_COURS").put("aos", t - 60_000L).put("los", t + 300_000L).put("mode", "PD120")))
        val img = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(320, 256, 1), "jpg", it) }.toByteArray()
        // No pass known yet: its picture is refused.
        assertEquals(409, envoie("POST", "/api/publication/direct/sstv-image", img, auth).statusCode())
        assertEquals(200, json(pilote).statusCode())
        assertEquals(200, envoie("POST", "/api/publication/direct/sstv-image", img, auth).statusCode())
        val e = JSONObject(get("/api/direct/etat").body())
        assertEquals("sstv", e.getJSONObject("direct").getString("vue"))
        assertEquals("ISS", e.getJSONObject("direct").getJSONObject("sstv").getString("sat"))
        assertTrue(e.getJSONObject("bandeau").getBoolean("passageEnCours"))
        assertEquals(200, get("/api/direct/sstv.jpg").statusCode())
        // « Basculer sur un passage SSTV en cours » off: QO-100 stays, the picture is refused.
        site.reglages.change { it.copy(direct = it.direct.copy(basculeSstv = false)) }
        assertEquals("qo100", JSONObject(get("/api/direct/etat").body()).getJSONObject("direct").getString("vue"))
        assertEquals(403, envoie("POST", "/api/publication/direct/sstv-image", img, auth).statusCode())
    }

    /** SatMe-PC's v1 multipart, as SitePc builds it: `type`, `meta`, `fichier`. */
    private fun multipart(type: String, meta: JSONObject, nom: String, mime: String, b: ByteArray): Pair<String, ByteArray> {
        val f = "SatMe-essai0123"
        val avant = "--$f\r\nContent-Disposition: form-data; name=\"type\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$type\r\n" +
            "--$f\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n$meta\r\n" +
            "--$f\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"$nom\"\r\nContent-Type: $mime\r\n\r\n"
        return "multipart/form-data; boundary=$f" to (avant.toByteArray() + b + "\r\n--$f--\r\n".toByteArray())
    }

    private fun v1(type: String, id: String) = auth + mapOf("X-SatMe-Version" to "1", "X-SatMe-Type" to type, "X-SatMe-Id" to id, "User-Agent" to "SatMe-PC/essai")

    @Test fun `the v1 format - multipart, the type in the path or in a header, ping, withdrawal, live types`() {
        // ping, POST, at {type} and at the one address.
        for (chemin in listOf("/api/publication/ping", "/api/publication")) {
            val p = envoie("POST", chemin, JSONObject().put("type", "ping").put("version", 1).toString().toByteArray(), v1("ping", "p1") + ("Content-Type" to "application/json"))
            assertEquals(chemin, 200, p.statusCode())
            assertTrue(JSONObject(p.body()).has("indicatif"))
        }
        assertEquals(401, envoie("POST", "/api/publication/ping", "{}".toByteArray(), mapOf("X-SatMe-Type" to "ping")).statusCode())
        // A picture as v1, a byte of it a CR LF and a dash or two to tease the parser.
        val png = PublicationsTest.png(couleur = 0x0D0A2D)
        val meta = JSONObject().put("version", 1).put("type", "sstv-image").put("id", "id-1").put("cle", "sstv:v1.png").put("satellite", "ISS")
            .put("utc", "2026-10-03T13:51:19Z").put("mode", "PD120").put("titre", "ISS · PD120").put("fichier", JSONObject().put("nom", "v1.png").put("type", "image/png"))
        val (ct, corps) = multipart("sstv-image", meta, "v1.png", "image/png", png)
        val r1 = envoie("POST", "/api/publication/sstv-image", corps, v1("sstv-image", "id-1") + ("Content-Type" to ct))
        assertEquals(r1.body(), 201, r1.statusCode())
        val id = JSONObject(r1.body()).getString("id")
        val p = site.publications.parId(id)!!
        assertEquals("ISS", p.meta.getString("satellite"))
        assertEquals("sstv:v1.png", p.cle)
        assertFalse(p.meta.has("version")); assertFalse(p.meta.has("id"))
        assertTrue(java.util.Arrays.equals(png, site.publications.fichierDe(p).readBytes()))
        // The same delivery again, at the one address this time: the same publication.
        val r2 = envoie("POST", "/api/publication", corps, v1("sstv-image", "id-1") + ("Content-Type" to ct))
        assertEquals(200, r2.statusCode()); assertTrue(JSONObject(r2.body()).getBoolean("deja"))
        // Broken or incomplete: 400; too large: 413.
        assertEquals(400, envoie("POST", "/api/publication/sstv-image", corps.copyOf(corps.size - 30), v1("sstv-image", "id-2") + ("Content-Type" to ct)).statusCode())
        val (ct2, sansFichier) = multipart("qso", JSONObject(), "x", "text/plain", ByteArray(0))
        assertEquals(400, envoie("POST", "/api/publication/qso", String(sansFichier).replace("name=\"fichier\"", "name=\"autre\"").toByteArray(), v1("qso", "id-3") + ("Content-Type" to ct2)).statusCode())
        val (ct3, gros) = multipart("qso", JSONObject(), "x.adi", "text/plain", ByteArray((1 shl 20) + 10) { 'a'.code.toByte() })
        assertEquals(413, envoie("POST", "/api/publication/qso", gros, v1("qso", "id-4") + ("Content-Type" to ct3)).statusCode())
        // A contact as ADIF.
        val adif = "<CALL:6>EA4ABC <QSO_DATE:8>20261003 <TIME_ON:6>135100 <SAT_NAME:5>SO-50 <MODE:2>FM <GRIDSQUARE:6>IN80DD <EOR>\n"
        val (ct4, qso) = multipart("qso", JSONObject().put("cle", "qso:1").put("indicatif", "EA4ABC"), "1.adi", "text/plain", adif.toByteArray())
        assertEquals(201, envoie("POST", "/api/publication/qso", qso, v1("qso", "id-5") + ("Content-Type" to ct4)).statusCode())
        // Withdrawn by its key.
        val w = envoie("POST", "/api/publication/retrait", JSONObject().put("type", "retrait").put("typeRetire", "sstv-image").put("cle", "sstv:v1.png").toString().toByteArray(),
            v1("retrait", "id-6") + ("Content-Type" to "application/json"))
        assertEquals(200, w.statusCode())
        assertNull(site.publications.parId(id))
        // The live types, at the one address: the state, a DATV picture in multipart, all forgotten.
        site.reglages.change { it.copy(bandeau = it.bandeau.copy(actif = true), direct = Direct(actif = true, source = "station")) }
        val t = System.currentTimeMillis()
        val etat = JSONObject().put("type", "direct").put("version", 1).put("t", t)
            .put("pilote", JSONObject().put("prochain", JSONObject().put("sat", "ISS").put("aos", t + 60_000L).put("los", t + 600_000L)))
        assertEquals(200, envoie("POST", "/api/publication", etat.toString().toByteArray(), v1("direct", "d1") + ("Content-Type" to "application/json")).statusCode())
        assertEquals("ISS", JSONObject(get("/api/direct/etat").body()).getJSONObject("bandeau").getJSONObject("prochain").getString("sat"))
        val img = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(320, 256, 1), "jpg", it) }.toByteArray()
        val (ct5, datv) = multipart("direct-datv-image", JSONObject().put("t", t), "image.jpg", "image/jpeg", img)
        assertEquals(200, envoie("POST", "/api/publication/direct-datv-image", datv, v1("direct-datv-image", "d2") + ("Content-Type" to ct5)).statusCode())
        assertEquals(200, envoie("POST", "/api/publication", "{}".toByteArray(), v1("direct-efface", "d3") + ("Content-Type" to "application/json")).statusCode())
        assertFalse(JSONObject(get("/api/direct/etat").body()).getJSONObject("bandeau").has("prochain"))
        // An unknown type: 404.
        assertEquals(404, envoie("POST", "/api/publication", "{}".toByteArray(), v1("autre", "z") + ("Content-Type" to "application/json")).statusCode())
    }
}
