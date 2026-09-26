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
 * La disposition des touches du clavier de saisie.
 *
 * L'alphabétique était seul, et pour une raison défendable : sur une grille de
 * six de large, l'ordre alphabétique **se cherche des yeux**, tandis qu'une
 * disposition AZERTY se cherche du doigt, par habitude — habitude qu'on n'a
 * pas sur six colonnes. Le raisonnement tenait tant qu'on gardait six colonnes.
 *
 * Il tombe dès qu'on rend aux dispositions leurs vraies rangées : dix, neuf et
 * sept touches, comme sur le clavier que l'opérateur utilise tous les jours.
 * Là, l'habitude transfère — et c'est bien pour cela qu'Olivier les demande.
 *
 * Le prix est réel et il faut le dire : dix colonnes au lieu de six font des
 * touches nettement plus étroites. C'est un arbitrage entre la vitesse de
 * l'habitude et la sûreté de la cible, et il n'appartient qu'à celui qui tape.
 * D'où un réglage, et l'alphabétique conservé par défaut : il ne demande
 * aucune habitude, ce qui est ce qu'il faut à qui découvre l'application.
 */
object DispositionClavier {

    const val ALPHABETIQUE = "abc"
    const val AZERTY = "azerty"
    const val QWERTY = "qwerty"

    /** Les trente-six caractères que le clavier doit porter, sans exception. */
    private val ATTENDUS: Set<Char> = (('A'..'Z') + ('0'..'9')).toSet()

    private val ALPHA: List<List<Char>> = listOf(
        ('A'..'F').toList(),
        ('G'..'L').toList(),
        ('M'..'R').toList(),
        ('S'..'X').toList(),
        listOf('Y', 'Z', '0', '1', '2', '3'),
        listOf('4', '5', '6', '7', '8', '9'),
    )

    // Les chiffres en rangée haute, dans l'ordre du clavier physique : c'est
    // « 1234567890 » qu'on a sous les doigts, et non « 0123456789 ».
    private val CHIFFRES = "1234567890".toList()

    private val AZ: List<List<Char>> = listOf(
        CHIFFRES,
        "AZERTYUIOP".toList(),
        "QSDFGHJKLM".toList(),
        "WXCVBN".toList(),
    )

    private val QW: List<List<Char>> = listOf(
        CHIFFRES,
        "QWERTYUIOP".toList(),
        "ASDFGHJKL".toList(),
        "ZXCVBNM".toList(),
    )

    /**
     * Les rangées à afficher pour la disposition [nom].
     *
     * Un nom inconnu rend l'alphabétique : un réglage venu d'une version
     * ultérieure, ou abîmé, ne doit pas laisser l'opérateur sans clavier.
     */
    fun rangees(nom: String): List<List<Char>> = when (nom) {
        AZERTY -> AZ
        QWERTY -> QW
        else -> ALPHA
    }

    /**
     * Toutes les touches attendues sont-elles présentes, une fois chacune ?
     *
     * Une lettre manquante ne se verrait qu'au moment où un indicatif la
     * réclame, en plein passage, et l'on croirait à une panne du clavier. Le
     * banc pose donc la question à chaque disposition, une fois pour toutes.
     */
    fun complete(rangees: List<List<Char>>): Boolean {
        val touches = rangees.flatten()
        return touches.size == ATTENDUS.size && touches.toSet() == ATTENDUS
    }

    /** Les dispositions offertes, dans l'ordre où l'écran les propose. */
    val toutes: List<String> = listOf(ALPHABETIQUE, AZERTY, QWERTY)
}
