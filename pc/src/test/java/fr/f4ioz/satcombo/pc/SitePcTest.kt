/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.sun.net.httpserver.HttpServer
import fr.f4ioz.satcombo.demo.ModulesStation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** « Publication web » against a fake site on this machine: the wire format, what goes, when, and what stays queued. */
class SitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The fake site: records each request, answers what [reponse] says. */
    private class Recu(val methode: String, val chemin: String, val auth: String, val entetes: Map<String, String>, val corps: ByteArray) {
        /** The multipart's parts (name → content type and bytes), or none. */
        val parties: Map<String, Pair<String, ByteArray>> by lazy { parties(entetes["content-type"].orEmpty(), corps) }
        val meta: JSONObject get() = parties["meta"]?.let { JSONObject(String(it.second, Charsets.UTF_8)) } ?: JSONObject()
        val fichier: ByteArray get() = parties["fichier"]?.second ?: ByteArray(0)
        val json: JSONObject get() = JSONObject(String(corps, Charsets.UTF_8))
    }
    private val recus = CopyOnWriteArrayList<Recu>()
    @Volatile private var reponse: (Recu) -> Pair<Int, String> = { 201 to "{\"ok\":true,\"id\":\"0123456789abcdef\",\"url\":\"http://x/gallery/item/0123456789abcdef\"}" }
    private lateinit var serveur: HttpServer
    private var t = 1_000_000L

    @Before fun demarre() {
        serveur = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serveur.createContext("/") { ex ->
            val e = ex.requestHeaders.keys.associate { it.lowercase() to ex.requestHeaders.getFirst(it) }
            val r = Recu(ex.requestMethod, ex.requestURI.toString(), e["authorization"].orEmpty(), e, ex.requestBody.readBytes())
            recus += r
            val (code, texte) = reponse(r)
            val b = texte.toByteArray()
            ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
            if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }
            ex.close()
        }
        serveur.start()
    }

    @After fun arrete() { serveur.stop(0) }

    private val url get() = "http://127.0.0.1:" + serveur.address.port + "/api/publication/{type}"

    /** The settings of the last [site]. */
    private lateinit var st: StockageFichier

    /** « Mon site web » switched on in Réglages › Modules ([on]), as the owner does before anything goes. */
    private fun site(demo: Boolean = false, on: Boolean = true): SitePc {
        st = StockageFichier(tmp.newFile("reglages-" + System.nanoTime() + ".properties"))
        if (on) ModulesStation.pose(st, emptySet())
        val s = SitePc(st, tmp.newFolder(), { demo }, { t })
        s.sources = { g, ref -> SitePc.Contenu(("contenu de " + ref).toByteArray(), null, if (g == SitePc.Genre.QSO) "text/plain" else "image/png",
            JSONObject().put("satellite", "ISS").put("titre", "image $ref")) }
        return s
    }

    /** The address and the token set, then « Tester la connexion » passed (unless not [teste]); the test's request forgotten. */
    private fun regle(s: SitePc, jeton: String = "le-jeton", teste: Boolean = true) {
        s.sert("/api/site/reglages", "POST") { k -> when (k) { "url" -> url; "jeton" -> jeton; else -> "" } }
        if (!teste) return
        val avant = reponse
        reponse = { 200 to "{\"ok\":true,\"site\":\"x\",\"indicatif\":\"F4XYZ\"}" }
        assertTrue(s.essai().startsWith("ok"))
        reponse = avant
        recus.clear()
    }

    @Test fun `nothing goes before the site is set`() {
        val s = site()
        assertTrue(s.publie(SitePc.Genre.SSTV, "a.png", true)!!.startsWith("l'adresse du site n'est pas réglée"))
        assertEquals(0, s.envoieDus())
        assertTrue(recus.isEmpty())
    }

    @Test fun `a picture by hand goes as v1 - headers, multipart, meta, file`() {
        val s = site(); regle(s)
        assertNull(s.publie(SitePc.Genre.SSTV, "a.png", true))
        assertEquals(1, s.envoieDus())
        val r = recus.single()
        assertEquals("POST", r.methode)
        assertEquals("/api/publication/sstv-image", r.chemin)
        assertEquals("Bearer le-jeton", r.auth)
        assertEquals("1", r.entetes["x-satme-version"])
        assertEquals("sstv-image", r.entetes["x-satme-type"])
        assertTrue(r.entetes["user-agent"]!!.startsWith("SatMe-PC/"))
        val id = r.entetes["x-satme-id"]!!
        assertTrue(r.entetes["content-type"]!!.startsWith("multipart/form-data; boundary="))
        // The parts: type, meta, fichier — in that order.
        assertEquals(listOf("type", "meta", "fichier"), r.parties.keys.toList())
        assertEquals("sstv-image", String(r.parties.getValue("type").second))
        assertTrue(r.parties.getValue("meta").first.startsWith("application/json"))
        assertEquals("image/png", r.parties.getValue("fichier").first)
        val m = r.meta
        assertEquals(1, m.getInt("version")); assertEquals("sstv-image", m.getString("type")); assertEquals(id, m.getString("id"))
        assertEquals("ISS", m.getString("satellite"))
        assertEquals("sstv:a.png", m.getString("cle"))
        assertEquals("image a.png", m.getString("titre"))
        val f = m.getJSONObject("fichier")
        assertEquals("a.png", f.getString("nom")); assertEquals("image/png", f.getString("type")); assertEquals(16L, f.getLong("taille"))
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("contenu de a.png".toByteArray()).joinToString("") { "%02x".format(it) }, f.getString("sha256"))
        assertEquals("contenu de a.png", String(r.fichier))
        assertEquals("0123456789abcdef", s.publication(SitePc.Genre.SSTV, "a.png")!!.id)
        // The token is never given back to the page.
        assertFalse(s.json().toString().contains("le-jeton"))
        assertTrue(s.json().getBoolean("jeton"))
    }

    @Test fun `each kind as chosen - automatic, at a click, never`() {
        val s = site(); regle(s)
        // Defaults: every kind at a click only, nothing by itself until the owner chooses « Automatique ».
        SitePc.Genre.values().forEach { assertEquals(it.name, SitePc.Mode.CLIC, s.mode(it)) }
        s.nouveauContact(42L)
        s.nouvelleVideo("v.webm")
        s.publie(SitePc.Genre.SSTV, "b.png", false)
        assertEquals(0, s.envoieDus())
        assertTrue(recus.isEmpty())
        s.regleMode(SitePc.Genre.SSTV, SitePc.Mode.AUTO)
        s.publie(SitePc.Genre.SSTV, "b.png", false)
        assertEquals(1, s.envoieDus())
        assertEquals("/api/publication/sstv-image", recus.single().chemin)
        s.regleMode(SitePc.Genre.SSTV, SitePc.Mode.JAMAIS)
        assertNotNull(s.publie(SitePc.Genre.SSTV, "c.png", true))
        s.regleMode(SitePc.Genre.QSO, SitePc.Mode.AUTO)
        s.nouveauContact(43L)
        assertEquals(1, s.envoieDus())
        assertEquals("/api/publication/qso", recus.last().chemin)
        assertEquals("qso:43", recus.last().meta.getString("cle"))
    }

    @Test fun `while the site does not answer, the item waits on disk and is sent later`() {
        val dossier = tmp.newFolder("pc")
        val st = StockageFichier(File(dossier, "r.properties"))
        ModulesStation.pose(st, emptySet())
        val s = SitePc(st, dossier, { false }, { t })
        s.sources = { _, ref -> SitePc.Contenu(ref.toByteArray(), null, "image/png", JSONObject()) }
        regle(s)
        reponse = { 503 to "" }
        assertNull(s.publie(SitePc.Genre.SSTV, "d.png", true))
        assertEquals(0, s.envoieDus())
        assertEquals(1, recus.size)
        // Not again before its delay (30 s).
        assertEquals(0, s.envoieDus()); assertEquals(1, recus.size)
        val file = s.json().getJSONArray("file")
        assertEquals(1, file.length()); assertEquals("HTTP 503", file.getJSONObject(0).getString("erreur"))
        // SatMe-PC restarted: the queue read back from the disk; the site answers now.
        reponse = { 201 to "{\"ok\":true,\"id\":\"00000000000000aa\"}" }
        t += 31_000L
        val s2 = SitePc(st, dossier, { false }, { t })
        assertEquals(1, s2.envoieDus())
        assertEquals(0, s2.json().getJSONArray("file").length())
        assertEquals(2, recus.size)
        assertEquals("d.png", String(recus.last().fichier))
        // The same delivery tried again: the same X-SatMe-Id.
        assertEquals(recus[0].entetes["x-satme-id"], recus[1].entetes["x-satme-id"])
    }

    @Test fun `a refusal is final, not retried`() {
        val s = site(); regle(s)
        reponse = { 415 to "{\"ok\":false,\"erreur\":\"contenu non reconnu\"}" }
        s.publie(SitePc.Genre.SSTV, "e.png", true)
        assertEquals(0, s.envoieDus())
        t += 10 * 3_600_000L
        assertEquals(0, s.envoieDus())
        assertEquals(1, recus.size)
        assertTrue(s.json().getJSONArray("historique").getJSONObject(0).getString("texte").contains("contenu non reconnu"))
    }

    @Test fun `the test asks the ping with the token`() {
        val s = site(); regle(s)
        reponse = { r -> if (r.auth == "Bearer le-jeton") 200 to "{\"ok\":true,\"site\":\"https://example.org\",\"indicatif\":\"F4XYZ\"}" else 401 to "" }
        assertEquals("ok : site de F4XYZ (https://example.org)", s.essai())
        val p = recus.single()
        assertEquals("POST", p.methode)
        assertEquals("/api/publication/ping", p.chemin)
        assertEquals("ping", p.entetes["x-satme-type"])
        assertTrue(p.entetes["content-type"]!!.startsWith("application/json"))
        assertEquals("ping", p.json.getString("type")); assertEquals(1, p.json.getInt("version"))
        // Any 2xx will do, whatever the body.
        reponse = { 204 to "" }
        assertEquals("ok : le site a répondu (HTTP 204)", s.essai())
        reponse = { r -> if (r.auth == "Bearer le-jeton") 204 to "" else 401 to "" }
        regle(s, "faux")
        assertEquals("jeton refusé par le site", s.essai())
    }

    @Test fun `the demonstration publishes to this machine only`() {
        val s = site(demo = true)
        s.sert("/api/site/reglages", "POST") { k -> when (k) { "url" -> "https://example.org/satme/{type}"; "jeton" -> "j"; else -> "" } }
        assertTrue(s.publie(SitePc.Genre.SSTV, "f.png", true)!!.startsWith("démonstration"))
        regle(s)
        assertNull(s.publie(SitePc.Genre.SSTV, "f.png", true))
        assertEquals(1, s.envoieDus())
    }

    @Test fun `switched off, nothing goes out - no queue, no test, no live data, no button`() {
        val s = site(); regle(s)
        s.regleMode(SitePc.Genre.SSTV, SitePc.Mode.AUTO)
        reponse = { 503 to "" }
        assertNull(s.publie(SitePc.Genre.SSTV, "g.png", true))
        assertEquals(0, s.envoieDus()); recus.clear()
        reponse = { 201 to "{\"ok\":true,\"id\":\"00000000000000bb\"}" }
        // Réglages › Modules: « Mon site web » unticked.
        ModulesStation.pose(st, setOf(ModulesStation.SITE))
        t += 10 * 3_600_000L
        assertEquals(0, s.envoieDus())
        assertEquals(SitePc.MODULE_COUPE, s.publie(SitePc.Genre.SSTV, "h.png", true))
        assertNull(s.publie(SitePc.Genre.SSTV, "h.png", false))
        s.nouvelleImage("i.png"); s.nouveauContact(44L)
        assertEquals(SitePc.MODULE_COUPE, s.essai())
        assertNull(s.envoieDirect("direct-spectre", JSONObject()))
        assertEquals(SitePc.MODULE_COUPE, s.retire(SitePc.Genre.SSTV, "g.png"))
        // The page's actions refused; its state says « off », so no 🌐 button anywhere.
        listOf("reglages", "essai", "publie", "retire", "direct", "maintenant").forEach { r ->
            val o = JSONObject(String(s.sert("/api/site/$r", "POST") { k -> if (k == "type") "sstv-image" else if (k == "url") url else "" }.corps))
            assertFalse(r, o.getBoolean("ok"))
        }
        val o = s.json()
        assertFalse(o.getBoolean("module")); assertFalse(o.getBoolean("configure"))
        assertTrue(recus.isEmpty())
        // Back on: what waited goes.
        ModulesStation.pose(st, emptySet())
        assertEquals(1, s.envoieDus())
        assertTrue(s.json().getBoolean("configure"))
    }

    @Test fun `a new station - off, then the assistant - ready only after a test that passes`() {
        val s = site(on = false)
        assertFalse(ModulesStation.actif(st, ModulesStation.SITE))
        assertFalse(s.json().getBoolean("module"))
        ModulesStation.pose(st, ModulesStation.bascule(ModulesStation.coupes(st), ModulesStation.SITE, true))
        assertTrue(s.json().getBoolean("module"))
        // Address and token saved but not tested: not ready, nothing goes.
        regle(s, teste = false)
        assertFalse(s.json().getBoolean("pret")); assertFalse(s.json().getBoolean("configure"))
        assertTrue(s.publie(SitePc.Genre.SSTV, "a.png", true)!!.contains("pas encore été testée"))
        // A test that fails: still not ready.
        reponse = { 401 to "" }
        val faux = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> url; "jeton" -> "faux"; else -> "" } }.corps))
        assertFalse(faux.getBoolean("ok")); assertEquals("jeton refusé par le site", faux.getString("message"))
        assertFalse(s.json().getBoolean("configure"))
        // The assistant's step 3 with the right token: kept and ready; every kind « Sur clic », the live parts off.
        reponse = { 200 to "{\"ok\":true,\"site\":\"x\",\"indicatif\":\"F4XYZ\"}" }
        val bon = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> url; "jeton" -> "le-jeton"; else -> "" } }.corps))
        assertTrue(bon.getBoolean("ok")); assertTrue(bon.getJSONObject("etat").getBoolean("configure"))
        val modes = s.json().getJSONObject("modes")
        modes.keySet().forEach { assertEquals(it, "clic", modes.getString(it)) }
        // Another address: to be tested again.
        s.sert("/api/site/reglages", "POST") { k -> if (k == "url") "http://127.0.0.1:1" else "" }
        assertFalse(s.json().getBoolean("configure"))
    }

    @Test fun `an existing station - on only if its site was already set`() {
        val avec = StockageFichier(tmp.newFile("avec.properties"))
        avec.edit().putStringSet(ModulesStation.CLE, setOf("noeud")).putString("site_url", "https://mon-site-radioamateur.fr").putString("site_jeton", "j").apply()
        ModulesStation.migre(avec) { it == ModulesStation.SITE && SitePc.regle(avec) }
        assertTrue(ModulesStation.actif(avec, ModulesStation.SITE))
        assertFalse(ModulesStation.actif(avec, "noeud"))
        // Set before the test was asked: counts as tested; its address (the site's root then) becomes the `site` module's API.
        val ancien = SitePc(avec, tmp.newFolder(), { false }, { t })
        assertTrue(ancien.json().getBoolean("configure"))
        assertEquals("https://mon-site-radioamateur.fr/api/publication/{type}", ancien.url)
        assertEquals("https://mon-site-radioamateur.fr/api/publication/qso", ancien.adresse("qso"))
        // Only once.
        assertEquals("https://mon-site-radioamateur.fr/api/publication/{type}", SitePc(avec, tmp.newFolder(), { false }, { t }).url)
        val sans = StockageFichier(tmp.newFile("sans.properties"))
        sans.edit().putStringSet(ModulesStation.CLE, emptySet()).putString("site_url", "https://mon-site-radioamateur.fr").apply()
        ModulesStation.migre(sans) { it == ModulesStation.SITE && SitePc.regle(sans) }
        assertFalse(ModulesStation.actif(sans, ModulesStation.SITE))
        // A new install: off.
        val neuf = StockageFichier(tmp.newFile("neuf.properties"))
        ModulesStation.migre(neuf) { it == ModulesStation.SITE && SitePc.regle(neuf) }
        assertFalse(ModulesStation.actif(neuf, ModulesStation.SITE))
        assertFalse(SitePc(neuf, tmp.newFolder(), { false }, { t }).json().getBoolean("module"))
    }

    @Test fun `the delays grow to an hour`() {
        assertEquals(30_000L, SitePc.attente(1))
        assertEquals(60_000L, SitePc.attente(2))
        assertEquals(3_600_000L, SitePc.attente(20))
        assertEquals("https://example.org", SitePc.normalise(" example.org/ "))
        assertEquals("https://example.org/api/{type}", SitePc.normalise("example.org/api/{type}"))
        assertTrue(SitePc.local("http://127.0.0.1:8201"))
        assertTrue(SitePc.local("http://127.0.0.1:8201/r/{type}"))
        assertFalse(SitePc.local("https://example.org"))
        // Plain http only to this machine or the local network.
        for (u in listOf("http://127.0.0.1:8080/x", "http://192.168.1.20/r.php", "http://10.0.0.5/{type}", "http://172.20.1.1/", "http://nas.local/r", "http://nas/r", "http://[fd00::1]/r"))
            assertNull(u, SitePc.refusHttp(u))
        for (u in listOf("http://example.org/r.php", "http://203.0.113.5/r", "http://172.32.0.1/"))
            assertNotNull(u, SitePc.refusHttp(u))
        assertNull(SitePc.refusHttp("https://example.org/r.php"))
    }

    @Test fun `one address for every kind, without a token`() {
        val s = site()
        s.sert("/api/site/essai", "POST") { k -> if (k == "url") url.removeSuffix("/api/publication/{type}") + "/recevoir.php" else "" }
        assertTrue(s.json().getBoolean("configure"))
        assertFalse(s.json().getBoolean("modele"))
        recus.clear()
        s.publie(SitePc.Genre.QSO, "42", true); s.publie(SitePc.Genre.PLANCHE, "p1", true)
        assertEquals(2, s.envoieDus())
        assertEquals(listOf("/recevoir.php", "/recevoir.php"), recus.map { it.chemin })
        assertEquals(listOf("qso", "planche"), recus.map { it.entetes["x-satme-type"] })
        assertEquals(listOf("qso", "planche"), recus.map { String(it.parties.getValue("type").second) })
        // No token set: no Authorization at all.
        assertTrue(recus.all { it.entetes["authorization"] == null })
        // Each kind its own key, file name and type.
        assertEquals("qso:42", recus[0].meta.getString("cle")); assertEquals("42.adi", recus[0].meta.getJSONObject("fichier").getString("nom"))
        assertEquals("text/plain", recus[0].parties.getValue("fichier").first)
        assertEquals("planche:p1", recus[1].meta.getString("cle")); assertEquals("p1.png", recus[1].meta.getJSONObject("fichier").getString("nom"))
        assertTrue(recus[0].entetes["x-satme-id"] != recus[1].entetes["x-satme-id"])
    }

    @Test fun `a withdrawal and the live data are JSON of their own type`() {
        val s = site(); regle(s)
        s.publie(SitePc.Genre.SSTV, "a.png", true); s.envoieDus(); recus.clear()
        reponse = { 200 to "{\"ok\":true}" }
        assertNull(s.retire(SitePc.Genre.SSTV, "a.png"))
        val r = recus.single()
        assertEquals("/api/publication/retrait", r.chemin)
        assertEquals("retrait", r.entetes["x-satme-type"])
        assertEquals("sstv-image", r.json.getString("typeRetire")); assertEquals("sstv:a.png", r.json.getString("cle")); assertEquals("0123456789abcdef", r.json.getString("id"))
        recus.clear()
        assertEquals(200, s.envoieDirect("direct", JSONObject().put("t", 5L).put("pilote", JSONObject.NULL))!!.first)
        assertEquals("/api/publication/direct", recus.last().chemin)
        assertEquals("direct", recus.last().json.getString("type")); assertTrue(recus.last().json.isNull("pilote"))
        s.envoieDirect("direct-datv-image", null, byteArrayOf(1, 2, 3))
        val i = recus.last()
        assertEquals("/api/publication/direct-datv-image", i.chemin)
        assertEquals("image/jpeg", i.parties.getValue("fichier").first)
        assertEquals(3, i.fichier.size)
        assertEquals("direct-datv-image", i.meta.getString("type"))
    }

    @Test fun `a refused token stops everything, the item kept, until a new test`() {
        val s = site(); regle(s)
        reponse = { 401 to "{\"erreur\":\"jeton refusé\"}" }
        s.publie(SitePc.Genre.SSTV, "a.png", true); s.publie(SitePc.Genre.SSTV, "b.png", true)
        assertEquals(0, s.envoieDus())
        assertEquals(1, recus.size)
        assertFalse(s.json().getBoolean("configure"))
        assertEquals(2, s.json().getJSONArray("file").length())
        t += 10 * 3_600_000L
        assertEquals(0, s.envoieDus()); assertEquals(1, recus.size)
        reponse = { 201 to "{\"ok\":true}" }
        regle(s)
        t += 10 * 3_600_000L
        assertEquals(2, s.envoieDus())
    }

    @Test fun `a public address in plain http is refused`() {
        val s = site()
        val r = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> if (k == "url") "http://example.org/r.php" else "" }.corps))
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("message").startsWith("http:// seulement"))
        assertTrue(recus.isEmpty())
    }

    @Test fun `a folder - its layout, its index, its feed, written whole`() {
        val s = site()
        val d = tmp.newFolder("publie")
        val r = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> d.absolutePath; else -> "" } }.corps))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(0, d.listFiles()!!.size)
        s.sources = { g, ref -> SitePc.Contenu(("contenu de " + ref).toByteArray(), null, if (g == SitePc.Genre.QSO) "text/plain" else "image/png",
            JSONObject().put("satellite", "ISS").put("titre", "image $ref").put("utc", "2025-10-03T13:51:19Z")) }
        s.publie(SitePc.Genre.SSTV, "a.png", true)
        s.publie(SitePc.Genre.SSTV, "b.png", true)
        assertEquals(2, s.envoieDus())
        assertTrue(recus.isEmpty())
        val index = JSONObject(File(d, "index.json").readText())
        assertEquals(1, index.getInt("version"))
        val l = index.getJSONArray("elements")
        assertEquals(2, l.length())
        // Newest first; each file under type/year/id, its meta beside it.
        val b = l.getJSONObject(0)
        assertEquals("sstv:b.png", b.getString("cle"))
        val id = b.getString("id")
        assertEquals("sstv-image/2025/$id.png", b.getString("chemin"))
        assertEquals("contenu de b.png", File(d, b.getString("chemin")).readText())
        val m = JSONObject(File(d, "sstv-image/2025/$id.json").readText())
        assertEquals("ISS", m.getString("satellite")); assertEquals("sstv-image", m.getString("type")); assertEquals(1, m.getInt("version"))
        val flux = File(d, "flux.xml").readText()
        assertTrue(flux.contains("<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
        assertTrue(flux.contains("<link href=\"sstv-image/2025/$id.png\""))
        // A new version of the same item replaces the old one, files and all.
        s.publie(SitePc.Genre.SSTV, "b.png", true); s.envoieDus()
        val l2 = JSONObject(File(d, "index.json").readText()).getJSONArray("elements")
        assertEquals(2, l2.length())
        assertFalse(File(d, "sstv-image/2025/$id.png").exists()); assertFalse(File(d, "sstv-image/2025/$id.json").exists())
        // Withdrawn.
        assertNull(s.retire(SitePc.Genre.SSTV, "a.png"))
        assertEquals(1, JSONObject(File(d, "index.json").readText()).getJSONArray("elements").length())
        // Never a temporary file left behind.
        assertTrue(d.walkTopDown().none { it.name.endsWith(".tmp") })
        // The live parts: direct.json merged in place, the pictures; no spectrum.
        assertEquals(200, s.envoieDirect("direct", JSONObject().put("t", 1L).put("pilote", JSONObject().put("maitre", true)))!!.first)
        assertEquals(200, s.envoieDirect("direct", JSONObject().put("t", 2L).put("datv", JSONObject.NULL))!!.first)
        val dj = JSONObject(File(d, "direct/direct.json").readText())
        assertTrue(dj.getJSONObject("pilote").getBoolean("maitre")); assertTrue(dj.isNull("datv")); assertEquals(2L, dj.getLong("t"))
        assertEquals(200, s.envoieDirect("direct-sstv-image", null, byteArrayOf(9))!!.first)
        assertTrue(File(d, "direct/sstv.jpg").isFile)
        assertEquals(415, s.envoieDirect("direct-spectre", JSONObject())!!.first)
        // Switched off: nothing written.
        ModulesStation.pose(st, setOf(ModulesStation.SITE))
        val avant = File(d, "index.json").readText()
        s.publie(SitePc.Genre.SSTV, "c.png", true); t += 3_600_000L; s.envoieDus()
        assertNull(s.envoieDirect("direct", JSONObject()))
        assertEquals(avant, File(d, "index.json").readText())
    }

    @Test fun `an SSDV picture is sent again as it grows - same key, once quiet or complete`() {
        val s = site(); regle(s)
        assertEquals(SitePc.Mode.CLIC, s.mode(SitePc.Genre.SSDV))
        // At a click by default: packets alone send nothing.
        s.nouveauPaquetSsdv("img-1", false)
        t += SitePc.SSDV_CALME_MS + 1
        assertTrue(s.ticSsdv().isEmpty())
        // Sent by hand once: from then on it goes again by itself when it grows.
        assertNull(s.publie(SitePc.Genre.SSDV, "img-1", true))
        assertEquals(1, s.envoieDus())
        assertEquals("/api/publication/ssdv-image", recus.last().chemin)
        assertEquals("ssdv:img-1", recus.last().meta.getString("cle"))
        s.nouveauPaquetSsdv("img-1", false)
        t += 10_000L
        assertTrue("not yet: packets still coming", s.ticSsdv().isEmpty())
        t += SitePc.SSDV_CALME_MS
        assertEquals(listOf("img-1"), s.ticSsdv())
        assertEquals(1, s.envoieDus())
        assertEquals(2, recus.size)
        assertEquals("ssdv:img-1", recus.last().meta.getString("cle"))
        // Automatique: a new picture goes; complete, at once.
        s.regleMode(SitePc.Genre.SSDV, SitePc.Mode.AUTO)
        s.nouveauPaquetSsdv("img-2", true)
        assertEquals(listOf("img-2"), s.ticSsdv())
        // Never: nothing.
        s.regleMode(SitePc.Genre.SSDV, SitePc.Mode.JAMAIS)
        s.nouveauPaquetSsdv("img-3", true)
        assertTrue(s.ticSsdv().isEmpty())
    }

    @Test fun `a folder that cannot be written is not ready`() {
        val s = site()
        val f = tmp.newFile("pas-un-dossier")
        val r = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> File(f, "x").absolutePath; else -> "" } }.corps))
        assertFalse(r.getBoolean("ok"))
        assertFalse(s.json().getBoolean("configure"))
        val rel = JSONObject(String(s.sert("/api/site/essai", "POST") { k -> when (k) { "dossier" -> "relatif/dossier"; else -> "" } }.corps))
        assertTrue(rel.getString("message").contains("chemin complet"))
        assertTrue(site(demo = true).let { d -> d.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> tmp.newFolder().absolutePath; else -> "" } }
            d.essai().startsWith("démonstration") })
    }

    companion object {
        /** A multipart body's parts, by name: their Content-Type and bytes. */
        fun parties(type: String, corps: ByteArray): Map<String, Pair<String, ByteArray>> {
            if (!type.startsWith("multipart/form-data")) return emptyMap()
            val f = "--" + type.substringAfter("boundary=")
            val texte = String(corps, Charsets.ISO_8859_1)
            val r = LinkedHashMap<String, Pair<String, ByteArray>>()
            assertTrue(texte.startsWith(f + "\r\n")); assertTrue(texte.endsWith(f + "--\r\n"))
            for (bloc in texte.removeSuffix(f + "--\r\n").split(f + "\r\n").drop(1)) {
                val tete = bloc.substringBefore("\r\n\r\n")
                val nom = Regex("name=\"([^\"]+)\"").find(tete)!!.groupValues[1]
                val ct = tete.lines().firstOrNull { it.startsWith("Content-Type:", true) }?.substringAfter(':')?.trim().orEmpty()
                r[nom] = ct to bloc.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
            }
            return r
        }
    }
}
