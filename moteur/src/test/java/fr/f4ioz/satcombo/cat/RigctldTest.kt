/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket

/** The rigctld protocol, server side: answers, extended mode, refusals; then over a real socket. */
class RigctldTest {

    private class Faux(var l: LectureRigctld) : StationRigctld {
        val accords = ArrayList<Long>()
        val modes = ArrayList<Pair<String, Int>>()
        override fun lecture() = l
        override fun accorde(hz: Long): Int { accords += hz; return 0 }
        override fun mode(mode: String, bandeHz: Int): Int { modes += mode to bandeHz; return 0 }
    }

    private val qo100 = LectureRigctld(10_489_750_000L, "USB", 2400, emissionHz = 2_400_250_000L)
    private val serveurs = ArrayList<ServeurRigctld>()
    @After fun fin() { serveurs.forEach { it.arrete() } }

    @Test fun lecturesSimples() {
        val p = RigctldProtocole(Faux(qo100))
        assertEquals("10489750000\n", p.traite("f"))
        assertEquals("10489750000\n", p.traite("\\get_freq\r\n"))
        assertEquals("USB\n2400\n", p.traite("m"))
        assertEquals("VFOA\n", p.traite("v"))
        assertEquals("0\n", p.traite("t"))
        assertEquals("1\nVFOB\n", p.traite("s"))
        assertEquals("2400250000\n", p.traite("i"))
        assertEquals("USB\n2400\n", p.traite("x"))
        assertEquals("0\n", p.traite("j"))
        assertEquals("1\n", p.traite("\\get_powerstat"))
        assertEquals("0\n", p.traite("\\chk_vfo"))
        assertEquals("", p.traite(""))
        assertNull(p.traite("q"))
        assertNull(p.traite("Q"))
    }

    @Test fun sansSplitNiEmission() {
        val f = Faux(LectureRigctld(145_800_000L, "FM", 12000))
        val p = RigctldProtocole(f)
        assertEquals("0\nVFOA\n", p.traite("s"))
        assertEquals("145800000\n", p.traite("i"))
        f.l = f.l.copy(emet = true)
        assertEquals("1\n", p.traite("\\get_ptt"))
    }

    @Test fun modeEtendu() {
        val p = RigctldProtocole(Faux(qo100))
        assertEquals("get_freq:\nFrequency: 10489750000\nRPRT 0\n", p.traite("+f"))
        assertEquals("get_mode:;Mode: USB;Passband: 2400;RPRT 0\n", p.traite(";\\get_mode"))
        assertEquals("get_split_vfo:|Split: 1|TX VFO: VFOB|RPRT 0\n", p.traite("|s"))
        assertEquals("set_ptt: 1\nRPRT -11\n", p.traite("+T 1"))
        assertEquals("set_freq: 145800000\nRPRT -11\n", p.traite("+\\set_freq 145800000"))
    }

    @Test fun emissionToujoursRefusee() {
        val f = Faux(qo100.copy(accordPermis = true))
        val p = RigctldProtocole(f)
        assertEquals("RPRT -11\n", p.traite("T 1"))
        assertEquals("RPRT -11\n", p.traite("T 0"))
        assertEquals("RPRT -11\n", p.traite("\\set_ptt 1"))
        assertEquals("RPRT -11\n", p.traite("I 2400300000"))
        assertEquals("RPRT -11\n", p.traite("X USB 2400"))
        assertEquals("RPRT -11\n", p.traite("\\set_powerstat 0"))
        assertEquals("RPRT -11\n", p.traite("V VFOB"))
        assertEquals("RPRT -11\n", p.traite("F VFOB 145800000"))
        assertEquals("RPRT -11\n", p.traite("S 0 VFOA"))
        assertEquals("RPRT -11\n", p.traite("\\set_level RFPOWER 0.5"))
        // What is already so is accepted, as a no-op.
        assertEquals("RPRT 0\n", p.traite("S 1 VFOB"))
        assertEquals("RPRT 0\n", p.traite("V VFOA"))
        assertEquals("RPRT 0\n", p.traite("J 0"))
        assertTrue(f.accords.isEmpty())
    }

    @Test fun accordRefuseSansLaCase() {
        val f = Faux(qo100)
        val p = RigctldProtocole(f)
        assertEquals("RPRT -11\n", p.traite("F 10489800000"))
        assertEquals("RPRT -11\n", p.traite("M LSB 0"))
        assertTrue(f.accords.isEmpty() && f.modes.isEmpty())
    }

    @Test fun accordPermis() {
        val f = Faux(qo100.copy(accordPermis = true))
        val p = RigctldProtocole(f)
        assertEquals("RPRT 0\n", p.traite("F 10489800000"))
        assertEquals("RPRT 0\n", p.traite("\\set_freq 145800000.000000"))
        assertEquals("RPRT 0\n", p.traite("F VFOA 435000000"))
        assertEquals(listOf(10_489_800_000L, 145_800_000L, 435_000_000L), f.accords)
        assertEquals("RPRT -1\n", p.traite("F abc"))
        assertEquals("RPRT 0\n", p.traite("M PKTUSB -1"))
        assertEquals("RPRT -1\n", p.traite("M XYZ 0"))
        assertEquals(listOf("PKTUSB" to -1), f.modes)
        assertTrue(p.traite("M ?")!!.contains("USB"))
    }

    @Test fun inconnu() {
        val p = RigctldProtocole(Faux(qo100))
        assertEquals("RPRT -4\n", p.traite("\\get_nimporte"))
        assertEquals("RPRT -11\n", p.traite("\\set_nimporte 1"))
    }

    @Test fun etatComplet() {
        val l = RigctldProtocole(Faux(qo100)).traite("\\dump_state")!!.split("\n")
        assertEquals("1", l[0])
        assertEquals("0 0 0 0 0 0 0", l[4])
        assertEquals("0 0 0 0 0 0 0", l[5])
        assertTrue(l[3].startsWith("100000.000000 12000000000.000000 "))
        assertTrue("ptt_type=0x1" in l)
        assertTrue("has_set_freq=0" in l)
        assertEquals("done", l[l.size - 2])
        assertEquals("", l.last())
        val permis = RigctldProtocole(Faux(qo100.copy(accordPermis = true))).traite("\\dump_state")!!
        assertTrue(permis.contains("has_set_freq=1\n"))
        val ext = RigctldProtocole(Faux(qo100)).traite("+\\dump_state")!!
        assertTrue(ext.startsWith("dump_state:\n1\n") && ext.endsWith("done\nRPRT 0\n"))
    }

    @Test fun modes() {
        assertEquals("USB", RigctldProtocole.modeProtocole("USB"))
        assertEquals("LSB", RigctldProtocole.modeProtocole("lsb"))
        assertEquals("FM", RigctldProtocole.modeProtocole("NFM"))
        assertEquals("FM", RigctldProtocole.modeProtocole("FM-N"))
        assertEquals("CW", RigctldProtocole.modeProtocole("CW"))
        assertEquals("CWR", RigctldProtocole.modeProtocole("CW-R"))
        assertEquals("PKTUSB", RigctldProtocole.modeProtocole("DATA-U"))
        assertEquals("PKTLSB", RigctldProtocole.modeProtocole("DATA-L"))
        assertEquals("PKTFM", RigctldProtocole.modeProtocole("DATA-FM"))
        assertEquals("AM", RigctldProtocole.modeProtocole("AM"))
        assertEquals("USB", RigctldProtocole.modeProtocole(null))
        assertEquals("USB", RigctldProtocole.modeProtocole("BPSK"))
    }

    /** A logging program's opening and polling, over a socket: line by line, pipelined. */
    @Test fun parLeReseau() {
        val f = Faux(qo100)
        val s = ServeurRigctld(f).also { serveurs += it }
        s.demarre(InetAddress.getLoopbackAddress(), 0)
        Socket(InetAddress.getLoopbackAddress(), s.portReel).use { c ->
            val e = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.US_ASCII))
            val o = c.getOutputStream()
            fun dit(t: String) { o.write(t.toByteArray()); o.flush() }
            dit("\\chk_vfo\n"); assertEquals("0", e.readLine())
            dit("\\dump_state\n")
            var n = 0
            while (e.readLine() != "done") n++
            assertTrue(n > 20)
            dit("f\nm\nt\n")
            assertEquals("10489750000", e.readLine()); assertEquals("USB", e.readLine()); assertEquals("2400", e.readLine()); assertEquals("0", e.readLine())
            dit("T 1\n"); assertEquals("RPRT -11", e.readLine())
            var k = 0
            while (s.nbClients != 1 && k++ < 100) Thread.sleep(10)
            assertEquals(1, s.nbClients)
            dit("q\n")
            assertNull(e.readLine())
        }
        var k = 0
        while (s.nbClients != 0 && k++ < 100) Thread.sleep(10)
        assertEquals(0, s.nbClients)
        s.arrete()
        assertTrue(!s.ecoute)
    }
}
