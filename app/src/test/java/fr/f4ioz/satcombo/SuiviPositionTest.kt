/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.SuiviPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le suivi de position en mouvement.
 *
 * Le défaut d'origine : le suivi n'était lancé qu'à l'ouverture de la carte et
 * arrêté en la quittant. Sur la page des passages — celle qu'on regarde pendant
 * qu'on trafique — la position restait celle du démarrage de l'application, et
 * un opérateur monté sur une colline voyait des azimuts calculés pour l'endroit
 * d'où il était parti. Sans le moindre message pour le prévenir, ce qui est le
 * pire des cas : des chiffres faux qui ont l'air justes.
 */
class SuiviPositionTest {

    private val jn18fs = 48.86 to 2.35      // région parisienne
    private val t0 = 1_800_000_000_000L

    @Test
    fun la_distance_est_juste_sur_un_trajet_connu() {
        // Paris → Lyon, environ 392 km à vol d'oiseau.
        val d = SuiviPosition.distanceM(48.86, 2.35, 45.76, 4.84)
        assertEquals(392_000.0, d, 8_000.0)
    }

    @Test
    fun deux_points_confondus_sont_a_distance_nulle() {
        assertEquals(0.0, SuiviPosition.distanceM(48.86, 2.35, 48.86, 2.35), 1e-6)
    }

    /**
     * Sans position antérieure, on calcule : c'est le cas d'une application qui
     * vient de démarrer et qui reçoit son premier point.
     */
    @Test
    fun le_premier_point_declenche_toujours_un_calcul() {
        assertTrue(SuiviPosition.doitRecalculer(
            null, null, jn18fs.first, jn18fs.second, 0L, t0))
        assertTrue(SuiviPosition.doitRecalculer(
            48.86, null, jn18fs.first, jn18fs.second, t0, t0))
    }

    /**
     * Le cœur du réglage. Un GPS qui frémit de quelques mètres ne doit pas
     * relancer une prédiction SGP4 sur quarante-huit heures pour tous les
     * satellites suivis : ce serait dépenser la batterie sans changer une
     * seconde aux horaires affichés.
     */
    @Test
    fun un_fremissement_du_gps_ne_relance_pas_la_prediction() {
        // Une trentaine de mètres, deux heures plus tard : le délai est
        // largement franchi, mais pas la distance.
        val proche = jn18fs.first + 0.0003
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, proche, jn18fs.second,
            t0, t0 + 7_200_000L))
    }

    @Test
    fun un_vrai_deplacement_relance_la_prediction() {
        // Une dizaine de kilomètres vers le nord.
        val colline = jn18fs.first + 0.1
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, colline, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS))
    }

    /**
     * Le seuil de distance ne suffit pas seul : sur autoroute on le franchit
     * toutes les quatre-vingt-dix secondes, et l'on passerait la journée à
     * prédire au lieu d'afficher.
     */
    @Test
    fun le_plancher_de_temps_tient_meme_a_grande_distance() {
        val loin = jn18fs.first + 1.0     // une centaine de kilomètres
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, loin, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS - 1))
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, loin, jn18fs.second,
            t0, t0 + SuiviPosition.DELAI_MIN_MS))
    }

    @Test
    fun le_seuil_tombe_ou_il_est_annonce() {
        // Un degré de latitude fait environ 111 km : on vise le seuil de près.
        val juste_sous = jn18fs.first + (SuiviPosition.SEUIL_M * 0.9) / 111_000.0
        val juste_au_dessus = jn18fs.first + (SuiviPosition.SEUIL_M * 1.1) / 111_000.0
        val plus_tard = t0 + SuiviPosition.DELAI_MIN_MS
        assertFalse(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, juste_sous, jn18fs.second, t0, plus_tard))
        assertTrue(SuiviPosition.doitRecalculer(
            jn18fs.first, jn18fs.second, juste_au_dessus, jn18fs.second, t0, plus_tard))
    }

    /**
     * Le zéro absolu mérite son essai. C'est ce que rend un récepteur qui n'a
     * pas encore de position, et il tombe dans le golfe de Guinée — un endroit
     * parfaitement valide, ce qui le rend d'autant plus traître : accepté, il
     * déplacerait le QTH de plusieurs milliers de kilomètres et pointerait
     * l'antenne au hasard.
     */
    @Test
    fun le_point_nul_du_recepteur_est_refuse() {
        assertFalse(SuiviPosition.vraisemblable(0.0, 0.0))
        assertTrue(SuiviPosition.vraisemblable(jn18fs.first, jn18fs.second))
    }

    @Test
    fun des_coordonnees_hors_du_globe_sont_refusees() {
        assertFalse(SuiviPosition.vraisemblable(91.0, 0.0))
        assertFalse(SuiviPosition.vraisemblable(-91.0, 0.0))
        assertFalse(SuiviPosition.vraisemblable(45.0, 181.0))
        assertTrue(SuiviPosition.vraisemblable(-33.9, 151.2))
    }

    // ------------------------------------------------------------- le veilleur

    /**
     * Le cœur du second correctif, et la raison pour laquelle le premier ne
     * suffisait pas.
     *
     * L'ancienne garde se contentait de vérifier que la tâche **existait**.
     * Or une demande de position adressée aux services Google avant qu'ils ne
     * soient prêts laisse une tâche parfaitement vivante qui ne délivre jamais
     * rien. La tâche existait, donc on ne la relançait pas, donc plus rien
     * n'arrivait — jusqu'à ce qu'ouvrir la carte demande une autre cadence, ce
     * qui annulait et relançait la tâche, et tout se remettait à marcher. D'où
     * « il faut aller sur une carte pour la mise à jour ».
     */
    @Test
    fun une_tache_vivante_mais_muette_est_relancee() {
        val demarre = t0
        val silence = t0 + SuiviPosition.SILENCE_MAX_MS + 1
        assertTrue(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = 0L, demarreDepuisMs = demarre, maintenantMs = silence))
    }

    /**
     * Mais on laisse au fournisseur le temps du premier point : un démarrage à
     * froid du GPS met parfois une minute, et relancer pendant ce temps-là
     * empêcherait justement le point d'arriver.
     */
    @Test
    fun on_laisse_au_gps_le_temps_du_premier_point() {
        assertFalse(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = 0L, demarreDepuisMs = t0,
            maintenantMs = t0 + SuiviPosition.SILENCE_MAX_MS - 1))
    }

    @Test
    fun un_suivi_qui_delivre_n_est_pas_derange() {
        val maintenant = t0 + 600_000L
        assertFalse(SuiviPosition.doitRelancer(
            auto = true, tacheActive = true,
            dernierPointMs = maintenant - 10_000L,
            demarreDepuisMs = t0, maintenantMs = maintenant))
    }

    @Test
    fun une_tache_morte_est_relancee_sans_attendre() {
        assertTrue(SuiviPosition.doitRelancer(
            auto = true, tacheActive = false,
            dernierPointMs = t0, demarreDepuisMs = t0, maintenantMs = t0 + 1))
    }

    /** En mode manuel, on ne relance rien : l'opérateur a choisi son QTH. */
    @Test
    fun le_mode_manuel_n_est_jamais_relance() {
        assertFalse(SuiviPosition.doitRelancer(
            auto = false, tacheActive = false,
            dernierPointMs = 0L, demarreDepuisMs = 0L,
            maintenantMs = t0 + 10 * SuiviPosition.SILENCE_MAX_MS))
    }

    /**
     * Le silence toléré doit rester très supérieur à la cadence de fond, sinon
     * le veilleur relancerait le suivi entre deux points normaux et
     * l'empêcherait de jamais s'établir.
     */
    @Test
    fun le_silence_tolere_laisse_passer_plusieurs_points_normaux() {
        assertTrue(SuiviPosition.SILENCE_MAX_MS >= 4 * SuiviPosition.CADENCE_FOND_MS)
    }

    /**
     * La cadence de fond est bien plus lente que celle de la carte, et c'est
     * délibéré : le suivi tourne désormais en permanence, donc sa cadence est
     * devenue une ligne du bilan de batterie et non un détail d'affichage.
     */
    @Test
    fun la_cadence_de_fond_menage_la_batterie() {
        assertTrue(SuiviPosition.CADENCE_FOND_MS >= 5 * SuiviPosition.CADENCE_CARTE_MS)
        // Mais assez rapide pour voir un carré changer sans attendre.
        assertTrue(SuiviPosition.CADENCE_FOND_MS <= 60_000L)
    }
}
