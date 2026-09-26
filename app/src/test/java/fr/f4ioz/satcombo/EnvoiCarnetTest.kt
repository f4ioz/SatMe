/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.EnvoiCarnet
import fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le banc de l'envoi vers Wavelog. */
class EnvoiCarnetTest {

    private fun f(t: Long, ind: String = "F1FPL", envoye: Long = 0L) = Fiche(t, ind, envoye)

    // ------------------------------------------------------ la sélection

    @Test
    fun un_contact_neuf_attend_d_etre_depose() {
        assertEquals(listOf(10L), EnvoiCarnet.aDeposer(listOf(f(10))).map { it.timeMs })
    }

    /**
     * Wavelog ne dédoublonne pas : un contact déjà déposé qui repartirait
     * ferait un doublon à effacer à la main sur l'interface web.
     */
    @Test
    fun un_contact_deja_depose_ne_repart_pas() {
        assertEquals(emptyList<Long>(),
            EnvoiCarnet.aDeposer(listOf(f(10, envoye = 99))).map { it.timeMs })
    }

    /** Même règle que le fichier ADIF : sans indicatif, ce n'est pas un contact. */
    @Test
    fun un_contact_sans_indicatif_ne_part_jamais() {
        assertEquals(emptyList<Long>(),
            EnvoiCarnet.aDeposer(listOf(f(10, ind = ""))).map { it.timeMs })
    }

    /**
     * Du plus ancien au plus récent : si l'envoi s'arrête en chemin, ce qui
     * est parti forme un bloc continu et non un carnet troué.
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

    // --------------------------------------------------------- le bilan

    @Test
    fun le_bilan_compte_ce_qui_est_passe() {
        val j = listOf(f(1), f(2), f(3))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L, 2L), refuses = 0)
        assertEquals(2, b.deposes)
        assertEquals(1, b.restants)
    }

    /**
     * Un refus ne marque rien : le contact repartira au prochain essai. Il ne
     * doit donc pas être compté deux fois — ni comme déposé, ni retiré de ce
     * qui reste.
     */
    @Test
    fun un_refus_reste_a_deposer() {
        val j = listOf(f(1), f(2))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L), refuses = 1)
        assertEquals(1, b.deposes)
        assertEquals(1, b.refuses)
        assertEquals(1, b.restants)
    }

    /** Une coupure au premier contact ne dépose rien et ne perd rien. */
    @Test
    fun une_coupure_immediate_laisse_tout_en_attente() {
        val j = listOf(f(1), f(2), f(3))
        val b = EnvoiCarnet.bilan(j, acceptes = emptySet(), refuses = 1)
        assertEquals(0, b.deposes)
        assertEquals(3, b.restants)
    }

    /** Un acquittement portant sur un contact déjà déposé ne compte pas double. */
    @Test
    fun un_acquittement_hors_lot_ne_compte_pas() {
        val j = listOf(f(1), f(2, envoye = 50))
        val b = EnvoiCarnet.bilan(j, acceptes = setOf(1L, 2L), refuses = 0)
        assertEquals(1, b.deposes)
        assertEquals(0, b.restants)
    }
}
