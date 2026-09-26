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
 * Retrouver un contact dans la bande.
 *
 * C'est le seul rattrapage qui ne dépende pas de la mémoire de l'opérateur. Dix
 * entrées anonymes après un gros passage, ça ne se restitue pas de tête — la
 * mémoire de travail plafonne vers sept éléments, et encore, sans le stress du
 * passage ni le rangement de l'antenne juste après. On en retrouverait quatre
 * et on en inventerait deux, ce qui est pire que de n'avoir rien noté. Mais si
 * la bande tourne, chaque tampon a sa position dedans, et le correspondant y
 * donne son indicatif lui-même.
 *
 * Deux cas, et ils ne demandent pas le même travail :
 *
 *  - **SatMe enregistre.** L'instant du tampon et l'instant du fichier sont
 *    donnés par la même horloge : la position se calcule et il n'y a rien à
 *    régler.
 *  - **Un enregistreur extérieur.** Il manque le point d'ancrage — à quel
 *    instant UTC correspond la seconde zéro du fichier ? L'horloge d'un
 *    dictaphone dérive et la date du fichier est celle de l'arrêt, pas du
 *    départ. Mais **un passage dure douze minutes**, et la dérive d'une horloge
 *    de dictaphone sur douze minutes est très inférieure à la seconde : un seul
 *    point d'alignement suffit alors à caler tout le fichier.
 */
object Rattrapage {

    /** Marge avant le tampon, en secondes : on parle avant d'appuyer. */
    const val AVANT_S: Long = 12

    /** Marge après. */
    const val APRES_S: Long = 8

    /**
     * Position d'un contact dans un enregistrement, en millisecondes.
     *
     * Négatif si le contact précède le début de la bande — cas d'un
     * enregistrement lancé en retard, qui doit se dire au lieu d'être ramené à
     * zéro en silence.
     */
    fun position(contactMs: Long, debutBandeMs: Long): Long = contactMs - debutBandeMs

    /** Le contact tombe-t-il dans la bande ? */
    fun dansLaBande(contactMs: Long, debutBandeMs: Long, dureeMs: Long): Boolean {
        val p = position(contactMs, debutBandeMs)
        return p >= 0 && p <= dureeMs
    }

    /**
     * Le début de bande déduit d'un seul point d'alignement.
     *
     * L'opérateur fait défiler le fichier jusqu'à un moment qu'il reconnaît,
     * désigne le contact correspondant, et tout le reste tombe en place : les
     * deux bases de temps avancent au même rythme sur une durée aussi courte.
     */
    fun debutDeduit(contactMs: Long, positionDansFichierMs: Long): Long =
        contactMs - positionDansFichierMs

    /** Fenêtre d'écoute autour d'un contact, bornée au fichier. */
    fun fenetre(positionMs: Long, dureeMs: Long): LongRange {
        val debut = (positionMs - AVANT_S * 1000).coerceAtLeast(0L)
        val fin = (positionMs + APRES_S * 1000).coerceAtMost(dureeMs.coerceAtLeast(0L))
        return debut..fin.coerceAtLeast(debut)
    }

    /**
     * Cohérence d'un alignement proposé.
     *
     * Un décalage de plus d'une journée trahit un fichier qui n'est pas celui
     * du passage — on le dit plutôt que d'aligner sur du vide et de laisser
     * l'opérateur écouter du silence en croyant avoir raté son repère.
     */
    fun alignementVraisemblable(debutDeduitMs: Long, contactsMs: List<Long>): Boolean {
        if (contactsMs.isEmpty()) return false
        val premier = contactsMs.min()
        val dernier = contactsMs.max()
        return debutDeduitMs <= premier + 60_000L &&
            debutDeduitMs >= dernier - 86_400_000L
    }
}
