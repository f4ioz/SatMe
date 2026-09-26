/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs

/**
 * Qui, de la PLL ou du logiciel, encaisse le Doppler.
 *
 * Jusqu'ici, suivre le Doppler avec la clé RTL revenait à lui redonner une
 * fréquence chaque seconde. Cela paraît raisonnable, et c'est pourtant ce qui
 * rendait l'écoute désagréable : reprogrammer la PLL du R820T ne se fait pas en
 * silence. Le tuner se raccroche, la boucle se restabilise, et pendant quelques
 * dizaines de millisecondes ce qui sort n'est plus du signal mais un
 * transitoire. Une fois par seconde pendant dix minutes, cela fait six cents
 * petits accrocs dans un passage : en SSTV cela se voit sur l'image, en
 * radiosonde cela mange des trames, et à l'oreille cela s'entend comme un clic
 * régulier que l'on finit par attribuer au satellite.
 *
 * La correction ne coûte presque rien une fois qu'on a vu où la mettre. L'IQ
 * arrive en bande de base ; le multiplier par une exponentielle complexe avant
 * le filtre de canal déplace la fréquence d'écoute sans toucher au matériel.
 * Reste la décision, et c'est tout ce qu'il y a ici — pas une ligne d'Android,
 * donc entièrement vérifiable au banc.
 *
 * Trois nombres la gouvernent, et ils ne disent pas la même chose :
 * [FINE_LIMIT_HZ] est le seuil au-delà duquel on se résout à bouger la PLL et à
 * recentrer ; [FINE_MAX_HZ] est la butée physique, celle du canal réellement
 * numérisé ; [DEADBAND_HZ] est le point en dessous duquel on ne touche à rien,
 * car corriger de trois hertz coûte plus cher que de les ignorer.
 */
object DopplerTuner {

    /** Au-delà, on reprogramme la PLL et l'on repart d'un décalage nul. */
    const val FINE_LIMIT_HZ = 30_000L

    /** Butée physique : au-delà, on sortirait du canal réellement numérisé. */
    const val FINE_MAX_HZ = 80_000L

    /** En deçà, on ne touche à rien : le remède coûterait plus que le mal. */
    const val DEADBAND_HZ = 10L

    /**
     * La décision : où poser la PLL, quel décalage fin appliquer, et s'il faut
     * réellement reprogrammer le tuner.
     */
    data class Plan(
        /** Fréquence à donner au tuner (inchangée quand [retune] est faux). */
        val pllHz: Long,
        /** Décalage logiciel à appliquer sur l'IQ, en hertz. */
        val fineHz: Long,
        /** Vrai seulement quand la PLL doit réellement être reprogrammée. */
        val retune: Boolean
    )

    /**
     * @param wantHz fréquence à écouter, Doppler compris.
     * @param pllHz fréquence actuellement donnée au tuner ; zéro ou moins
     *   signifie « jamais accordé », et impose donc une première programmation.
     * @param fineHz décalage logiciel en cours.
     */
    fun plan(wantHz: Long, pllHz: Long, fineHz: Long): Plan {
        if (wantHz <= 0L) return Plan(pllHz, fineHz, false)
        if (pllHz <= 0L) return Plan(wantHz, 0L, true)

        val delta = wantHz - pllHz
        // Au-delà du seuil, le décalage fin ne suffit plus : on recentre. On
        // recentre sur la fréquence voulue elle-même, et non sur le repos, pour
        // que le passage reparte avec toute la marge devant lui.
        if (abs(delta) > FINE_LIMIT_HZ) return Plan(wantHz, 0L, true)

        // Sous la bande morte, on rend le décalage en cours à l'identique :
        // l'appelant compare et n'écrit rien.
        if (abs(delta - fineHz) < DEADBAND_HZ) return Plan(pllHz, fineHz, false)
        return Plan(pllHz, clampFine(delta), false)
    }

    /** Le décalage ne sort jamais du canal numérisé, quoi qu'on lui demande. */
    fun clampFine(hz: Long): Long = hz.coerceIn(-FINE_MAX_HZ, FINE_MAX_HZ)

    /** Un décalage de cette taille tient-il dans le canal ? */
    fun fits(hz: Long): Boolean = abs(hz) <= FINE_MAX_HZ
}
