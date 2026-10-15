/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class WebSocketPcTest {

    @Test fun `la cle d'acceptation de la RFC`() {
        // RFC 6455, section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketPc.cleAcceptation("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun `trames du serveur, toutes longueurs`() {
        // RFC 6455, section 5.7: "Hello", one unmasked text frame.
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f), WebSocketPc.texte("Hello"))
        val moyen = WebSocketPc.trame(WebSocketPc.BINAIRE, ByteArray(256))
        assertEquals(126, moyen[1].toInt())
        assertEquals(1, moyen[2].toInt()); assertEquals(0, moyen[3].toInt())
        assertEquals(4 + 256, moyen.size)
        val grand = WebSocketPc.trame(WebSocketPc.BINAIRE, ByteArray(65536))
        assertEquals(127, grand[1].toInt())
        assertEquals(1, grand[7].toInt())
        assertEquals(10 + 65536, grand.size)
        assertArrayEquals(byteArrayOf(0x88.toByte(), 2, 0x03, 0xE8.toByte()), WebSocketPc.fermeture())
    }

    @Test fun `trames du client, demasquees`() {
        // RFC 6455, section 5.7: "Hello", masked.
        val b = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58)
        val t = WebSocketPc.litTrame(ByteArrayInputStream(b))!!
        assertTrue(t.fin); assertEquals(WebSocketPc.TEXTE, t.opcode); assertEquals("Hello", String(t.donnees))
        // 300 octets: the 16-bit length.
        val long = ByteArray(300) { it.toByte() }
        val l = WebSocketPc.litTrame(ByteArrayInputStream(WebSocketPc.trameClient(WebSocketPc.BINAIRE, long)))!!
        assertArrayEquals(long, l.donnees)
        // Unmasked from a client, or too large: refused.
        assertNull(WebSocketPc.litTrame(ByteArrayInputStream(WebSocketPc.texte("Hello"))))
        assertNull(WebSocketPc.litTrame(ByteArrayInputStream(WebSocketPc.trameClient(WebSocketPc.BINAIRE, ByteArray(WebSocketPc.MAX_CLIENT + 1)))))
        // Cut short.
        assertNull(WebSocketPc.litTrame(ByteArrayInputStream(b.copyOf(6))))
    }

    @Test fun `la demande d'ouverture`() {
        val ok = "GET /chat HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        val d = WebSocketPc.litDemande(ByteArrayInputStream(ok.toByteArray()))!!
        assertTrue(d.valide)
        assertEquals("/chat", d.chemin)
        assertTrue(String(WebSocketPc.reponse(d.cle!!)).contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"))
        val http = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
        assertFalse(WebSocketPc.litDemande(ByteArrayInputStream(http.toByteArray()))!!.valide)
        assertNull(WebSocketPc.litDemande(ByteArrayInputStream("GET / HTTP/1.1\r\nHost".toByteArray())))
    }

    // ------------------------------------------------------------ the link, end to end

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-lien-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun libre(): Int = ServerSocket(0).use { it.localPort }

    @Test fun `le lien, de bout en bout, sur ce PC seulement`() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val lien = LienSatellitePc(st, reseau = false)
        assertFalse("off by default", lien.actif)
        lien.demarreSiActif()
        assertFalse(lien.ecoute)
        val port = libre()
        lien.regle(true, port)
        try {
            assertTrue(lien.ecoute)
            val s = Socket(InetAddress.getLoopbackAddress(), port)
            s.soTimeout = 5000
            val o = s.getOutputStream(); val i = s.getInputStream()
            o.write(("GET / HTTP/1.1\r\nHost: localhost:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            val tete = StringBuilder()
            while (!tete.endsWith("\r\n\r\n")) tete.append(i.read().toChar())
            assertTrue(tete.startsWith("HTTP/1.1 101"))
            assertTrue(tete.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
            fun lit(): WebSocketPc.Trame {
                // Server frames are unmasked: read them by hand.
                val b0 = i.read(); var n = i.read()
                if (n == 126) n = (i.read() shl 8) or i.read()
                val d = ByteArray(n); var k = 0
                while (k < n) k += i.read(d, k, n - k)
                return WebSocketPc.Trame(b0 and 0x80 != 0, b0 and 0x0F, d)
            }
            assertEquals("hello", JSONObject(String(lit().donnees)).getString("type"))
            // Ping, pong with the same payload.
            o.write(WebSocketPc.trameClient(WebSocketPc.PING, "abc".toByteArray()))
            val pong = lit()
            assertEquals(WebSocketPc.PONG, pong.opcode); assertEquals("abc", String(pong.donnees))
            // A contact logged.
            val t0 = System.currentTimeMillis()
            while (lien.nbClients == 0 && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20)
            lien.qso(LogEntry(timeMs = 1792089000000L, satName = "SO-50", catnum = 27607, azimuthDeg = 120.0, elevationDeg = 30.0,
                callsign = "F4XYZ", theirLocator = "JN18"))
            val q = JSONObject(String(lit().donnees))
            assertEquals("qsoLogged", q.getString("type")); assertEquals("F4XYZ", q.getString("call")); assertEquals("SO-50", q.getString("satellite"))
            // Close, echoed.
            o.write(WebSocketPc.trameClient(WebSocketPc.FERMETURE, byteArrayOf(0x03, 0xE8.toByte())))
            val c = lit()
            assertEquals(WebSocketPc.FERMETURE, c.opcode); assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), c.donnees)
            s.close()
            // Not a WebSocket: refused.
            Socket(InetAddress.getLoopbackAddress(), port).use { h ->
                h.soTimeout = 5000
                h.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertTrue(String(h.getInputStream().readBytes()).startsWith("HTTP/1.1 400"))
            }
            // Kept on, and its port.
            assertTrue(LienSatellitePc(st, false).actif)
            assertEquals(port, LienSatellitePc(st, false).port)
        } finally { lien.regle(false, null) }
        assertFalse(lien.ecoute)
    }

    @Test fun `les alertes de passage, une fois chacune`() {
        var maintenant = 1792089000000L
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { maintenant })
        val lien = LienSatellitePc(st, reseau = false) { maintenant }
        val aos = maintenant + 4 * 60_000L
        val p = SatPass("ISS", 25544, aos, aos + 600_000L, 45.0, 200.0, 80.0, false, false)
        val l = listOf(p)
        val a = lien.alertes(maintenant, l)
        assertEquals(1, a.size); assertEquals("aosSoon", a[0].getString("kind")); assertEquals(240, a[0].getLong("inS"))
        assertTrue(lien.alertes(maintenant + 1000, l).isEmpty())
        maintenant = aos + 2_000L
        assertEquals("aos", lien.alertes(maintenant, l).single().getString("kind"))
        maintenant = aos + 300_000L
        assertTrue(lien.alertes(maintenant, l).isEmpty())
        maintenant = aos + 600_500L
        assertEquals("los", lien.alertes(maintenant, l).single().getString("kind"))
        // Long past: nothing.
        assertTrue(lien.alertes(aos + 3_600_000L, listOf(p.copy(aosEpochMs = aos + 1, losEpochMs = aos + 600_001L))).isEmpty())
        // The passes watched: none without a satellite followed or a favourite.
        assertTrue(lien.surveilles().isEmpty())
        assertNotNull(lien)
    }
}
