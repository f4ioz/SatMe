/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.GardeLiaisonCat
import fr.f4ioz.satcombo.cat.Ic9700Sim
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

/**
 * A rig that stops answering, then answers again, behind fake lines: the
 * loop stops writing, tries now and then (further and further apart), opens
 * the port again, and takes the rig back by itself — without ever keying it.
 */
class RepriseCatTest {

    /** The rig behind a cable: [muet] the rig switched off (the cable still there), every frame counted. */
    private class Cable(val rig: SerialLink) : SerialLink {
        @Volatile var muet = false
        @Volatile var debranche = false
        val ecrites = ArrayList<ByteArray>()
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            if (debranche) return false
            synchronized(ecrites) { ecrites += bytes }
            if (muet) return true
            return rig.write(bytes, timeoutMs)
        }
        override fun read(buf: ByteArray, timeoutMs: Int): Int = if (muet || debranche) 0 else rig.read(buf, timeoutMs)
        // Closing the port does not switch the rig off.
        override fun close() {}
        fun nombre() = synchronized(ecrites) { ecrites.size }
    }

    /** One port that can vanish (USB unplugged) and come back; each opening counted. */
    private class Ports(val cable: Cable) : PortsSerie {
        val port = PortSerie(0, 0, 0, 1, "/dev/ttyUSB0 — IC-9700", "IC9700-1", 0x10C4, "IC-9700")
        @Volatile var present = true
        val ouvertures = ArrayList<Lignes>()
        override fun liste() = if (present) listOf(port) else emptyList()
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture {
            if (!present) return Ouverture(null, "port introuvable")
            ouvertures += lignes
            return Ouverture(cable, "")
        }
    }

    private fun estPtt(f: ByteArray): Boolean = CatDecode.splitCiv(f).any { it.size >= 7 && (it[4].toInt() and 0xFF) == 0x1C && it[5].toInt() == 0x00 }

    @Test
    fun icom_usb_muet_puis_debranche_puis_revenu() = runBlocking {
        val sim = Ic9700Sim()
        val cable = Cable(sim)
        val ports = Ports(cable)
        val civ = CivController(ports).apply { pacingMs = 0L }
        assertTrue(civ.open(0, 19_200))
        var t = 0L
        val g = GardeLiaisonCat()
        g.ouvert(t)
        var reprises = 0
        // The driver dates its answers with the PC's clock: seen here on the test's.
        var vu = civ.derniereReponseMs
        var repondu = 0L
        val r = RepriseCat(g, { civ.derniereReponseMs.let { if (it != vu) { vu = it; repondu = t } }; repondu },
            sonde = { civ.readFrequency() != null },
            portPresent = { ports.liste().any { it.cle == ports.port.cle } },
            rouvre = { ports.liste().firstOrNull()?.let { civ.ouvrePort(it, 19_200) } ?: false },
            repris = { reprises++ }, horloge = { t })
        // Alive: the rounds run.
        civ.readFrequency()
        assertTrue(r.avantTour())

        // The rig switched off: after the check, the rounds stop.
        cable.muet = true
        val tours = ArrayList<Boolean>()
        var avantMuet = 0
        while (t < 60_000L) { t += 100; val ok = r.avantTour(); if (g.muet) tours += ok else avantMuet++ }
        assertTrue(g.muet)
        // Said silent after the 4 s without an answer and one read.
        assertTrue("rounds before: $avantMuet", avantMuet in 38..42)
        // Ten rounds a second would have been 560 more rounds of frames; silent, only the tries went out.
        assertTrue("rounds run while silent: ${tours.count { it }}", tours.count { it } == 0)
        assertTrue("frames while silent: ${cable.nombre()}", cable.nombre() <= 8)
        assertTrue(r.sondes in 4..6)

        // Unplugged: nothing tried while it is gone.
        cable.debranche = true; ports.present = false
        val avant = r.sondes
        while (t < 120_000L) { t += 100; r.avantTour() }
        assertEquals(avant, r.sondes)
        assertTrue(g.message(t).contains("port USB a disparu"))

        // Plugged back and switched on: taken back within a second, the port opened again.
        cable.debranche = false; cable.muet = false; ports.present = true
        val retour = t
        while (g.muet && t < retour + 5_000L) { t += 100; r.avantTour() }
        assertFalse(g.muet)
        assertTrue("back after ${t - retour} ms", t - retour <= 1_000L)
        assertEquals(1, reprises)
        assertTrue(r.reouvertures >= 1)
        // Every opening as the connection's own (CI-V lines up), on that port only.
        assertTrue(ports.ouvertures.all { it == Lignes.LEVEES })
        // Never keyed, never asked to key.
        assertTrue(sim.commandesPtt.isEmpty())
        assertTrue(synchronized(cable.ecrites) { cable.ecrites.none { estPtt(it) } })
        assertTrue(r.avantTour())
    }

    @Test
    fun ts2000_muet_puis_revenu_sans_rien_qui_emette() = runBlocking {
        val sim = Ts2000Sim()
        val cable = Cable(sim)
        val ports = Ports(cable)
        val l = Ts2000Lien(ports).apply { pacingMs = 0 }
        assertTrue(l.ouvrePort(ports.port, 57_600))
        var t = 0L
        var horlogeLien = l.derniereReponseMs
        val g = GardeLiaisonCat()
        g.ouvert(t)
        var reprises = 0
        val r = RepriseCat(g, { if (l.derniereReponseMs > horlogeLien) { horlogeLien = l.derniereReponseMs; t } else 0L },
            sonde = { l.commande("FA;", 50) != null },
            portPresent = { ports.present },
            rouvre = { l.ouvrePort(ports.port, 57_600) },
            repris = { reprises++ }, horloge = { t })
        cable.muet = true
        while (t < 30_000L) { t += 100; r.avantTour() }
        assertTrue(g.muet)
        val pendant = cable.nombre()
        cable.muet = false
        while (g.muet && t < 70_000L) { t += 100; r.avantTour() }
        assertFalse(g.muet)
        assertEquals(1, reprises)
        assertTrue(pendant < 40)
        assertEquals(0, sim.tentativesEmission)
        assertTrue(ports.ouvertures.all { it == Lignes.BASSES })
    }

    @Test
    fun reseau_aucune_reouverture_ici_une_lecture_des_que_la_session_revient() = runBlocking {
        var t = 0L
        var session = true
        var repond = true
        var lectures = 0
        val g = GardeLiaisonCat()
        g.ouvert(0)
        var reprises = 0
        val r = RepriseCat(g, { 0L }, sonde = { lectures++; repond }, portPresent = { session }, rouvre = null,
            repris = { reprises++ }, horloge = { t })
        // The session drops: waiting (the session reconnects by itself), nothing sent.
        session = false; repond = false
        while (t < 20_000L) { t += 100; r.avantTour() }
        assertTrue(g.muet)
        assertEquals(0, lectures)
        // Back: one read at once, alive.
        session = true; repond = true
        t += 100; r.avantTour()
        assertFalse(g.muet)
        assertEquals(1, lectures)
        assertEquals(1, reprises)
        assertEquals(0, r.reouvertures)
    }
}
