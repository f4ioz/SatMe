/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.rotor

/**
 * Ce que l'écran montre quand le contrôleur saute une réponse.
 *
 * Un fil série n'est pas une base de données : sur un émulateur Arduino qui
 * pilote deux moteurs, la réponse au `C2` arrive en retard une fois de temps
 * en temps, et parfois pas du tout. Jusqu'ici, une seule réponse manquée
 * suffisait à tout effacer — position du mât, libellés `AZ MÂT` / `ÉL MÂT`,
 * ligne « Visée : rotor », et la boussole rebasculait sur le satellite pour
 * revenir au mât la seconde suivante. « ça suit bien… et puis ça ne suit plus
 * le rotor, on passe en normal. » Vu de l'opérateur, l'application clignote
 * entre deux mondes ; vu du câble, il ne s'est rien passé du tout.
 *
 * On garde donc la dernière position lue pendant [DEFAUT_MS]. Deux choses,
 * pourtant, ne se négocient pas.
 *
 * **La tenue est courte.** Un G-5500 tourne à six degrés par seconde : au bout
 * de quatre secondes, une position tenue peut être fausse de vingt-quatre
 * degrés. C'est acceptable pour ne pas faire clignoter un affichage, cela ne
 * le serait pas pour décider d'un pointage. La tenue sert l'écran, jamais la
 * consigne : [rotorTick] continue de commander le mât d'après la position
 * calculée du satellite, pas d'après une lecture périmée.
 *
 * **La tenue finit.** Passé le délai, on rend la main au satellite, libellé
 * compris. Un contrôleur débranché doit se voir. Tenir indéfiniment la
 * dernière position d'un mât qui ne répond plus serait exactement le genre de
 * mensonge tranquille que cette application s'interdit : un chiffre juste,
 * affiché longtemps après avoir cessé d'être vrai.
 */
object RotorTenue {

    /** Le temps qu'on garde une position que le contrôleur n'a pas confirmée. */
    const val DEFAUT_MS = 4000L

    /**
     * @param lue        la position qui vient d'être lue, ou null si le
     *                   contrôleur s'est tu à ce tour.
     * @param derniere   la dernière position lue pour de bon, ou null si l'on
     *                   n'en a jamais eu.
     * @param dateMs     l'instant de cette dernière lecture.
     * @param maintenant l'instant présent.
     * @param tenueMs    la durée de tenue.
     * @return ce que l'écran doit montrer : la lecture fraîche, la précédente
     *         si elle est encore jeune, ou null pour rendre la main.
     */
    fun montrer(
        lue: RotorPos?,
        derniere: RotorPos?,
        dateMs: Long,
        maintenant: Long,
        tenueMs: Long = DEFAUT_MS
    ): RotorPos? {
        if (lue != null) return lue
        if (derniere == null) return null
        val age = maintenant - dateMs
        // Une horloge qui recule (changement d'heure, redémarrage) ne doit pas
        // prolonger la tenue à l'infini : on ne garde que ce qui est jeune.
        if (age < 0L || age > tenueMs) return null
        return derniere
    }
}
