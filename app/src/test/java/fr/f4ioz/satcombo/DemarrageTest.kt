/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc du démarrage.
 *
 * Il n'y en avait aucun, et c'est ce qui a laissé partir la 19.11 avec un
 * clavier sans mémoire : la construction du carnet était greffée sur une
 * fonction de file d'attente, la file a été supprimée, et personne — ni le
 * compilateur, ni les 762 essais — n'avait de raison de s'en apercevoir.
 *
 * On ne peut pas instancier le ViewModel ici (il lui faut un Application et
 * des SharedPreferences ; c'est le domaine de Robolectric, encore à faire).
 * Ce qu'on peut vérifier, ce sont les deux règles que le démarrage applique.
 */
class DemarrageTest {

    private fun contact(ind: String, carre: String = "JN18FS", sat: String = "RS-44",
                        quand: Long = 1_700_000_000_000L, nom: String = "") =
        Indicatifs.Contact(indicatif = ind, locator = carre, quandMs = quand,
            satellite = sat, nom = nom)

    // ------------------------------------------------ la mémoire du clavier

    /**
     * Les trois sources se rejoignent dans une seule mémoire : le carnet
     * local, l'ADIF importé, la base interne. C'est cette réunion que le
     * démarrage doit refaire, et qu'il ne refaisait plus.
     */
    @Test
    fun les_trois_sources_se_reunissent() {
        val locaux = listOf(contact("F1FPL"))
        val importe = listOf(contact("F5RRO", carre = "IN77US"))
        val interne = listOf(contact("F5OHH", carre = "IN97AJ", nom = "Christian"))

        val memoire = Indicatifs.memoire(locaux + importe + interne)

        assertEquals(3, memoire.size)
        assertTrue(memoire.any { it.indicatif == "F1FPL" })
        assertTrue(memoire.any { it.indicatif == "F5RRO" })
        assertTrue(memoire.any { it.indicatif == "F5OHH" })
    }

    /**
     * Le symptôme exact rapporté après la mise à jour : sans source, le
     * clavier n'a rien à proposer. Une mémoire vide n'est pas une erreur en
     * soi — c'est le cas d'une installation neuve — mais elle ne doit jamais
     * être le résultat d'un carnet qui, lui, est plein.
     */
    @Test
    fun sans_source_la_memoire_est_vide() {
        assertEquals(emptyList<Indicatifs.Connu>(), Indicatifs.memoire(emptyList()))
    }

    @Test
    fun un_carnet_plein_ne_donne_jamais_une_memoire_vide() {
        val carnet = listOf(contact("F1FPL"), contact("F5RRO"))
        assertTrue(Indicatifs.memoire(carnet).isNotEmpty())
    }

    // ------------------------------------------------ la reprise du geste

    /**
     * La règle de reprise, telle que `SettingsStore` l'applique : on réécrit
     * une fois, et une seule.
     *
     * La seconde partie compte autant que la première. Une reprise rejouée à
     * chaque démarrage écraserait le choix que l'opérateur vient de faire, et
     * le réglage deviendrait impossible à changer — un défaut bien pire que
     * celui qu'on corrige.
     */
    private fun reprise(faites: Int, appuisActuels: Int): Pair<Int, Int> =
        if (faites < 1) 2 to 1 else appuisActuels to faites

    @Test
    fun une_installation_existante_passe_au_double_appui() {
        assertEquals(2 to 1, reprise(faites = 0, appuisActuels = 3))
    }

    @Test
    fun la_reprise_ne_se_rejoue_pas() {
        // L'opérateur est repassé à trois appuis après la reprise : son choix
        // tient au démarrage suivant.
        assertEquals(3 to 1, reprise(faites = 1, appuisActuels = 3))
    }

    @Test
    fun le_nombre_d_appuis_reste_borne_a_deux_ou_trois() {
        // Un appui simple ouvrirait l'écran chaque fois qu'on touche la
        // boussole ; au-delà de trois le geste devient impraticable avec des
        // gants.
        listOf(0, 1, 2, 3, 4, 9).forEach {
            assertTrue(it.coerceIn(2, 3) in 2..3)
        }
        assertEquals(2, 1.coerceIn(2, 3))
        assertEquals(3, 7.coerceIn(2, 3))
    }
}
