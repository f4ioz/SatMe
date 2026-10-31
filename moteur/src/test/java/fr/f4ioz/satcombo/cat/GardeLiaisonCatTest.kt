/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import fr.f4ioz.satcombo.cat.GardeLiaisonCat.Geste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision alone, with a clock in hand: when to check, how long to wait, when to reopen. */
class GardeLiaisonCatTest {

    @Test
    fun vivant_puis_une_lecture_avant_de_dire_muet() {
        val g = GardeLiaisonCat()
        g.ouvert(0)
        assertEquals(Geste.TOUR, g.decide(3_000, 2_500, true, true))
        // 4 s without an answer: one read, not yet « silent ».
        assertEquals(Geste.SONDE, g.decide(6_600, 2_500, true, true))
        assertFalse(g.muet)
        // It answers: alive, the loop goes on.
        assertFalse(g.resultat(6_700, true))
        assertEquals(Geste.TOUR, g.decide(6_800, 2_500, true, true))
    }

    @Test
    fun muet_les_essais_s_espacent_jusqu_a_30_s_puis_le_port_est_rouvert() {
        val g = GardeLiaisonCat()
        g.ouvert(0)
        assertEquals(Geste.SONDE, g.decide(5_000, 1_000, true, true))
        assertFalse(g.resultat(5_000, false))
        assertTrue(g.muet)
        assertEquals(1_000L, g.muetDepuisMs)
        // Between tries nothing is sent.
        assertEquals(Geste.ATTENDS, g.decide(6_000, 1_000, true, true))
        var t = 5_000L
        val attentes = ArrayList<Long>()
        repeat(6) {
            var u = t + 100
            while (g.decide(u, 1_000, true, true) == Geste.ATTENDS) u += 100
            attentes += u - t
            g.resultat(u, false)
            t = u
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), attentes)
        assertTrue(g.message(t + 10_000, "IC-9700"), g.message(t + 10_000, "IC-9700").startsWith("IC-9700 muet depuis"))
        assertTrue(g.message(t + 10_000).contains("nouvel essai dans 20 s"))
    }

    @Test
    fun le_premier_essai_lit_seulement_les_suivants_rouvrent_le_port() {
        val g = GardeLiaisonCat()
        g.ouvert(0)
        g.decide(5_000, 0, true, true); g.resultat(5_000, false)
        assertEquals(Geste.SONDE, g.decide(7_000, 0, true, true))
        g.resultat(7_000, false)
        assertEquals(Geste.ROUVRE, g.decide(11_000, 0, true, true))
        // A network link is not reopened here: a read.
        assertEquals(Geste.SONDE, g.decide(11_000, 0, null, false))
    }

    @Test
    fun port_disparu_rien_tant_qu_il_manque_puis_rouvert_aussitot() {
        val g = GardeLiaisonCat()
        g.ouvert(0)
        assertEquals(Geste.ATTENDS, g.decide(5_000, 500, false, true))
        assertTrue(g.muet)
        assertTrue(g.message(20_000).contains("port USB a disparu"))
        assertEquals(Geste.ATTENDS, g.decide(60_000, 500, false, true))
        // Back: at once, whatever the backoff said.
        assertEquals(Geste.ROUVRE, g.decide(60_100, 500, true, true))
        assertTrue(g.resultat(60_300, true))
        assertNull(g.muetDepuisMs)
        assertEquals(1, g.reprises)
        assertEquals("", g.message(60_400))
    }

    @Test
    fun un_refus_est_date_et_oublie_apres_une_minute() {
        val g = GardeLiaisonCat()
        g.refus("fréquence 29,400 MHz", 1_000)
        assertEquals("refusé par le poste : fréquence 29,400 MHz (il y a 4 s)", g.messageRefus(5_000))
        assertEquals("", g.messageRefus(70_000))
    }

    @Test
    fun les_durees_en_mots() {
        assertEquals("à l'instant", GardeLiaisonCat.ilYa(1_200))
        assertEquals("il y a 12 s", GardeLiaisonCat.ilYa(12_000))
        assertEquals("il y a 4 min", GardeLiaisonCat.ilYa(4 * 60_000 + 20_000))
        assertEquals("2 min", GardeLiaisonCat.duree(120_000))
        assertEquals("1 h 05", GardeLiaisonCat.duree(65 * 60_000L))
    }
}
