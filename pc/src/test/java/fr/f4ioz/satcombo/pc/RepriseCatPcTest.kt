/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SatMe-PC's CAT on a fake IC-9700 cable (no port of this PC opened): the rig
 * switched off is said silent and left alone, switched on again it is taken
 * back by itself — and never keyed on the way.
 */
class RepriseCatPcTest {

    private class Cable(val rig: SerialLink) : SerialLink {
        @Volatile var muet = false
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean = if (muet) true else rig.write(bytes, timeoutMs)
        override fun read(buf: ByteArray, timeoutMs: Int): Int = if (muet) 0 else rig.read(buf, timeoutMs)
        override fun close() {}
    }

    private class Ports(val cable: Cable) : PortsSerie {
        val port = PortSerie(0, 0, 0, 1, "/dev/ttyFAUX0 — IC-9700", "FAUX-IC9700", 0x10C4, "IC-9700")
        /** The lines asked at each opening, in order. */
        val ouvertures = java.util.concurrent.CopyOnWriteArrayList<Lignes>()
        override fun liste() = listOf(port)
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture { ouvertures += lignes; return Ouverture(cable, "") }
    }

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-reprise-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun attends(maxMs: Long, quoi: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < fin) { if (quoi()) return true; Thread.sleep(50) }
        return quoi()
    }

    @Test
    fun poste_eteint_puis_rallume() {
        val sim = Ic9700Sim()
        val cable = Cable(sim)
        val ports = Ports(cable)
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), ports, portee)
        st.connecteCat(StationPc.ChoixCat("IC9700", 0, 19_200))
        assertTrue(attends(5_000) { st.etat.value.catConnecte })
        assertTrue(st.etat.value.catStatut, st.etat.value.catStatut.contains("IC-9700"))

        cable.muet = true
        assertTrue("never said silent", attends(12_000) { st.etat.value.catMuet })
        assertTrue(st.etat.value.catLiaison, st.etat.value.catLiaison.startsWith("IC-9700 muet depuis"))
        // Still « connected »: the operator's choice stands, only the writing stopped.
        assertTrue(st.etat.value.catConnecte)
        // The manual connection unchanged (CI-V lines up); then at least one automatic reopening, lines low.
        assertTrue("no automatic reopening", attends(15_000) { ports.ouvertures.size >= 2 })
        assertTrue(ports.ouvertures.toString(), ports.ouvertures.first() == Lignes.LEVEES)
        assertTrue(ports.ouvertures.toString(), ports.ouvertures.drop(1).all { it == Lignes.BASSES })
        assertTrue(st.etat.value.catLiaison, st.etat.value.catLiaison.contains("sans lignes RTS/DTR"))

        cable.muet = false
        assertTrue("never taken back", attends(12_000) { !st.etat.value.catMuet })
        assertFalse(st.etat.value.catLiaison.contains("muet"))
        assertTrue(sim.commandesPtt.isEmpty())
        assertFalse(sim.transmitting)
        assertTrue(ports.ouvertures.drop(1).all { it == Lignes.BASSES })
    }
}
