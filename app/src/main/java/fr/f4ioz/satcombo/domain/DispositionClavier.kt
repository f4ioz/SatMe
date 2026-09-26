/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * Key layout of the callsign keypad.
 *
 * On a six-wide grid, alphabetical order is **found by eye**, while AZERTY is
 * found by finger habit — a habit that does not exist on six columns. With the
 * real keyboard rows (up to ten keys), the habit transfers.
 *
 * The cost: ten columns make much narrower keys. Habit speed versus target
 * safety is the typist's call, hence a setting. Alphabetical stays the
 * default since it needs no habit.
 */
object DispositionClavier {

    const val ALPHABETIQUE = "abc"
    const val AZERTY = "azerty"
    const val QWERTY = "qwerty"

    /** The thirty-six characters the keypad must carry, no exception. */
    private val ATTENDUS: Set<Char> = (('A'..'Z') + ('0'..'9')).toSet()

    private val ALPHA: List<List<Char>> = listOf(
        ('A'..'F').toList(),
        ('G'..'L').toList(),
        ('M'..'R').toList(),
        ('S'..'X').toList(),
        listOf('Y', 'Z', '0', '1', '2', '3'),
        listOf('4', '5', '6', '7', '8', '9'),
    )

    // Digits on the top row in physical keyboard order: "1234567890", not
    // "0123456789".
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
     * Rows to show for layout [nom]. An unknown name gives alphabetical: a
     * setting from a later version, or corrupted, must not leave the operator
     * without a keypad.
     */
    fun rangees(nom: String): List<List<Char>> = when (nom) {
        AZERTY -> AZ
        QWERTY -> QW
        else -> ALPHA
    }

    /**
     * Is every expected key present exactly once? A missing letter would only
     * show when a callsign needs it, mid-pass; the tests check each layout.
     */
    fun complete(rangees: List<List<Char>>): Boolean {
        val touches = rangees.flatten()
        return touches.size == ATTENDUS.size && touches.toSet() == ATTENDUS
    }

    /** Available layouts, in the order the screen offers them. */
    val toutes: List<String> = listOf(ALPHABETIQUE, AZERTY, QWERTY)
}
