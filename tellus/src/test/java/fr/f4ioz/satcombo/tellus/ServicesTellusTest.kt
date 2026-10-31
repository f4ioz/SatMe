/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class ServicesTellusTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun propagation_lue_et_au_plus_une_fois_par_heure() {
        var t = 10_000_000L
        var n = 0
        val p = Propagation(File(tmp.root, "p.xml"), telecharge = { n++; Simulation.HAMQSL }, horloge = { t })
        val e = Propagation.lis(Simulation.HAMQSL)
        assertEquals(142, e.flux); assertEquals(8, e.a); assertEquals(2, e.k); assertEquals(118, e.taches)
        assertEquals("Good" to "Good", e.bandes["30m-20m"])
        assertEquals("Fair" to "Good", e.bandes["80m-40m"])
        assertTrue(p.rafraichis())
        assertEquals(1, n)
        assertEquals(142, p.etat()!!.flux)
        // Fresh cache: no fetch.
        t += 30 * 60_000L; p.etat(); Thread.sleep(100)
        assertEquals(1, n)
        t += 31 * 60_000L; p.etat(); Thread.sleep(300)
        assertEquals(2, n)
        // A broken answer is not kept.
        val q = Propagation(File(tmp.root, "q.xml"), telecharge = { "<html>erreur</html>" })
        assertFalse(q.rafraichis())
        assertNull(q.etat())
    }

    @Test fun spot_du_cluster() {
        val s = ClusterDx.lisSpot("DX de EA5XYZ:     14025.0  K1ABC        CW 599 up 1                  1234Z JN11", 5L)!!
        assertEquals("EA5XYZ", s.deQui); assertEquals(14025.0, s.khz, 0.0); assertEquals("K1ABC", s.dx)
        assertEquals("CW 599 up 1", s.commentaire); assertEquals("1234", s.heure)
        assertNull(ClusterDx.lisSpot("WWV de VE7CC <18>:   SFI=142, A=8, K=2", 0))
        assertNotNull(ClusterDx.lisSpot("DX de F5SIM-#:    7074.6  JA1ABC       FT8 -12 dB                1201Z", 0))
    }

    @Test fun cluster_lecture_seule_n_envoie_que_l_indicatif() {
        val serveur = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        val recu = StringBuffer()
        val t = Thread {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("Welcome\r\nPlease enter your call: ".toByteArray()); o.flush()
                val e = c.getInputStream()
                c.soTimeout = 1500
                runCatching { while (true) { val b = e.read(); if (b < 0 || b == '\n'.code) break; recu.append(b.toChar()) } }
                o.write("DX de G4ABC:     7010.5  EA8XYZ       CW 579                         1201Z\r\n".toByteArray())
                o.write("DX de W1AW:      14074.0 VK3DEF       FT8 -18                        1202Z\r\n".toByteArray()); o.flush()
                // Anything more the client would send.
                runCatching { while (true) { val b = e.read(); if (b < 0) break; recu.append(b.toChar()) } }
            }
        }.apply { isDaemon = true; start() }
        val cl = ClusterDx(relanceMs = 60_000L)
        cl.demarre("127.0.0.1", serveur.localPort, "f0abc")
        val fin = System.currentTimeMillis() + 5000
        while (cl.spots().size < 2 && System.currentTimeMillis() < fin) Thread.sleep(20)
        assertEquals(2, cl.spots().size)
        assertEquals("VK3DEF", cl.spots().first().dx)
        assertEquals(1, cl.json(7_000_000L, 7_200_000L).getJSONArray("spots").length())
        Thread.sleep(300)
        cl.arrete(); t.join(3000); serveur.close()
        assertEquals("F0ABC\r", recu.toString())
        assertEquals("F0ABC\r\n", cl.envoye.toString())
    }

    @Test fun carnet_terrestre_en_adif() {
        val c = CarnetTellus(tmp.root)
        assertNull(c.ajoute("", 7_074_000L, "USB", "59", "59", "", "", "JN18EU"))
        c.ajoute("ea8xyz", 7_010_500L, "CW", "579", "559", "IL18", "", "JN18EU", tMs = 1_790_000_000_000L)
        c.ajoute("K1ABC", 14_200_000L, "USB", "59", "57", "FN42", "", "JN18EU", tMs = 1_790_000_600_000L)
        assertEquals(2, c.contacts().size)
        assertTrue(File(tmp.root, "qso_log.json").isFile)
        val a = c.adif("F0ABC")
        assertTrue(a.contains("<CALL:6>EA8XYZ"))
        assertTrue(a.contains("<BAND:3>40m")); assertTrue(a.contains("<BAND_RX:3>40m"))
        assertTrue(a.contains("<FREQ:8>7.010500")); assertTrue(a.contains("<FREQ_RX:8>7.010500"))
        assertTrue(a.contains("<MODE:3>SSB<SUBMODE:3>USB"))
        assertTrue(a.contains("<PROGRAMVERSION:8>TellusMe"))
        assertFalse(a.contains("SAT_NAME")); assertFalse(a.contains("PROP_MODE")); assertFalse(a.contains("ANT_EL"))
        val ctl = c.controle()
        assertEquals(0, ctl.getInt("bloquants"))
    }

    @Test fun plan_de_bande_iaru_r1() {
        assertEquals("cw", PlanHf.famille(PlanHf.segment(14_020_000L)!!.usage))
        assertEquals("num", PlanHf.famille(PlanHf.segment(14_074_000L)!!.usage))
        assertEquals("phonie", PlanHf.famille(PlanHf.segment(14_200_000L)!!.usage))
        assertEquals("balise", PlanHf.famille(PlanHf.segment(14_100_000L)!!.usage))
        assertEquals("cw", PlanHf.famille(PlanHf.segment(10_116_000L)!!.usage))
        assertEquals("40m", PlanHf.bande(7_100_000L)!!.nom)
        assertEquals("2m", PlanHf.bande(144_300_000L)!!.nom)
        assertNull(PlanHf.bande(9_000_000L))
        // Segments of a band never overlap and stay inside it.
        for (b in PlanHf.BANDES.take(11)) {
            val s = PlanHf.SEGMENTS_HF.filter { it.bande == b.nom }.sortedBy { it.basHz }
            assertTrue(b.nom, s.isNotEmpty())
            assertTrue(b.nom, s.first().basHz >= b.basHz && s.last().hautHz <= b.hautHz)
            for (i in 1 until s.size) assertTrue(b.nom, s[i].basHz >= s[i - 1].hautHz)
            assertNotNull(b.nom, PlanHf.segment(b.accordHz))
        }
    }
}
