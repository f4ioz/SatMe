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
 * The mast's tracking loop, on a computer: a simulated GS-232 mast, one round
 * of the loop per simulated second, as fast as the test wants. Until the move
 * to the engine this loop lived in the phone's ViewModel and could not be
 * tested at all.
 */
class StationRotorTest {

    private fun pos(az: Double, el: Double) = SatPosition(az, el, 1000.0, 0.0, 800.0, 0.0, 0.0, true)

    private class Monde(var sat: SatPosition?) : MondeRotor {
        var emission = false
        override fun satellite() = sat
        override fun nomSatellite() = "ISS"
        override fun arcDuPassage() = emptyList<Pair<Double, Double>>()
        override fun aosDuPassage() = 0L
        override fun enEmission() = emission
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long) = emptyList<LongRange>()
        override fun adaptateursDesPostes() = emptyList<String>()
    }

    /** The test's clock, moved by [seconde]. */
    private var t = 1_000_000L

    private fun CoroutineScope.station(monde: Monde): StationRotor {
        val s = SettingsStore(StockageMemoire()).apply {
            rotorSim = true; rotorEnabled = true; rotorMinEl = 0; rotorParkAz = 180; rotorParkEl = 0
            rotorMaxAz = 450; rotorMaxEl = 90; rotorAzStop = "NORTH"
        }
        return StationRotor(s, null, this, monde, horloge = { t }, boucleAuto = false)
    }

    private suspend fun attends(cond: () -> Boolean) {
        repeat(500) { if (cond()) return; delay(10) }
        error("never happened")
    }

    /** One second of the loop. */
    private suspend fun StationRotor.seconde() { rotorTick(); t += 1_000 }

    private fun commandes(st: StationRotor) = st.simulateur!!.received.count { it.startsWith("W") }

    @Test
    fun le_mat_simule_se_connecte() = runBlocking {
        val st = station(Monde(null))
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        assertTrue(st.simulateur != null)
    }

    @Test
    fun sous_l_horizon_il_va_au_garage_et_y_reste_tranquille() = runBlocking {
        val st = station(Monde(pos(90.0, -10.0)))
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        repeat(60) { st.seconde() }
        val sim = st.simulateur!!
        assertEquals(180.0, sim.azDeg, 1.0)
        assertEquals(0.0, sim.elDeg, 1.0)
        assertEquals("one command to get there, then nothing: ${sim.received}", 1, commandes(st))
        assertEquals(180.0, st.etat.value.rotorTargetAz!!, 0.01)
    }

    @Test
    fun au_dessus_il_suit_le_satellite() = runBlocking {
        val monde = Monde(pos(100.0, 20.0))
        val st = station(monde)
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        // Ten minutes of a satellite crossing from 100° to 160°, rising to 50°.
        for (s in 0 until 600) {
            monde.sat = pos(100.0 + s * 0.1, 20.0 + s * 0.05)
            st.seconde()
        }
        val sim = st.simulateur!!
        assertEquals(160.0, sim.azDeg, 6.0)
        assertEquals(50.0, sim.elDeg, 6.0)
        val n = commandes(st)
        assertTrue("about one command per 4° of travel, got $n", n in 5..40)
        // The screen sees where the mast points.
        assertEquals(sim.azDeg, st.etat.value.rotorActualAz!!, 1.0)
    }

    @Test
    fun arreter_coupe_le_suivi() = runBlocking {
        val st = station(Monde(pos(100.0, 20.0)))
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        repeat(5) { st.seconde() }
        st.rotorStopNow(); attends { !st.etat.value.rotorEnabled }
        val avant = commandes(st)
        repeat(30) { st.seconde() }
        assertFalse(st.etat.value.rotorEnabled)
        assertEquals("no command once stopped", avant, commandes(st))
    }

    @Test
    fun les_reglages_relus_arrivent_dans_l_etat() = runBlocking {
        val s = SettingsStore(StockageMemoire())
        val st = StationRotor(s, null, this, Monde(null), horloge = { t }, boucleAuto = false)
        s.rotorDeadband = 7; s.rotorAzStop = "SOUTH"
        val e = st.rechargeReglages()
        assertEquals(7, e.rotorDeadband)
        assertEquals("SOUTH", e.rotorAzStop)
        assertEquals(e, st.etat.value)
    }
}
