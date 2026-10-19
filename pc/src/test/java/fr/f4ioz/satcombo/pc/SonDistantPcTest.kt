/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.SonDistant
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** « Mode distant »: the sound over a WebSocket — its frames, its ping, receive only, behind the session. */
class SonDistantPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sondistant-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun station() = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee).also { it.settings.manualLocator = "JN18FS" }

    private class Client(val s: Socket, val statut: String, val tete: String) {
        val e: InputStream = s.getInputStream()
        fun lit(): WebSocketPc.Trame = WebSocketPc.litTrameServeur(e, 1 shl 20)!!
        fun envoie(op: Int, b: ByteArray) { s.getOutputStream().write(WebSocketPc.trameClient(op, b)); s.getOutputStream().flush() }
        fun texte(): JSONObject { while (true) { val t = lit(); if (t.opcode == WebSocketPc.TEXTE) return JSONObject(String(t.donnees)) } }
    }

    private fun ouvre(port: Int, chemin: String, cookie: String? = null, origine: String? = null, upgrade: Boolean = true): Client {
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 5000
        val h = StringBuilder("GET $chemin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
        if (upgrade) h.append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n")
        cookie?.let { h.append("Cookie: $it\r\n") }
        origine?.let { h.append("Origin: $it\r\n") }
        s.getOutputStream().write(h.append("\r\n").toString().toByteArray()); s.getOutputStream().flush()
        val tete = StringBuilder()
        while (!tete.endsWith("\r\n\r\n")) { val b = s.getInputStream().read(); if (b < 0) break; tete.append(b.toChar()) }
        return Client(s, tete.lineSequence().first(), tete.toString())
    }

    /** A tone fed as the dongle would, until [fini]. */
    private fun source(d: DiffusionSon, fini: () -> Boolean) = thread(isDaemon = true) {
        var k = 0L
        while (!fini()) {
            val pcm = ShortArray(2048) { (sin(2 * PI * 700.0 * (k + it) / 44_100) * 12_000).toInt().toShort() }
            k += 2048
            d.ajoute("sdr", pcm, pcm.size, 44_100)
            Thread.sleep(46)
        }
    }

    @Test
    fun le_son_en_trames_de_20_ms_avec_ping() {
        val d = DiffusionSon()
        val s = ServeurPc(station(), 0, diffusion = d); s.demarre()
        var fini = false
        try {
            val c = ouvre(s.portReel, "/api/son/ws?source=sdr&rate=8000&codec=ulaw")
            assertEquals("HTTP/1.1 101 Switching Protocols", c.statut)
            val b = c.texte()
            assertEquals("bonjour", b.getString("t")); assertEquals(8000, b.getInt("rate")); assertFalse(b.getBoolean("amont"))
            assertTrue(d.ecoute("sdr"))
            source(d) { fini }
            var seq = -1L
            repeat(30) {
                val t = c.lit()
                if (t.opcode != WebSocketPc.BINAIRE) return@repeat
                val l = SonDistant.lit(t.donnees)!!
                assertEquals(SonDistant.ULAW, l.codec); assertEquals(8000, l.rate); assertEquals(160, l.pcm.size)
                if (seq >= 0) assertEquals(seq + 1, l.seq)
                seq = l.seq
                assertTrue(Math.abs(System.currentTimeMillis() - l.ms) < 2000)
            }
            assertTrue(seq >= 20)
            // The page's clock sent back with the server's: its round trip.
            c.envoie(WebSocketPc.TEXTE, """{"t":"ping","id":7,"c":123.5}""".toByteArray())
            val p = c.texte()
            assertEquals("pong", p.getString("t")); assertEquals(7, p.getInt("id")); assertEquals(123.5, p.getDouble("c"), 0.0)
            // Another rate and PCM: the frames follow.
            c.envoie(WebSocketPc.TEXTE, """{"t":"config","rate":16000,"codec":"pcm"}""".toByteArray())
            assertEquals(16000, c.texte().getInt("rate"))
            var vu = false
            repeat(40) { val t = c.lit(); if (t.opcode == WebSocketPc.BINAIRE) SonDistant.lit(t.donnees)!!.let { if (it.rate == 16000) { assertEquals(SonDistant.PCM16, it.codec); assertEquals(320, it.pcm.size); vu = true } } }
            assertTrue(vu)
            c.envoie(WebSocketPc.FERMETURE, byteArrayOf(0x03, 0xE8.toByte()))
            var ferme = false
            repeat(80) { if (!ferme) { val t = c.lit(); if (t.opcode == WebSocketPc.FERMETURE) ferme = true } }
            assertTrue(ferme)
            c.s.close()
            // The page gone: nobody listens any more.
            val t0 = System.currentTimeMillis()
            while (d.ecoute("sdr") && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20)
            assertFalse(d.ecoute("sdr"))
        } finally { fini = true; s.arrete() }
    }

    @Test
    fun rien_ne_monte_ni_son_ni_ptt() {
        val d = DiffusionSon()
        val s = ServeurPc(station(), 0, diffusion = d); s.demarre()
        try {
            for (envoi in listOf<(Client) -> Unit>(
                { it.envoie(WebSocketPc.BINAIRE, byteArrayOf(SonDistant.AMONT_SON.toByte(), 0, 0x40, 0x1F) + ByteArray(172)) },
                { it.envoie(WebSocketPc.TEXTE, """{"t":"ptt","on":true}""".toByteArray()) },
                { it.envoie(WebSocketPc.TEXTE, """{"t":"micro"}""".toByteArray()) })) {
                val c = ouvre(s.portReel, "/api/son/ws")
                assertEquals("HTTP/1.1 101 Switching Protocols", c.statut)
                c.texte()
                envoi(c)
                val r = c.texte()
                assertEquals("refus", r.getString("t"))
                var code = -1
                while (code < 0) { val t = c.lit(); if (t.opcode == WebSocketPc.FERMETURE) code = ((t.donnees[0].toInt() and 255) shl 8) or (t.donnees[1].toInt() and 255) }
                assertEquals(1008, code)
                // …and the socket closed by the server.
                assertEquals(-1, c.e.read())
                c.s.close()
            }
        } finally { s.arrete() }
    }

    @Test
    fun une_autre_machine_sans_session_est_refusee() {
        val acces = AccesDistantPc(dossier, reseau = true, iterations = 1000)
        acces.regle("mot-de-passe-du-mat")
        val s = ServeurPc(station(), 0, diffusion = DiffusionSon()); s.acces = acces; s.demarre()
        try {
            s.estLocal = { false }
            val sans = ouvre(s.portReel, "/api/son/ws")
            assertTrue(sans.statut, sans.statut.startsWith("HTTP/1.1 401"))
            assertFalse(sans.tete.contains("Sec-WebSocket-Accept"))
            sans.s.close()
            val faux = ouvre(s.portReel, "/api/son/ws", cookie = AccesDistantPc.COOKIE + "=nimporte")
            assertTrue(faux.statut.startsWith("HTTP/1.1 401")); faux.s.close()
            // Logged in: the cookie opens it.
            val c = URL("http://127.0.0.1:${s.portReel}/connexion").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write("mdp=mot-de-passe-du-mat&suite=%2F".toByteArray()) }
            assertEquals(303, c.responseCode)
            val cookie = c.headerFields["Set-Cookie"]!!.first().substringBefore(';')
            val avec = ouvre(s.portReel, "/api/son/ws", cookie = cookie)
            assertEquals("HTTP/1.1 101 Switching Protocols", avec.statut)
            assertEquals("bonjour", avec.texte().getString("t"))
            avec.s.close()
        } finally { s.arrete() }
    }

    @Test
    fun une_page_d_un_autre_site_ne_peut_ecouter() {
        val s = ServeurPc(station(), 0, diffusion = DiffusionSon()); s.demarre()
        try {
            val ailleurs = ouvre(s.portReel, "/api/son/ws", origine = "https://exemple.invalid")
            assertTrue(ailleurs.statut, ailleurs.statut.startsWith("HTTP/1.1 403")); ailleurs.s.close()
            val ici = ouvre(s.portReel, "/api/son/ws", origine = "http://127.0.0.1:${s.portReel}")
            assertEquals("HTTP/1.1 101 Switching Protocols", ici.statut); ici.s.close()
            val http = ouvre(s.portReel, "/api/son/ws", upgrade = false)
            assertTrue(http.statut.startsWith("HTTP/1.1 400")); http.s.close()
        } finally { s.arrete() }
        assertTrue(SonDistantPc.origineAdmise(null, "x"))
        assertFalse(SonDistantPc.origineAdmise("http://192.168.20.5:8090.evil", "192.168.20.5:8090"))
    }

    @Test
    fun le_marqueur_d_essai_claque_a_chaque_seconde() {
        val d = DiffusionSon()
        // 100 ms at 8 kHz ending 30 ms past a second: the click in the first 20 ms of that second only.
        val m = d.marque(800, 8000, 5_000_030L)
        val debut = 800 - 1 - 30 * 8
        assertEquals(0, m[debut - 2].toInt())
        assertTrue((debut + 1..debut + 20 * 8 - 2).any { m[it] > 10_000 })
        assertTrue((debut + 20 * 8 + 1 until 800).all { m[it].toInt() == 0 })
        assertFalse(d.marqueur)
    }
}
