/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrthodromieTest {
    @Test
    fun le_grand_cercle_va_d_une_station_a_l_autre_par_le_nord() {
        // Paris to Los Angeles: the great circle goes far north (by Hudson Bay), not along the parallel.
        val p = Orthodromie.points(48.85, 2.35, 34.05, -118.24)
        assertEquals(48.85, p.first().first, 1e-6); assertEquals(2.35, p.first().second, 1e-6)
        assertEquals(34.05, p.last().first, 1e-6); assertEquals(-118.24, p.last().second, 1e-6)
        assertTrue(p.maxOf { it.first } > 60.0)   // 61.7°, far north of both ends
        // Over the date line it stays continuous.
        val q = Orthodromie.points(35.0, 170.0, 40.0, -170.0)
        assertEquals(190.0, q.last().second, 1e-6)
    }
}
