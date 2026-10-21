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
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.rotor.RotorChemin
import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPlanPassage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.sin

/**
 * Olivier's passes through his south stop, played through the whole tracking
 * loop on a simulated mast (6°/s), from five minutes before AOS: where it
 * waits, how far it turns, how much of the pass it covers.
 */
class StationRotorRejeuTest {

    private var t = 0L

    private class Monde(val trace: List<Triple<Long, Double, Double>>, val horloge: () -> Long) : MondeRotor {
        override fun satellite(): SatPosition {
            val now = horloge()
            val i = trace.indexOfFirst { it.first >= now }
            val (az, el) = when {
                i < 0 -> trace.last().second to -1.0
                i == 0 -> trace.first().second to (if (now < trace.first().first) -1.0 else trace.first().third)
                else -> {
                    val a = trace[i - 1]; val b = trace[i]
                    val f = (now - a.first).toDouble() / (b.first - a.first)
                    var d = b.second - a.second
                    if (d > 180) d -= 360.0; if (d < -180) d += 360.0
                    RotorMath.norm360(a.second + d * f) to a.third + (b.third - a.third) * f
                }
            }
            return SatPosition(az, el, 1000.0, 0.0, 800.0, 0.0, 0.0, true)
        }
        override fun nomSatellite() = "ISS"
        override fun arcDuPassage() = trace.map { it.second to it.third }
        override fun aosDuPassage() = trace.first().first
        override fun enEmission() = false
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long) = emptyList<LongRange>()
        override fun adaptateursDesPostes() = emptyList<String>()
    }

    private fun trace(nom: String) = JournalPassage.lit(javaClass.getResource("/rotor/$nom.passage")!!.readText())!!
        .points.map { Triple(it.tMs, it.az, it.el) }

    private suspend fun attends(cond: () -> Boolean) {
        repeat(500) { if (cond()) return; delay(10) }
        error("never happened")
    }

    private data class Resultat(val course: Double, val couverture: Double, val etat: EtatRotor, val bascules: Int,
                                val attenteAz: Double)

    private fun CoroutineScope.rejoue(nom: String, maxEl: Int): Resultat = runBlocking {
        val tr = trace(nom)
        t = tr.first().first - 5 * 60_000L
        val s = SettingsStore(StockageMemoire()).apply {
            rotorSim = true; rotorEnabled = true; rotorMinEl = 0; rotorParkAz = 180; rotorParkEl = 0
            rotorMaxAz = 360; rotorMaxEl = maxEl; rotorAzStop = "SOUTH"; rotorFlip = false
            rotorDeadband = 3; rotorMaxError = 15; rotorPreAos = 3; rotorVitesse = 6f
        }
        val st = StationRotor(s, null, this, Monde(tr) { t }, horloge = { t }, boucleAuto = false)
        st.traceTemps = tr
        st.connectRotor(); attends { st.etat.value.rotorConnected }
        val sim = st.simulateur!!
        while (t < tr.first().first) { st.rotorTick(); t += 1_000 }
        val attente = sim.azDeg
        val avant = sim.azTravelDeg
        var couvert = 0.0; var total = 0.0
        var bascules = 0; var retourne: Boolean? = null
        while (t <= tr.last().first) {
            st.rotorTick()
            val p = Monde(tr) { t }.satellite()
            val w = max(0.0, sin(Math.toRadians(p.elevationDeg)))
            val (va, ve) = RotorPlanPassage.versCiel(sim.azDeg, sim.elDeg)
            total += w
            if (RotorChemin.separation(p.azimuthDeg, p.elevationDeg, va, ve) <= 15.0) couvert += w
            val r = sim.elDeg > 90.0
            if (retourne != null && r != retourne) bascules++
            retourne = r
            t += 1_000
        }
        Resultat(sim.azTravelDeg - avant, couvert / total, st.etat.value, bascules, attente)
    }

    @Test
    fun iss_du_21_10_a_180_degres() = runBlocking {
        val r = rejoue("25544_1792574351174", 180)
        println("ISS 21/10 180°: travel ${r.course} coverage ${r.couverture} waited at ${r.attenteAz} ${r.etat.rotorPlan?.texte}")
        assertTrue("travel ${r.course}", r.course < 220)
        assertTrue("coverage ${r.couverture}", r.couverture > 0.95)
        assertTrue("no flapping: ${r.bascules}", r.bascules <= 1)
        // It waited before AOS on the plan's start, not at the stop.
        val plan = r.etat.rotorPlan
        assertNotNull(plan)
        assertEquals(RotorMath.norm360(plan!!.mat.first().az + (if (plan.mat.first().retourne) 180.0 else 0.0)),
            RotorMath.norm360(r.attenteAz), 5.0)
    }

    @Test
    fun iss_du_21_10_a_90_degres_deroule_et_le_dit() = runBlocking {
        val r = rejoue("25544_1792574351174", 90)
        println("ISS 21/10 90°: travel ${r.course} coverage ${r.couverture} ${r.etat.rotorPlan?.texte}")
        val plan = r.etat.rotorPlan!!
        assertEquals("deroule", plan.changement)
        assertTrue(plan.texte, plan.texte.contains("déroulement prévu"))
        assertTrue("coverage ${r.couverture}", r.couverture > 0.85)
        assertEquals(0, r.bascules)
    }

    @Test
    fun so50_du_21_10_a_180_degres() = runBlocking {
        val r = rejoue("27607_1792592640991", 180)
        println("SO-50 21/10 180°: travel ${r.course} coverage ${r.couverture} ${r.etat.rotorPlan?.texte}")
        assertTrue("travel ${r.course}", r.course < 300)
        assertTrue("coverage ${r.couverture}", r.couverture > 0.95)
        assertTrue("no flapping: ${r.bascules}", r.bascules <= 1)
    }
}
