/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.Ts2000Lien
import fr.f4ioz.satcombo.cat.Ts2000Sim
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** On the PC the TS-2000 opens on the port chosen only, at the speed chosen only (the phone's driver). */
class Ts2000PcTest {

    private val porta = PortSerie(0, 0, 0, 2, "/dev/ttyUSB0 — Dual RS232", "X", 0x0403, "Dual RS232")
    private val portb = PortSerie(1, 0, 1, 2, "/dev/ttyUSB1 — Dual RS232", "X", 0x0403, "Dual RS232")

    private class Ports(val lien: (PortSerie) -> SerialLink) : PortsSerie {
        val ouverts = ArrayList<Pair<PortSerie, Lignes>>()
        override fun liste() = emptyList<PortSerie>()
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture {
            ouverts += p to lignes; return Ouverture(lien(p), "")
        }
    }

    /** The RigExpert's PTT port: sends back what it is sent. */
    private class Echo : SerialLink {
        private val q = java.util.concurrent.LinkedBlockingQueue<Byte>()
        var ferme = false
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { bytes.forEach { q.put(it) }; return true }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val b = q.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
            buf[0] = b; var n = 1
            while (n < buf.size) { buf[n] = q.poll() ?: break; n++ }
            return n
        }
        override fun close() { ferme = true }
    }

    @Test
    fun le_port_choisi_seul_lignes_basses() = runBlocking {
        val sim = Ts2000Sim()
        val pp = Ports { sim }
        val l = Ts2000Lien(pp).apply { pacingMs = 0 }
        assertTrue(l.ouvrePort(porta, 57_600))
        assertEquals(listOf(porta to Lignes.BASSES), pp.ouverts)
        assertEquals(57_600, l.vitesse)
        assertEquals(0, sim.tentativesEmission)
    }

    @Test
    fun le_port_qui_renvoie_nos_mots_est_referme_et_rien_d_autre_n_est_essaye() = runBlocking {
        val echo = Echo()
        val pp = Ports { echo }
        val l = Ts2000Lien(pp).apply { pacingMs = 0 }
        assertFalse(l.ouvrePort(portb, 57_600))
        assertTrue(echo.ferme)
        assertFalse(l.isOpen)
        assertEquals(1, pp.ouverts.size)
    }
}
