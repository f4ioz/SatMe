/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.RafraichissementTle
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SatNOGS DB as a source: its TLE JSON read, one satellite asked by number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSatnogsTest {

    private val json = """[{"tle0":"0 AO-91",
        "tle1":"1 43017U 17073E   26272.14668020  .00005031  00000-0  21515-3 0  9995",
        "tle2":"2 43017  97.4522 137.2373 0145825 329.8524  29.4373 15.14104588481110",
        "tle_source":"Space-Track.org","norad_cat_id":43017},
        {"tle0":"ISS","tle1":"1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927",
        "tle2":"2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"}]"""

    @Test
    fun json_satnogs_lu() {
        val lus = TleRepository().parse(json)
        assertEquals(listOf(43017, 25544), lus.map { it.catalogNumber })
        assertEquals("AO-91", lus[0].name)  // the 3LE "0 " prefix removed
        assertTrue(lus[0].line1.startsWith("1 43017U"))
    }

    @Test
    fun un_satellite_demande_par_numero_pas_toute_la_liste() {
        val satnogs = Sources.ALL.first { it.id == Sources.SATNOGS }
        assertEquals(listOf("https://db.satnogs.org/api/tle/?norad_cat_id=43017&format=json"),
            RafraichissementTle.adresses(43017, listOf(satnogs)))
    }

    @Test
    fun par_le_serveur_d_abord() {
        val satnogs = Sources.ALL.first { it.id == Sources.SATNOGS }
        assertEquals(listOf("https://gp.f4ioz.fr/gp/satnogs.json", satnogs.url),
            ServeurGp.adresses("https://gp.f4ioz.fr", satnogs))
    }
}
