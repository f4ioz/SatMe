/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SuiviMontee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc de la règle d'écriture de la montée.
 *
 * Il garde une correction née d'une vidéo prise au shack : sur RS-44 puis
 * FO-29, l'IC-9700 et SatMe se disputaient le même couple de VFO. L'opérateur
 * tournait vers le bas, SatMe écrivait la montée, le suivi inversé du poste
 * renvoyait la réception vers le haut, et ainsi de suite.
 *
 * **La condition à ne jamais reperdre** : ne rien écrire pendant que
 * l'opérateur tourne, sur un poste qui tient le couple lui-même.
 */
class SuiviMonteeTest {

    private val LARGE = 500L      // un écart bien au-dessus de tous les seuils

    // ---- le défaut filmé ----

    @Test
    fun sur_ic9700_on_se_tait_pendant_que_loperateur_tourne() {
        assertFalse(
            SuiviMontee.doitEcrire("IC9700", operateurTourne = true,
                txSuitVite = true, maintienDoppler = false, ecartHz = LARGE))
        // Et le réglage « suit vite » ne doit pas pouvoir le contourner :
        // c'est lui qui avait introduit la bagarre.
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

    // ---- ce qui ne doit surtout pas changer ----

    @Test
    fun les_ft817_continuent_decrire_pendant_quil_tourne() {
        // Deux postes indépendants : personne ne recale la montée à notre
        // place. Olivier a dit que tout y fonctionne ; on n'y touche pas.
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

    // ---- le maintien passe avant tout ----

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

    // ---- les seuils ----

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

    // ---- la liste des postes ----

    @Test
    fun seul_lic9700_tient_le_couple_lui_meme() {
        assertTrue(SuiviMontee.posteSuitSeul("IC9700"))
        assertFalse(SuiviMontee.posteSuitSeul("FT817x2"))
        assertFalse(SuiviMontee.posteSuitSeul("FT817TX"))
        // Un poste inconnu est traité comme indépendant : se taire chez
        // quelqu'un dont on ignore le comportement le priverait de suivi sans
        // raison, là où écrire ne fait de dégât que sur les postes qui
        // recalent seuls — et ceux-là sont nommés.
        assertFalse(SuiviMontee.posteSuitSeul("UN_AUTRE_POSTE"))
    }
}
