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

    private fun site(demo: Boolean = false): SitePc {
        val s = SitePc(StockageFichier(tmp.newFile("reglages-" + System.nanoTime() + ".properties")), tmp.newFolder(), { demo }, { t })
        s.sources = { g, ref -> SitePc.Contenu(("contenu de " + ref).toByteArray(), null, if (g == SitePc.Genre.QSO) "text/plain" else "image/png",
            JSONObject().put("satellite", "ISS").put("titre", "image $ref")) }
        return s
    }

    private fun regle(s: SitePc, jeton: String = "le-jeton") =
        s.sert("/api/site/reglages", "POST") { k -> when (k) { "url" -> url; "jeton" -> jeton; else -> "" } }

    @Test fun `nothing goes before the site is set`() {
        val s = site()
        assertEquals("le site web n'est pas réglé (Réglages › Site web)", s.publie(SitePc.Genre.SSTV, "a.png", true))
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
        // Defaults: SSTV pictures and planches by themselves, contacts at a click.
        s.nouveauContact(42L)
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

    @Test fun `the delays grow to an hour`() {
        assertEquals(30_000L, SitePc.attente(1))
        assertEquals(60_000L, SitePc.attente(2))
        assertEquals(3_600_000L, SitePc.attente(20))
        assertEquals("https://f4ioz.fr", SitePc.normalise(" f4ioz.fr/ "))
        assertTrue(SitePc.local("http://127.0.0.1:8201"))
        assertFalse(SitePc.local("https://f4ioz.fr"))
    }
}
