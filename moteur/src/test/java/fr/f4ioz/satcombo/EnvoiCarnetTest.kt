/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.EnvoiCarnet
import fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche
import org.junit.Assert.assertEquals
import org.junit.Test

/** Uploading to Wavelog. */
class EnvoiCarnetTest {

    private fun f(t: Long, ind: String = "F1FPL", envoye: Long = 0L) = Fiche(t, ind, envoye)

    // ------------------------------------------------------ selection

    @Test
    fun un_contact_neuf_attend_d_etre_depose() {
        assertEquals(listOf(10L), EnvoiCarnet.aDeposer(listOf(f(10))).map { it.timeMs })
    }

    /**
     * Wavelog does not deduplicate: re-sending an uploaded contact creates a
     * duplicate to delete by hand in the web interface.
     */
    @Test
    fun un_contact_deja_depose_ne_repart_pas() {
        assertEquals(emptyList<Long>(),
            EnvoiCarnet.aDeposer(listOf(f(10, envoye = 99))).map { it.timeMs })
    }

    /** Same rule as the ADIF file: no callsign, no contact. */
    @Test
    fun un_contact_sans_indicatif_ne_part_jamais() {
        assertEquals(emptyList<Long>(),
            EnvoiCarnet.aDeposer(listOf(f(10, ind = ""))).map { it.timeMs })
    }

    /**
     * Oldest first: if the upload stops midway, what was sent is a contiguous
     * block, not a log full of holes.
     */
    @Test
    fun les_plus_anciens_partent_en_premier() {
        assertEquals(listOf(1L, 5L, 9L),
            EnvoiCarnet.aDeposer(listOf(f(9), f(1), f(5))).map { it.timeMs })
    }

    @Test
    fun le_compte_annonce_ce_qui_attend() {
        val j = listOf(f(1), f(2, envoye = 50), f(3), f(4, ind = ""))
        assertEquals(2, EnvoiCarnet.combienAttendent(j))
    }

    @Test
    fun un_carnet_entierement_depose_n_attend_rien() {
        assertEquals(0, EnvoiCarnet.combienAttendent(listOf(f(1, envoye = 9), f(2, envoye = 9))))
    }

    // --------------------------------------------------------- summary

    @Test
    fun le_bilan_compte_ce_qui_est_passe() {
        val j = listOf(f(1), f(2), f(3))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L, 2L), refuses = 0)
        assertEquals(2, b.deposes)
        assertEquals(1, b.restants)
    }

    /**
     * A rejection marks nothing: the contact goes again next time. So it is
     * neither counted as uploaded nor removed from what remains.
     */
    @Test
    fun un_refus_reste_a_deposer() {
        val j = listOf(f(1), f(2))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L), refuses = 1)
        assertEquals(1, b.deposes)
        assertEquals(1, b.refuses)
        assertEquals(1, b.restants)
    }

    /** A failure on the first contact uploads nothing and loses nothing. */
    @Test
    fun une_coupure_immediate_laisse_tout_en_attente() {
        val j = listOf(f(1), f(2), f(3))
        val b = EnvoiCarnet.bilan(j, acceptes = emptySet(), refuses = 1)
        assertEquals(0, b.deposes)
        assertEquals(3, b.restants)
    }

    /** An ack for an already-uploaded contact does not count twice. */
    @Test
    fun un_acquittement_hors_lot_ne_compte_pas() {
        val j = listOf(f(1), f(2, envoye = 50))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L, 2L), refuses = 0)
        assertEquals(1, b.deposes)
        assertEquals(0, b.restants)
    }
}
