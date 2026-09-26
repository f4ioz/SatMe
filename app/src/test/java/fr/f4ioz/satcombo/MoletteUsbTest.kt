/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MoletteUsb
import fr.f4ioz.satcombo.domain.MoletteUsb.Geste
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue

/** Le banc de la molette USB détournée en commande de VFO. */
class MoletteUsbTest {

    @Test
    fun tourner_deplace_le_vfo_dans_les_deux_sens() {
        assertEquals(Geste.Bouge(100L),
            MoletteUsb.geste(MoletteUsb.VOLUME_UP, actif = true, externe = true, pasHz = 100L))
        assertEquals(Geste.Bouge(-100L),
            MoletteUsb.geste(MoletteUsb.VOLUME_DOWN, actif = true, externe = true, pasHz = 100L))
    }

    @Test
    fun l_appui_change_le_pas() {
        assertEquals(Geste.ChangePas,
            MoletteUsb.geste(MoletteUsb.VOLUME_MUTE, actif = true, externe = true, pasHz = 100L))
    }

    /**
     * **La garde qui rend la fonction acceptable.**
     *
     * Sans elle, activer la molette confisquerait les boutons de volume du
     * téléphone : l'opérateur ne pourrait plus régler son écoute, ce qui sur
     * une application de trafic serait absurde.
     */
    @Test
    fun les_boutons_du_telephone_restent_au_telephone() {
        assertEquals(Geste.Ignore,
            MoletteUsb.geste(MoletteUsb.VOLUME_UP, actif = true, externe = false, pasHz = 100L))
        assertEquals(Geste.Ignore,
            MoletteUsb.geste(MoletteUsb.VOLUME_MUTE, actif = true, externe = false, pasHz = 100L))
    }

    /** Réglage fermé : qui n'a pas de molette ne doit rien perdre. */
    @Test
    fun sans_le_reglage_tout_passe_au_systeme() {
        assertEquals(Geste.Ignore,
            MoletteUsb.geste(MoletteUsb.VOLUME_UP, actif = false, externe = true, pasHz = 100L))
    }

    @Test
    fun les_autres_touches_ne_nous_concernent_pas() {
        assertEquals(Geste.Ignore,
            MoletteUsb.geste(66, actif = true, externe = true, pasHz = 100L))
    }

    @Test
    fun les_pas_defilent_en_boucle() {
        assertEquals(100L, MoletteUsb.pasSuivant(10L))
        assertEquals(1_000L, MoletteUsb.pasSuivant(100L))
        assertEquals(10L, MoletteUsb.pasSuivant(1_000L))
    }

    /** Un réglage abîmé ne doit pas figer la molette sur un pas introuvable. */
    @Test
    fun un_pas_inconnu_revient_au_premier() {
        assertEquals(10L, MoletteUsb.pasSuivant(7L))
        assertEquals(10L, MoletteUsb.pasSuivant(0L))
    }
}

/**
 * Le banc du boîtier à trois touches.
 *
 * Il garde surtout deux gardes : une touche non apprise ne déclenche jamais
 * rien, et les touches qui permettent de sortir de l'application ne sont pas
 * apprenables.
 */
class MoletteBoitierTest {

    private fun touches() = MoletteUsb.Touches(
        codeA = 131, cibleA = MoletteUsb.Cible.SHIFT_RX,
        codeB = 132, cibleB = MoletteUsb.Cible.SHIFT_TX,
        codeC = 133, cibleC = MoletteUsb.Cible.VFO)

    @org.junit.Test
    fun chaque_touche_choisit_sa_cible() {
        assertEquals(MoletteUsb.Geste.ChoisitCible(MoletteUsb.Cible.SHIFT_RX),
            MoletteUsb.geste(131, true, true, 100L, touches()))
        assertEquals(MoletteUsb.Geste.ChoisitCible(MoletteUsb.Cible.SHIFT_TX),
            MoletteUsb.geste(132, true, true, 100L, touches()))
        assertEquals(MoletteUsb.Geste.ChoisitCible(MoletteUsb.Cible.VFO),
            MoletteUsb.geste(133, true, true, 100L, touches()))
    }

    @org.junit.Test
    fun la_molette_tourne_toujours() {
        assertEquals(MoletteUsb.Geste.Bouge(100L), MoletteUsb.geste(24, true, true, 100L, touches()))
        assertEquals(MoletteUsb.Geste.Bouge(-100L), MoletteUsb.geste(25, true, true, 100L, touches()))
    }

    @org.junit.Test
    fun une_touche_non_apprise_ne_declenche_rien() {
        // Trois zéros ne doivent pas répondre au même code, sinon la cible
        // changerait à chaque frappe du boîtier.
        val vierges = MoletteUsb.Touches()
        assertEquals(MoletteUsb.Geste.Ignore, MoletteUsb.geste(0, true, true, 100L, vierges))
        assertEquals(MoletteUsb.Geste.Bouge(100L), MoletteUsb.geste(24, true, true, 100L, vierges))
    }

    @org.junit.Test
    fun une_touche_apprise_sur_le_volume_prend_le_pas() {
        // Le cas courant : un boîtier de macros n'a souvent que le volume à
        // offrir. Si l'opérateur l'a appris, c'est qu'il veut la cible.
        val t = MoletteUsb.Touches(codeA = 24, cibleA = MoletteUsb.Cible.SHIFT_TX)
        assertEquals(MoletteUsb.Geste.ChoisitCible(MoletteUsb.Cible.SHIFT_TX),
            MoletteUsb.geste(24, true, true, 100L, t))
    }

    @org.junit.Test
    fun les_touches_du_telephone_restent_au_telephone() {
        assertEquals(MoletteUsb.Geste.Ignore, MoletteUsb.geste(131, true, false, 100L, touches()))
    }

    @org.junit.Test
    fun le_reglage_ferme_laisse_tout_passer() {
        assertEquals(MoletteUsb.Geste.Ignore, MoletteUsb.geste(131, false, true, 100L, touches()))
    }

    @org.junit.Test
    fun on_ne_peut_pas_confisquer_la_sortie() {
        assertTrue(!MoletteUsb.apprenable(4))      // retour
        assertTrue(!MoletteUsb.apprenable(3))      // accueil
        assertTrue(!MoletteUsb.apprenable(187))    // applications récentes
        assertTrue(!MoletteUsb.apprenable(0))
        assertTrue(MoletteUsb.apprenable(24))
        assertTrue(MoletteUsb.apprenable(131))
    }

    // ---- le poussoir de la molette ----

    @Test
    fun le_poussoir_vaut_sourdine_par_defaut() {
        // Le comportement d'avant est conservé sans cas particulier : c'est la
        // même règle, avec une valeur par défaut.
        val t = MoletteUsb.Touches()
        assertEquals(MoletteUsb.Geste.ChangePas,
            MoletteUsb.geste(MoletteUsb.VOLUME_MUTE, true, true, 100L, t))
    }

    @Test
    fun le_poussoir_appris_ailleurs_garde_son_action() {
        val t = MoletteUsb.Touches(codeD = 66, actionD = MoletteUsb.Action.CIBLE)
        assertEquals(MoletteUsb.Geste.CibleSuivante,
            MoletteUsb.geste(66, true, true, 100L, t))
        // Et « Sourdine » ne fait plus rien : une seule touche pour l'appui.
        assertEquals(MoletteUsb.Geste.Ignore,
            MoletteUsb.geste(MoletteUsb.VOLUME_MUTE, true, true, 100L, t))
    }

    @Test
    fun le_poussoir_peut_remettre_a_zero() {
        val t = MoletteUsb.Touches(codeD = 67, actionD = MoletteUsb.Action.ZERO)
        assertEquals(MoletteUsb.Geste.RemetZero,
            MoletteUsb.geste(67, true, true, 100L, t))
    }

    @Test
    fun une_touche_de_cible_lemporte_sur_le_poussoir() {
        // Si la même touche était apprise deux fois, c'est la cible qui gagne :
        // sans ordre défini, le boîtier ferait tantôt l'un tantôt l'autre.
        val t = MoletteUsb.Touches(codeA = 30, codeD = 30)
        assertEquals(MoletteUsb.Geste.ChoisitCible(MoletteUsb.Cible.SHIFT_RX),
            MoletteUsb.geste(30, true, true, 100L, t))
    }

    @Test
    fun un_poussoir_non_appris_ne_declenche_rien() {
        val t = MoletteUsb.Touches(codeD = 0)
        assertEquals(MoletteUsb.Geste.Ignore,
            MoletteUsb.geste(MoletteUsb.VOLUME_MUTE, true, true, 100L, t))
    }

    @Test
    fun les_cibles_defilent_en_boucle() {
        assertEquals("SHIFT_TX", MoletteUsb.cibleSuivante("SHIFT_RX"))
        assertEquals("VFO", MoletteUsb.cibleSuivante("SHIFT_TX"))
        assertEquals("SHIFT_RX", MoletteUsb.cibleSuivante("VFO"))
        // Une valeur abîmée ne fige pas le défilement.
        assertEquals("SHIFT_RX", MoletteUsb.cibleSuivante("n'importe quoi"))
    }
}
