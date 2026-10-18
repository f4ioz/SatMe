/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.ReglageDatv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tuning by UDP (who may, how often), the wide band chat (nothing sent but on « Envoyer »). */
class OptionsDatvPcTest {
    private fun stockage() = StockageFichier(File.createTempFile("satme-options", ".json").also { it.delete(); it.deleteOnExit() })

    @Test fun `accord udp coupe d'origine, ce PC seulement, limite`() {
        var t = 0L
        var r = ReglageDatv()
        val demandes = mutableListOf<ReglageDatv>()
        val a = AccordUdpPc(stockage(), reseauPermis = false, actuel = { r }, accorde = { demandes += it; r = it; null }, actif = { true }, horloge = { t })
        assertFalse(a.on); assertFalse(a.reseau)
        assertNotNull(a.regle(null, null, true))
        assertNotNull(a.regle(null, 80, null))
        assertTrue(a.permis(InetAddress.getLoopbackAddress()))
        assertFalse(a.permis(InetAddress.getByName("192.168.1.20")))
        assertTrue(a.recoit("FREQ=10494750;SR=333").startsWith("accordé"))
        t = 1_000L
        assertTrue(a.recoit("FREQ=10497000;SR=1000").startsWith("ignorée"))
        t = 2_500L
        assertEquals("déjà sur ce réglage", a.recoit("FREQ=10494750;SR=333"))
        assertTrue(a.recoit("pas une commande").startsWith("refusée"))
        assertTrue(a.recoit("[GlobalMsg],Freq=10497000,Offset=9750000,Srate=1000").startsWith("accordé"))
        assertEquals(listOf(10_494_750L, 10_497_000L), demandes.map { it.cielKhz })
    }

    @Test fun `accord udp le reseau local seulement avec --reseau et choisi`() {
        val a = AccordUdpPc(stockage(), reseauPermis = true, actuel = { ReglageDatv() }, accorde = { null })
        assertFalse(a.permis(InetAddress.getByName("192.168.1.20")))
        assertNull(a.regle(false, null, true))
        assertTrue(a.permis(InetAddress.getByName("192.168.1.20")))
        assertTrue(a.permis(InetAddress.getByName("10.1.2.3")))
        assertFalse(a.permis(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun `accord udp par un vrai datagramme sur la boucle locale`() {
        val port = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val recus = LinkedBlockingQueue<ReglageDatv>()
        val a = AccordUdpPc(stockage(), false, { ReglageDatv() }, { recus.put(it); null })
        assertNull(a.regle(true, port, null))
        try {
            DatagramSocket().use { s ->
                val b = "FREQ=10494750;SR=333".toByteArray()
                s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), port))
            }
            assertEquals(10_494_750L, recus.poll(3, TimeUnit.SECONDS)?.cielKhz)
        } finally { a.ferme() }
    }

    /** A connection played from a script; what the client sends, kept. */
    private class Faux(lignes: List<String>) {
        val file = LinkedBlockingQueue(lignes)
        val envoyes = mutableListOf<String>()
        @Volatile var ferme = false
        fun connexion() = ChatLargePc.Connexion({ var x: String? = null; while (!ferme && x == null) x = file.poll(50, TimeUnit.MILLISECONDS); x }, { synchronized(envoyes) { envoyes += it } }, { ferme = true })
    }

    @Test fun `salon en lecture seule tant qu'on n'envoie pas`() {
        val f = Faux(listOf("0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":5000}", "40{\"sid\":\"b\"}",
            "42[\"history\",{\"nicks\":[\"G4XAT\"],\"history\":[{\"time\":\"2026-10-17T08:22:04Z\",\"name\":\"G4SAQ\",\"message\":\"Hi\"}]}]",
            "2", "42[\"viewers\",{\"num\":7}]"))
        var t = 0L
        var indicatif = ""
        val c = ChatLargePc(stockage(), { indicatif }, { t }, { f.connexion() })
        assertFalse(c.on)
        c.boucle()
        assertTrue(f.envoyes.isEmpty())
        c.on = true
        c.json(-1)
        val fil = Thread { c.boucle() }.apply { isDaemon = true; start() }
        val debut = System.currentTimeMillis()
        while (c.chat.spectateurs != 7 && System.currentTimeMillis() - debut < 5_000) Thread.sleep(20)
        assertEquals(7, c.chat.spectateurs)
        assertEquals(listOf("Hi"), c.chat.liste().map { it.texte })
        // Only the protocol's packets went: joining, the pong.
        assertEquals(listOf("40", "3"), synchronized(f.envoyes) { f.envoyes.toList() })
        // Without a callsign: read only.
        assertNotNull(c.envoie("bonjour"))
        assertTrue(c.json(-1).getBoolean("lectureSeule"))
        indicatif = "F4IOZ"
        assertNull(c.envoie("bonjour"))
        t = 1_000L
        assertNotNull(c.envoie("encore"))   // within 3 s
        t = 4_000L
        assertNull(c.envoie("encore"))
        assertEquals(listOf("40", "3", "42[\"setnick\",{\"nick\":\"F4IOZ\"}]", "42[\"message\",{\"message\":\"bonjour\"}]",
            "42[\"message\",{\"message\":\"encore\"}]"), synchronized(f.envoyes) { f.envoyes.toList() })
        c.on = false
        fil.join(5_000)
        assertFalse(fil.isAlive)
    }
}
