/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Refreshing one satellite. The button used to query CelesTrak only, whatever
 * the sources chosen, and to fail in silence: with the address blocked by
 * CelesTrak, nothing ever happened, AMSAT bulletin or not.
 */
class RafraichissementTleTest {

    private val amsat = TleSource("amsat_gp", "AMSAT",
        "https://newark192.amsat.org/gpdata/current/daily-bulletin.json")
    private val amateur = TleSource("amateur", "Celestrak Amateur",
        "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json")
    private val stations = TleSource("stations", "Celestrak Stations",
        "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json")

    /** SO-50 with the given epoch field ("YYDDD.DDDDDDDD"). */
    private fun so50(epoque: String) = TleEntry(
        "SO-50",
        "1 27607U 02058C   $epoque  .00000000  00000-0  00000-0 0  9990",
        "2 27607  64.5555 100.0000 0050000 100.0000 260.0000 14.80000000000000")

    @Test
    fun avec_amsat_seul_celestrak_n_est_pas_interroge() {
        val a = RafraichissementTle.adresses(27607, listOf(amsat))
        assertEquals(listOf(amsat.url), a)
    }

    @Test
    fun celestrak_passe_par_la_requete_d_un_seul_satellite() {
        // Its group files are large, and CelesTrak blocks addresses that
        // fetch too often: one small query, once, whatever the groups enabled.
        val a = RafraichissementTle.adresses(27607, listOf(amsat, amateur, stations))
        assertEquals(2, a.size)
        assertEquals(amsat.url, a[0])
        assertTrue(a[1].contains("CATNR=27607"))
    }

    @Test
    fun celestrak_injoignable_n_empeche_pas_amsat() {
        val r = RafraichissementTle.plusRecent(27607,
            listOf("celestrak", "amsat")) { u ->
            if (u == "celestrak") throw IOException("connect timed out")
            listOf(so50("26265.18446013"))
        }
        assertTrue(r is RafraichissementTle.Resultat.Trouve)
    }

    @Test
    fun la_reponse_la_plus_recente_l_emporte() {
        val vieux = so50("26265.18446013")
        val recent = so50("26268.50000000")
        val r = RafraichissementTle.plusRecent(27607, listOf("a", "b")) { u ->
            if (u == "a") listOf(vieux) else listOf(recent)
        } as RafraichissementTle.Resultat.Trouve
        assertEquals(recent.line1, r.entree.line1)
    }

    @Test
    fun aucune_source_ne_repond() {
        val r = RafraichissementTle.plusRecent(27607, listOf("a", "b")) {
            throw IOException("blocked")
        }
        assertEquals(RafraichissementTle.Resultat.Injoignable, r)
    }

    @Test
    fun les_sources_repondent_sans_ce_satellite() {
        val r = RafraichissementTle.plusRecent(27607, listOf("a")) { emptyList() }
        assertEquals(RafraichissementTle.Resultat.Absent, r)
        assertFalse(r is RafraichissementTle.Resultat.Trouve)
    }
}
