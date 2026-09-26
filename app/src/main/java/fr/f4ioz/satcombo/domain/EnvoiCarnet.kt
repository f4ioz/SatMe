/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Ce qui part au carnet en ligne, et ce qui n'y repart pas.
 *
 * Wavelog — comme Cloudlog — accepte ce qu'on lui donne : il ne dédoublonne
 * pas. Repousser le carnet entier à chaque envoi y ferait donc un doublon par
 * contact et par tentative, et le ménage se ferait à la main, contact par
 * contact, sur une interface web.
 *
 * La marque est donc **locale et posée après coup** : un contact n'est réputé
 * déposé que si le serveur a répondu qu'il l'avait pris. Un échec réseau, une
 * clé refusée, une coupure au milieu du lot — rien de tout cela ne marque, et
 * la tentative suivante reprend là où elle en était.
 *
 * L'ordre compte aussi : du plus ancien au plus récent. Si l'envoi s'arrête en
 * chemin, ce qui est parti forme un bloc continu, et non un carnet troué.
 */
object EnvoiCarnet {

    /** Un contact, réduit à ce que la règle regarde. */
    data class Fiche(
        val timeMs: Long,
        val indicatif: String,
        val envoyeMs: Long,
    )

    /** Ce que l'envoi a donné. */
    data class Bilan(
        val deposes: Int,
        val refuses: Int,
        val restants: Int,
    )

    /**
     * Les contacts qui n'ont pas encore été déposés, du plus ancien au plus
     * récent.
     *
     * Un contact sans indicatif n'en est pas un et ne part jamais : c'est la
     * même règle que pour le fichier ADIF, et elle vaut ici pour la même
     * raison — un enregistrement sans CALL n'est pas un trafic incomplet, le
     * carnet d'en face le refusera ou le rangera de travers.
     */
    fun aDeposer(journal: List<Fiche>): List<Fiche> =
        journal
            .filter { it.indicatif.isNotBlank() && it.envoyeMs <= 0L }
            .sortedBy { it.timeMs }

    /** Combien de contacts attendent, pour l'annoncer sur le bouton. */
    fun combienAttendent(journal: List<Fiche>): Int = aDeposer(journal).size

    /**
     * Le bilan d'un envoi.
     *
     * [acceptes] sont les instants que le serveur a pris. Ce qui reste
     * n'inclut pas les refusés : ils repartiront au prochain essai, et les
     * compter deux fois donnerait un total qui ne veut rien dire.
     */
    fun bilan(journal: List<Fiche>, acceptes: Set<Long>, refuses: Int): Bilan {
        val attendaient = aDeposer(journal)
        val deposes = attendaient.count { it.timeMs in acceptes }
        return Bilan(
            deposes = deposes,
            refuses = refuses,
            restants = attendaient.size - deposes)
    }
}
