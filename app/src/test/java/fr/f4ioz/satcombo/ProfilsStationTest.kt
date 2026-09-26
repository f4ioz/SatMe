/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.NomSatellite
import fr.f4ioz.satcombo.domain.ProfilsStation
import fr.f4ioz.satcombo.domain.ProfilsStation.Profil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching contacts to Wavelog station profiles.
 *
 * This rule prevents the costliest and quietest loss on upload: portable
 * operations filed under the home grid square.
 */
class ProfilsStationTest {

    private val profils = listOf(
        Profil(id = "1", carre = "JN06XJ", indicatif = "F4IOZ/P"),
        Profil(id = "2", carre = "IN77", indicatif = "F4IOZ/P"),
        Profil(id = "9", carre = "JN16AJ", indicatif = "F4IOZ"))

    /** A single-square location, short for readability. */
    private fun lieu(carre: String, precision: Int = 4): Set<String> =
        ProfilsStation.emplacement(carre, "", precision)

    // ------------------------------------------------- grid lines

    /**
     * On a grid line the operator is **in both squares at once**.
     *
     * Wavelog handles this: a profile grid containing a comma is imported into
     * MY_VUCC_GRIDS instead of MY_GRIDSQUARE. A "JN16,JN06" profile is the
     * right way to declare it — provided only contacts claiming it go there.
     */
    @Test
    fun une_ligne_est_un_emplacement_a_part() {
        assertNotEquals(ProfilsStation.emplacement("JN16AJ", "JN16,JN06"),
                        ProfilsStation.emplacement("JN16AJ", ""))
    }

    @Test
    fun l_ordre_des_carres_ne_compte_pas() {
        assertEquals(ProfilsStation.emplacement("JN16AJ", "JN16,JN06"),
                     ProfilsStation.emplacement("JN16AJ", "JN06,JN16"))
    }

    @Test
    fun un_profil_sur_ligne_couvre_les_contacts_de_la_ligne() {
        val surLigne = listOf(Profil(id = "7", carre = "JN16,JN06", indicatif = "F4IOZ/P"))
        val cle = ProfilsStation.emplacement("JN16AJ", "JN16,JN06")
        assertEquals("7",
            ProfilsStation.apparieEmplacements(listOf(cle), surLigne, "F4IOZ/P", 4)[cle])
    }

    /** Otherwise the double claim would go to contacts not entitled to it. */
    @Test
    fun un_profil_sur_ligne_ne_couvre_pas_un_carre_seul() {
        val surLigne = listOf(Profil(id = "7", carre = "JN16,JN06", indicatif = "F4IOZ/P"))
        val cle = ProfilsStation.emplacement("JN16AJ", "")
        assertNull(ProfilsStation.apparieEmplacements(listOf(cle), surLigne, "F4IOZ/P", 4)[cle])
    }

    /** And the reverse: the claim would be lost. */
    @Test
    fun un_profil_simple_ne_couvre_pas_une_ligne() {
        val cle = ProfilsStation.emplacement("JN16AJ", "JN16,JN06")
        assertNull(ProfilsStation.apparieEmplacements(listOf(cle), profils, "F4IOZ", 4)[cle])
    }

    @Test
    fun les_espaces_du_profil_ne_genent_pas() {
        assertEquals(setOf("JN16", "JN06"),
            ProfilsStation.carresDuProfil(
                Profil(id = "7", carre = "JN16, JN06", indicatif = "F4IOZ/P"), 4))
    }

    @Test
    fun le_nom_d_un_emplacement_se_lit() {
        assertEquals("JN06,JN16",
            ProfilsStation.nomEmplacement(ProfilsStation.emplacement("JN16AJ", "JN16,JN06")))
        assertEquals("JN16",
            ProfilsStation.nomEmplacement(ProfilsStation.emplacement("JN16AJ", "")))
    }

    @Test
    fun le_depot_d_un_contact_sur_ligne_suit_son_profil() {
        val table = mapOf(ProfilsStation.emplacement("JN16AJ", "JN16,JN06") to "7")
        assertEquals("7",
            ProfilsStation.profilPourEmplacement("JN16AJ", "JN16,JN06", table, "1", 4))
        // The same square without a double claim falls back to the default.
        assertEquals("1",
            ProfilsStation.profilPourEmplacement("JN16AJ", "", table, "1", 4))
    }

    // ------------------------------------------------- single square

    @Test
    fun un_carre_connu_trouve_son_profil() {
        assertEquals(mapOf("JN06" to "1"),
            ProfilsStation.apparieEmplacements(listOf(lieu("JN06XJ")), profils, "F4IOZ/P", 4)
                .mapKeys { ProfilsStation.nomEmplacement(it.key) })
    }

    /** Six characters on one side, four on the other: without truncation, no match. */
    @Test
    fun les_deux_cotes_sont_tronques_a_la_meme_maille() {
        assertEquals(mapOf("IN77" to "2"),
            ProfilsStation.apparieEmplacements(listOf(lieu("IN77US")), profils, "F4IOZ/P", 4)
                .mapKeys { ProfilsStation.nomEmplacement(it.key) })
    }

    @Test
    fun un_carre_sans_profil_reste_vide() {
        assertNull(ProfilsStation.apparieEmplacements(
            listOf(lieu("JO21AB")), profils, "F4IOZ/P", 4)[lieu("JO21AB")])
    }

    /** JN16AJ exists, but under F4IOZ, not F4IOZ/P: not the same location. */
    @Test
    fun l_indicatif_de_station_distingue_deux_emplacements() {
        assertNull(ProfilsStation.apparieEmplacements(
            listOf(lieu("JN16AJ")), profils, "F4IOZ/P", 4)[lieu("JN16AJ")])
        assertEquals("9",
            ProfilsStation.apparieEmplacements(
                listOf(lieu("JN16AJ")), profils, "F4IOZ", 4)[lieu("JN16AJ")])
    }

    @Test
    fun en_maille_six_les_carres_voisins_ne_se_confondent_plus() {
        assertNull(ProfilsStation.apparieEmplacements(listOf(lieu("IN77US", 6)),
            profils, "F4IOZ/P", 6)[lieu("IN77US", 6)])
    }

    @Test
    fun le_contact_part_sur_le_profil_de_son_carre() {
        val table = mapOf(lieu("JN06") to "1", lieu("IN77") to "2")
        assertEquals("1", ProfilsStation.profilPourEmplacement("JN06XJ", "", table, "7", 4))
        assertEquals("2", ProfilsStation.profilPourEmplacement("IN77US", "", table, "7", 4))
    }

    /** Without a fetched table, the single profile from settings applies. */
    @Test
    fun sans_table_on_retombe_sur_le_profil_par_defaut() {
        assertEquals("7", ProfilsStation.profilPourEmplacement("JN06XJ", "", emptyMap(), "7", 4))
    }

    @Test
    fun un_carre_hors_table_retombe_aussi_sur_le_defaut() {
        assertEquals("7",
            ProfilsStation.profilPourEmplacement("JO21AB", "", mapOf(lieu("JN06") to "1"), "7", 4))
    }

    @Test
    fun le_corps_de_fusee_disparait() {
        assertEquals("RS-44", NomSatellite.propre("RS-44 & BREEZE-KM R/B"))
    }

    @Test
    fun le_designateur_entre_parentheses_l_emporte() {
        assertEquals("FO-29", NomSatellite.propre("JAS-2 (FO-29)"))
        assertEquals("SO-50", NomSatellite.propre("SAUDISAT 1C (SO-50)"))
    }

    @Test
    fun un_nom_deja_propre_ne_bouge_pas() {
        listOf("FO-29", "RS-44", "ISS", "QO-100").forEach {
            assertEquals(it, NomSatellite.propre(it))
            assertTrue(!NomSatellite.aNettoyer(it))
        }
    }

    @Test
    fun on_sait_dire_ce_qui_est_a_nettoyer() {
        assertTrue(NomSatellite.aNettoyer("RS-44 & BREEZE-KM R/B"))
    }
}
