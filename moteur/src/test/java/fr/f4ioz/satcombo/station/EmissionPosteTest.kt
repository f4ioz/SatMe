/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.PuissanceRf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rig's PTT held by SatMe, on the simulated IC-9700: on only while asked, off by every way out. */
class EmissionPosteTest {

    private var t = 1_000_000L
    private val sim = Ic9700Sim()
    private val civ = CivController().apply { pacingMs = 0L; attach(sim) }
    @Volatile private var joignable = true
    @Volatile private var interdit: String? = null

    private val poste = EmissionPoste(object : EmissionPoste.Lien {
        override fun pret() = if (!joignable) "poste non connecté" else interdit
        override fun joignable() = joignable
        override suspend fun ptt(on: Boolean) = civ.setTransmit(on)
    }, { t }).apply { totMs = 180_000L }

    @Test fun rien_n_emet_au_depart() = runBlocking {
        poste.tic()
        assertFalse(poste.etat.emet)
        assertFalse(sim.transmitting)
        assertTrue(sim.commandesPtt.isEmpty())
    }

    @Test fun appui_puis_relache() = runBlocking {
        assertNull(poste.appuie("a"))
        assertTrue(sim.transmitting)
        assertTrue(poste.tenuPar("a"))
        t += 1_500; assertTrue(poste.vie("a")); poste.tic()
        assertTrue(sim.transmitting)
        poste.relache("relâché")
        assertFalse(sim.transmitting)
        assertEquals(listOf(true, false), sim.commandesPtt)
        assertEquals("relâché", poste.etat.raison)
    }

    @Test fun un_seul_client_et_stop_de_n_importe_qui() = runBlocking {
        assertNull(poste.appuie("a"))
        assertNotNull(poste.appuie("b", distant = true))
        assertFalse(poste.vie("b"))
        poste.relache("arrêt par un autre")
        assertFalse(sim.transmitting)
    }

    @Test fun chien_coupe_le_ptt_en_deux_secondes() = runBlocking {
        assertNull(poste.appuie("a", distant = true))
        t += 1_900; poste.tic()
        assertTrue(sim.transmitting)
        t += 200; poste.tic()
        assertFalse(sim.transmitting)
        assertTrue(poste.etat.raison, poste.etat.raison.contains("nouvelles"))
    }

    @Test fun minuterie_coupe_meme_si_le_client_vit() = runBlocking {
        poste.totMs = 10_000L
        assertNull(poste.appuie("a"))
        repeat(9) { t += 1_000; poste.vie("a"); poste.tic() }
        assertTrue(sim.transmitting)
        assertEquals(1L, poste.restantS())
        t += 1_000; poste.vie("a"); poste.tic()
        assertFalse(sim.transmitting)
        assertTrue(poste.etat.raison.contains("minuterie"))
    }

    @Test fun liaison_perdue_puis_retrouvee_le_relachement_est_renvoye() = runBlocking {
        assertNull(poste.appuie("a"))
        joignable = false
        poste.tic()
        assertFalse(poste.etat.emet)
        assertTrue(poste.offEnAttente)
        // The rig still keyed (nothing could reach it); back, the unkey goes at once.
        assertTrue(sim.transmitting)
        joignable = true
        poste.tic()
        assertFalse(sim.transmitting)
        assertFalse(poste.offEnAttente)
    }

    @Test fun emission_retiree_pendant_l_emission_le_relachement_passe_quand_meme() = runBlocking {
        assertNull(poste.appuie("a"))
        interdit = "émission par le réseau non autorisée"
        poste.tic()
        assertFalse(sim.transmitting)
        assertNotNull(poste.appuie("a"))
        assertFalse(sim.transmitting)
    }

    @Test fun poste_non_connecte_rien_n_est_envoye() = runBlocking {
        joignable = false
        assertNotNull(poste.appuie("a"))
        assertTrue(sim.commandesPtt.isEmpty())
    }

    @Test fun accord_pendant_l_emission_en_mode_satellite() = runBlocking {
        // The Doppler loop's pair written while keyed: the simulated rig takes it, nothing refused.
        civ.setSatelliteMode(true)
        assertNull(poste.appuie("a"))
        civ.setSatellitePair(435_800_000L, 145_900_000L)
        assertEquals(435_800_000L, sim.mainHz)
        assertEquals(145_900_000L, sim.subHz)
        assertTrue(sim.pendantEmission.isNotEmpty())
        assertEquals(0, sim.refusals)
        assertTrue(sim.transmitting)
        poste.relache()
    }

    @Test fun accord_direct_pendant_l_emission() = runBlocking {
        val a = AccordDirect()
        assertNull(poste.appuie("a"))
        a.ajoute(100); a.ajoute(100); a.ajoute(-50)
        val n = a.applique({ civ.readFrequency() }, { civ.setFrequency(it) })
        assertEquals(435_000_150L, n)
        assertEquals(435_000_150L, sim.mainHz)
        assertNull(a.applique({ civ.readFrequency() }, { civ.setFrequency(it) }))
        assertTrue(sim.transmitting)
        poste.relache()
    }

    // ------------------------------------------------------------ RF power

    @Test fun puissance_codage() {
        assertArrayEquals(byteArrayOf(0x00, 0x00), PuissanceRf.encode(0))
        assertArrayEquals(byteArrayOf(0x01, 0x28), PuissanceRf.encode(128))
        assertArrayEquals(byteArrayOf(0x02, 0x55), PuissanceRf.encode(255))
        assertArrayEquals(byteArrayOf(0x02, 0x55), PuissanceRf.encode(400))
        assertEquals(128, PuissanceRf.decode(byteArrayOf(0x01, 0x28)))
        assertEquals(255, PuissanceRf.decode(byteArrayOf(0x02, 0x55)))
        assertNull(PuissanceRf.decode(byteArrayOf(0x02, 0x56)))
        assertNull(PuissanceRf.decode(byteArrayOf(0x01, 0x2A)))
        for (v in 0..255) assertEquals(v, PuissanceRf.decode(PuissanceRf.encode(v)))
        assertEquals(50, PuissanceRf.pourcent(128))
        assertEquals(128, PuissanceRf.niveau(50))
    }

    @Test fun puissance_en_watts_par_bande_et_plafond() {
        assertEquals(100.0, PuissanceRf.watts("IC9700", 255, 145_900_000L)!!, 0.01)
        assertEquals(75.0, PuissanceRf.watts("IC9700", 255, 435_000_000L)!!, 0.01)
        assertEquals(10.0, PuissanceRf.watts("IC9700", 255, 1_268_000_000L)!!, 0.01)
        assertEquals(50.2, PuissanceRf.watts("IC9700", 128, 145_900_000L)!!, 0.1)
        assertNull(PuissanceRf.watts("IC9700", 128, 28_000_000L))
        assertEquals(PuissanceRf.niveau(25), PuissanceRf.plafonne(255, 25))
        assertEquals(10, PuissanceRf.plafonne(10, 25))
    }

    @Test fun puissance_lue_et_ecrite_sur_la_bande_qui_emet() = runBlocking {
        assertTrue(civ.setRfPowerTx(64))
        assertEquals(64, sim.puissanceMain)
        assertEquals(64, civ.readRfPowerTx())
        // In satellite mode: SUB, then MAIN selected again.
        civ.setSatelliteMode(true)
        assertTrue(civ.setRfPowerTx(25))
        assertEquals(25, sim.puissanceSub)
        assertEquals(64, sim.puissanceMain)
        assertFalse(sim.onSub)
        assertEquals(25, civ.readRfPowerTx())
        assertEquals(0, sim.refusals)
        assertFalse(sim.transmitting)
    }
}
