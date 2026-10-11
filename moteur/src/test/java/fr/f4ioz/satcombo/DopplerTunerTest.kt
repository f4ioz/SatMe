/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.DopplerTuner
import fr.f4ioz.satcombo.domain.PassPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Splitting work between the tuner PLL and the software mixer. The key test
 * replays a real ISS pass and counts PLL reprogrammings — something no code
 * review can tell you.
 */
class DopplerTunerTest {

    private val rest = 435_500_000L

    @Test
    fun le_premier_accord_programme_la_pll() {
        // Zero means "never tuned".
        val p = DopplerTuner.plan(rest, 0L, 0L)
        assertTrue(p.retune)
        assertEquals(rest, p.pllHz)
        assertEquals(0L, p.fineHz)
    }

    @Test
    fun un_ecart_modeste_ne_touche_pas_au_tuner() {
        val p = DopplerTuner.plan(rest + 9_000L, rest, 0L)
        assertTrue("la PLL n'avait aucune raison de bouger", !p.retune)
        assertEquals(rest, p.pllHz)
        assertEquals(9_000L, p.fineHz)
        // Sign check: listening higher shifts upward.
        assertTrue(DopplerTuner.plan(rest - 9_000L, rest, 0L).fineHz == -9_000L)
    }

    @Test
    fun sous_la_bande_morte_on_ne_touche_a_rien() {
        // Correcting 3 Hz costs more than ignoring it: the current offset is
        // returned unchanged so the caller compares equal and writes nothing.
        val p = DopplerTuner.plan(rest + 9_003L, rest, 9_000L)
        assertEquals(9_000L, p.fineHz)
        assertTrue(!p.retune)
        // 10 Hz does go through.
        assertEquals(9_010L, DopplerTuner.plan(rest + 9_010L, rest, 9_000L).fineHz)
    }

    @Test
    fun au_dela_du_seuil_on_recentre_et_l_on_repart_de_zero() {
        val p = DopplerTuner.plan(rest + 31_000L, rest, 25_000L)
        assertTrue("il fallait recentrer", p.retune)
        assertEquals(rest + 31_000L, p.pllHz)
        // Recentre on the wanted frequency, not the rest frequency, so the pass
        // continues with the full margin ahead.
        assertEquals(0L, p.fineHz)
        // Just under the threshold, still no move.
        assertTrue(!DopplerTuner.plan(rest + 30_000L, rest, 0L).retune)
    }

    @Test
    fun la_butee_physique_est_respectee() {
        assertEquals(DopplerTuner.FINE_MAX_HZ, DopplerTuner.clampFine(200_000L))
        assertEquals(-DopplerTuner.FINE_MAX_HZ, DopplerTuner.clampFine(-200_000L))
        assertTrue(DopplerTuner.fits(80_000L))
        assertTrue(!DopplerTuner.fits(80_001L))
        // The recentre threshold must sit inside the hard limit, or the PLL
        // would always move too late.
        assertTrue(DopplerTuner.FINE_LIMIT_HZ < DopplerTuner.FINE_MAX_HZ)
    }

    @Test
    fun une_consigne_absurde_ne_fait_rien() {
        val p = DopplerTuner.plan(0L, rest, 1_234L)
        assertTrue(!p.retune)
        assertEquals(rest, p.pllHz)
        assertEquals(1_234L, p.fineHz)
    }

    @Test
    fun un_vrai_passage_d_iss_ne_reprogramme_la_pll_qu_une_fois() {
        val iss = TleEntry(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
            line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303"
        )
        val paris = Observer(48.80, 2.48, 50.0, "JN18FS")
        val predictor = PassPredictor()
        val pass = predictor.upcomingPasses(iss, paris, 1704110400000L, 48, 20.0)
            .maxByOrNull { it.maxElevationDeg }
        assertTrue("aucun passage exploitable", pass != null)

        // Second by second, exactly like live tracking.
        val samples = predictor.samplePositions(iss, paris,
            pass!!.aosEpochMs, pass.losEpochMs, 1_000L)
        assertTrue("passage trop court : ${samples.size} s", samples.size > 300)

        var pll = 0L
        var fine = 0L
        var writes = 0
        var worstFine = 0L
        for (s in samples) {
            val want = Doppler.downlink(rest, s.rangeRateKmS)
            val p = DopplerTuner.plan(want, pll, fine)
            if (p.retune) { pll = p.pllHz; writes++ }
            fine = p.fineHz
            worstFine = maxOf(worstFine, abs(fine))
            // At every instant, what we hear is what we wanted to hear.
            assertTrue("écart résiduel de ${want - (pll + fine)} Hz",
                abs(want - (pll + fine)) < DopplerTuner.DEADBAND_HZ)
        }
        assertEquals("reprogrammations de PLL sur un passage entier", 1, writes)
        // On 435 MHz the swing nears ±10 kHz: the fine offset works but never
        // gets near its limit.
        assertTrue("décalage maximal atteint : $worstFine Hz",
            worstFine in 3_000L..DopplerTuner.FINE_LIMIT_HZ)
        assertTrue(worstFine < DopplerTuner.FINE_MAX_HZ / 2)
    }
}
