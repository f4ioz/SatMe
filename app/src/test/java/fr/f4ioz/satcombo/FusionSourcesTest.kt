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

    // A SupGP segment for 15/10: a prediction for later, not the ISS of today.
    private val futur = TleEntry("ISS [Segment 60]", "1 25544U 98067A   26288.25000000  .00003738  00000-0  76743-4 0  9993", l2)
    private val maintenant = fraiche.epochMs!! + 3_600_000L

    @Test
    fun des_elements_du_futur_ne_l_emportent_pas() {
        val r = TleRepository.fusionne(listOf(listOf(fraiche), listOf(futur)), maintenant)
        assertEquals(fraiche.line1, r.single().line1)
        // Even against older ones: the old set is for now, the future one is not.
        assertEquals(ancienne.line1, TleRepository.fusionne(listOf(listOf(futur), listOf(ancienne)), maintenant).single().line1)
    }

    @Test
    fun le_segment_supgp_en_cours_est_choisi() {
        val segments = (0..20).map { k ->
            val jour = 275.0 + k * 0.25   // every six hours from 02/10
            TleEntry("ISS [Segment $k]", "1 25544U 98067A   26%012.8f  .00003738  00000-0  76743-4 0  9993".format(java.util.Locale.US, jour), l2)
        }
        val r = fr.f4ioz.satcombo.data.RafraichissementTle.plusRecent(25544, listOf("supgp")) { segments }
        val e = (r as fr.f4ioz.satcombo.data.RafraichissementTle.Resultat.Trouve).entree.epochMs!!
        // Not the last segment (07/10): one no further ahead than an hour.
        assertTrue(e <= System.currentTimeMillis() + TleRepository.AVANCE_MAX_MS ||
            segments.all { (it.epochMs ?: 0L) > System.currentTimeMillis() })
    }

    @Test
    fun le_bulletin_tle_d_amsat_accompagne_son_json() {
        val s = Sources.aTelecharger(setOf("amsat_gp"))
        assertEquals(listOf("amsat_gp", "amsat_tle"), s.map { it.id })
        assertTrue(Sources.aTelecharger(setOf("satnogs")).none { it.id == "amsat_tle" })
    }
}
