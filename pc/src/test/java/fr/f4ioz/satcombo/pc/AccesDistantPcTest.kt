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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/** The password asked of other machines: its hash, the sessions, the tries counted, and every route behind it. */
class AccesDistantPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-acces-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    @Test
    fun le_mot_de_passe_est_hache_sale_et_verifie() {
        val h = MotDePasse.hache("tournesol-radar")
        assertTrue(h, h.startsWith("pbkdf2-sha256$210000$"))
        assertFalse(h.contains("tournesol"))
        assertTrue(MotDePasse.verifie("tournesol-radar", h))
        assertFalse(MotDePasse.verifie("tournesol-radaR", h))
        assertFalse(MotDePasse.verifie("", h))
        // Salted: the same password, another hash.
        assertNotEquals(h, MotDePasse.hache("tournesol-radar"))
        assertTrue(MotDePasse.ITERATIONS >= 200_000)
        // Malformed records never match.
        assertFalse(MotDePasse.verifie("x", "pbkdf2-sha256$0\$AA\$AA"))
        assertFalse(MotDePasse.verifie("x", "md5$1$2$3"))
        assertFalse(MotDePasse.verifie("x", "pbkdf2-sha256$1000\$!!\$AA"))
    }

    @Test
    fun la_phrase_tiree_est_de_quatre_mots_simples() {
        assertTrue(MotDePasse.MOTS.size >= 256)
        assertEquals(MotDePasse.MOTS.size, MotDePasse.MOTS.toSet().size)
        val p = MotDePasse.phrase()
        assertTrue(p, Regex("[a-z]+(-[a-z]+){3}").matches(p))
        assertTrue(p.split('-').all { it in MotDePasse.MOTS })
    }

    @Test
    fun une_session_vaut_trente_jours_et_survit_au_redemarrage() {
        var t = 1_000_000L
        val f = File(dossier, "s.txt")
        val s = SessionsPc(f, { t })
        val j = s.cree()
        assertEquals(43, j.length)
        assertTrue(s.valide(j))
        assertFalse(s.valide(j + "x"))
        assertFalse(s.valide(null)); assertFalse(s.valide(""))
        // Only the token's hash on disk.
        assertFalse(f.readText().contains(j))
        assertTrue(f.readText().contains(SessionsPc.empreinte(j)))
        // A restart: still valid.
        val s2 = SessionsPc(f, { t })
        assertTrue(s2.valide(j))
        assertEquals(1, s2.nombre)
        // Thirty days later: over.
        t += SessionsPc.DUREE_MS + 1
        assertFalse(s2.valide(j))
        assertEquals(0, SessionsPc(f, { t }).nombre)
    }

    @Test
    fun fermer_les_sessions_garde_la_sienne() {
        val s = SessionsPc(null)
        val a = s.cree(); val b = s.cree(); val c = s.cree()
        assertNotEquals(a, b)
        s.retire(b)
        assertFalse(s.valide(b)); assertTrue(s.valide(a))
        s.retireTout(sauf = c)
        assertFalse(s.valide(a)); assertTrue(s.valide(c))
        s.retireTout()
        assertFalse(s.valide(c))
    }

    @Test
    fun cinq_echecs_une_minute_puis_davantage() {
        var t = 0L
        val l = LimiteEssais { t }
        repeat(4) { l.echec("10.0.0.5") }
        assertEquals(0L, l.attente("10.0.0.5"))
        l.echec("10.0.0.5")
        assertEquals(60_000L, l.attente("10.0.0.5"))
        // Another address is not held.
        assertEquals(0L, l.attente("10.0.0.6"))
        t += 60_000
        assertEquals(0L, l.attente("10.0.0.5"))
        repeat(5) { l.echec("10.0.0.5") }
        assertEquals(120_000L, l.attente("10.0.0.5"))
        t += 120_000
        repeat(5) { l.echec("10.0.0.5") }
        assertEquals(240_000L, l.attente("10.0.0.5"))
        // Never more than an hour.
        repeat(50) { t += 3_600_000; repeat(5) { l.echec("10.0.0.5") } }
        assertEquals(3_600_000L, l.attente("10.0.0.5"))
        // A success forgets.
        l.reussite("10.0.0.5")
        assertEquals(0L, l.attente("10.0.0.5"))
    }

    @Test
    fun premier_demarrage_en_reseau_tire_un_mot_de_passe_une_fois() {
        // Without --reseau: nothing drawn.
        val sans = AccesDistantPc(dossier, reseau = false, iterations = 1000)
        assertNull(sans.initialise())
        assertFalse(sans.defini)
        val a = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        val ligne = a.initialise()
        assertNotNull(ligne)
        val p = a.initial!!
        assertTrue(ligne!!.contains(p))
        assertTrue(a.defini)
        // Only its hash on disk (beside the drawn words, kept until another password is chosen).
        val txt = File(dossier, "acces-distant.properties").readText()
        assertTrue(txt.contains("pbkdf2-sha256"))
        // Restarted: the same password, not another.
        val b = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        b.initialise()
        assertEquals(p, b.initial)
        assertTrue(b.connecte("10.0.0.2", p) is AccesDistantPc.Connexion.Ouverte)
        // Changed: the drawn one forgotten, the sessions closed.
        val ouverte = b.connecte("10.0.0.2", p) as AccesDistantPc.Connexion.Ouverte
        assertEquals("8 caractères au moins", b.regle("court"))
        assertNull(b.regle("mon-mot-de-passe"))
        assertNull(b.initial)
        assertFalse(b.session(ouverte.jeton))
        assertFalse(File(dossier, "acces-distant.properties").readText().contains(p))
        // Removed: never drawn again, refused to everyone.
        b.retire()
        val c = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        assertNull(c.initialise())
        assertFalse(c.defini)
        assertEquals(AccesDistantPc.PAS_DEFINI, (c.connecte("10.0.0.2", "mon-mot-de-passe") as AccesDistantPc.Connexion.Refusee).raison)
    }

    @Test
    fun trop_d_essais_meme_le_bon_attend() {
        var t = 5_000L
        val a = AccesDistantPc(dossier, reseau = true, horloge = { t }, iterations = 1000)
        a.regle("le-bon-mot-de-passe")
        repeat(4) { assertTrue(a.connecte("10.0.0.9", "faux") is AccesDistantPc.Connexion.Refusee) }
        val r = a.connecte("10.0.0.9", "faux") as AccesDistantPc.Connexion.Refusee
        assertTrue(r.raison, r.raison.contains("1 min"))
        assertTrue(a.connecte("10.0.0.9", "le-bon-mot-de-passe") is AccesDistantPc.Connexion.Refusee)
        t += 60_000
        assertTrue(a.connecte("10.0.0.9", "le-bon-mot-de-passe") is AccesDistantPc.Connexion.Ouverte)
    }

    @Test
    fun la_suite_reste_sur_ce_site() {
        assertEquals("/pc#sdr", AccesDistantPc.suite("/pc#sdr"))
        assertEquals("/", AccesDistantPc.suite("//exemple.org/"))
        assertEquals("/", AccesDistantPc.suite("https://exemple.org/"))
        assertEquals("/", AccesDistantPc.suite("/\\exemple.org"))
        assertEquals("/", AccesDistantPc.suite("/a\r\nSet-Cookie: x=1"))
        assertEquals("/", AccesDistantPc.suite(null))
        assertEquals("/", AccesDistantPc.suite("/connexion"))
    }

    // ------------------------------------------------------------------ the server

    private class Rep(val code: Int, val corps: String, val entetes: Map<String?, List<String>>)

    private fun requete(base: String, chemin: String, methode: String = "GET", cookie: String? = null, corps: String? = null, lire: Boolean = true): Rep {
        val c = URL(base + chemin).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = methode
        c.connectTimeout = 3000; c.readTimeout = 3000
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (corps != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(corps.toByteArray()) }
        }
        val code = c.responseCode
        val t = if (!lire) "" else runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
        val e = c.headerFields
        c.disconnect()
        return Rep(code, t, e)
    }

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        return st
    }

    /** Routes of every kind: page, API, streams (SSE, sound, MJPEG), files, uploads, gestures. */
    private val ROUTES = listOf("GET" to "/api/etat", "GET" to "/api/flux?t=etat", "GET" to "/api/son/flux?source=sdr",
        "GET" to "/api/datv/video.mjpeg", "GET" to "/api/reglages", "GET" to "/sstv/x.png", "GET" to "/enregistrements/x.wav",
        "GET" to "/sauvegarde.zip", "GET" to "/carnet.adi", "GET" to "/api/acces", "GET" to "/api/montage",
        "POST" to "/api/cat/connecter?modele=SIM", "POST" to "/api/import", "POST" to "/api/sauvegarde/restaure",
        "POST" to "/api/acces/mdp", "POST" to "/api/datv/emission/demarre", "GET" to "/planche/api/modeles", "GET" to "/journal/api/passages")

    @Test
    fun une_autre_machine_sans_session_n_a_rien_ce_pc_passe() {
        val st = station()
        val acces = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        acces.regle("mot-de-passe-du-mat")
        val s = ServeurPc(st, 0, diffusion = DiffusionSon())
        s.acces = acces
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            // This PC: free, as before.
            assertEquals(200, requete(base, "/api/etat").code)
            assertEquals(200, requete(base, "/api/flux?t=etat", lire = false).code)
            assertEquals(200, requete(base, "/api/son/flux?source=sdr", lire = false).code)
            assertEquals(200, requete(base, "/").code)
            val a = JSONObject(requete(base, "/api/acces").corps)
            assertTrue(a.getBoolean("local")); assertTrue(a.getBoolean("defini"))

            // Another machine (as the server sees it).
            s.estLocal = { false }
            for ((m, r) in ROUTES) {
                val rep = requete(base, r, m, corps = if (m == "POST") "mdp=12345678" else null, lire = !r.contains("flux") && !r.contains("mjpeg"))
                assertEquals("$m $r", 401, rep.code)
                assertEquals("$m $r", "1", rep.entetes["X-SatMe-Connexion"]?.firstOrNull())
            }
            assertFalse(st.etat.value.catConnecte)
            // A page: to the login, then back.
            val p = requete(base, "/pc")
            assertEquals(303, p.code)
            assertEquals("/connexion?suite=%2Fpc", p.entetes["Location"]?.first())
            val login = requete(base, "/connexion?suite=%2Fpc")
            assertEquals(200, login.code)
            assertTrue(login.corps.contains("Mot de passe"))
            // The application's files: anyone.
            assertEquals(200, requete(base, "/manifest.webmanifest").code)
            assertEquals(200, requete(base, "/sw.js").code)
            // A wrong password: no cookie.
            val faux = requete(base, "/connexion", "POST", corps = "mdp=faux&suite=%2Fpc")
            assertEquals(401, faux.code)
            assertNull(faux.entetes["Set-Cookie"])
            assertTrue(faux.corps.contains("incorrect"))
            // The right one: a session cookie, HttpOnly and SameSite=Strict, back to the page.
            val bon = requete(base, "/connexion", "POST", corps = "mdp=mot-de-passe-du-mat&suite=%2Fpc")
            assertEquals(303, bon.code)
            assertEquals("/pc", bon.entetes["Location"]?.first())
            val sc = bon.entetes["Set-Cookie"]!!.first()
            assertTrue(sc, sc.contains("HttpOnly") && sc.contains("SameSite=Strict"))
            val cookie = sc.substringBefore(';')
            assertEquals(200, requete(base, "/api/etat", cookie = cookie).code)
            assertEquals(200, requete(base, "/api/flux?t=etat", cookie = cookie, lire = false).code)
            assertEquals(200, requete(base, "/api/son/flux?source=sdr", cookie = cookie, lire = false).code)
            val a2 = JSONObject(requete(base, "/api/acces", cookie = cookie).corps)
            assertTrue(a2.getBoolean("connecte")); assertFalse(a2.getBoolean("local"))
            // Removing the password: from the PC only.
            assertFalse(JSONObject(requete(base, "/api/acces/retire", "POST", cookie = cookie).corps).getBoolean("ok"))
            // Logged out: refused again.
            assertEquals(200, requete(base, "/deconnexion", "POST", cookie = cookie).code)
            assertEquals(401, requete(base, "/api/etat", cookie = cookie).code)
            // No password at all: nothing, said so.
            acces.retire()
            val rien = requete(base, "/connexion")
            assertEquals(403, rien.code)
            assertTrue(rien.corps.contains("Mot de passe à définir sur le PC"))
        } finally { s.arrete() }
    }

    /** The same through a real network address of this machine, when it has one. */
    @Test
    fun par_une_vraie_adresse_du_reseau() {
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        }.getOrNull() ?: return
        val acces = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        acces.regle("mot-de-passe-du-mat")
        val s = ServeurPc(station(), 0, reseau = true, diffusion = DiffusionSon())
        s.acces = acces
        s.demarre()
        try {
            val loin = "http://${ip.hostAddress}:${s.portReel}"
            val r = runCatching { requete(loin, "/api/etat") }.getOrNull() ?: return
            assertEquals(401, r.code)
            assertEquals(401, requete(loin, "/api/flux?t=etat", lire = false).code)
            assertEquals(401, requete(loin, "/api/son/flux", lire = false).code)
            assertEquals(200, requete("http://127.0.0.1:${s.portReel}", "/api/etat").code)
        } finally { s.arrete() }
    }
}
