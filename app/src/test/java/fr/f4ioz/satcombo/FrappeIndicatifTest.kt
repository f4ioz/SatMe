/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le banc de l'ordre de frappe.
 *
 * `DL/PA3GAN`, tapé lettre par lettre dans le bon ordre, sortait
 * `DLA3GAN/P` : dès que le texte contenait `DL/P`, il se lisait comme
 * « DL en portable » et toutes les lettres suivantes passaient devant la
 * barre.
 *
 * Le texte ne peut pas trancher : la touche `/P /M` et la touche `/`
 * produisent exactement la même barre. C'est le geste qui sait, et c'est donc
 * le geste qu'on interroge.
 */
class FrappeIndicatifTest {

    /** Frappe une suite de lettres, la marque de suffixe restant à faux. */
    private fun tape(debut: String, lettres: String, suffixePose: Boolean = false): String {
        var s = debut
        lettres.forEach { s = Indicatifs.ajoute(s, it, suffixePose) }
        return s
    }

    // ------------------------------------------------ le préfixe de pays

    /** Le défaut rapporté le 25 août, sur FO-29. */
    @Test
    fun un_prefixe_de_pays_s_ecrit_dans_l_ordre() {
        assertEquals("DL/PA3GAN", tape("DL/", "PA3GAN"))
    }

    /** La lettre qui suit la barre est celle qui déclenchait tout. */
    @Test
    fun la_lettre_juste_apres_la_barre_reste_apres() {
        assertEquals("DL/P", tape("DL/", "P"))
    }

    @Test
    fun les_autres_prefixes_suivent_la_meme_regle() {
        assertEquals("F/DF2ET", tape("F/", "DF2ET"))
        assertEquals("EA6/DF2ET", tape("EA6/", "DF2ET"))
        // Un M après la barre ne fait pas davantage un mobile.
        assertEquals("LA/M0NKC", tape("LA/", "M0NKC"))
    }

    @Test
    fun un_indicatif_sans_barre_s_ecrit_dans_l_ordre() {
        assertEquals("F1FPL", tape("", "F1FPL"))
    }

    // ------------------------------------------------ le suffixe posé

    /**
     * L'autre moitié de la règle, et la raison pour laquelle l'insertion
     * existe : on pose le portable dès qu'on l'entend, puis on finit
     * l'indicatif devant.
     */
    @Test
    fun un_suffixe_pose_reste_au_bout() {
        assertEquals("F4IOZX/P", Indicatifs.ajoute("F4IOZ/P", 'X', suffixePose = true))
    }

    /**
     * Le portable posé **avant** l'indicatif, sur un champ vide. Le cas
     * paraissait théorique ; il est celui de l'opérateur qui entend « portable »
     * en premier et pose la marque avant que l'indicatif ne soit complet.
     * Il rendait `/PF4IOZ`.
     */
    @Test
    fun un_indicatif_se_complete_entierement_devant_le_suffixe() {
        assertEquals("F4IOZ/P", tape("/P", "F4IOZ", suffixePose = true))
    }

    /** Préfixe de pays **et** suffixe d'exploitation : chacun à sa place. */
    @Test
    fun le_suffixe_tient_meme_avec_un_prefixe_devant() {
        assertEquals("DL/PA3GANX/P",
            Indicatifs.ajoute("DL/PA3GAN/P", 'X', suffixePose = true))
    }

    // ------------------------------------------------ la lecture du texte

    /** Ce que `separe` doit continuer de dire, puisque tout en dépend. */
    @Test
    fun un_prefixe_n_est_pas_un_suffixe() {
        assertEquals("DL/PA3GAN" to "", Indicatifs.separe("DL/PA3GAN"))
        assertEquals("DL/PA3GAN" to "/P", Indicatifs.separe("DL/PA3GAN/P"))
        assertEquals("F4IOZ" to "/P", Indicatifs.separe("F4IOZ/P"))
    }

    /**
     * `DL/PA3GAN` et `PA3GAN` sont deux entités distinctes : le carnet ne doit
     * pas proposer le carré de l'une pour l'autre.
     */
    @Test
    fun le_prefixe_fait_une_entree_a_part() {
        assertEquals("DL/PA3GAN", Indicatifs.cle("DL/PA3GAN"))
        assertEquals("PA3GAN", Indicatifs.cle("PA3GAN"))
    }
}
