/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.TleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ISS was five minutes off: AMSAT's JSON bulletin had its elements of
 * 23/09, its TLE file those of 02/10, and the first source read won.
 */
class FusionSourcesTest {

    private val l2 = "2 25544  51.6312 131.4121 0006946 211.9293 148.1275 15.48707684588318"
    private val ancienne = TleEntry("ISS", "1 25544U 98067A   26266.43236339  .00003738  00000-0  76743-4 0  9993", l2)
    private val fraiche = TleEntry("ISS (ZARYA)", "1 25544U 98067A   26275.01380287  .00003738  00000-0  76743-4 0  9993", l2)
    private val ao7 = TleEntry("AO-07", "1 07530U 74089B   26274.50000000 -.00000035  00000-0  00000-0 0  9991",
        "2 07530 101.9900 300.0000 0012000 100.0000 260.0000 12.53600000000000")

    @Test
    fun les_elements_les_plus_frais_l_emportent_le_nom_reste() {
        val r = TleRepository.fusionne(listOf(listOf(ancienne, ao7), listOf(fraiche)))
        val iss = r.single { it.catalogNumber == 25544 }
        assertEquals(fraiche.line1, iss.line1)
        assertEquals("ISS", iss.name)            // the first source's name is kept
        assertEquals(2, r.size)
    }

    @Test
    fun des_elements_plus_vieux_ne_remplacent_pas() {
        val r = TleRepository.fusionne(listOf(listOf(fraiche), listOf(ancienne)))
        assertEquals(fraiche.line1, r.single().line1)
    }

    @Test
    fun le_bulletin_tle_d_amsat_accompagne_son_json() {
        val s = Sources.aTelecharger(setOf("amsat_gp"))
        assertEquals(listOf("amsat_gp", "amsat_tle"), s.map { it.id })
        assertTrue(Sources.aTelecharger(setOf("satnogs")).none { it.id == "amsat_tle" })
    }
}
