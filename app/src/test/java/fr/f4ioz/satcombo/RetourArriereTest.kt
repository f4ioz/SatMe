/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.RetourArriere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le bouton retour ferme ce que l'on voit, et rien d'autre.
 *
 * L'ordre des branches d'un `when` ne se relit pas : il se constate quand le
 * mât s'arrête. Le 2 août, le retour essayait la fiche satellite avant les
 * écrans plein cadre ; sur l'écran Rotor il appelait donc `backToList()`, qui
 * arrête la poursuite, pendant que l'écran Rotor restait affiché. Aucune
 * alerte, aucun message : simplement « Aucun satellite suivi » et un mât qui
 * ne suit plus. Ces essais figent l'ordre.
 */
class RetourArriereTest {

    private fun geste(
        e: Screen,
        section: Boolean = false,
        selection: Boolean = false,
        modeSelection: Boolean = false
    ) = RetourArriere.geste(e, section, selection, modeSelection)

    @Test
    fun sur_l_ecran_rotor_le_retour_ferme_le_rotor_et_ne_touche_pas_a_la_poursuite() {
        // La panne du 2 août, en une ligne. Une fiche est ouverte dessous —
        // c'est le cas normal, on ouvre l'écran du mât depuis un satellite —
        // et pourtant c'est le rotor qui se ferme, parce que c'est lui qui est
        // dessiné.
        assertEquals(
            RetourArriere.Geste.FERMER_ROTOR,
            geste(Screen.ROTOR, selection = true))
    }

    @Test
    fun tous_les_ecrans_plein_cadre_passent_devant_la_fiche() {
        // La même faute peut se refaire sur n'importe lequel : chacun se
        // dessine par-dessus la fiche, chacun doit se fermer avant elle.
        val attendus = mapOf(
            Screen.LOCATOR to RetourArriere.Geste.FERMER_LOCATOR,
            Screen.GLOBE to RetourArriere.Geste.FERMER_GLOBE,
            Screen.SKED to RetourArriere.Geste.FERMER_SKED,
            Screen.TIMELINE to RetourArriere.Geste.FERMER_TIMELINE,
            Screen.PHOTO to RetourArriere.Geste.FERMER_PHOTO,
            Screen.ACTIVATION to RetourArriere.Geste.FERMER_ACTIVATION,
            Screen.SSTV to RetourArriere.Geste.FERMER_SSTV,
            Screen.SDR to RetourArriere.Geste.FERMER_SDR,
            Screen.APT to RetourArriere.Geste.FERMER_APT,
            Screen.SONDE to RetourArriere.Geste.FERMER_SONDE,
            Screen.ROTOR to RetourArriere.Geste.FERMER_ROTOR,
            Screen.QO100 to RetourArriere.Geste.FERMER_QO100,
            Screen.AGENDA to RetourArriere.Geste.FERMER_AGENDA,
            Screen.NOMMAGE to RetourArriere.Geste.FERMER_NOMMAGE,
            Screen.FT8 to RetourArriere.Geste.FERMER_FT8,
        )
        attendus.forEach { (ecran, attendu) ->
            assertEquals("écran $ecran, fiche ouverte", attendu,
                geste(ecran, selection = true))
            assertEquals("écran $ecran, mode sélection", attendu,
                geste(ecran, modeSelection = true))
        }
        // Et le compte y est : si l'énumération grandit, cet essai doit être
        // relu plutôt que contourné.
        assertEquals("un écran a été ajouté sans passer par ici",
            17, Screen.entries.size)
    }

    /**
     * La flèche de la barre du haut suit la même liste que le bouton du
     * téléphone.
     *
     * Elle avait la sienne, écrite à la main, et FT8 y manquait : l'écran
     * s'ouvrait sans plus offrir de sortie. Cet essai garde l'invariant qui a
     * remplacé les deux listes — **tout écran plein cadre a une fermeture**,
     * donc une flèche.
     */
    @Test
    fun tout_ecran_plein_cadre_offre_une_sortie() {
        val sansSortie = Screen.entries.filter { e ->
            e != Screen.PASSES &&
                geste(e) == RetourArriere.Geste.RIEN
        }
        assertEquals("ces écrans n'ont aucune sortie : $sansSortie",
            emptyList<Screen>(), sansSortie)
    }

    @Test
    fun les_reglages_se_ferment_par_couches() {
        // Une sous-section ouverte se referme d'abord ; le second retour ferme
        // les réglages. Sans quoi un aller-retour dans les réglages sort de
        // l'écran d'un coup et l'opérateur perd sa place.
        assertEquals(RetourArriere.Geste.SECTION_REGLAGES,
            geste(Screen.SETTINGS, section = true))
        assertEquals(RetourArriere.Geste.FERMER_REGLAGES, geste(Screen.SETTINGS))
        // Même avec une fiche dessous : les réglages sont devant.
        assertEquals(RetourArriere.Geste.FERMER_REGLAGES,
            geste(Screen.SETTINGS, selection = true))
    }

    @Test
    fun sur_la_liste_le_retour_ferme_la_fiche_puis_le_mode_selection() {
        assertEquals(RetourArriere.Geste.RETOUR_LISTE,
            geste(Screen.PASSES, selection = true))
        assertEquals(RetourArriere.Geste.QUITTER_SELECTION,
            geste(Screen.PASSES, modeSelection = true))
        // La fiche passe devant le mode sélection : elle est au-dessus.
        assertEquals(RetourArriere.Geste.RETOUR_LISTE,
            geste(Screen.PASSES, selection = true, modeSelection = true))
    }

    @Test
    fun sur_la_liste_nue_le_retour_quitte_l_application() {
        // Rien à fermer : le système reprend la main. Intercepter ici
        // emprisonnerait l'utilisateur dans l'application.
        assertEquals(RetourArriere.Geste.RIEN, geste(Screen.PASSES))
        assertFalse(RetourArriere.intercepte(Screen.PASSES, false, false, false))
        assertTrue(RetourArriere.intercepte(Screen.ROTOR, false, false, false))
        assertTrue(RetourArriere.intercepte(Screen.PASSES, false, true, false))
    }
}
