/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorPos
import fr.f4ioz.satcombo.rotor.RotorTenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La tenue de position : ne pas clignoter, sans pour autant mentir.
 *
 * Le 2 août, une seule réponse manquée sur le fil suffisait à effacer toute la
 * position du mât ; la boussole rebasculait sur le satellite pour revenir au
 * mât la seconde suivante. « ça suit bien… et puis ça ne suit plus le rotor,
 * on passe en normal. » Ces essais tiennent les deux bouts de la corde : la
 * position tenue doit survivre à un trou, et elle doit mourir avant d'être
 * fausse.
 */
class RotorTenueTest {

    private val pos = RotorPos(155.0, 16.0)
    private val autre = RotorPos(200.0, 40.0)

    @Test
    fun une_lecture_fraiche_passe_toujours_devant_la_precedente() {
        // Le cas ordinaire : le contrôleur a répondu, on affiche sa réponse.
        // Rien de tenu ne doit pouvoir la recouvrir, fût-elle d'une milliseconde.
        val vu = RotorTenue.montrer(autre, pos, dateMs = 1_000L, maintenant = 1_001L)
        assertEquals(autre, vu)
    }

    @Test
    fun un_trou_court_ne_fait_pas_disparaitre_le_mat() {
        // Une réponse sautée, trois cents millisecondes plus tard : c'est
        // exactement le trou observé sur l'émulateur Arduino occupé à faire
        // tourner deux moteurs. L'écran ne doit rien montrer de ce trou.
        val vu = RotorTenue.montrer(null, pos, dateMs = 10_000L, maintenant = 10_300L)
        assertEquals(pos, vu)
    }

    @Test
    fun la_tenue_finit_et_le_satellite_reprend_la_main() {
        // Passé le délai, la main revient au satellite — libellé compris. Un
        // contrôleur débranché doit se voir : tenir indéfiniment la dernière
        // position d'un mât muet serait un chiffre juste affiché longtemps
        // après avoir cessé d'être vrai.
        val juste = RotorTenue.montrer(null, pos, 10_000L, 10_000L + RotorTenue.DEFAUT_MS)
        assertEquals("la tenue doit couvrir toute sa durée", pos, juste)
        val apres = RotorTenue.montrer(null, pos, 10_000L, 10_001L + RotorTenue.DEFAUT_MS)
        assertNull("le mât muet est resté affiché au-delà de la tenue", apres)
    }

    @Test
    fun une_horloge_qui_recule_ne_prolonge_pas_la_tenue() {
        // Changement d'heure, redémarrage, correction NTP : un âge négatif ne
        // doit pas passer pour « très jeune » et figer la position à l'écran
        // jusqu'à la fin des temps.
        assertNull(RotorTenue.montrer(null, pos, dateMs = 50_000L, maintenant = 10_000L))
    }

    @Test
    fun sans_lecture_precedente_il_n_y_a_rien_a_tenir() {
        // Au tout premier tour, ou juste après un débranchement qui a effacé
        // la mémoire : on ne montre pas une position inventée.
        assertNull(RotorTenue.montrer(null, null, dateMs = 0L, maintenant = 1_000L))
    }

    @Test
    fun une_tenue_nulle_rend_la_main_immediatement() {
        // La durée est un paramètre : à zéro, le comportement d'avant 18.27.
        // Un essai le fixe, pour que « désactiver la tenue » reste possible
        // sans rouvrir la question.
        assertNull(RotorTenue.montrer(null, pos, 10_000L, 10_001L, tenueMs = 0L))
    }
}
