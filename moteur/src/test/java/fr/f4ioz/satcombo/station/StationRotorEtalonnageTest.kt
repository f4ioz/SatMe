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
import fr.f4ioz.satcombo.demo.ApiEtalonnageRotor
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.rotor.Gs232Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration assistant on the station's side, against the simulated
 * K3NG mast: tracking held off and given back, the controller's own
 * calibration through the station, the software correction on what is sent
 * and what is shown, and the controller's range never exceeded — flip
 * included.
 */
class StationRotorEtalonnageTest {

    private fun pos(az: Double, el: Double) = SatPosition(az, el, 1000.0, 0.0, 800.0, 0.0, 0.0, true)

    private class Monde(var sat: SatPosition?) : MondeRotor {
        override fun satellite() = sat
        override fun nomSatellite() = "ISS"
        override fun arcDuPassage() = emptyList<Pair<Double, Double>>()
        override fun aosDuPassage() = 0L
        override fun enEmission() = false
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long) = emptyList<LongRange>()
        override fun adaptateursDesPostes() = emptyList<String>()
    }

    private var t = 1_000_000L

    private fun CoroutineScope.station(monde: Monde, reglages: SettingsStore.() -> Unit = {}): StationRotor {
        val s = SettingsStore(StockageMemoire()).apply {
            rotorSim = true; rotorEnabled = true; rotorMinEl = 0; rotorParkAz = 180; rotorParkEl = 0
            rotorMaxAz = 360; rotorMaxEl = 180; rotorFlip = true; rotorAzStop = "NORTH"
            reglages()
        }
        return StationRotor(s, null, this, monde, horloge = { t }, boucleAuto = false)
    }

    private suspend fun attends(cond: () -> Boolean) {
        repeat(500) { if (cond()) return; delay(10) }
        error("never happened")
    }

    private suspend fun StationRotor.secondes(n: Int) = repeat(n) { rotorTick(); t += 1_000 }

    private fun StationRotor.w() = simulateur!!.received.filter { it.startsWith("W") }

    @Test
    fun l_assistant_arrete_le_suivi_et_le_rend() = runBlocking {
        val st = station(Monde(pos(100.0, 30.0)))
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        assertTrue(st.etat.value.rotorEnabled)
        st.etalonnageOuvre()
        assertFalse(st.etat.value.rotorEnabled)
        // An order to follow meanwhile (the auto-pilot, a click) waits.
        st.setRotorEnabled(true)
        assertFalse(st.etat.value.rotorEnabled)
        val avant = st.w().size
        st.secondes(5)
        assertEquals("nothing sent while the assistant is open", avant, st.w().size)
        st.etalonnageFerme()
        assertTrue(st.etat.value.rotorEnabled)
    }

    @Test
    fun ferme_sans_suivi_avant_reste_sans_suivi() = runBlocking {
        val st = station(Monde(null)) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        st.etalonnageOuvre(); st.etalonnageFerme()
        assertFalse(st.etat.value.rotorEnabled)
    }

    @Test
    fun les_commandes_du_controleur_seulement_assistant_ouvert() = runBlocking {
        val st = station(Monde(null)) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        assertFalse(st.etalonnageCommande(Gs232Codec.Etalonnage.EL_ZERO).ok)
        assertTrue(st.simulateur!!.received.none { it == "O2" })
        st.etalonnageOuvre()
        val r = st.etalonnageCommande(Gs232Codec.Etalonnage.EL_ZERO)
        assertTrue(r.message, r.ok)
        assertEquals("ECRIT", r.issue)
        assertTrue(st.etat.value.rotorEtalJournal.toString(), st.etat.value.rotorEtalJournal.any { it.contains("Wrote to memory") })
        assertEquals(1, st.simulateur!!.ecrituresEeprom)
    }

    @Test
    fun la_route_veut_une_confirmation_avant_l_eeprom() = runBlocking {
        val st = station(Monde(null)) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        ApiEtalonnageRotor.action(st, "/api/rotor/etalonnage/ouvrir") { "" }
        val sans = ApiEtalonnageRotor.action(st, "/api/rotor/etalonnage/commande") { if (it == "c") "F2" else "" }
        assertFalse(sans.getBoolean("ok"))
        assertEquals(0, st.simulateur!!.ecrituresEeprom)
        val avec = ApiEtalonnageRotor.action(st, "/api/rotor/etalonnage/commande") { mapOf("c" to "F2", "confirme" to "1")[it].orEmpty() }
        assertTrue(avec.toString(), avec.getBoolean("ok"))
        assertEquals(1, st.simulateur!!.ecrituresEeprom)
        assertTrue(ApiEtalonnageRotor.etat(st).getBoolean("commandes"))
    }

    @Test
    fun la_correction_vaut_pour_les_consignes_et_l_affichage() = runBlocking {
        val st = station(Monde(null)) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        val sim = st.simulateur!!
        // The pot as Olivier's: the controller reads 180 at a real 160.
        sim.deregleElevation(reelLuEnHaut = 160.0)
        assertTrue(st.setRotorCorrection("EL", "0:0;180:160"))
        st.etalonnageOuvre()
        assertNull(st.etalonnageVa(az = 90.0, el = 90.0))
        // 90 real is 101 for the controller (whole degrees on the wire).
        assertEquals("W090 101", st.w().last())
        repeat(30) { sim.advance(1000); st.rotorTick() }
        assertEquals("the boom really at 90", 90.0, sim.elDeg, 1.0)
        assertEquals("SatMe shows the real angle", 90.0, st.etat.value.rotorActualEl!!, 1.0)
        assertEquals("and the raw reading beside it", 101.0, st.etat.value.rotorBrutEl!!, 0.6)
        // Reset: the controller is taken at its word again.
        ApiEtalonnageRotor.action(st, "/api/rotor/etalonnage/raz") { "" }
        assertEquals("", st.etat.value.rotorCorrEl)
    }

    @Test
    fun avec_le_flip_jamais_plus_que_la_course_du_controleur() = runBlocking {
        val monde = Monde(null)
        val st = station(monde) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        val sim = st.simulateur!!
        sim.deregleElevation(reelLuEnHaut = 160.0)
        st.setRotorCorrection("EL", "0:0;180:160")
        st.etalonnageOuvre()
        // A real 170 would need 191 from a controller that stops at 180: refused, nothing sent.
        val n = st.w().size
        assertNotNull(st.etalonnageVa(az = 0.0, el = 170.0))
        assertEquals(n, st.w().size)
        assertEquals(160.0, st.porteeReelle().second.endInclusive, 1e-6)
        // Lying back over the top, at a real 150 (the controller is told 169).
        assertNull(st.etalonnageVa(az = 20.0, el = 150.0))
        repeat(40) { sim.advance(1000); st.rotorTick() }
        st.etalonnageFerme()
        // Tracking over the top, low on the far side: flipped, it would want 165 real.
        monde.sat = pos(200.0, 15.0)
        st.setRotorEnabled(true)
        repeat(90) { sim.advance(1000); st.rotorTick(); t += 1_000 }
        assertTrue(st.w().toString(), st.w().all { it.substring(5, 8).toInt() <= 180 })
        assertTrue("it did stay flipped, at the controller's top: " + st.w(), st.w().last().endsWith("180"))
        assertTrue(st.etat.value.rotorEcretageDeg > 1.0)
        assertEquals("the controller never refused a frame", 0, sim.refusals)
    }

    @Test
    fun sans_correction_les_trames_sont_celles_d_avant() = runBlocking {
        val st = station(Monde(null)) { rotorEnabled = false }
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        assertTrue(st.correction.estIdentite)
        st.etalonnageOuvre()
        assertNull(st.etalonnageVa(az = 90.0, el = 45.0))
        assertEquals("W090 045", st.w().last())
    }

    @Test
    fun le_tableau_d_azimut_tient_compte_du_tour_et_de_la_butee() = runBlocking {
        // A 450° north-stop mast: the controller reads 370, the compass 12.
        val st = station(Monde(null)) { rotorEnabled = false; rotorMaxAz = 450 }
        val c = st.ajusteCorrection("AZ", listOf(10.0 to 8.0, 370.0 to 12.0), parPoints = true)!!
        assertEquals(8.0, c.reel(10.0), 1e-9)
        assertEquals("12 on the compass is the ring's 372", 372.0, c.reel(370.0), 1e-9)
        // A south-stop mast counting from its stop: the controller's 0 is the true 180.
        val sud = station(Monde(null)) { rotorEnabled = false; rotorAzStop = "SOUTH"; rotorAzFromStop = true }
        val cs = sud.ajusteCorrection("AZ", listOf(0.0 to 183.0, 180.0 to 2.0), parPoints = true)!!
        assertEquals(3.0, cs.reel(0.0), 1e-9)
        assertEquals("2 on the compass, half a turn from the stop", 182.0, cs.reel(180.0), 1e-9)
        // K3NG on a south-start ring answers modulo 360: its 10 is the ring's 370.
        val k3 = station(Monde(null)) { rotorEnabled = false; rotorAzStop = "SOUTH" }
        val ck = k3.ajusteCorrection("AZ", listOf(180.0 to 181.0, 10.0 to 12.0), parPoints = true)!!
        assertEquals(181.0, ck.reel(180.0), 1e-9)
        assertEquals(372.0, ck.reel(370.0), 1e-9)
    }
}
