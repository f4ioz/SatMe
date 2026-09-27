/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.Chemin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back retraces the steps. Every close once landed on the pass list:
 * Settings › Rotor › back lost the settings, and the Rotor gear lost the Rotor.
 */
class CheminTest {

    /** Walks like the view model: current page, then open or close. */
    private class Marche {
        val c = Chemin()
        var ecran = Screen.PASSES
        var section: String? = null

        fun ouvre(vers: Screen, versSection: String? = null) {
            c.va(ecran, section, vers, versSection)
            ecran = vers
            section = if (vers == Screen.SETTINGS) versSection else section
        }
        fun choisit(s: String?) { c.choixSection(); section = s }
        fun ferme() { val e = c.retour(); ecran = e.ecran; section = e.section }
        /** The phone's back button inside the settings. */
        fun retourReglages() {
            if (section != null && !c.fermeReglages(section)) choisit(null) else ferme()
        }
    }

    @Test
    fun reglages_puis_rotor_le_retour_revient_aux_reglages() {
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.ouvre(Screen.ROTOR)
        m.ferme()
        assertEquals(Screen.SETTINGS, m.ecran)
        assertEquals(null, m.section)
        m.ferme()
        assertEquals(Screen.PASSES, m.ecran)
    }

    @Test
    fun le_retour_rouvre_la_section_quittee() {
        // Settings › Recordings › "Open the SSTV page" › back: the section the
        // button was in, not the menu.
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.choisit("recordings")
        m.ouvre(Screen.SSTV)
        m.ferme()
        assertEquals(Screen.SETTINGS, m.ecran)
        assertEquals("recordings", m.section)
    }

    @Test
    fun l_engrenage_ouvre_la_section_et_le_retour_revient_a_l_ecran() {
        val m = Marche()
        m.ouvre(Screen.SSTV)
        m.ouvre(Screen.SETTINGS, Chemin.sectionDe(Screen.SSTV))
        assertEquals("recordings", m.section)
        m.retourReglages()
        assertEquals("un seul retour ramène à l'écran SSTV", Screen.SSTV, m.ecran)
        m.ferme()
        assertEquals(Screen.PASSES, m.ecran)
    }

    @Test
    fun engrenage_puis_menu_le_retour_passe_par_le_menu() {
        // Once the operator picks another section, the menu is part of the path.
        val m = Marche()
        m.ouvre(Screen.SDR)
        m.ouvre(Screen.SETTINGS, "accord")
        m.choisit(null)
        m.choisit("cat")
        m.retourReglages()
        assertEquals(Screen.SETTINGS, m.ecran)
        assertEquals(null, m.section)
        m.retourReglages()
        assertEquals(Screen.SDR, m.ecran)
    }

    @Test
    fun le_rotor_n_est_plus_perdu_par_son_engrenage() {
        // Settings › Rotor › gear (menu) › back › back › back.
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.ouvre(Screen.ROTOR)
        m.ouvre(Screen.SETTINGS, Chemin.sectionDe(Screen.ROTOR))
        m.retourReglages()
        assertEquals(Screen.ROTOR, m.ecran)
        m.ferme()
        assertEquals(Screen.SETTINGS, m.ecran)
        m.ferme()
        assertEquals(Screen.PASSES, m.ecran)
    }

    @Test
    fun partir_de_la_liste_efface_le_chemin() {
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.ouvre(Screen.ROTOR)
        m.ferme(); m.ferme()
        m.ouvre(Screen.QO100)
        m.ferme()
        assertEquals(Screen.PASSES, m.ecran)
        m.ferme()
        assertEquals("rien à dépiler : la liste", Screen.PASSES, m.ecran)
    }

    @Test
    fun rouvrir_la_meme_page_n_empile_rien() {
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.ouvre(Screen.PHOTO)
        m.ouvre(Screen.PHOTO)
        m.ferme()
        assertEquals(Screen.SETTINGS, m.ecran)
    }

    @Test
    fun les_allers_retours_par_engrenage_restent_bornes() {
        val m = Marche()
        m.ouvre(Screen.SSTV)
        repeat(50) {
            m.ouvre(Screen.SETTINGS, "recordings")
            m.ouvre(Screen.SSTV)
        }
        var n = 0
        while (m.ecran != Screen.PASSES) { m.ferme(); n++ }
        assertTrue("$n retours", n <= 9)
    }

    @Test
    fun section_a_la_main_le_retour_rend_le_menu() {
        val m = Marche()
        m.ouvre(Screen.SETTINGS)
        m.choisit("cat")
        assertFalse(m.c.fermeReglages("cat"))
        m.retourReglages()
        assertEquals(Screen.SETTINGS, m.ecran)
        assertEquals(null, m.section)
    }
}
