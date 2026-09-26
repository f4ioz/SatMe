/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace colour in sun mode: darkened until readable on white, no further.
 *
 * The former threshold weighted gamma-encoded components: magenta ended at
 * 3.1:1, while orange was darkened to 10:1 and lost its hue.
 */
class ContrasteSoleilTest {

    private fun contraste(rgb: FloatArray) =
        ContrasteSoleil.contrasteSurBlanc(rgb[0], rgb[1], rgb[2])

    @Test
    fun toute_couleur_atteint_4_5_sur_blanc() {
        val pas = (0..20).map { it / 20f }
        for (r in pas) for (g in pas) for (b in pas) {
            val c = ContrasteSoleil.assombris(r, g, b)
            assertTrue("($r, $g, $b) → ${contraste(c)}:1", contraste(c) >= 4.5f)
        }
    }

    @Test
    fun le_magenta_et_le_rouge_passent() {
        assertTrue(contraste(ContrasteSoleil.assombris(1f, 0f, 1f)) >= 4.5f)
        assertTrue(contraste(ContrasteSoleil.assombris(1f, 0f, 0f)) >= 4.5f)
    }

    @Test
    fun une_couleur_n_est_pas_assombrie_plus_que_necessaire() {
        // Orange used to end at 10:1. Darkening stops just past 4.5:1.
        val c = ContrasteSoleil.assombris(1f, 0.6f, 0f)
        assertTrue("orange trop sombre : ${contraste(c)}:1", contraste(c) < 6f)
    }

    @Test
    fun une_couleur_deja_lisible_est_rendue_telle_quelle() {
        val c = ContrasteSoleil.assombris(0f, 0f, 0.5f)
        assertEquals(0f, c[0], 0f); assertEquals(0f, c[1], 0f); assertEquals(0.5f, c[2], 0f)
    }

    @Test
    fun le_blanc_de_reference_vaut_21_sur_1_pour_le_noir() {
        assertEquals(21f, ContrasteSoleil.contrasteSurBlanc(0f, 0f, 0f), 0.01f)
        assertEquals(1f, ContrasteSoleil.contrasteSurBlanc(1f, 1f, 1f), 0.01f)
    }
}
