/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the beacon calibration may retune while FT8/FT4 decodes the dongle's sound. */
class Qo100PcTest {

    @Test
    fun sans_ft8_le_calage_bouge_quand_il_veut() {
        for (t in listOf(0L, 3_000L, 7_200L, 14_500L)) assertTrue(Qo100Pc.entreEmissions(1_792_603_500_000L + t, null))
    }

    @Test
    fun avec_ft8_seulement_dans_la_derniere_seconde_du_creneau() {
        val debut = 1_792_603_530_000L          // a slot's start (multiple of 15 s)
        for (t in listOf(0L, 500L, 7_000L, 13_000L, 13_999L)) assertFalse("$t", Qo100Pc.entreEmissions(debut + t, 15_000L))
        for (t in listOf(14_000L, 14_500L, 14_999L)) assertTrue("$t", Qo100Pc.entreEmissions(debut + t, 15_000L))
    }

    @Test
    fun avec_ft4_tous_les_sept_secondes_et_demie() {
        val debut = 1_792_603_530_000L
        assertFalse(Qo100Pc.entreEmissions(debut + 3_000L, 7_500L))
        assertTrue(Qo100Pc.entreEmissions(debut + 6_800L, 7_500L))
        assertFalse(Qo100Pc.entreEmissions(debut + 7_600L, 7_500L))
        assertTrue(Qo100Pc.entreEmissions(debut + 14_600L, 7_500L))
    }
}
