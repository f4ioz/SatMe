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
 * Quelle fenêtre mutuelle présenter en premier.
 *
 * Le calcul du sked balaie quarante-huit heures et rend toutes les fenêtres où
 * le satellite est visible des deux stations. Ouvert par la porte — on choisit
 * un satellite, on tape un carré — le premier créneau venu est le bon : c'est
 * le prochain.
 *
 * Ouvert **depuis une annonce**, il ne l'est plus. hams.at a dit à quelle heure
 * le rendez-vous a lieu ; présenter le créneau de ce soir alors qu'on vient
 * d'appuyer sur une annonce de mercredi matin oblige à faire défiler une liste
 * pour retrouver ce qu'on avait déjà sous les yeux.
 *
 * D'où la visée : l'instant annoncé désigne la fenêtre qui le contient. S'il
 * n'y en a pas — les éléments orbitaux ont vieilli, le créneau local a glissé
 * de quelques minutes, ou la station annoncée n'est pas visible d'ici pendant
 * tout son passage — on prend la plus proche plutôt que rien. Une fenêtre
 * voisine se reconnaît d'un coup d'œil ; la première de la liste, non.
 */
object SkedVisee {

    /**
     * L'indice de la fenêtre à présenter parmi [fenetres], jamais hors bornes.
     *
     * Sans [visee], c'est la première : l'ordre est chronologique, donc la
     * première est la prochaine.
     */
    fun index(fenetres: List<LongRange>, visee: Long?): Int {
        if (fenetres.isEmpty() || visee == null) return 0
        val dedans = fenetres.indexOfFirst { visee in it }
        if (dedans >= 0) return dedans
        return fenetres.indices.minByOrNull {
            minOf(
                kotlin.math.abs(fenetres[it].first - visee),
                kotlin.math.abs(fenetres[it].last - visee))
        } ?: 0
    }
}
