/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.TleSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** The SatMe GP server's addresses, and the source kept as a fallback. */
class ServeurGpTest {

    private val amsat = TleSource("amsat_gp", "AMSAT", "https://newark192.amsat.org/gpdata/current/daily-bulletin.json")
    private val amateur = TleSource("amateur", "Amateur", "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json")

    @Test
    fun sans_serveur_la_source_seule() {
        assertEquals(listOf(amateur.url), ServeurGp.adresses("", amateur))
        assertEquals(listOf(amateur.url), ServeurGp.adresses("   ", amateur))
    }

    @Test
    fun le_serveur_d_abord_la_source_en_secours() {
        assertEquals(listOf("https://gp.exemple.org/gp/amateur.json", amateur.url),
            ServeurGp.adresses("https://gp.exemple.org/", amateur))
        // SatMe's AMSAT source id is not the server's group name.
        assertEquals("https://gp.exemple.org/gp/amsat.json", ServeurGp.adresses("gp.exemple.org", amsat)[0])
    }

    @Test
    fun adresse_saisie_sans_schema_ou_en_http() {
        assertEquals("https://gp.exemple.org/gp/catnr/25544.json", ServeurGp.catnr("gp.exemple.org", 25544))
        assertEquals("http://192.168.1.50:8080/gp/index.json", ServeurGp.index("http://192.168.1.50:8080/"))
    }
}
