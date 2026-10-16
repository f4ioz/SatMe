/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.GardeQo100.Origine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The narrow band plan, frequency by frequency, and the guards built on it. */
class GardeQo100Test {

    /** The AMSAT-DL plan: each published edge, on both sides. */
    @Test
    fun le_plan_officiel_segment_par_segment() {
        val attendu = listOf(
            10_489_500_000L to "balise_basse",
            10_489_505_000L to "cw", 10_489_539_999L to "cw",
            10_489_540_000L to "num_etroit", 10_489_579_999L to "num_etroit",
            10_489_580_000L to "num_large", 10_489_649_999L to "num_large",
            10_489_650_000L to "ssb_bas", 10_489_744_999L to "ssb_bas",
            10_489_750_000L to "balise_mediane",
            10_489_755_000L to "ssb_haut", 10_489_849_999L to "ssb_haut",
            10_489_850_000L to "diffusion", 10_489_857_999L to "diffusion",
            10_489_858_000L to "urgence", 10_489_860_000L to "urgence", 10_489_864_999L to "urgence",
            10_489_865_000L to "mixte", 10_489_989_999L to "mixte",
            10_489_995_000L to "balise_multimedia",
            10_490_000_000L to "balise_haute",
        )
        for ((hz, cle) in attendu) assertEquals("$hz", cle, Qo100.segment(hz)?.cle)
        // The four beacons' markers.
        val reperes = Qo100.SEGMENTS.filter { it.usage == Qo100.Usage.BALISE }.map { it.repereHz }
        assertEquals(listOf(10_489_500_000L, 10_489_750_000L, 10_489_995_000L, 10_490_000_000L), reperes)
        // Contests: 870 to 990, inside the mixed segment, not below.
        assertTrue(10_489_870_000L in Qo100.CONCOURS)
        assertTrue(10_489_989_999L in Qo100.CONCOURS)
        assertFalse(10_489_869_999L in Qo100.CONCOURS)
        assertFalse(10_489_990_000L in Qo100.CONCOURS)
        assertEquals("mixte", Qo100.segment(Qo100.CONCOURS.first)?.cle)
        assertEquals("mixte", Qo100.segment(Qo100.CONCOURS.last)?.cle)
    }

    @Test
    fun l_urgence_n_est_jamais_envoyee_quand_satme_la_propose() {
        val urg = 10_489_860_000L
        assertTrue(GardeQo100.urgence(urg))
        assertFalse(GardeQo100.urgence(10_489_857_999L))
        assertFalse(GardeQo100.urgence(10_489_865_000L))
        // By hand: written (the red banner says it); proposed: held back.
        assertTrue(GardeQo100.monteeEnvoyable(urg, Origine.MAIN))
        assertFalse(GardeQo100.monteeEnvoyable(urg, Origine.PROPOSEE))
        assertFalse(GardeQo100.proposable(urg))
        // Elsewhere the origin does not matter; beacons stay closed to both.
        assertTrue(GardeQo100.monteeEnvoyable(10_489_700_000L, Origine.PROPOSEE))
        assertTrue(GardeQo100.proposable(10_489_700_000L))
        assertFalse(GardeQo100.monteeEnvoyable(Qo100.BALISE_MEDIANE_HZ, Origine.MAIN))
        assertFalse(GardeQo100.proposable(Qo100.BALISE_MEDIANE_HZ))
    }

    @Test
    fun la_place_libre_ft8_reste_dans_les_segments_numeriques() {
        assertEquals(listOf(10_489_540_000L until 10_489_650_000L), GardeQo100.ZONES_NUMERIQUES)
        // The usual dial: all of 500..2 500 Hz is digital.
        assertEquals(500..2_500, GardeQo100.plageAudioNumerique(10_489_540_000L))
        // A dial just below the segment: from where the signal enters it.
        assertEquals(1_030..2_500, GardeQo100.plageAudioNumerique(10_489_539_000L))
        // Near the top: cut where the SSB segment begins.
        assertEquals(500..1_969, GardeQo100.plageAudioNumerique(10_489_648_000L))
        // Every proposal fits whole inside: never SSB, never emergency.
        for (cadran in 10_489_530_000L..10_489_660_000L step 1_000L) {
            val r = GardeQo100.plageAudioNumerique(cadran) ?: continue
            for (a in listOf(r.first, r.last)) {
                assertTrue(GardeQo100.numerique(cadran + a - 30))
                assertTrue(GardeQo100.numerique(cadran + a + 30))
            }
        }
        // In the SSB segment, the emergency one or the CW one: nowhere.
        assertNull(GardeQo100.plageAudioNumerique(10_489_700_000L))
        assertNull(GardeQo100.plageAudioNumerique(10_489_858_000L))
        assertNull(GardeQo100.plageAudioNumerique(10_489_510_000L))
    }

    @Test
    fun un_mode_hors_de_son_segment_donne_un_conseil() {
        // SSB in the CW segment, FT8 among the SSB, CW in the digital one.
        assertEquals("cw", GardeQo100.conseilMode(10_489_520_000L, "SSB")?.segment?.cle)
        assertEquals("qo100_conseil_mode", GardeQo100.conseilMode(10_489_700_000L, "FT8")?.cle)
        assertNotNull(GardeQo100.conseilMode(10_489_560_000L, "CW"))
        // SSTV is too wide for the 500 Hz segment.
        assertNotNull(GardeQo100.conseilMode(10_489_560_000L, "SSTV"))
        assertEquals("qo100_conseil_large", GardeQo100.conseilMode(10_489_560_000L, "VARA")?.cle)
        // What fits: nothing said.
        assertNull(GardeQo100.conseilMode(10_489_520_000L, "CW"))
        assertNull(GardeQo100.conseilMode(10_489_541_000L, "FT8"))
        assertNull(GardeQo100.conseilMode(10_489_600_000L, "SSTV"))
        assertNull(GardeQo100.conseilMode(10_489_700_000L, "SSB"))
        assertNull(GardeQo100.conseilMode(10_489_900_000L, "FT8"))
        assertNull(GardeQo100.conseilMode(10_489_900_000L, "SSB"))
        // Unknown mode, outside the band plan, emergency (the banner speaks): nothing.
        assertNull(GardeQo100.conseilMode(10_489_520_000L, "XYZ"))
        assertNull(GardeQo100.conseilMode(145_800_000L, "SSB"))
        assertNull(GardeQo100.conseilMode(10_489_860_000L, "FT8"))
    }
}
