/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.AmsatReport
import fr.f4ioz.satcombo.data.AmsatStatus
import fr.f4ioz.satcombo.data.NomsAmsat
import fr.f4ioz.satcombo.data.TleEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AMSAT status with another source: "OSCAR 7" (CelesTrak) is AMSAT's "AO-07". */
class StatutParNumeroTest {

    private val rapports = mapOf(
        "AO-07" to AmsatReport("AO-07", "U/v", AmsatStatus.ACTIVE, 3),
        "RS-44" to AmsatReport("RS-44", "V/u", AmsatStatus.NOT_HEARD, 0),
    )

    @After fun vide() { nomsAmsat = emptyMap() }

    @Test
    fun sans_table_le_nom_de_celestrak_ne_trouve_rien() {
        assertNull(amsatMatch("OSCAR 7", rapports, 7530))
    }

    @Test
    fun par_le_numero_le_nom_amsat_est_retrouve() {
        nomsAmsat = mapOf(7530 to "AO-07", 44909 to "RS-44")
        assertEquals("AO-07", amsatMatch("OSCAR 7", rapports, 7530)?.name)
        assertEquals("RS-44", amsatMatch("RS-44 & BRIZ-KM R/B", rapports, 44909)?.name)
        // The name alone still works.
        assertEquals("AO-07", amsatMatch("AO-7", rapports)?.name)
    }

    @Test
    fun la_table_vient_du_bulletin_amsat() {
        val t = NomsAmsat.table(listOf(TleEntry("AO-07", "1 07530U 74089B   26274.50000000 -.00000035  00000-0  00000-0 0  9991",
            "2 07530 101.9900 300.0000 0012000 100.0000 260.0000 12.53600000000000")))
        assertEquals(mapOf(7530 to "AO-07"), t)
    }
}
