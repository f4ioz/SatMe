/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The wide band spectrum: connected only when wanted, the frames read, a click tuning, the « veille », the WebSocket client. */
class SpectreLargePcTest {

    private var t = 1_000_000L
    private val stockage = StockageMemoire()
    private val datv = DatvPc(stockage, File(System.getProperty("java.io.tmpdir")), horloge = { t })
    private var ouvertures = 0
    private val demandes = ArrayList<ReglageDatv>()
    private var refus: String? = null

    /** A frame: the noise, the beacon, a 500 kS/s and a 2 MS/s station. */
    private fun trame(): ByteArray {
        val n = 922
        val v = IntArray(n) { 11_000 + (it * 7919 % 400) }
        fun signal(c: Long, sr: Int, h: Int) {
            for (i in 0 until n) if (Math.abs(BandeLarge.khz(i.toDouble(), n) - c) < sr * 0.45) v[i] = 11_000 + h
        }
        signal(Datv.BALISE_KHZ, 1500, 27_000); signal(10_493_750L, 500, 22_000); signal(10_496_250L, 2000, 26_000)
        return ByteArray(2 * n) { i -> (if (i % 2 == 0) v[i / 2] and 0xFF else v[i / 2] shr 8).toByte() }
    }

    private fun spectre(trames: Int = 3) = SpectreLargePc(stockage, datv, { t }, {
        ouvertures++
        var reste = trames
        SpectreLargePc.Source({ if (reste-- > 0) trame() else null }, {})
    }, { demandes += it; refus })

    @Test fun `rien sans l'option ni l'onglet`() {
        val s = spectre()
        s.json(-1)
        s.boucle()
        assertEquals(0, ouvertures)
        assertFalse(s.json(-1).has("valeurs"))
        // On, but the tab not seen for 10 s.
        s.action("/api/datv/large/reglage", mapOf("on" to "1"))
        t += 11_000
        s.boucle()
        assertEquals(0, ouvertures)
    }

    @Test fun `trames lues, signaux trouves`() {
        val s = spectre()
        s.action("/api/datv/large/reglage", mapOf("on" to "1"))
        s.json(-1)
        s.boucle()
        assertEquals(1, ouvertures)
        assertEquals(3L, s.numero)
        val j = s.json(-1)
        assertEquals(922, j.getJSONArray("valeurs").length())
        val l = j.getJSONArray("signaux")
        assertEquals(3, l.length())
        assertTrue(l.getJSONObject(0).getBoolean("balise"))
        assertEquals(500, l.getJSONObject(1).getInt("sr"))
        assertEquals(2000, l.getJSONObject(2).getInt("sr"))
        // Same frame already drawn: not sent again.
        assertFalse(s.json(s.numero).has("valeurs"))
        // The connection fell: tried again later, not at once.
        s.boucle()
        assertEquals(1, ouvertures)
        t += 2_500
        s.json(-1); s.boucle()
        assertEquals(2, ouvertures)
    }

    @Test fun `rien pour le site sans l'option, sans trames fraiches ou sans recepteur`() {
        val s = spectre()
        assertNull(s.pourSite())
        s.action("/api/datv/large/reglage", mapOf("on" to "1"))
        s.json(-1); s.boucle()
        assertTrue(s.frais())
        // The DATV receiver stopped: the live is not on QO-100's DATV, nothing for the site.
        assertNull(s.pourSite())
    }

    @Test fun `le signal selectionne, la balise a part`() {
        fun sg(c: Long, sr: Int, balise: Boolean = false) = fr.f4ioz.satcombo.datv.SignalLarge(c - sr / 2, c + sr / 2, c, sr.toLong(), sr, 30_000, 0.0, balise, false)
        val l = listOf(sg(Datv.BALISE_KHZ, 1500, true), sg(10_494_750L, 333), sg(10_495_250L, 333))
        assertEquals(10_494_750L, SpectreLargePc.selectionnee(l, 10_494_760L)!!.centreKhz)
        assertEquals(10_495_250L, SpectreLargePc.selectionnee(l, 10_495_200L)!!.centreKhz)
        // Between two signals, on the beacon: none.
        assertNull(SpectreLargePc.selectionnee(l, 10_495_000L))
        assertNull(SpectreLargePc.selectionnee(l, Datv.BALISE_KHZ))
        // Nothing selected without a fresh spectrum.
        assertNull(spectre().selection()); assertEquals("", spectre().indicatifSelection())
    }

    @Test fun `un clic accorde le recepteur, OL garde`() {
        val s = spectre()
        val avant = datv.reglage
        assertEquals(true, s.action("/api/datv/large/accorde", mapOf("khz" to "10493750", "sr" to "500")).getBoolean("ok"))
        assertEquals(10_493_750L, demandes.last().cielKhz)
        assertEquals(500, demandes.last().srKs)
        assertEquals(avant.olKhz, demandes.last().olKhz)
        assertEquals(avant.entreeB, demandes.last().entreeB)
        // No MiniTiouner: said, nothing more.
        refus = "LongMynd introuvable"
        val r = s.action("/api/datv/large/accorde", mapOf("khz" to "10496250", "sr" to "2000"))
        assertFalse(r.getBoolean("ok"))
        assertEquals("LongMynd introuvable", r.getString("erreur"))
        assertFalse(s.action("/api/datv/large/accorde", mapOf("khz" to "10400000", "sr" to "500")).getBoolean("ok"))
    }

    @Test fun `veille sur les signaux, arretee a la main`() {
        val s = spectre(trames = 4)
        assertFalse(s.action("/api/datv/large/veille", mapOf("on" to "1")).getBoolean("ok"))
        s.action("/api/datv/large/reglage", mapOf("on" to "1", "attente" to "8"))
        s.json(-1); s.boucle()
        assertTrue(s.action("/api/datv/large/veille", mapOf("on" to "1")).getBoolean("ok"))
        s.tic()
        assertEquals(1, demandes.size)
        assertEquals(500, demandes[0].srKs)
        t += 8_000; s.trame(trame()); s.tic()
        assertEquals(2000, demandes.last().srKs)
        // Tuned by hand: the « veille » stops.
        s.action("/api/datv/large/accorde", mapOf("khz" to "10491500", "sr" to "1500"))
        assertFalse(s.veille.active)
        // Its failure stops it too.
        s.action("/api/datv/large/veille", mapOf("on" to "1"))
        refus = "LongMynd introuvable"
        s.tic()
        assertFalse(s.veille.active)
        assertTrue(s.veille.etat.contains("LongMynd introuvable"))
    }

    @Test fun `le client WebSocket ecoute, repond aux pings, ferme poliment`() {
        val serveur = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var demande: WebSocketPc.Demande? = null
        var pong: WebSocketPc.Trame? = null
        var fin: WebSocketPc.Trame? = null
        val th = thread {
            serveur.accept().use { c ->
                val e = c.getInputStream(); val o = c.getOutputStream()
                demande = WebSocketPc.litDemande(e)
                o.write(WebSocketPc.reponse(demande!!.cle!!))
                o.write(WebSocketPc.trame(WebSocketPc.BINAIRE, byteArrayOf(1, 2, 3)))
                o.write(WebSocketPc.trame(WebSocketPc.PING, byteArrayOf(9)))
                o.write(WebSocketPc.trame(WebSocketPc.BINAIRE, ByteArray(70_000) { 5 }))
                o.flush()
                pong = WebSocketPc.litTrame(e)
                o.write(WebSocketPc.fermeture()); o.flush()
                fin = WebSocketPc.litTrame(e)
            }
        }
        val c = ClientWebSocketPc.ouvre("127.0.0.1", serveur.localPort, "/wb/fft", "fft", "SatMe-PC", tls = false)
        assertArrayEquals(byteArrayOf(1, 2, 3), c.recoit())
        assertEquals(70_000, c.recoit()!!.size)
        assertNull(c.recoit())
        th.join(5000)
        serveur.close()
        assertTrue(demande!!.valide)
        assertEquals("/wb/fft", demande!!.chemin)
        assertEquals("fft", demande!!.entetes["sec-websocket-protocol"])
        assertEquals("SatMe-PC", demande!!.entetes["user-agent"])
        assertEquals(WebSocketPc.PONG, pong!!.opcode)
        assertArrayEquals(byteArrayOf(9), pong!!.donnees)
        assertEquals(WebSocketPc.FERMETURE, fin!!.opcode)
    }

    @Test fun `client refuse sans la bonne cle`() {
        val serveur = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val th = thread {
            serveur.accept().use { c ->
                WebSocketPc.litDemande(c.getInputStream())
                c.getOutputStream().write(WebSocketPc.reponse("autre")); c.getOutputStream().flush()
                Thread.sleep(200)
            }
        }
        val r = runCatching { ClientWebSocketPc.ouvre("127.0.0.1", serveur.localPort, "/", "fft", "SatMe-PC", tls = false) }
        th.join(5000); serveur.close()
        assertTrue(r.isFailure)
    }
}
