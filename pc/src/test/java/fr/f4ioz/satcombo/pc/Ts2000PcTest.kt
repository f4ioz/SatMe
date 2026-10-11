/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.DispositionSat
import fr.f4ioz.satcombo.cat.ModePoste
import fr.f4ioz.satcombo.cat.SerialLink
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

class Ts2000PcTest {

    private val port = PortSerie(0, 0, 0, 1, "/dev/ttyUSB0 — RigExpert", "X", 0x0403, "RigExpert")

    /** A PC with one port, whatever is behind it; remembers the lines asked. */
    private class Ports(val lien: SerialLink) : PortsSerie {
        var lignes: Lignes? = null
        override fun liste() = emptyList<PortSerie>()
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes): Ouverture { this.lignes = lignes; return Ouverture(lien, "") }
    }

    /** The RigExpert's second port: sends back what it is sent. */
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
    fun le_ts2000_s_ouvre_lignes_basses_et_se_reconnait() = runBlocking {
        val sim = Ts2000Sim()
        val ports = Ports(sim)
        val l = LienTs2000Pc(ports).apply { pacingMs = 0 }
        assertTrue(l.ouvre(port, 57_600))
        assertEquals(Lignes.BASSES, ports.lignes)
    }

    @Test
    fun le_port_qui_renvoie_nos_mots_est_referme() = runBlocking {
        val echo = Echo()
        val l = LienTs2000Pc(Ports(echo)).apply { pacingMs = 0 }
        assertFalse(l.ouvre(port, 57_600))
        assertTrue(echo.ferme)
        assertFalse(l.isOpen)
        assertTrue(l.lastError.contains("port B"))
    }

    @Test
    fun arme_en_fm_regle_la_montee_puis_rend_la_main_a_la_descente_sans_jamais_emettre() = runBlocking {
        val sim = Ts2000Sim()
        val l = LienTs2000Pc(Ports(sim)).apply { pacingMs = 0 }
        assertTrue(l.ouvre(port, 57_600))
        val p = PosteTs2000Pc(l)
        p.arme(DispositionSat.CROSS_BAND, ModePoste(0x05, "FM"), ModePoste(0x05, "FM"), 670)
        assertEquals(4, sim.mode)
        assertEquals(4, sim.modeMontee)
        assertTrue(sim.tonOn)
        assertFalse("control back on the downlink", sim.controleMontee)
        p.ecrisCouple(436_795_000L, 145_850_000L, memeBande = false)
        assertEquals(436_795_000L, sim.fa)
        assertEquals(145_850_000L, sim.fb)
        assertEquals(436_795_000L, p.lisDescente(false))
        assertEquals(145_850_000L, p.lisMontee())
        assertEquals(0, sim.tentativesEmission)
    }

    @Test
    fun en_emission_la_montee_n_est_ni_lue_ni_ecrite() = runBlocking {
        val sim = Ts2000Sim()
        val l = LienTs2000Pc(Ports(sim)).apply { pacingMs = 0 }
        assertTrue(l.ouvre(port, 57_600))
        val p = PosteTs2000Pc(l)
        sim.emet = true
        val avant = sim.fb
        p.ecrisMontee(145_900_000L, false)
        assertEquals(avant, sim.fb)
        assertEquals(null, p.lisMontee())
    }
}
