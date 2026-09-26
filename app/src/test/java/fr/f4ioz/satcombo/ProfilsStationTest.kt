/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le banc du rattachement au profil de station.
 *
 * C'est la règle qui empêche la perte la plus coûteuse et la plus discrète du
 * dépôt : des sorties portables reclassées sous le carré de la maison.
 */
class ProfilsStationTest {

    private val profils = listOf(
        Profil(id = "1", carre = "JN06XJ", indicatif = "F4IOZ/P"),
        Profil(id = "2", carre = "IN77", indicatif = "F4IOZ/P"),
        Profil(id = "9", carre = "JN16AJ", indicatif = "F4IOZ"))

    /** Un emplacement à carré unique, écrit court pour la lisibilité. */
    private fun lieu(carre: String, precision: Int = 4): Set<String> =
        ProfilsStation.emplacement(carre, "", precision)

    // ------------------------------------------------- lignes de carrés

    /**
     * Posé sur une ligne, l'opérateur est **dans les deux carrés à la fois**.
     *
     * Wavelog le sait : quand le carré d'un profil contient une virgule, son
     * import l'écrit dans MY_VUCC_GRIDS au lieu de MY_GRIDSQUARE. Un profil
     * « JN16,JN06 » est donc la façon correcte de déclarer l'opération —
     * encore faut-il n'y envoyer que les contacts qui la revendiquent.
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

    /** Sinon on donnerait la double revendication à qui n'y a pas droit. */
    @Test
    fun un_profil_sur_ligne_ne_couvre_pas_un_carre_seul() {
        val surLigne = listOf(Profil(id = "7", carre = "JN16,JN06", indicatif = "F4IOZ/P"))
        val cle = ProfilsStation.emplacement("JN16AJ", "")
        assertNull(ProfilsStation.apparieEmplacements(listOf(cle), surLigne, "F4IOZ/P", 4)[cle])
    }

    /** Et l'inverse : la revendication serait perdue. */
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
        // Le même carré sans revendication double retombe sur le défaut.
        assertEquals("1",
            ProfilsStation.profilPourEmplacement("JN16AJ", "", table, "1", 4))
    }

    // ------------------------------------------------- carré unique

    @Test
    fun un_carre_connu_trouve_son_profil() {
        assertEquals(mapOf("JN06" to "1"),
            ProfilsStation.apparieEmplacements(listOf(lieu("JN06XJ")), profils, "F4IOZ/P", 4)
                .mapKeys { ProfilsStation.nomEmplacement(it.key) })
    }

    /** Six caractères d'un côté, quatre de l'autre : sans troncature, rien. */
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

    /** JN16AJ existe, mais sous F4IOZ et non F4IOZ/P : ce n'est pas le même. */
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

    /** Sans table relevée, le profil unique des réglages fait foi. */
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
