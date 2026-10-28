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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** « Réglages › Site web » against a fake site on this machine: what goes, when, and what stays queued. */
class SitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The fake site: records each request, answers what [reponse] says. */
    private class Recu(val methode: String, val chemin: String, val auth: String, val meta: JSONObject, val corps: ByteArray)
    private val recus = CopyOnWriteArrayList<Recu>()
    @Volatile private var reponse: (Recu) -> Pair<Int, String> = { 201 to "{\"ok\":true,\"id\":\"0123456789abcdef\",\"url\":\"http://x/gallery/item/0123456789abcdef\"}" }
    private lateinit var serveur: HttpServer
    private var t = 1_000_000L

    @Before fun demarre() {
        serveur = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serveur.createContext("/") { ex ->
            val m = ex.requestHeaders.getFirst("X-SatMe-Meta")?.let { JSONObject(String(Base64.getUrlDecoder().decode(it))) } ?: JSONObject()
            val r = Recu(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization").orEmpty(), m, ex.requestBody.readBytes())
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

    private val url get() = "http://127.0.0.1:" + serveur.address.port

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
        assertEquals("le site web n'est pas réglé (Réglages › Services en ligne › Mon site web)", s.publie(SitePc.Genre.SSTV, "a.png", true))
        assertEquals(0, s.envoieDus())
        assertTrue(recus.isEmpty())
    }

    @Test fun `a picture by hand goes with the token, its words and its key`() {
        val s = site(); regle(s)
        assertNull(s.publie(SitePc.Genre.SSTV, "a.png", true))
        assertEquals(1, s.envoieDus())
        val r = recus.single()
        assertEquals("POST", r.methode)
        assertEquals("/api/publication/sstv-image", r.chemin)
        assertEquals("Bearer le-jeton", r.auth)
        assertEquals("ISS", r.meta.getString("satellite"))
        assertEquals("sstv:a.png", r.meta.getString("cle"))
        assertEquals("contenu de a.png", String(r.corps))
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
        assertEquals("d.png", String(recus.last().corps))
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
        reponse = { r -> if (r.auth == "Bearer le-jeton") 200 to "{\"ok\":true,\"site\":\"https://f4ioz.fr\",\"indicatif\":\"F4IOZ\"}" else 401 to "" }
        assertEquals("ok : site de F4IOZ (https://f4ioz.fr)", s.essai())
        assertEquals("/api/publication/ping", recus.single().chemin)
        regle(s, "faux")
        assertEquals("jeton refusé par le site", s.essai())
    }

    @Test fun `the demonstration publishes to this machine only`() {
        val s = site(demo = true)
        s.sert("/api/site/reglages", "POST") { k -> when (k) { "url" -> "https://f4ioz.fr"; "jeton" -> "j"; else -> "" } }
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
        assertNull(s.envoieDirect("POST", "/spectre", "application/json", ByteArray(1)))
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
        // Set before the test was asked: counts as tested.
        assertTrue(SitePc(avec, tmp.newFolder(), { false }, { t }).json().getBoolean("configure"))
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
        assertEquals("https://f4ioz.fr", SitePc.normalise(" f4ioz.fr/ "))
        assertTrue(SitePc.local("http://127.0.0.1:8201"))
        assertFalse(SitePc.local("https://f4ioz.fr"))
    }
}
