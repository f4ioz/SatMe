/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {

    // The ISS, two epochs (day 280 and day 284 of 2026).
    private val ancien = listOf("ISS",
        "1 25544U 98067A   26280.50000000  .00016717  00000-0  10270-3 0  9001",
        "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537")
    private val recent = listOf("ISS (ZARYA)",
        "1 25544U 98067A   26284.50000000  .00016717  00000-0  10270-3 0  9002",
        "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537")

    @Test
    fun un_fichier_tle_se_lit_trois_lignes_par_satellite() {
        val l = Catalogue.lisTle((ancien + "" + recent + "ligne perdue").joinToString("\n"))
        assertEquals(2, l.size)
        assertEquals(25544, l[0].catalogNumber)
    }

    @Test
    fun les_elements_les_plus_frais_gardent_le_nom_amsat() {
        val gp = Catalogue.lisTle(ancien.joinToString("\n"))
        val tle = Catalogue.lisTle(recent.joinToString("\n"))
        val f = Catalogue.fusionne(gp, tle)
        assertEquals(1, f.size)
        assertEquals("ISS", f[0].name)
        assertEquals(recent[1], f[0].line1)
    }

    @Test
    fun celestrak_n_est_jamais_demande() {
        assertTrue(Catalogue.interdit("https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json"))
        assertFalse(Catalogue.interdit(Catalogue.AMSAT_GP))
        assertFalse(Catalogue.interdit(Catalogue.AMSAT_TLE))
    }

    @Test
    fun les_emetteurs_actifs_avec_descente_d_abord() {
        val json = """[
          {"description":"Beacon","mode":"CW","downlink_low":435000000,"alive":false,"invert":false,"type":"Transmitter"},
          {"description":"FM","mode":"FM","uplink_low":145850000,"downlink_low":436795000,"alive":true,"invert":false,"type":"Transceiver"},
          {"description":"Telemetry","mode":null,"alive":true,"type":"Transmitter"}]"""
        val l = Catalogue.lisEmetteurs(json)
        assertEquals(listOf("FM", "Telemetry", "Beacon"), l.map { it.description })
        assertEquals(null, l[1].mode)
    }
}
