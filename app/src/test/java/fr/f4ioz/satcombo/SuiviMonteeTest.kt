/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SuiviMontee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to write the uplink. The IC-9700 and SatMe once fought over the VFO
 * pair (operator tunes, SatMe writes uplink, radio's reverse tracking pushes
 * back…). **Never lose this**: write nothing while the operator is tuning, on
 * a radio that keeps the pair in step itself.
 */
class SuiviMonteeTest {

    private val LARGE = 500L      // offset well above every threshold

    // ---- the original bug ----

    @Test
    fun sur_ic9700_on_se_tait_pendant_que_loperateur_tourne() {
        assertFalse(
            SuiviMontee.doitEcrire("IC9700", operateurTourne = true,
                txSuitVite = true, maintienDoppler = false, ecartHz = LARGE))
        // The "fast tracking" setting must not bypass this: it is what
        // started the fight.
        assertFalse(
            SuiviMontee.doitEcrire("IC9700", operateurTourne = true,
                txSuitVite = false, maintienDoppler = false, ecartHz = LARGE))
    }

    @Test
    fun sur_ic9700_on_ecrit_de_nouveau_des_quil_lache_la_molette() {
        assertTrue(
            SuiviMontee.doitEcrire("IC9700", operateurTourne = false,
                txSuitVite = true, maintienDoppler = false, ecartHz = LARGE))
    }

    // ---- what must not change ----

    @Test
    fun les_ft817_continuent_decrire_pendant_quil_tourne() {
        // Two independent radios: nobody else corrects the uplink. This setup
        // works; leave it alone.
        assertTrue(
            SuiviMontee.doitEcrire("FT817x2", operateurTourne = true,
                txSuitVite = true, maintienDoppler = false, ecartHz = LARGE))
        assertTrue(
            SuiviMontee.doitEcrire("FT817TX", operateurTourne = true,
                txSuitVite = true, maintienDoppler = false, ecartHz = LARGE))
    }

    @Test
    fun sans_suivi_rapide_les_ft817_attendent_la_fin_du_delai() {
        assertFalse(
            SuiviMontee.doitEcrire("FT817x2", operateurTourne = true,
                txSuitVite = false, maintienDoppler = false, ecartHz = LARGE))
        assertTrue(
            SuiviMontee.doitEcrire("FT817x2", operateurTourne = false,
                txSuitVite = false, maintienDoppler = false, ecartHz = LARGE))
    }

    // ---- Doppler hold overrides everything ----

    @Test
    fun le_maintien_doppler_interdit_toute_ecriture() {
        for (poste in listOf("IC9700", "FT817x2", "FT817TX")) {
            for (tourne in listOf(true, false)) {
                assertFalse("poste $poste, tourne $tourne",
                    SuiviMontee.doitEcrire(poste, tourne, txSuitVite = true,
                        maintienDoppler = true, ecartHz = LARGE))
            }
        }
    }

    // ---- thresholds ----

    @Test
    fun un_ecart_trop_petit_nest_pas_ecrit() {
        assertFalse(
            SuiviMontee.doitEcrire("FT817x2", operateurTourne = false,
                txSuitVite = true, maintienDoppler = false, ecartHz = 19L))
        assertTrue(
            SuiviMontee.doitEcrire("FT817x2", operateurTourne = false,
                txSuitVite = true, maintienDoppler = false, ecartHz = 20L))
    }

    @Test
    fun le_suivi_rapide_resserre_le_seuil() {
        assertEquals(20L, SuiviMontee.seuilHz(true))
        assertEquals(50L, SuiviMontee.seuilHz(false))
    }

    // ---- radio list ----

    @Test
    fun seul_lic9700_tient_le_couple_lui_meme() {
        assertTrue(SuiviMontee.posteSuitSeul("IC9700"))
        assertFalse(SuiviMontee.posteSuitSeul("FT817x2"))
        assertFalse(SuiviMontee.posteSuitSeul("FT817TX"))
        // An unknown radio is treated as independent: staying silent would
        // deprive it of tracking for no reason, whereas writing only harms
        // radios that track on their own — and those are named.
        assertFalse(SuiviMontee.posteSuitSeul("UN_AUTRE_POSTE"))
    }
}
