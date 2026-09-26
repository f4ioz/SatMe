/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SkedVisee
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le banc de la fenêtre mise en avant après un calcul de sked. */
class SkedViseeTest {

    private val min = 60_000L
    private val t = 1_800_000_000_000L

    /** Trois créneaux mutuels sur les 48 h : ce soir, demain matin, demain soir. */
    private val fenetres = listOf(
        t + 60 * min..t + 75 * min,
        t + 600 * min..t + 618 * min,
        t + 1_300 * min..t + 1_312 * min)

    @Test
    fun sans_visee_c_est_le_prochain_creneau() {
        assertEquals(0, SkedVisee.index(fenetres, null))
    }

    /** Le défaut rapporté : l'annonce de mercredi matin ouvrait sur ce soir. */
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
     * Les éléments orbitaux vieillissent et le créneau local glisse de
     * quelques minutes. Une visée qui tombe juste à côté doit désigner la
     * fenêtre voisine, pas la première de la liste : on la reconnaît d'un coup
     * d'œil, alors que la première venue n'a aucun rapport.
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
