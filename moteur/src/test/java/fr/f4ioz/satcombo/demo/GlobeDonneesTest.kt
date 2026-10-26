/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** The globes' landmarks: one to a few by country, each well placed, of a known family, drawn by the page. */
class GlobeDonneesTest {

    private fun km(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = Math.PI / 180
        return 6371 * acos((sin(la1 * r) * sin(la2 * r) + cos(la1 * r) * cos(la2 * r) * cos((lo2 - lo1) * r)).coerceIn(-1.0, 1.0))
    }

    @Test
    fun les_monuments_sont_nombreux_et_uniques() {
        val l = GlobeDonnees.MONUMENTS
        assertTrue("au moins 150 : " + l.size, l.size in 150..300)
        assertEquals("identifiants uniques", l.size, l.map { it.id }.toSet().size)
        assertTrue("plus de 100 pays", l.map { it.pays }.toSet().size > 100)
        val principaux = l.count { it.rang == 1 }
        assertTrue("principaux : $principaux", principaux in 40..90)
        l.forEach { m ->
            assertTrue(m.id, m.id.matches(Regex("[a-z0-9_]+")))
            assertTrue(m.id, m.nom.isNotBlank() && !m.nom.contains('|') && !m.nom.contains(';'))
            assertTrue(m.id, m.lat in -90.0..90.0 && m.lon in -180.0..180.0)
            assertTrue(m.id + " : " + m.type, m.type in GlobeDonnees.TYPES_MONUMENTS)
            assertTrue(m.id, m.pays.matches(Regex("[A-Z]{2}")))
            assertTrue(m.id, m.rang == 1 || m.rang == 2)
        }
        // Every family has some.
        GlobeDonnees.TYPES_MONUMENTS.keys.forEach { t -> assertTrue(t, l.count { it.type == t } >= 5) }
    }

    @Test
    fun chaque_monument_est_dans_son_pays() {
        // Far from its country's capital only where the country is that wide (or overseas): a sign or a figure typed wrong shows here.
        val loin = setOf("kourou", "kilauea", "baikal", "vostotchny")
        val capitales = GlobeDonnees.CAPITALES.groupBy { it.pays }
        GlobeDonnees.MONUMENTS.forEach { m ->
            val c = capitales[m.pays] ?: return@forEach
            val d = c.minOf { km(m.lat, m.lon, it.lat, it.lon) }
            if (m.id !in loin) assertTrue(m.id + " à " + d.toInt() + " km de " + c.first().nom, d < 4000)
        }
        // A few placed by hand, checked against their known places.
        val par = GlobeDonnees.MONUMENTS.associateBy { it.id }
        assertTrue(km(par.getValue("tour_eiffel").lat, par.getValue("tour_eiffel").lon, 48.8584, 2.2945) < 1)
        assertTrue(km(par.getValue("machu_picchu").lat, par.getValue("machu_picchu").lon, -13.1631, -72.5450) < 2)
        assertTrue(km(par.getValue("angkor").lat, par.getValue("angkor").lon, 13.4125, 103.8670) < 2)
        assertTrue(km(par.getValue("uluru").lat, par.getValue("uluru").lon, -25.3444, 131.0369) < 2)
    }

    @Test
    fun les_dessins_propres_existent_et_partent_dans_la_page() {
        GlobeDonnees.MONUMENTS.mapNotNull { it.modele }.toSet().forEach { assertTrue(it, OrbitesJs.JS.contains("case '$it'")) }
        // The journal's videos keep the eighteen they always drew.
        assertEquals(18, GlobeDonnees.MONUMENTS.count { it.modele != null && it.rang == 1 })
        GlobeDonnees.TYPES_MONUMENTS.keys.forEach { assertTrue(it, OrbitesJs.JS.contains("case '$it'")) }
        val j = JSONObject(GlobeDonnees.json())
        assertEquals(GlobeDonnees.MONUMENTS.size, j.getJSONArray("monuments").length())
        assertEquals(8, j.getJSONArray("monuments").getJSONArray(0).length())
        assertEquals(GlobeDonnees.TYPES_MONUMENTS.size, j.getJSONArray("typesMonuments").length())
    }
}
