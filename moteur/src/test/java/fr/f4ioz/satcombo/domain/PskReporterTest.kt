/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PskReporterTest {

    private fun hex(s: String): ByteArray {
        val c = s.replace(" ", "")
        return ByteArray(c.length / 2) { c.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private val recepteur = PskReporterIpfix.Recepteur("F4IOZ", "JN18EU", "SatMe-PC 1.0", "QO-100")
    private val spot = PskReporterIpfix.Spot("K1ABC", 10_489_541_000L, -12, "FT8", "FN42", 1_760_000_000L)

    private val gabaritRecepteur = "0003 002C 9992 0004 0000 " +
        "8002 FFFF 0000768F 8004 FFFF 0000768F 8008 FFFF 0000768F 8009 FFFF 0000768F 0000"
    private val gabaritEmetteur = "0002 003C 9993 0007 " +
        "8001 FFFF 0000768F 8005 0005 0000768F 8006 0001 0000768F 800A FFFF 0000768F " +
        "800B 0001 0000768F 0096 0004 8003 FFFF 0000768F"
    private val donneesRecepteur = "9992 0028 " +
        "05 4634494F5A 06 4A4E31384555 0C 5361744D652D504320312E30 06 514F2D313030 000000"
    private val donneesSpot = "9993 0020 " +
        "05 4B31414243 027139B188 F4 03 465438 01 68E77800 04 464E3432 0000"

    @Test fun `les gabarits, octet pour octet`() {
        assertArrayEquals(hex(gabaritRecepteur), PskReporterIpfix.gabaritRecepteur())
        assertArrayEquals(hex(gabaritEmetteur), PskReporterIpfix.gabaritEmetteur())
    }

    @Test fun `le recepteur et un emetteur, octet pour octet`() {
        assertArrayEquals(hex(donneesRecepteur), PskReporterIpfix.donneesRecepteur(recepteur))
        assertArrayEquals(hex(donneesSpot), PskReporterIpfix.donneesSpots(listOf(spot)))
    }

    @Test fun `le paquet entier, avec et sans gabarits`() {
        val avec = PskReporterIpfix.paquet(recepteur, listOf(spot), 0x68E77810L, 7L, 0x01020304, avecGabarits = true)
        assertArrayEquals(hex("000A 00C0 68E77810 00000007 01020304 " + gabaritRecepteur + gabaritEmetteur + donneesRecepteur + donneesSpot), avec)
        val sans = PskReporterIpfix.paquet(recepteur, listOf(spot), 0x68E77810L, 7L, 0x01020304, avecGabarits = false)
        assertArrayEquals(hex("000A 0058 68E77810 00000007 01020304 " + donneesRecepteur + donneesSpot), sans)
    }

    @Test fun `beaucoup de stations, plusieurs datagrammes sous la taille`() {
        val l = (0 until 200).map { spot.copy(indicatif = "K" + (it % 10) + "A" + ('A' + it / 26 % 26) + ('A' + it % 26)) }
        val morceaux = PskReporterIpfix.decoupe(recepteur, l)
        assertTrue(morceaux.size > 1)
        assertEquals(200, morceaux.sumOf { it.size })
        for (m in morceaux) assertTrue(PskReporterIpfix.paquet(recepteur, m, 0, 0, 0, true).size <= PskReporterIpfix.TAILLE_MAX)
    }

    @Test fun `ce qui est un indicatif, et ce qui n'en est pas`() {
        for (s in listOf("F4IOZ", "K1ABC", "2E0ABC", "3D2AG", "4X1AB", "VK2ABC", "E73X", "F4IOZ/P", "EA8/F4IOZ", "1A0KM"))
            assertTrue(s, PskReporter.estIndicatif(s))
        for (s in listOf("<...>", "<F4IOZ>", "CQ", "DX", "POTA", "RR73", "73", "JN18", "QRZ", "", "F4IOZ/P/QRP/X", "12345"))
            assertFalse(s, PskReporter.estIndicatif(s))
    }

    @Test fun `la frequence, QO-100 la descente plus l'audio, un satellite le cadran, sinon rien`() {
        assertNull(PskReporter.frequence(false, 145_900_000L, false, 0L, true, 1500))
        assertEquals(10_489_541_000L, PskReporter.frequence(true, 739_541_000L, true, 10_489_540_000L, false, 1000))
        assertEquals(10_489_552_000L, PskReporter.frequence(true, 10_489_550_000L, true, 10_489_540_000L, false, 2000))
        assertEquals(435_600_000L, PskReporter.frequence(true, 435_600_000L, false, 0L, true, 1500))
        assertEquals(14_075_500L, PskReporter.frequence(true, 14_074_000L, false, 0L, false, 1500))
        assertNull(PskReporter.frequence(true, null, false, 0L, true, 1500))
        assertEquals(10_489_541_000L, PskReporter.frequenceCle(true, 10_489_540_000L, 1000))
        assertNull(PskReporter.frequenceCle(false, 10_489_540_000L, 1000))
    }

    @Test fun `un lot toutes les cinq minutes, une station par mode, filtre`() {
        var t = 1_760_000_000_000L
        val envoyes = ArrayList<ByteArray>()
        val p = PskReporter({ envoyes += it }, { t }, 42)
        assertTrue(p.ajoute("K1ABC", null, "FT8", -10, 10_489_541_000L, t, "QO-100", "F4IOZ"))
        assertTrue(p.ajoute("K1ABC", "FN42", "FT8", -5, 10_489_542_000L, t + 15_000, "QO-100", "F4IOZ"))
        assertTrue(p.ajoute("K1ABC", null, "FT4", -5, 10_489_542_000L, t + 15_000, "QO-100", "F4IOZ"))
        assertFalse(p.ajoute("<...>", null, "FT8", 0, 10_489_541_000L, t, "QO-100", "F4IOZ"))
        assertFalse(p.ajoute("F4IOZ", null, "FT8", 0, 10_489_541_000L, t, "QO-100", "F4IOZ/P"))
        assertFalse(p.ajoute("POTA", null, "FT8", 0, 10_489_541_000L, t, "QO-100", "F4IOZ"))
        assertFalse(p.ajoute("DL1XYZ", null, "FT8", 0, null, t, "QO-100", "F4IOZ"))
        assertEquals(2, p.enAttente)

        t += 4 * 60_000L
        p.tic(true, "F4IOZ", "JN18EU", "SatMe-PC 1.0")
        assertTrue(envoyes.isEmpty())
        t += 60_000L
        p.tic(true, "F4IOZ", "JN18EU", "SatMe-PC 1.0")
        assertEquals(1, envoyes.size)
        assertEquals(0, p.enAttente)
        assertEquals(2, p.dernierNombre)
        val b = envoyes[0]
        // Header, then the templates (first datagram), the receiver, the two senders.
        assertEquals(10, (b[0].toInt() shl 8) or b[1].toInt())
        assertEquals(b.size, ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        val texte = String(b, Charsets.ISO_8859_1)
        assertEquals(2, Regex("K1ABC").findAll(texte).count())
        assertTrue(texte.contains("FN42"))
        assertTrue(texte.contains("QO-100"))
    }

    @Test fun `sans indicatif ou coupe, rien ne part`() {
        var t = 0L
        val envoyes = ArrayList<ByteArray>()
        val p = PskReporter({ envoyes += it }, { t }, 1)
        p.ajoute("K1ABC", null, "FT8", 0, 435_600_000L, t, "RS-44", "")
        t += PskReporter.LOT_MS
        p.tic(true, "", "JN18EU", "SatMe 20.83")
        assertTrue(envoyes.isEmpty())
        assertTrue(p.etat.contains("Indicatif"))
        p.ajoute("K1ABC", null, "FT8", 0, 435_600_000L, t, "RS-44", "")
        t += PskReporter.LOT_MS
        p.tic(false, "F4IOZ", "JN18EU", "SatMe 20.83")
        assertTrue(envoyes.isEmpty())
        assertEquals(0, p.enAttente)
    }

    @Test fun `l'envoi UDP, sur une prise locale`() {
        java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { s ->
            s.soTimeout = 5_000
            val e = EnvoiUdp("127.0.0.1", s.localPort)
            val p = PskReporterIpfix.paquet(recepteur, listOf(spot), 1L, 0L, 5, true)
            e(p)
            val r = java.net.DatagramPacket(ByteArray(2048), 2048)
            s.receive(r)
            assertArrayEquals(p, r.data.copyOf(r.length))
        }
    }
}
