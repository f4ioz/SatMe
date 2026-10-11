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
        var demandes = 0
        override fun liste() = ports
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) { demandes++ }
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes): Ouverture {
            ouverts += p to lignes
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
}
