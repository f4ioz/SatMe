/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SkedVisee
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which window is highlighted after a sked computation. */
class SkedViseeTest {

    private val min = 60_000L
    private val t = 1_800_000_000_000L

    /** Three mutual windows over 48 h: tonight, tomorrow morning, tomorrow evening. */
    private val fenetres = listOf(
        t + 60 * min..t + 75 * min,
        t + 600 * min..t + 618 * min,
        t + 1_300 * min..t + 1_312 * min)

    @Test
    fun sans_visee_c_est_le_prochain_creneau() {
        assertEquals(0, SkedVisee.index(fenetres, null))
    }

    /** The reported bug: a Wednesday-morning announcement opened on tonight. */
    @Test
    fun la_visee_designe_la_fenetre_qui_la_contient() {
        assertEquals(1, SkedVisee.index(fenetres, t + 605 * min))
    }

    @Test
    fun une_visee_sur_le_bord_compte_comme_dedans() {
        assertEquals(1, SkedVisee.index(fenetres, t + 600 * min))
        assertEquals(1, SkedVisee.index(fenetres, t + 618 * min))
    }

    /**
     * Orbital elements age and the local window drifts by a few minutes. A
     * target just outside must pick the neighbouring window, not the first in
     * the list: the operator recognises the former, the latter is unrelated.
     */
    @Test
    fun une_visee_juste_a_cote_prend_la_fenetre_voisine() {
        assertEquals(1, SkedVisee.index(fenetres, t + 597 * min))
        assertEquals(1, SkedVisee.index(fenetres, t + 621 * min))
    }

    @Test
    fun une_visee_lointaine_prend_tout_de_meme_la_plus_proche() {
        assertEquals(2, SkedVisee.index(fenetres, t + 1_500 * min))
        assertEquals(0, SkedVisee.index(fenetres, t - 500 * min))
    }

    @Test
    fun sans_fenetre_l_indice_reste_valide() {
        assertEquals(0, SkedVisee.index(emptyList(), t))
        assertEquals(0, SkedVisee.index(emptyList(), null))
    }

    @Test
    fun l_indice_reste_dans_les_bornes() {
        listOf(null, t, t - 10_000 * min, t + 10_000 * min).forEach { visee ->
            val i = SkedVisee.index(fenetres, visee)
            assert(i in fenetres.indices) { "indice hors bornes : $i" }
        }
    }
}
