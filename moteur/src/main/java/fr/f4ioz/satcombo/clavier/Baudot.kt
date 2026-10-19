/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

/**
 * RTTY's five-bit code (ITA2): two pages, letters and figures, chosen by the
 * LTRS and FIGS codes. Bits are sent least significant first.
 */
object Baudot {
    const val LTRS = 0x1F
    const val FIGS = 0x1B
    const val ESPACE = 0x04
    const val CR = 0x08
    const val LF = 0x02

    /** Letters page; '\u0000' for the codes that print nothing. */
    val LETTRES: CharArray = charArrayOf(
        '\u0000', 'E', '\n', 'A', ' ', 'S', 'I', 'U', '\r', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
        'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q', 'O', 'B', 'G', '\u0000', 'M', 'X', 'V', '\u0000',
    )

    /** Figures page (ITA2; the national-use places as amateurs print them, bell and WRU silent). */
    val CHIFFRES: CharArray = charArrayOf(
        '\u0000', '3', '\n', '-', ' ', '\'', '8', '7', '\r', '$', '4', '\u0000', ',', '!', ':', '(',
        '5', '+', ')', '2', '#', '6', '0', '1', '9', '?', '&', '\u0000', '.', '/', '=', '\u0000',
    )

    /**
     * Codes in, text out. [usos] (unshift on space): a space puts the letters
     * page back, as most stations send.
     */
    class Lecteur(var usos: Boolean = true, private val sortie: (Char) -> Unit) {
        var chiffres = false
            private set

        fun code(c: Int) {
            when (c and 0x1F) {
                LTRS -> chiffres = false
                FIGS -> chiffres = true
                else -> {
                    val k = c and 0x1F
                    val ch = (if (chiffres) CHIFFRES else LETTRES)[k]
                    if (k == ESPACE && usos) chiffres = false
                    if (ch != '\u0000') sortie(ch)
                }
            }
        }

        fun reinitialise() { chiffres = false }
    }

    /** [texte] as codes, LTRS first, shifts where the page changes; what ITA2 lacks is dropped. */
    fun encode(texte: String, usos: Boolean = true): IntArray {
        val out = ArrayList<Int>()
        out += LTRS
        var fig = false
        for (raw in texte.uppercase()) {
            val ch = if (raw == '\n') '\n' else raw
            val l = LETTRES.indexOf(ch).takeIf { it > 0 }
            val f = CHIFFRES.indexOf(ch).takeIf { it > 0 }
            when {
                ch == ' ' || ch == '\r' || ch == '\n' -> {
                    out += LETTRES.indexOf(ch)
                    if (ch == ' ' && usos) fig = false
                }
                l != null -> { if (fig) { out += LTRS; fig = false }; out += l }
                f != null -> { if (!fig) { out += FIGS; fig = true }; out += f }
            }
        }
        return out.toIntArray()
    }
}
