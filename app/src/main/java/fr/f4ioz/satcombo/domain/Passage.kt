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
 * Ce qui appartient au passage en cours.
 *
 * La question se pose à chaque contact : « celui-là, je l'ai déjà appelé
 * tout à l'heure ? ». Elle a d'abord été tranchée par une durée — même
 * satellite, moins d'une heure — et cette réponse est fausse deux fois :
 *
 * - **Trop large.** Un contact fait quarante minutes plus tôt, entre deux
 *   passages, tombait dans la fenêtre. C'est ce qu'Olivier a vu le 25 août :
 *   « ✓ 1 » annonçait F1FPL comme déjà appelé, alors que ce contact datait
 *   d'un autre passage, satellite à −70° d'élévation.
 * - **Trop étroite.** RS-44 ou les Molnya restent levés plusieurs heures.
 *   Une heure y coupe le passage en son milieu et fait réapparaître comme
 *   neuf un correspondant appelé vingt minutes plus tôt.
 *
 * Un passage n'a pas de durée : il a un début et une fin, que le calculateur
 * SGP4 donne déjà (AOS et LOS). C'est cette fenêtre qui fait foi, et elle
 * seule. Hors passage — satellite sous l'horizon — la question n'a pas de
 * sens : la réponse est vide, et le compteur disparaît.
 *
 * L'élévation inscrite dans l'entrée sert de second verrou : un contact
 * enregistré alors que le satellite était sous l'horizon n'a été fait sur
 * aucun passage. Ce sont les essais de table, et ils n'ont pas à revenir
 * hanter le terrain.
 */
object Passage {

    /** Un passage : de l'acquisition à la perte du signal. */
    data class Fenetre(val debutMs: Long, val finMs: Long)

    /** Une entrée du journal, réduite à ce que la règle regarde. */
    data class Inscrit(
        val timeMs: Long,
        val satellite: String,
        val elevationDeg: Double,
        val indicatif: String,
    )

    /**
     * Le passage qui contient [maintenant], s'il y en a un.
     *
     * Les fenêtres viennent de la prédiction du satellite affiché. Aucune ne
     * contient l'instant présent : le satellite n'est pas levé, il n'y a pas
     * de passage en cours.
     */
    fun enCours(fenetres: List<Fenetre>, maintenant: Long): Fenetre? =
        fenetres.firstOrNull { maintenant >= it.debutMs && maintenant <= it.finMs }

    /**
     * Les indicatifs déjà travaillés pendant [fenetre], du plus récent au plus
     * ancien, sans doublon.
     *
     * Sans fenêtre, la liste est vide : mieux vaut ne rien annoncer que
     * d'annoncer un autre passage.
     */
    fun indicatifs(
        journal: List<Inscrit>,
        satellite: String,
        fenetre: Fenetre?,
    ): List<String> {
        if (fenetre == null || satellite.isBlank()) return emptyList()
        return journal
            .filter {
                it.satellite == satellite &&
                    it.indicatif.isNotBlank() &&
                    it.elevationDeg >= 0.0 &&
                    it.timeMs >= fenetre.debutMs &&
                    it.timeMs <= fenetre.finMs
            }
            .sortedByDescending { it.timeMs }
            .map { it.indicatif }
            .distinct()
    }
}
