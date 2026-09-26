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
 * Faut-il écrire la montée pendant que l'opérateur tourne la réception ?
 *
 * **La question n'a pas la même réponse selon le poste**, et c'est tout
 * l'objet de ce fichier.
 *
 * Sur une paire de FT-817, les deux postes sont indépendants : personne ne
 * recale la montée à notre place, et la laisser en arrière ferait émettre à
 * côté du correspondant qu'on vient de trouver. Il faut écrire.
 *
 * Sur un IC-9700 **en mode satellite**, le poste fait lui-même le suivi
 * inversé : bouger SUB déplace MAIN dans l'autre sens. Écrire la montée
 * pendant que l'opérateur tourne crée alors une boucle — il descend, nous
 * écrivons une montée plus haute, le poste remonte sa réception, il descend
 * encore. C'est exactement ce qui a été filmé sur RS-44 et FO-29 : la somme
 * des deux VFO restait rigoureusement constante, preuve que c'était le poste,
 * et non SatMe, qui tenait le couple.
 *
 * La règle précédente supposait que « l'opérateur ne touche pas l'émission ».
 * Sur un poste à double VFO commandé par un seul bouton, cette phrase est
 * fausse : toucher l'une déplace l'autre.
 *
 * **Pas de réglage pour arbitrer cela.** Un interrupteur ne ferait que
 * déplacer le piège sur l'opérateur, qui n'a aucun moyen de deviner que son
 * poste et l'application se disputent le même VFO.
 */
object SuiviMontee {

    /** Les postes qui tiennent eux-mêmes le couple en mode satellite. */
    private val SUIVI_INTERNE = setOf("IC9700")

    /** Vrai si ce poste recale la montée tout seul quand la descente bouge. */
    fun posteSuitSeul(rigModel: String): Boolean = rigModel in SUIVI_INTERNE

    /**
     * L'écart minimal qui justifie une écriture, en hertz.
     *
     * Plus fin quand l'émission doit suivre vite : sans cela on entendrait le
     * correspondant sans pouvoir lui répondre au même endroit.
     */
    fun seuilHz(txSuitVite: Boolean): Long = if (txSuitVite) 20L else 50L

    /**
     * @param operateurTourne vrai tant que l'arbitre voit la molette bouger,
     *   c'est-à-dire tant que la réception n'est pas rendue au pilotage.
     */
    fun doitEcrire(
        rigModel: String,
        operateurTourne: Boolean,
        txSuitVite: Boolean,
        maintienDoppler: Boolean,
        ecartHz: Long
    ): Boolean {
        // Le maintien passe avant tout : c'est la demande explicite de ne plus
        // rien écrire, et rien ne doit pouvoir la contourner.
        if (maintienDoppler) return false
        // Le poste s'en charge : se taire est la seule façon de ne pas se
        // battre avec lui.
        if (operateurTourne && posteSuitSeul(rigModel)) return false
        if (ecartHz < seuilHz(txSuitVite)) return false
        // Hors de ce cas, la montée suit vite si on le lui a demandé, et
        // attend la fin du délai de reprise sinon.
        return txSuitVite || !operateurTourne
    }
}
