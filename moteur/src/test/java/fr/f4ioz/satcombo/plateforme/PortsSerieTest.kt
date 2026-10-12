/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.plateforme

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rig and rotator drivers over the platform's ports, without a phone. */
class PortsSerieTest {

    /** Ports that hand out a given line and remember how they were opened. */
    private class FauxPorts(val ports: List<PortSerie>, val lien: (PortSerie) -> SerialLink?,
                            val refus: String = "") : PortsSerie {
        val ouverts = ArrayList<Pair<PortSerie, Lignes>>()
        /** (port, speed, two stop bits) for each opening. */
        val reglages = ArrayList<Triple<PortSerie, Int, Boolean>>()
        var demandes = 0
        override fun liste() = ports
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) { demandes++ }
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture {
            ouverts += p to lignes
            reglages += Triple(p, baud, deuxArrets)
            return lien(p)?.let { Ouverture(it, "") } ?: Ouverture(null, refus)
        }
    }

    private fun port(i: Int, appareil: Int, port: Int, n: Int, cle: String) =
        PortSerie(i, appareil, port, n, "adaptateur $appareil · $port", cle, 0x10C4, "IC-9700")

    /** An IC-9700 alone: one adapter, two ports. */
    private val ic9700 = listOf(port(0, 0, 0, 2, "IC9700"), port(1, 0, 1, 2, "IC9700"))

    @Test
    fun le_ci_v_ouvre_son_port_lignes_levees() = runBlocking {
        val sim = Ic9700Sim()
        val pp = FauxPorts(ic9700, { sim })
        val cat = CivController(pp).apply { pacingMs = 0 }
        assertTrue(cat.open(1, 115_200))
        assertEquals(1, pp.ouverts.single().first.index)
        assertEquals(Lignes.LEVEES, pp.ouverts.single().second)
        assertTrue(cat.isOpen)
        assertEquals(listOf("adaptateur 0 · 0", "adaptateur 0 · 1"), cat.availableDeviceNames())
    }

    @Test
    fun un_adaptateur_a_deux_ports_se_retrouve_par_sa_cle() = runBlocking {
        val sim = Ic9700Sim()
        val pp = FauxPorts(ic9700, { sim })
        val cat = CivController(pp).apply { pacingMs = 0 }
        // The key remembered is unknown here (plugged elsewhere): the only adapter is taken.
        assertTrue(cat.openParCle("autre", 19_200, port = 1))
        assertEquals(1, pp.ouverts.single().first.port)
        assertEquals(2, cat.nombrePorts("autre"))
    }

    @Test
    fun le_refus_d_ouvrir_arrive_tel_quel_a_l_operateur() = runBlocking {
        val pp = FauxPorts(ic9700, { null }, refus = "Permission refusée")
        val cat = CivController(pp)
        assertFalse(cat.open(0, 115_200))
        assertEquals("Permission refusée", cat.lastError)
    }

    @Test
    fun le_rotor_ouvre_son_port_lignes_levees_et_retient_sa_cle() = runBlocking {
        val sim = Gs232Simulator()
        val pp = FauxPorts(listOf(port(0, 0, 0, 1, "ARDUINO-8523")), { sim })
        val rot = Gs232Rotor(pp).apply { pacingMs = 0; settleMs = 0 }
        assertTrue(rot.open(0, 9600))
        assertEquals(Lignes.LEVEES, pp.ouverts.single().second)
        assertEquals("ARDUINO-8523", rot.cleUsb)
        assertEquals("ARDUINO-8523" to "IC-9700", rot.identitePort(0))
        rot.requestPermissions()
        assertEquals("one request per adapter, not per port", 1, pp.demandes)
    }

    @Test
    fun sans_ports_rien_ne_s_ouvre() = runBlocking {
        assertFalse(CivController().open(0, 115_200))
        assertFalse(Gs232Rotor().open(0, 9600))
    }

    // ---------------------------------------------- the pair's rigs (CAT part 2)

    /** Hands out what [repond] says for each port: a simulated rig, or nothing. */
    private fun adaptateur(cle: String, produit: String, n: Int, appareil: Int = 0, debut: Int = 0) =
        (0 until n).map { PortSerie(debut + it, appareil, it, n, "$produit · $it", cle, 0x0403, produit, "SN$cle", "/dev/bus/usb/001/00$appareil") }

    @Test
    fun le_ft817_ouvre_le_premier_port_de_son_cable_en_8n2_lignes_telles_quelles() = runBlocking {
        val pp = FauxPorts(adaptateur("FTDI1", "FT232R", 1), { fr.f4ioz.satcombo.cat.Ft817Sim() })
        val cat = fr.f4ioz.satcombo.cat.Ft817Cat(pp)
        assertTrue(cat.open("FTDI1", 9_600))
        assertEquals(Lignes.TELLES, pp.ouverts.single().second)
        assertEquals(Triple(pp.ports[0], 9_600, true), pp.reglages.single())
        assertEquals("FTDI1", cat.boundSerial)
        // Two cables and an unknown key: nothing opened (one cable alone is taken whatever its key).
        val deux = FauxPorts(adaptateur("A", "FT232R", 1) + adaptateur("B", "FT232R", 1, appareil = 1, debut = 1),
            { fr.f4ioz.satcombo.cat.Ft817Sim() })
        assertFalse(fr.f4ioz.satcombo.cat.Ft817Cat(deux).open("AUTRE", 9_600))
        assertTrue(fr.f4ioz.satcombo.cat.Ft817Cat(deux).open("B", 9_600))
        assertEquals("B", deux.ouverts.single().first.cle)
    }

    @Test
    fun le_th_d72_ouvre_en_8n1_lignes_levees() = runBlocking {
        val pp = FauxPorts(adaptateur("D72", "TH-D72", 1), { fr.f4ioz.satcombo.cat.Thd72Sim() })
        assertTrue(fr.f4ioz.satcombo.cat.Thd72Lien(pp).open("D72", 9_600))
        assertEquals(Lignes.LEVEES, pp.ouverts.single().second)
        assertEquals(Triple(pp.ports[0], 9_600, false), pp.reglages.single())
    }

    /** The RigExpert's PTT/CW port: sends back what it is sent. */
    private class Echo : SerialLink {
        private val q = java.util.concurrent.LinkedBlockingQueue<Byte>()
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { bytes.forEach { q.put(it) }; return true }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val b = q.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
            buf[0] = b; var n = 1
            while (n < buf.size) { buf[n] = q.poll() ?: break; n++ }
            return n
        }
        override fun close() {}
    }

    @Test
    fun le_ts2000_n_essaie_que_le_premier_port_d_un_rigexpert_lignes_basses() = runBlocking {
        // A RigExpert, silent on its first port at 57600 then answering at 9600.
        val sim = fr.f4ioz.satcombo.cat.Ts2000Sim()
        var essais = 0
        val pp = FauxPorts(adaptateur("RE", "RigExpert TI-5", 2), { p -> essais++; if (p.port == 0 && essais >= 2) sim else null })
        val l = fr.f4ioz.satcombo.cat.Ts2000Lien(pp).apply { pacingMs = 0 }
        assertTrue(l.open("RE", 57_600))
        assertTrue(pp.ouverts.all { it.first.port == 0 && it.second == Lignes.BASSES })
        assertEquals(9_600, l.vitesse)
        assertEquals("RE", l.cle)
        assertEquals(0, sim.tentativesEmission)
    }

    @Test
    fun le_ts2000_quitte_le_port_qui_renvoie_nos_mots_et_passe_au_suivant() = runBlocking {
        val sim = fr.f4ioz.satcombo.cat.Ts2000Sim()
        val pp = FauxPorts(adaptateur("FT2232", "Dual RS232", 2), { p -> if (p.port == 0) Echo() else sim })
        val l = fr.f4ioz.satcombo.cat.Ts2000Lien(pp).apply { pacingMs = 0 }
        assertTrue(l.open("FT2232", 4_800))
        // The echoing port tried once only, then the second port, at 4800 with two stop bits.
        assertEquals(1, pp.reglages.count { it.first.port == 0 })
        assertEquals(Triple(pp.ports[1], 4_800, true), pp.reglages.last())
    }

    @Test
    fun la_paire_liste_un_adaptateur_par_ligne_et_demande_pour_chacun() {
        val ports = adaptateur("A", "IC-9700", 2) + adaptateur("B", "FT232R", 1, appareil = 1, debut = 2)
        val pp = FauxPorts(ports, { null })
        val paire = fr.f4ioz.satcombo.cat.Ft817Pair(pp)
        val l = paire.listDevices()
        assertEquals(listOf("A", "B"), l.map { it.cle })
        assertEquals("SNB", l[1].serial)
        assertEquals("/dev/bus/usb/001/001", l[1].deviceName)
        assertEquals("FT232R", l[1].label)
        paire.requestPermissions()
        assertEquals(2, pp.demandes)
    }
}
