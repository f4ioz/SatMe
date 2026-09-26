/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.FiltreMoisson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le banc du tri de la moisson Wavelog. */
class FiltreMoissonTest {

    // ------------------------------------------------ ce que le serveur trie

    /**
     * Seul le satellite peut être trié par Wavelog : son filtre `band`
     * n'accepte qu'une bande, et il n'existe aucun filtre par mode.
     */
    @Test
    fun seul_le_satellite_se_trie_chez_le_serveur() {
        assertEquals("SAT", FiltreMoisson.bandeServeur(FiltreMoisson.SAT))
        assertNull(FiltreMoisson.bandeServeur(FiltreMoisson.PHONIE_HF))
        assertNull(FiltreMoisson.bandeServeur(FiltreMoisson.CW))
        assertNull(FiltreMoisson.bandeServeur(FiltreMoisson.TOUT))
    }

    @Test
    fun le_tri_local_ne_sert_que_pour_les_choix_intermediaires() {
        assertFalse(FiltreMoisson.triLocal(FiltreMoisson.SAT))
        assertFalse(FiltreMoisson.triLocal(FiltreMoisson.TOUT))
        assertTrue(FiltreMoisson.triLocal(FiltreMoisson.PHONIE_HF))
        assertTrue(FiltreMoisson.triLocal(FiltreMoisson.CW))
    }

    // ------------------------------------------------------------ satellite

    @Test
    fun le_filtre_satellite_ne_retient_que_le_satellite() {
        assertTrue(FiltreMoisson.retient(FiltreMoisson.SAT, "SAT", "SSB", "2m"))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.SAT, "", "SSB", "20m"))
    }

    // ---------------------------------------------------------- phonie HF

    @Test
    fun la_phonie_hf_retient_la_voix_sur_les_bandes_hf() {
        listOf("SSB", "USB", "LSB", "AM", "FM").forEach { mode ->
            assertTrue(mode, FiltreMoisson.retient(
                FiltreMoisson.PHONIE_HF, "", mode, "20m"))
        }
    }

    /** C'est le but énoncé : écarter FT8, FT4 et leurs cousins. */
    @Test
    fun la_phonie_hf_ecarte_le_numerique() {
        listOf("FT8", "FT4", "JT65", "PSK31", "RTTY", "MFSK", "PKT").forEach { mode ->
            assertFalse(mode, FiltreMoisson.retient(
                FiltreMoisson.PHONIE_HF, "", mode, "20m"))
        }
    }

    /**
     * Un mode inventé demain doit être écarté lui aussi.
     *
     * On reconnaît ce qu'on garde plutôt que d'énumérer ce qu'on rejette :
     * une liste fermée du bon côté vieillit mieux.
     */
    @Test
    fun un_mode_numerique_inconnu_est_ecarte_aussi() {
        assertFalse(FiltreMoisson.retient(
            FiltreMoisson.PHONIE_HF, "", "FT12-TURBO", "20m"))
    }

    @Test
    fun la_phonie_hf_ecarte_le_vhf_et_le_satellite() {
        assertFalse(FiltreMoisson.retient(FiltreMoisson.PHONIE_HF, "", "SSB", "2m"))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.PHONIE_HF, "SAT", "SSB", "2m"))
    }

    // ----------------------------------------------------------------- CW

    @Test
    fun le_cw_retient_le_cw_hors_satellite() {
        assertTrue(FiltreMoisson.retient(FiltreMoisson.CW, "", "CW", "40m"))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.CW, "", "SSB", "40m"))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.CW, "SAT", "CW", "70cm"))
    }

    // --------------------------------------------------------------- tout

    @Test
    fun tout_retient_tout() {
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "SAT", "SSB", "2m"))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "", "FT8", "20m"))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "", "", ""))
    }

    // ------------------------------------------------------- cas limites

    /**
     * Un champ absent ne doit jamais faire retenir un contact par défaut :
     * mieux vaut une mémoire un peu courte qu'une mémoire pleine de ce qu'on
     * avait demandé d'écarter.
     */
    @Test
    fun les_champs_absents_ne_font_rien_passer() {
        assertFalse(FiltreMoisson.retient(FiltreMoisson.SAT, "", "", ""))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.PHONIE_HF, "", "", ""))
        assertFalse(FiltreMoisson.retient(FiltreMoisson.CW, "", "", ""))
    }

    @Test
    fun la_casse_et_les_espaces_ne_genent_pas() {
        assertTrue(FiltreMoisson.retient(FiltreMoisson.SAT, " sat ", "ssb", "2m"))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.PHONIE_HF, "", " ssb ", " 20M "))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.CW, "", "cw", "40m"))
    }

    /** Un réglage abîmé retombe sur le satellite, qui est le défaut. */
    @Test
    fun un_filtre_inconnu_retombe_sur_le_satellite() {
        assertTrue(FiltreMoisson.retient("gribouille", "SAT", "SSB", "2m"))
        assertFalse(FiltreMoisson.retient("gribouille", "", "SSB", "20m"))
    }
}
