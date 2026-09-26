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
 * Les chaînes de conversion de la station QO-100.
 *
 * Une chaîne, c'est un convertisseur de descente, un de montée, et un nom.
 * Elles vivent ici plutôt que dans les réglages généraux parce qu'elles ne
 * servent qu'à QO-100 : un LNB à 10 345 MHz n'a aucun sens sur RS-44, et le
 * réglage traînait dans un écran où on ne le cherchait pas.
 *
 * **Pourquoi plusieurs.** La station fixe et la station portable n'ont ni les
 * mêmes convertisseurs ni les mêmes oscillateurs, et chaque oscillateur porte
 * sa propre erreur mesurée. Retaper la valeur à chaque changement de site,
 * c'est se tromper un jour — et se tromper de trois cents kilohertz sur QO-100
 * revient à ne rien entendre du tout.
 */
object ChaineQo100 {

    /**
     * Une chaîne complète, telle qu'on la branche.
     *
     * Les oscillateurs sont en hertz et **mesurés, pas nominaux** : c'est tout
     * l'intérêt de les mémoriser. Zéro veut dire « pas de convertisseur de ce
     * côté » — le poste attaque directement, ce qui est le cas d'une clé SDR
     * posée sur la sortie du LNB.
     */
    data class Chaine(
        val nom: String = "",
        val descenteOlHz: Long = 0L,
        val monteeOlHz: Long = 0L,
    ) {
        val descenteActive: Boolean get() = descenteOlHz > 0L
        val monteeActive: Boolean get() = monteeOlHz > 0L

        /** La fréquence que le poste affichera en réception. */
        fun posteRx(cielHz: Long): Long =
            if (descenteActive) cielHz - descenteOlHz else cielHz

        /** La fréquence que le poste affichera en émission. */
        fun posteTx(monteeHz: Long): Long =
            if (monteeActive) monteeHz - monteeOlHz else monteeHz
    }

    /**
     * L'oscillateur local, déduit de **deux fréquences observées**.
     *
     * C'est la mesure que fait tout le monde et que personne n'automatise :
     * on écoute un signal sur un WebSDR de référence, on écoute le même signal
     * sur son poste, et l'écart est l'oscillateur de la chaîne. Rien d'autre à
     * savoir — ni la valeur nominale du LNB, ni la FI théorique, ni laquelle
     * des deux notices du fabricant dit vrai.
     *
     * L'opérateur n'a donc pas à calculer : il recopie deux nombres qu'il a
     * sous les yeux. La soustraction, elle, ne se trompe jamais de sens.
     */
    fun olMesure(cielHz: Long, posteHz: Long): Long = cielHz - posteHz

    /** Bornes de plausibilité d'un OL de descente : entre 9 et 11 GHz. */
    private val DESCENTE = 9_000_000_000L..11_000_000_000L

    /** Bornes d'un OL de montée : entre 1,5 et 2,4 GHz. */
    private val MONTEE = 1_500_000_000L..2_400_000_000L

    /**
     * Cette mesure est-elle crédible ?
     *
     * On ne refuse pas un chiffre parce qu'il s'écarte du nominal — c'est
     * précisément ce qu'on cherche à mesurer, et l'écart réel peut atteindre
     * plusieurs centaines de kilohertz. On refuse ce qui ne peut pas être un
     * oscillateur du tout : une inversion des deux champs, une virgule
     * déplacée, un mégahertz saisi pour un kilohertz.
     *
     * Un OL faux mais plausible ne se détecte pas ici. Il se détecte à
     * l'oreille, en cherchant la balise — et c'est pour cela que l'écran doit
     * afficher la fréquence obtenue plutôt que de se contenter d'un « validé ».
     */
    fun descenteCredible(olHz: Long): Boolean = olHz in DESCENTE

    fun monteeCredible(olHz: Long): Boolean = olHz in MONTEE

    /**
     * Les deux chaînes qu'on trouve par défaut, à remplir par la mesure.
     *
     * Ce sont des **noms**, pas des valeurs : proposer des oscillateurs
     * nominaux ferait croire qu'ils conviennent, alors que chaque exemplaire a
     * son erreur propre. Mieux vaut un champ vide qui appelle une mesure qu'un
     * chiffre plausible qui n'a jamais été vérifié.
     */
    val PAR_DEFAUT: List<Chaine> = listOf(
        Chaine(nom = "Fixe"),
        Chaine(nom = "Portable"),
    )

    /** Range une chaîne dans la liste, par son nom ; l'ajoute si elle est neuve. */
    fun range(liste: List<Chaine>, chaine: Chaine): List<Chaine> {
        val i = liste.indexOfFirst { it.nom.equals(chaine.nom, ignoreCase = true) }
        return if (i < 0) liste + chaine
        else liste.toMutableList().also { it[i] = chaine }
    }

    /** La chaîne portant ce nom, ou la première, ou une chaîne vide. */
    fun choisie(liste: List<Chaine>, nom: String): Chaine =
        liste.firstOrNull { it.nom.equals(nom, ignoreCase = true) }
            ?: liste.firstOrNull()
            ?: Chaine()
}
