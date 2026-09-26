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
 * La molette d'émission tenue pour un bouton de décalage.
 *
 * Ce fichier existe parce que le calcul qu'il contient a été faux deux fois de
 * suite, livré deux fois, et n'a été démasqué que par l'opérateur devant son
 * poste. Il vivait au milieu de la boucle CAT, mêlé à des lectures série et à
 * des écritures — donc invérifiable au banc. Isolé ici, il se raconte en
 * quelques lignes et se met en défaut en quelques essais.
 *
 * **Le piège, et il n'est pas évident.** La montée est recalculée à chaque tour
 * avec le Doppler de l'instant, alors que le poste, lui, reste sur la dernière
 * valeur qu'on lui a réellement envoyée — le seuil des vingt hertz espace
 * délibérément les écritures. Comparer la lecture du poste à la montée fraîche
 * ne mesure donc pas le geste de l'opérateur : cela mesure **la dérive Doppler
 * accumulée depuis la dernière écriture**. L'absorber dans le décalage change la
 * montée, ce qui provoque une écriture, ce qui remet l'écart à zéro, ce qui
 * laisse le Doppler recommencer — et le décalage oscille sans fin entre deux
 * valeurs.
 *
 * La seule comparaison honnête est avec **la dernière consigne écrite**. Le
 * Doppler y est déjà ; ce qui reste est ce que la main a fait.
 */
object MoletteTx {

    /**
     * En deçà, l'écart n'est pas un geste : c'est l'arrondi du poste.
     *
     * Même valeur que le seuil d'écriture de la boucle CAT, et ce n'est pas une
     * coïncidence — un écart qu'on ne juge pas digne d'être écrit ne peut pas
     * être digne d'être absorbé.
     */
    const val SEUIL_HZ: Long = 20L

    /** Ce qu'il faut faire après une lecture du poste d'émission. */
    class Decision(
        /** Le décalage à retenir. */
        val shiftHz: Long,
        /** La consigne de référence pour la prochaine comparaison. */
        val referenceHz: Long,
        /** Le geste a-t-il été consommé ? */
        val gesteConsomme: Boolean,
        /** Le décalage a-t-il changé ? */
        val absorbe: Boolean,
    )

    /**
     * @param shiftHz décalage courant.
     * @param referenceHz dernière montée réellement envoyée au poste. Zéro
     *   signifie qu'on n'a encore rien écrit : il n'y a alors rien à comparer.
     * @param lueHz montée relue sur le poste, ramenée au repère du satellite.
     * @param gesteVu l'arbitre a-t-il constaté un mouvement depuis la dernière
     *   absorption ?
     * @param moletteTranquille l'arbitre nous rend-il la main ?
     */
    fun decide(
        shiftHz: Long,
        referenceHz: Long,
        lueHz: Long,
        gesteVu: Boolean,
        moletteTranquille: Boolean,
        seuilHz: Long = SEUIL_HZ,
    ): Decision {
        // Tant que la molette tourne, on ne conclut rien : la valeur lue est
        // une position de passage, pas une intention.
        if (!moletteTranquille) return Decision(shiftHz, referenceHz, false, false)

        // Sans geste constaté, un écart ne peut venir que de l'arrondi du poste
        // ou de notre propre écriture. L'absorber ferait dériver le décalage
        // tout seul, à chaque tour de boucle, sans que personne n'ait rien
        // touché.
        if (!gesteVu) return Decision(shiftHz, referenceHz, false, false)

        // Rien d'écrit encore : aucune référence, donc aucune mesure possible.
        if (referenceHz == 0L) return Decision(shiftHz, referenceHz, true, false)

        val ecart = lueHz - referenceHz
        if (abs(ecart) < seuilHz) {
            // Geste trop petit pour compter, mais bien consommé : le laisser en
            // attente le ferait absorber plus tard, avec un écart qui aurait
            // entre-temps changé de sens.
            return Decision(shiftHz, referenceHz, true, false)
        }

        // La référence suit le poste. Sans cela, le même écart serait réabsorbé
        // au tour suivant, et le décalage doublerait à chaque passage.
        return Decision(shiftHz + ecart, lueHz, true, true)
    }
}
