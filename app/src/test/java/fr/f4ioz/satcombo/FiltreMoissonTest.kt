/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.FiltreMoisson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtering contacts harvested from Wavelog. */
class FiltreMoissonTest {

    // ------------------------------------------------ server-side filtering

    /**
     * Only satellite can be filtered by Wavelog: its `band` filter takes a
     * single band, and there is no mode filter.
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

    // ---------------------------------------------------------- HF phone

    @Test
    fun la_phonie_hf_retient_la_voix_sur_les_bandes_hf() {
        listOf("SSB", "USB", "LSB", "AM", "FM").forEach { mode ->
            assertTrue(mode, FiltreMoisson.retient(
                FiltreMoisson.PHONIE_HF, "", mode, "20m"))
        }
    }

    /** The stated goal: exclude FT8, FT4 and their relatives. */
    @Test
    fun la_phonie_hf_ecarte_le_numerique() {
        listOf("FT8", "FT4", "JT65", "PSK31", "RTTY", "MFSK", "PKT").forEach { mode ->
            assertFalse(mode, FiltreMoisson.retient(
                FiltreMoisson.PHONIE_HF, "", mode, "20m"))
        }
    }

    /**
     * A mode invented tomorrow must be excluded too: we list what we keep,
     * not what we reject, so the closed list is on the side that ages well.
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

    // --------------------------------------------------------------- all

    @Test
    fun tout_retient_tout() {
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "SAT", "SSB", "2m"))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "", "FT8", "20m"))
        assertTrue(FiltreMoisson.retient(FiltreMoisson.TOUT, "", "", ""))
    }

    // ------------------------------------------------------- edge cases

    /**
     * A missing field never lets a contact through by default: a slightly
     * short memory beats one full of what was meant to be excluded.
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

    /** A corrupted setting falls back to satellite, the default. */
    @Test
    fun un_filtre_inconnu_retombe_sur_le_satellite() {
        assertTrue(FiltreMoisson.retient("gribouille", "SAT", "SSB", "2m"))
        assertFalse(FiltreMoisson.retient("gribouille", "", "SSB", "20m"))
    }
}
