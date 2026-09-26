/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Passage
import fr.f4ioz.satcombo.domain.Passage.Fenetre
import fr.f4ioz.satcombo.domain.Passage.Inscrit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "already worked on this pass" list. */
class PassageTest {

    private val min = 60_000L
    private val t = 1_800_000_000_000L      // reference instant
    private val passage = Fenetre(t - 10 * min, t + 20 * min)

    private fun inscrit(quand: Long, ind: String, el: Double = 30.0,
                        sat: String = "RS-44") = Inscrit(quand, sat, el, ind)

    // ------------------------------------------------------- the window

    @Test
    fun le_passage_en_cours_est_celui_qui_contient_l_instant() {
        val fenetres = listOf(
            Fenetre(t - 200 * min, t - 150 * min),
            passage,
            Fenetre(t + 100 * min, t + 130 * min))
        assertEquals(passage, Passage.enCours(fenetres, t))
    }

    @Test
    fun sous_l_horizon_il_n_y_a_pas_de_passage() {
        val fenetres = listOf(Fenetre(t - 200 * min, t - 150 * min))
        assertNull(Passage.enCours(fenetres, t))
    }

    @Test
    fun sans_prediction_il_n_y_a_pas_de_passage() {
        assertNull(Passage.enCours(emptyList(), t))
    }

    // ------------------------------------------------------ the callsigns

    @Test
    fun un_contact_du_passage_compte() {
        val j = listOf(inscrit(t - 5 * min, "F1FPL"))
        assertEquals(listOf("F1FPL"), Passage.indicatifs(j, "RS-44", passage))
    }

    /**
     * Bug of 25 August: a contact made forty minutes earlier, satellite below
     * the horizon, was shown as worked on this pass.
     */
    @Test
    fun un_contact_d_avant_l_acquisition_n_est_pas_de_ce_passage() {
        val j = listOf(inscrit(t - 40 * min, "F1FPL", el = -70.0))
        assertEquals(emptyList<String>(), Passage.indicatifs(j, "RS-44", passage))
    }

    /** Even inside the window, an entry below the horizon is a bench test. */
    @Test
    fun un_contact_sous_l_horizon_ne_compte_pas() {
        val j = listOf(inscrit(t - 5 * min, "F1FPL", el = -12.0))
        assertEquals(emptyList<String>(), Passage.indicatifs(j, "RS-44", passage))
    }

    @Test
    fun sans_passage_en_cours_la_liste_est_vide() {
        val j = listOf(inscrit(t - 5 * min, "F1FPL"))
        assertEquals(emptyList<String>(), Passage.indicatifs(j, "RS-44", null))
    }

    @Test
    fun un_autre_satellite_n_est_pas_le_meme_passage() {
        val j = listOf(inscrit(t - 5 * min, "F5RRO", sat = "SO-50"))
        assertEquals(emptyList<String>(), Passage.indicatifs(j, "RS-44", passage))
    }

    @Test
    fun une_entree_sans_indicatif_ne_compte_pas() {
        val j = listOf(inscrit(t - 5 * min, ""))
        assertEquals(emptyList<String>(), Passage.indicatifs(j, "RS-44", passage))
    }

    /** A pass lasting several hours is not cut off after one hour. */
    @Test
    fun un_passage_long_garde_ses_contacts_du_debut() {
        val longue = Fenetre(t - 180 * min, t + 60 * min)
        val j = listOf(inscrit(t - 150 * min, "F5RRO"), inscrit(t - 5 * min, "F1FPL"))
        assertEquals(listOf("F1FPL", "F5RRO"), Passage.indicatifs(j, "RS-44", longue))
    }

    @Test
    fun le_plus_recent_vient_en_tete_et_sans_doublon() {
        val j = listOf(
            inscrit(t - 8 * min, "F5RRO"),
            inscrit(t - 2 * min, "F1FPL"),
            inscrit(t - 6 * min, "F5RRO"))
        assertEquals(listOf("F1FPL", "F5RRO"), Passage.indicatifs(j, "RS-44", passage))
    }
}
