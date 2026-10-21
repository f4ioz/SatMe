/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.demo.ApiRotorPlan
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.station.EtatRotor
import fr.f4ioz.satcombo.station.MondeRotor
import fr.f4ioz.satcombo.station.StationRotor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the page is served of the mast's path: the plan to come, and a logged contact's pass as it went. */
class ApiRotorPlanTest {

    private val iss = JournalPassage.lit(javaClass.getResource("/rotor/25544_1792574351174.passage")!!.readText())!!

    private class Monde : MondeRotor {
        override fun satellite(): SatPosition? = null
        override fun nomSatellite() = ""
        override fun arcDuPassage() = emptyList<Pair<Double, Double>>()
        override fun aosDuPassage() = 0L
        override fun enEmission() = false
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long) = emptyList<LongRange>()
        override fun adaptateursDesPostes() = emptyList<String>()
    }

    @Test
    fun le_plan_se_voit_sans_rotor_connecte() = runBlocking {
        val s = SettingsStore(StockageMemoire()).apply {
            rotorMaxAz = 360; rotorMaxEl = 180; rotorAzStop = "SOUTH"; rotorZones = "170-190<20"
        }
        val st = StationRotor(s, null, this, Monde(), boucleAuto = false)
        assertFalse(ApiRotorPlan.plan(st.apercuPlan(), st.etat.value).getBoolean("ok"))
        st.traceTemps = iss.points.map { Triple(it.tMs, it.az, it.el) }
        val j = ApiRotorPlan.plan(st.apercuPlan(), st.etat.value)
        assertTrue(j.getBoolean("ok"))
        assertEquals(180, j.getInt("elMax"))
        assertEquals("170-190<20", j.getString("zones"))
        assertEquals(iss.points.size, j.getJSONArray("sat").length())
        val m = j.getJSONArray("mat")
        assertEquals(iss.points.size, m.length())
        assertEquals(5, m.getJSONArray(0).length())
        // Over the top all the way: drawn where the antennas look, i.e. on the satellite.
        assertTrue(j.getBoolean("retourne"))
        assertEquals(iss.points.first().az, m.getJSONArray(0).getDouble(1), 3.0)
        assertTrue(j.getDouble("couverture") > 0.95)
        assertTrue(j.getString("texte").isNotBlank())
    }

    @Test
    fun le_passage_d_un_contact_et_le_rotor_tel_qu_il_est_alle() {
        val l = ApiRotorPlan.limites(EtatRotor(rotorMaxAz = 360, rotorMaxEl = 90, rotorAzStop = "SOUTH"))
        val t = iss.points[60].tMs
        val j = ApiRotorPlan.journal(listOf(iss), t, "ISS (ZARYA)", l, 15.0)
        assertTrue(j.getBoolean("ok"))
        assertTrue(j.getBoolean("rotor"))
        assertEquals(iss.points.size, j.getJSONArray("trace").length())
        assertEquals(464.0, j.getDouble("course"), 3.0)
        assertTrue(j.getDouble("couverture") in 0.85..0.97)
        assertFalse(ApiRotorPlan.journal(listOf(iss), t + 86_400_000L, "ISS", l, 15.0).getBoolean("ok"))
        assertFalse(ApiRotorPlan.journal(listOf(iss), t, "SO-50", l, 15.0).getBoolean("ok"))
    }
}
