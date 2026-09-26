/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.RetourArriere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back closes what is on screen, nothing else. `when` branch order slips past
 * review: Back once tried the satellite sheet first, so on the Rotor screen it
 * silently stopped tracking. These tests pin the order.
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
        // The original bug in one line. A sheet is open underneath (normal:
        // the mast screen is opened from a satellite), yet the rotor closes,
        // because it is what is drawn.
        assertEquals(
            RetourArriere.Geste.FERMER_ROTOR,
            geste(Screen.ROTOR, selection = true))
    }

    @Test
    fun tous_les_ecrans_plein_cadre_passent_devant_la_fiche() {
        // The same mistake can happen on any of them: each draws over the
        // sheet, each must close before it.
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
        // If the enum grows, this test must be revisited, not bypassed.
        assertEquals("un écran a été ajouté sans passer par ici",
            17, Screen.entries.size)
    }

    /**
     * The top-bar arrow uses the same list as Back (a separate list once left
     * FT8 with no way out): **every full-screen view has a close action**.
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
        // An open sub-section closes first; the second Back closes settings.
        // Otherwise the operator loses their place in one press.
        assertEquals(RetourArriere.Geste.SECTION_REGLAGES,
            geste(Screen.SETTINGS, section = true))
        assertEquals(RetourArriere.Geste.FERMER_REGLAGES, geste(Screen.SETTINGS))
        // Even with a sheet underneath: settings are on top.
        assertEquals(RetourArriere.Geste.FERMER_REGLAGES,
            geste(Screen.SETTINGS, selection = true))
    }

    @Test
    fun sur_la_liste_le_retour_ferme_la_fiche_puis_le_mode_selection() {
        assertEquals(RetourArriere.Geste.RETOUR_LISTE,
            geste(Screen.PASSES, selection = true))
        assertEquals(RetourArriere.Geste.QUITTER_SELECTION,
            geste(Screen.PASSES, modeSelection = true))
        // The sheet beats selection mode: it is on top.
        assertEquals(RetourArriere.Geste.RETOUR_LISTE,
            geste(Screen.PASSES, selection = true, modeSelection = true))
    }

    @Test
    fun sur_la_liste_nue_le_retour_quitte_l_application() {
        // Nothing to close: the system takes over. Intercepting here would
        // trap the user in the app.
        assertEquals(RetourArriere.Geste.RIEN, geste(Screen.PASSES))
        assertFalse(RetourArriere.intercepte(Screen.PASSES, false, false, false))
        assertTrue(RetourArriere.intercepte(Screen.ROTOR, false, false, false))
        assertTrue(RetourArriere.intercepte(Screen.PASSES, false, true, false))
    }
}
