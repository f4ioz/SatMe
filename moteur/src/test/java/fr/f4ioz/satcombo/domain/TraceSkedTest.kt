/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a sked's map and skies draw: the PC's page and video, the phone's video. */
class TraceSkedTest {

    @Test
    fun l_instant_prend_le_court_chemin_et_la_trace_reste_continue() {
        val p = listOf(PointSked(0, 350.0, 10.0, 10.0, 5.0, 40.0, 179.0, 800.0),
            PointSked(10_000, 10.0, 20.0, 350.0, 15.0, 41.0, -179.0, 800.0))
        val t = TraceSked(Observer(48.8, 2.3), Observer(40.0, -178.0), p)
        // Over the date line: one line, not a trip round the world.
        assertEquals(181.0, t.lons[1], 1e-9)
        val m = t.instant(5_000)
        assertEquals(0.0, m.azMoi, 1e-9)
        assertEquals(0.0, m.azAutre, 1e-9)
        assertEquals(15.0, m.elMoi, 1e-9)
        assertEquals(180.0, m.lon, 1e-9)
    }

    @Test
    fun l_empreinte_au_dessus_d_un_pole_fait_le_tour_de_la_carte() {
        val e = TraceSked.empreinte(80.0, 20.0, 1300.0)
        // Closed along the top edge: the circle, then two points at 85°.
        assertEquals(TraceSked.POINTS_CERCLE + 2, e.size)
        assertEquals(85.0, e.last()[0], 1e-9)
        assertTrue(Math.abs(e[TraceSked.POINTS_CERCLE - 1][1] - e[0][1]) > 300)
        // Away from the poles, the circle only.
        assertEquals(TraceSked.POINTS_CERCLE, TraceSked.empreinte(45.0, 2.0, 420.0).size)
    }
}
