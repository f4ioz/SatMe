/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.SatellitesImages
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture satellites' source: one by one, by number, on the SatMe GP server first. */
class SatellitesImagesTest {

    @Test
    fun chaque_satellite_d_images_est_demande_par_son_numero() {
        val l = Sources.aTelecharger(setOf("amsat_gp", Sources.IMAGES))
        assertEquals(listOf("amsat_gp", "amsat_tle"), l.take(2).map { it.id })
        assertEquals(SatellitesImages.LISTE.size, l.count { it.id.startsWith("images:") })
        val geoscan = l.first { it.id == "images:64890" }
        assertEquals(listOf("https://gp.f4ioz.fr/gp/catnr/64890.json"), ServeurGp.adresses(ServeurGp.DEFAUT, geoscan, seul = true))
        assertEquals(listOf("https://gp.f4ioz.fr/gp/catnr/64890.json", Sources.satnogsNumero(64890)), ServeurGp.adresses(ServeurGp.DEFAUT, geoscan))
        assertEquals("amsat", ServeurGp.groupe("amsat_gp")); assertEquals("satnogs", ServeurGp.groupe("satnogs"))
        assertTrue("no address of its own: never downloaded as a group", Sources.ALL.first { it.id == Sources.IMAGES }.url.isEmpty())
    }

    @Test
    fun un_nom_brut_prend_le_nom_usuel_un_vrai_nom_reste() {
        val iss = fr.f4ioz.satcombo.data.TleEntry("OBJECT Q", "1 64890U 25155Q   26285.28068813  .00012580  00000-0  33428-3 0  9990",
            "2 64890  97.4709 255.0212 0008811 103.7974 256.4249 15.33203139 98719")
        assertEquals("Geoscan-2", SatellitesImages.renomme(iss).name)
        assertEquals("GEOSCAN 2 (RS92S2)", SatellitesImages.renomme(iss.copy(name = "GEOSCAN 2 (RS92S2)")).name)
    }
}
