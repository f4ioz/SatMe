/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.EchelleSdr.Accord
import fr.f4ioz.satcombo.domain.EchelleSdr.Etat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDR scale's markers: their state, their Doppler band edges, what a click on them tunes. */
class EchelleSdrTest {

    private val t0 = 1_800_000_000_000L
    private fun fm(desc: String, mhz: Double) = Transmitter(desc, "FM", null, null, Math.round(mhz * 1e6), null, false, true, "Transmitter")
    private fun lineaire(desc: String, bas: Double, haut: Double) =
        Transmitter(desc, "USB", null, null, Math.round(bas * 1e6), Math.round(haut * 1e6), true, true, "Transponder")

    @Test fun etat_up_soon_down() {
        assertEquals(Etat.VISIBLE, EchelleSdr.etat(0.0, null, t0))
        assertEquals(Etat.VISIBLE, EchelleSdr.etat(42.0, t0 - 60_000, t0))
        assertEquals(Etat.BIENTOT, EchelleSdr.etat(-2.0, t0 + 4 * 60_000, t0))
        assertEquals(Etat.BIENTOT, EchelleSdr.etat(-2.0, t0 + 5 * 60_000, t0))
        assertEquals(Etat.SOUS, EchelleSdr.etat(-2.0, t0 + 5 * 60_000 + 1, t0))
        assertEquals(Etat.SOUS, EchelleSdr.etat(-30.0, null, t0))
        // An AOS already past while still below (a stale pass): down.
        assertEquals(Etat.SOUS, EchelleSdr.etat(-1.0, t0 - 1_000, t0))
    }

    @Test fun approaching_satellite_heard_higher_with_its_correction() {
        val s = EchelleSdr.Sat(1, "FM-SAT", 30.0, 120.0, rangeRateKmS = -6.0, rangeRate1sKmS = -5.99, correctionHz = 250)
        val m = EchelleSdr.marques(listOf(s to listOf(fm("FM", 145.900))), 145_800_000, 146_000_000, t0).single()
        val attendu = Doppler.downlink(145_900_000, -6.0) + 250
        assertEquals(attendu, m.basHz); assertEquals(m.basHz, m.hautHz)
        assertTrue("approaching: higher", m.basHz > 145_900_000)
        assertTrue(m.dopplerHz in 2_800..3_000)
        assertFalse(m.bande)
        // Approaching less fast a second later: the Doppler falls.
        assertTrue(m.derive < 0)
        assertEquals(Etat.VISIBLE, m.etat)
    }

    @Test fun transponder_edges_follow_the_doppler() {
        val tx = listOf(lineaire("Linear", 435.610, 435.670))
        val loin = EchelleSdr.Sat(2, "LIN", 10.0, 0.0, rangeRateKmS = 7.0)
        val m = EchelleSdr.marques(listOf(loin to tx), 435_500_000, 435_800_000, t0).single()
        assertTrue(m.bande)
        assertEquals(Doppler.downlink(435_610_000, 7.0), m.basHz)
        assertEquals(Doppler.downlink(435_670_000, 7.0), m.hautHz)
        assertTrue("receding: lower", m.hautHz < 435_670_000)
        assertEquals(435_610_000, m.reposBasHz); assertEquals(435_670_000, m.reposHautHz)
        // Whole band kept even when a part of it only falls in the window.
        val partiel = EchelleSdr.marques(listOf(loin to tx), 435_650_000, 435_900_000, t0).single()
        assertEquals(m.basHz, partiel.basHz)
        // Entirely out: nothing.
        assertTrue(EchelleSdr.marques(listOf(loin to tx), 435_700_000, 435_900_000, t0).isEmpty())
    }

    @Test fun dead_or_uplink_only_transmitters_are_skipped() {
        val s = EchelleSdr.Sat(3, "X", 5.0, 0.0, 0.0)
        val mort = fm("dead", 145.9).copy(alive = false)
        val montee = Transmitter("up", "FM", 145_900_000, null, null, null, false, true, "Transmitter")
        assertTrue(EchelleSdr.marques(listOf(s to listOf(mort, montee)), 145_000_000, 146_000_000, t0).isEmpty())
    }

    @Test fun followed_transmitter_flagged_and_drawn_last() {
        val a = EchelleSdr.Sat(10, "A", 20.0, 0.0, 0.0)
        val b = EchelleSdr.Sat(11, "B", -20.0, 0.0, 0.0)
        val l = EchelleSdr.marques(listOf(a to listOf(fm("A1", 145.85), fm("A2", 145.87)), b to listOf(fm("B1", 145.86))),
            145_800_000, 145_900_000, t0, suivi = 10 to "A2")
        assertEquals(listOf("B1", "A1", "A2"), l.map { it.emetteur })
        assertTrue(l.last().suivi); assertTrue(l.last().satSuivi)
        assertFalse(l[1].suivi); assertTrue(l[1].satSuivi)
    }

    @Test fun click_near_a_single_frequency_follows_it() {
        val up = EchelleSdr.Sat(20, "UP", 10.0, 0.0, 0.0)
        val down = EchelleSdr.Sat(21, "DOWN", -10.0, 0.0, 0.0)
        val l = EchelleSdr.marques(listOf(down to listOf(fm("D", 145.900)), up to listOf(fm("U", 145.9005))), 145_000_000, 146_000_000, t0)
        // Both within reach: the one up wins over the nearer one down.
        assertEquals(Accord.Emetteur(20, "U"), EchelleSdr.clic(145_900_000, l, 1_000))
        // Only the one down within reach: that one.
        assertEquals(Accord.Emetteur(21, "D"), EchelleSdr.clic(145_899_000, l, 1_000))
        assertEquals(Accord.Terrestre(145_950_000), EchelleSdr.clic(145_950_000, l, 1_000))
    }

    @Test fun click_in_a_band_keeps_the_place_in_its_rest_frame() {
        val s = EchelleSdr.Sat(30, "LIN", 25.0, 0.0, rangeRateKmS = -5.0, correctionHz = 100)
        val m = EchelleSdr.marques(listOf(s to listOf(lineaire("Linear", 435.800, 435.900))), 435_000_000, 436_000_000, t0).single()
        val ici = m.basHz + (m.hautHz - m.basHz) / 4
        val a = EchelleSdr.clic(ici, listOf(m), 500) as Accord.Bande
        assertEquals(30, a.catnum); assertEquals("Linear", a.emetteur)
        // A quarter into the band at rest too (within a hertz or two)…
        assertEquals(435_825_000.0, a.reposHz.toDouble(), 3.0)
        // …and heard exactly where clicked once the receiver applies Doppler and correction.
        assertEquals(ici.toDouble(), EchelleSdr.entendu(a.reposHz, s.rangeRateKmS, s.correctionHz).toDouble(), 2.0)
        // Later, the Doppler changed: the same rest frequency moves with the band's edges.
        val apres = s.copy(rangeRateKmS = 3.0)
        val m2 = EchelleSdr.marques(listOf(apres to listOf(lineaire("Linear", 435.800, 435.900))), 435_000_000, 436_000_000, t0).single()
        val ou = EchelleSdr.entendu(a.reposHz, apres.rangeRateKmS, apres.correctionHz)
        assertEquals((ou - m2.basHz).toDouble() / (m2.hautHz - m2.basHz), 0.25, 0.001)
    }

    @Test fun single_frequency_beats_the_band_around_it() {
        val s = EchelleSdr.Sat(40, "LIN", 25.0, 0.0, 0.0)
        val l = EchelleSdr.marques(listOf(s to listOf(lineaire("Linear", 145.925, 145.975), fm("Beacon", 145.950))), 145_000_000, 146_000_000, t0)
        assertEquals(Accord.Emetteur(40, "Beacon"), EchelleSdr.clic(145_950_200, l, 500))
        assertTrue(EchelleSdr.clic(145_940_000, l, 500) is Accord.Bande)
        assertEquals(Accord.Terrestre(145_980_000), EchelleSdr.clic(145_980_000, l, 500))
    }

    @Test fun json_carries_what_the_tooltip_says() {
        val s = EchelleSdr.Sat(50, "ISS", 12.34, 211.6, -3.0, aosMs = t0 - 60_000, losMs = t0 + 300_000, correctionHz = -40, favori = true)
        val o = EchelleSdr.json(EchelleSdr.marques(listOf(s to listOf(fm("SSTV", 145.800))), 145_700_000, 145_900_000, t0, 50 to "SSTV"), t0)
        val m = o.getJSONArray("marques").getJSONObject(0)
        assertEquals("ISS", m.getString("nom")); assertEquals("visible", m.getString("etat"))
        assertTrue(m.getBoolean("fav")); assertTrue(m.getBoolean("suivi"))
        assertEquals(12.3, m.getDouble("el"), 1e-9); assertEquals(212, m.getInt("az"))
        assertEquals(-40, m.getLong("corr")); assertEquals(t0 + 300_000, m.getLong("los"))
    }
}
