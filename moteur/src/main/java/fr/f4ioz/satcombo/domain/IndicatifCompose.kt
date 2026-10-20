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
 * A compound callsign split into who and where.
 *
 * `EA3/M5JFS` is M5JFS (the person, the directory's record) operating from
 * Spain (EA3: the DXCC entity). `F/ON4ABC/P` is ON4ABC portable in France,
 * `M5JFS/MM` is at sea (no entity), `W1AW/4` is in the 4th call area of its
 * own country, `DL1ABC/OH0` (prefix written after) is on Åland.
 *
 * Unlike [Indicatifs.base], which keeps a country prefix (two entities, two
 * memory entries), [base] here is the operator alone.
 */
object IndicatifCompose {

    /** Operating suffixes: they never name a country. */
    val SUFFIXES = setOf("P", "M", "MM", "AM", "QRP", "QRPP", "A", "B", "LH")

    data class Decoupe(
        /** The whole callsign, upper case, as written. */
        val complet: String,
        /** The operator's own callsign (the directory's record). */
        val base: String,
        /** The location prefix (EA3, F, OH0), or empty when at home. */
        val prefixe: String = "",
        /** The operating suffixes (P, MM, QRP…), in order. */
        val suffixes: List<String> = emptyList(),
        /** The call area changed by a digit (W1AW/4), or empty. */
        val chiffre: String = "",
    ) {
        val maritime: Boolean get() = "MM" in suffixes
        val aeronautique: Boolean get() = "AM" in suffixes

        /** What decides the entity: the location prefix, else the callsign itself. */
        val lieu: String get() = prefixe.ifEmpty { base }

        /** Away from the home address: the directory's position is not this contact's. */
        val deplace: Boolean get() = prefixe.isNotEmpty() || chiffre.isNotEmpty() ||
            suffixes.any { it in setOf("P", "M", "MM", "AM", "A") }

        /** More than the bare callsign. */
        val compose: Boolean get() = complet != base
    }

    /** Looks like a callsign: letters and a digit, ending with a letter. */
    private fun indicatif(s: String): Boolean =
        s.length >= 3 && s.any { it.isDigit() } && s.any { it.isLetter() } && s.last().isLetter()

    /** Looks like a location prefix: short, and known to the entity table or ending with a digit. */
    private fun prefixe(s: String): Boolean =
        s.length in 1..4 && s.all { it.isLetterOrDigit() } && (s.last().isDigit() || Dxcc.prefixe(s) || Dxcc.entite(s, simple = true) != null)

    fun decoupe(brut: String): Decoupe {
        val complet = brut.trim().uppercase().replace(" ", "")
        var morceaux = complet.split('/').filter { it.isNotEmpty() }
        if (morceaux.isEmpty()) return Decoupe(complet, "")
        val suffixes = ArrayList<String>()
        var chiffre = ""
        // From the end: suffixes and a call area digit go first (F/ON4ABC/P, W1AW/4/QRP).
        while (morceaux.size > 1) {
            val q = morceaux.last()
            when {
                q in SUFFIXES -> suffixes.add(0, q)
                q.length == 1 && q[0].isDigit() && chiffre.isEmpty() -> chiffre = q
                else -> break
            }
            morceaux = morceaux.dropLast(1)
        }
        if (morceaux.size == 1) return Decoupe(complet, morceaux[0], "", suffixes, chiffre)
        // The operator: the piece that looks like a callsign, the longest when several do.
        val candidats = morceaux.filter { indicatif(it) }.ifEmpty { morceaux }
        val base = candidats.maxByOrNull { it.length }!!
        // The location: another piece that can be a prefix (before or after the callsign).
        val autres = morceaux.filter { it != base }
        val pre = autres.firstOrNull { prefixe(it) && it.length < base.length }.orEmpty()
        return Decoupe(complet, base, pre, suffixes, chiffre)
    }

    fun base(brut: String): String = decoupe(brut).base
}
