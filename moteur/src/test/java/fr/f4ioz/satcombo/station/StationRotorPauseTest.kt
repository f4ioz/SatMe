/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A direction asked by hand between passes with « Suivre » kept
 * ([StationRotor.rotorGotoGardantSuivi]): the mast stays where it was sent,
 * tracking stays armed, and the next pass is tracked as if nothing had
 * happened — on a fake clock, a pass scheduled 30 minutes ahead, the
 * simulated GS-232.
 */
class StationRotorPauseTest {

    private var t = 1_000_000_000L
    private val aos = t + 30 * 60_000L
    private val los = aos + 10 * 60_000L

    /** A pass from AOS to LOS: azimuth 100° to 160°, up to 45°; below the horizon otherwise. */
    private inner class Monde : MondeRotor {
        override fun satellite(): SatPosition {
            val f = ((t - aos).toDouble() / (los - aos)).coerceIn(0.0, 1.0)
            val el = if (t in aos..los) 45.0 * Math.sin(Math.PI * f) + 0.5 else -20.0
            return SatPosition(100.0 + 60.0 * f, el, 1000.0, 0.0, 800.0, 0.0, 0.0, true)
        }
        override fun nomSatellite() = "ISS"
        override fun arcDuPassage() = emptyList<Pair<Double, Double>>()
        override fun aosDuPassage() = if (t <= los) aos else 0L
        override fun enEmission() = false
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long) = emptyList<LongRange>()
        override fun adaptateursDesPostes() = emptyList<String>()
    }

    private fun CoroutineScope.station(suivre: Boolean = true): StationRotor {
        val s = SettingsStore(StockageMemoire()).apply {
            rotorSim = true; rotorEnabled = suivre; rotorMinEl = 0; rotorParkAz = 180; rotorParkEl = 0
            rotorMaxAz = 450; rotorMaxEl = 90; rotorAzStop = "NORTH"; rotorPreAos = 3
        }
        return StationRotor(s, null, this, Monde(), horloge = { t }, boucleAuto = false)
    }

    private suspend fun attends(cond: () -> Boolean) {
        repeat(500) { if (cond()) return; delay(10) }
        error("never happened")
    }

    private suspend fun StationRotor.seconde() { rotorTick(); t += 1_000 }
    private fun commandes(st: StationRotor) = st.simulateur!!.received.count { it.startsWith("W") }

    @Test
    fun pointe_a_la_main_suivre_garde_et_le_passage_suivant_est_suivi() = runBlocking {
        val st = station()
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        repeat(60) { st.seconde() }
        val sim = st.simulateur!!
        assertEquals("parked first", 180.0, sim.azDeg, 1.0)

        // Another program points the mast at 90°/10° between passes.
        st.setRotorManualAz(90); st.setRotorManualEl(10)
        st.rotorGotoGardantSuivi()
        attends { st.etat.value.rotorPauseManuelle && st.etat.value.rotorTargetAz == 90.0 }
        assertTrue("« Suivre » kept", st.etat.value.rotorEnabled)
        // Twenty minutes later (8 min before AOS): still there, no command but the one sent.
        val apres = commandes(st)
        repeat(20 * 60) { st.seconde() }
        assertEquals(90.0, sim.azDeg, 1.0)
        assertEquals(10.0, sim.elDeg, 1.0)
        assertEquals("the loop left the mast alone", apres, commandes(st))
        assertTrue(st.etat.value.rotorPauseManuelle)
        assertTrue(st.etat.value.rotorEnabled)

        // The pre-AOS window (3 min): tracking takes the mast back, by itself.
        while (t < aos - 3 * 60_000L + 2_000L) st.seconde()
        assertFalse("the pause ends at the pre-positioning", st.etat.value.rotorPauseManuelle)
        assertTrue(commandes(st) > apres)
        // The pass: followed as usual.
        while (t < aos + 5 * 60_000L) st.seconde()
        val sat = Monde().satellite()
        assertEquals(sat.azimuthDeg, sim.azDeg, 8.0)
        assertEquals(sat.elevationDeg, sim.elDeg, 8.0)
        assertTrue(st.etat.value.rotorEnabled)
    }

    @Test
    fun suivre_decoche_reste_decoche_et_un_clic_sur_suivre_finit_la_pause() = runBlocking {
        val st = station(suivre = false)
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        st.setRotorManualAz(90); st.setRotorManualEl(0)
        st.rotorGotoGardantSuivi()
        attends { st.etat.value.rotorTargetAz == 90.0 }
        assertFalse(st.etat.value.rotorEnabled)
        assertFalse("no pause without tracking", st.etat.value.rotorPauseManuelle)

        val st2 = station()
        st2.connectRotor(); attends { st2.etat.value.rotorConnected }
        st2.setRotorManualAz(90); st2.setRotorManualEl(0)
        st2.rotorGotoGardantSuivi()
        attends { st2.etat.value.rotorPauseManuelle }
        // « Suivre » ticked again by hand (or the old goto, which unticks it): the pause is over.
        st2.setRotorEnabled(true)
        assertFalse(st2.etat.value.rotorPauseManuelle)
        repeat(30) { st2.seconde() }
        assertEquals("back to the park as before", 180.0, st2.simulateur!!.azDeg, 1.0)
    }

    @Test
    fun stop_garde_suivre_et_le_mat_reste_la() = runBlocking {
        val st = station()
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        repeat(5) { st.seconde() }
        st.rotorStopGardantSuivi()
        assertTrue(st.etat.value.rotorPauseManuelle)
        attends { st.simulateur!!.received.any { it.startsWith("S") } }
        val avant = commandes(st)
        repeat(120) { st.seconde() }
        assertTrue(st.etat.value.rotorEnabled)
        assertEquals("no command after the stop", avant, commandes(st))
    }
}
