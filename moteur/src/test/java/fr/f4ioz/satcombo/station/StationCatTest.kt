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
import fr.f4ioz.satcombo.cat.PosteCat
import fr.f4ioz.satcombo.cat.PosteCatCiv
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.Doppler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CAT Doppler loop against a simulated IC-9700, on a computer. Until the
 * move to the engine it lived in the phone's ViewModel, between serial reads
 * and screen state, and only its small pieces could be tested.
 */
class StationCatTest {

    /** SO-50 like: FM, V up, U down, 67.0 Hz in the description. */
    private val fm = Transmitter("Mode V/U FM CTCSS 67.0", "FM", 145_850_000L, 145_850_000L,
        436_795_000L, 436_795_000L, invert = false, alive = true, type = "Transceiver")

    /** RS-44 like: linear, inverting, V up, U down. */
    private val lineaire = Transmitter("Linear transponder", "USB", 145_935_000L, 145_995_000L,
        435_610_000L, 435_670_000L, invert = true, alive = true, type = "Transponder")

    private class Monde(val poste: PosteCat, var t: Transmitter) : MondeCat {
        var el = 30.0
        var rr = 0.0
        var maintien = false
        var repos: Long? = null
        var connecte = true
        var afc = 0L
        override fun afcRxHz() = afc
        override fun connecte() = connecte
        override fun poste() = poste
        override fun emetSeul() = false
        override fun aprsEnEmission() = false
        override fun satellite() = SatPosition(180.0, el, 1000.0, rr, 800.0, 0.0, 0.0, true)
        override fun catnum() = 27607
        override fun transpondeur() = t
        override fun sousHorizonAussi() = false
        override fun calibrationHz() = 0L
        override fun decalageTxHz() = 0L
        override fun poseDecalageTx(hz: Long) {}
        override fun decalageRxHz() = 0L
        override fun modeCw() = false
        override fun maintienDoppler() = maintien
        override fun dopplerRx() = true
        override fun moletteTxDecalage() = false
        override fun txSuitVite() = false
        override fun modelePoste() = "IC9700"
        override fun inversionForcee(): Boolean? = null
        override fun ctcssChoisi() = 0
        override fun ctcssAuto() = true
        override fun repos() = repos
        override fun poseRepos(hz: Long) { repos = hz }
        override fun posteRx(satHz: Long) = satHz
        override fun satDepuisPoste(posteHz: Long) = posteHz
        override fun posteTx(satHz: Long) = satHz
        override fun satDepuisPosteTx(posteHz: Long) = posteHz
    }

    private var t = 1_000_000L
    private val sim = Ic9700Sim()
    private val civ = CivController().apply { pacingMs = 0L; attach(sim) }

    private fun station(tx: Transmitter): Pair<StationCat, Monde> {
        val m = Monde(PosteCatCiv(civ), tx)
        return StationCat(m, horloge = { t }) to m
    }

    /** Writes the simulator received: CI-V command 0x05 (frequency) or 0x16 (functions). */
    private fun ecritures() = sim.received.count { it.size > 4 && ((it[4].toInt() and 0xFF) == 0x05) }

    @Test
    fun un_satellite_fm_en_bandes_croisees_est_arme_puis_suivi() = runBlocking {
        val (st, _) = station(fm)
        st.tour()
        assertTrue("satellite mode", sim.satMode)
        assertTrue("access tone", sim.toneOn)
        assertEquals(670, sim.toneTenthHz)
        assertEquals(0x05, sim.mainMode); assertEquals(0x05, sim.subMode)
        assertEquals(436_795_000L, sim.mainHz)
        assertEquals(145_850_000L, sim.subHz)
        assertFalse("ends on MAIN", sim.onSub)
        assertEquals(0, sim.refusals)
        assertEquals(436_795_000L, st.etat.value.descenteHz)
        assertTrue(st.etat.value.rxPilote)
    }

    @Test
    fun le_doppler_fm_suit_la_vitesse_radiale() = runBlocking {
        val (st, m) = station(fm)
        m.rr = -6.5   // coming closer: downlink above, uplink below
        st.tour()
        assertEquals(Doppler.downlink(436_795_000L, -6.5), sim.mainHz)
        assertEquals(Doppler.uplink(145_850_000L, -6.5), sim.subHz)
        assertTrue(sim.mainHz > 436_795_000L)
    }

    @Test
    fun sous_l_horizon_rien_n_est_ecrit() = runBlocking {
        val (st, m) = station(fm)
        m.el = -5.0
        repeat(5) { st.tour(); t += 100 }
        assertEquals(0, ecritures())
        assertFalse(sim.satMode)
        assertEquals(435_000_000L, st.etat.value.descenteHz)
    }

    @Test
    fun le_maintien_du_doppler_n_ecrit_rien() = runBlocking {
        val (st, m) = station(fm)
        m.maintien = true
        repeat(5) { st.tour(); t += 100 }
        assertEquals(0, ecritures())
        assertFalse("not even armed", sim.satMode)
    }

    @Test
    fun en_lineaire_la_boucle_lache_le_bouton_puis_reprend() = runBlocking {
        val (st, m) = station(lineaire)
        m.repos = 435_640_000L
        // A few quiet rounds: the loop drives and puts the rig on the rest.
        repeat(30) { st.tour(); t += 100 }
        assertEquals(435_640_000L, sim.mainHz)
        val ulAvant = sim.subHz
        assertTrue(st.etat.value.rxPilote)
        // The operator turns the RX dial 2 kHz up.
        val operateur = CivController().apply { pacingMs = 0L; attach(sim) }
        operateur.selectMainSub(false); operateur.setFrequency(435_642_000L)
        st.tour(); t += 100
        // The loop lets go: the rig stays where the operator put it, and the rest follows him.
        assertEquals(435_642_000L, sim.mainHz)
        assertFalse(st.etat.value.rxPilote)
        assertEquals(435_642_000L, m.repos)
        // One knob on an IC-9700: its uplink is not written while he turns.
        assertEquals(ulAvant, sim.subHz)
        // After the hold time (2 s by default), the loop drives again from the new rest,
        // the uplink moved 2 kHz down (inverting transponder).
        repeat(30) { st.tour(); t += 100 }
        assertTrue(st.etat.value.rxPilote)
        assertEquals(435_642_000L, sim.mainHz)
        assertEquals(ulAvant - 2_000L, sim.subHz)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_calage_sstv_passe_par_les_ecritures_de_la_boucle_sans_bouger_le_repos() = runBlocking {
        val (st, m) = station(lineaire)
        m.repos = 435_640_000L
        repeat(30) { st.tour(); t += 100 }
        val avant = ecritures()
        assertEquals(435_640_000L, sim.mainHz)
        // The SSTV tuning asks 60 Hz more: the next round writes it, once.
        m.afc = 60L
        repeat(10) { st.tour(); t += 100 }
        assertEquals(435_640_060L, sim.mainHz)
        assertEquals(435_640_000L, m.repos)
        assertTrue(ecritures() - avant <= 2)
        // On hold: nothing written, the rest read back without the tuning's share.
        m.maintien = true
        m.afc = 120L
        repeat(10) { st.tour(); t += 100 }
        assertEquals(435_640_060L, sim.mainHz)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun une_montee_hors_des_bandes_du_poste_n_est_pas_envoyee() = runBlocking {
        // A QO-100 like uplink (2.4 GHz) the rig cannot reach: only the downlink goes out.
        val qo = Transmitter("NB", "USB", 2_400_250_000L, 2_400_250_000L, 435_640_000L, 435_640_000L,
            invert = false, alive = true, type = "Transponder")
        val (st, _) = station(qo)
        st.ecrireCouple(435_640_000L, 2_400_250_000L, sameBand = false)
        assertEquals(435_640_000L, sim.mainHz)
        assertEquals(145_000_000L, sim.subHz)
    }
}
