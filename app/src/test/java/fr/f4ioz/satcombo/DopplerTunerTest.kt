/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le partage du travail entre la PLL et le mélangeur logiciel.
 *
 * L'essai qui compte est le dernier : il rejoue un vrai passage d'ISS seconde
 * par seconde et compte les reprogrammations du tuner. C'est le genre
 * d'affirmation qu'aucune relecture de code ne donne et qu'un compteur donne en
 * une seconde.
 */
class DopplerTunerTest {

    private val rest = 435_500_000L

    @Test
    fun le_premier_accord_programme_la_pll() {
        // Zéro veut dire « jamais accordé » : il faut bien commencer quelque part.
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
        // Et le signe suit le bon sens : écouter plus haut, décaler vers le haut.
        assertTrue(DopplerTuner.plan(rest - 9_000L, rest, 0L).fineHz == -9_000L)
    }

    @Test
    fun sous_la_bande_morte_on_ne_touche_a_rien() {
        // Corriger de trois hertz coûte plus cher que de les ignorer : le
        // décalage rendu est celui déjà en place, à l'identique, pour que
        // l'appelant compare et n'écrive rien.
        val p = DopplerTuner.plan(rest + 9_003L, rest, 9_000L)
        assertEquals(9_000L, p.fineHz)
        assertTrue(!p.retune)
        // Dix hertz, en revanche, passent.
        assertEquals(9_010L, DopplerTuner.plan(rest + 9_010L, rest, 9_000L).fineHz)
    }

    @Test
    fun au_dela_du_seuil_on_recentre_et_l_on_repart_de_zero() {
        val p = DopplerTuner.plan(rest + 31_000L, rest, 25_000L)
        assertTrue("il fallait recentrer", p.retune)
        assertEquals(rest + 31_000L, p.pllHz)
        // On recentre sur la fréquence voulue, et non sur le repos : le passage
        // repart avec toute la marge devant lui.
        assertEquals(0L, p.fineHz)
        // Juste en dessous du seuil, on ne bouge toujours pas.
        assertTrue(!DopplerTuner.plan(rest + 30_000L, rest, 0L).retune)
    }

    @Test
    fun la_butee_physique_est_respectee() {
        assertEquals(DopplerTuner.FINE_MAX_HZ, DopplerTuner.clampFine(200_000L))
        assertEquals(-DopplerTuner.FINE_MAX_HZ, DopplerTuner.clampFine(-200_000L))
        assertTrue(DopplerTuner.fits(80_000L))
        assertTrue(!DopplerTuner.fits(80_001L))
        // Le seuil de recentrage reste bien à l'intérieur de la butée, sans quoi
        // la PLL bougerait toujours trop tard.
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

        // Seconde par seconde, exactement comme le fait le suivi en direct.
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
            // Et à tout instant, ce qu'on écoute reste ce qu'on voulait écouter.
            assertTrue("écart résiduel de ${want - (pll + fine)} Hz",
                abs(want - (pll + fine)) < DopplerTuner.DEADBAND_HZ)
        }
        assertEquals("reprogrammations de PLL sur un passage entier", 1, writes)
        // Sur 435 MHz l'excursion approche les dix kilohertz de part et d'autre :
        // le décalage fin travaille, mais n'approche jamais sa butée.
        assertTrue("décalage maximal atteint : $worstFine Hz",
            worstFine in 3_000L..DopplerTuner.FINE_LIMIT_HZ)
        assertTrue(worstFine < DopplerTuner.FINE_MAX_HZ / 2)
    }
}
