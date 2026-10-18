/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ApiCarteTest {

    @Test fun traits_amincis() {
        // A round island of 2° drawn with 720 points, and a speck smaller than the tolerance.
        val ile = (0 until 720).flatMap { k -> val a = k * Math.PI / 360; listOf(10 + cos(a), 45 + sin(a)) }
        val miette = listOf(0.0, 0.0, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01)
        val t = JSONArray(ApiCarte.traits(JSONArray(listOf(JSONArray(ile), JSONArray(miette))).toString(), 0.05))
        assertEquals(1, t.length())
        val r = t.getJSONArray(0)
        assertTrue(r.length() / 2 in 8..80)
        // Still on the circle, within the tolerance and the rounding.
        for (i in 0 until r.length() / 2) {
            val d = Math.hypot(r.getDouble(2 * i) - 10, r.getDouble(2 * i + 1) - 45)
            assertEquals(1.0, d, 0.06)
        }
    }

    @Test fun point_arrondi() {
        val o = ApiCarte.point("F4IOZ", 48.123456, 2.987654, "loc" to "JN18", "rien" to null)
        assertEquals(48.1235, o.getDouble("lat"), 1e-9)
        assertEquals("JN18", o.getString("loc"))
        assertTrue(!o.has("rien"))
    }
}
