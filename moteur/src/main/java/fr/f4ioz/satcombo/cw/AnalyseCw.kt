/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cw

import fr.f4ioz.satcombo.domain.Dxcc

/**
 * What decoded CW holds, word by word: a callsign that makes sense, a grid
 * square, a report, the procedure words. Decoded Morse has errors; a word is
 * called a callsign only when it looks like one AND something backs it:
 * a country's prefix, its place (after DE, before DE), being sent twice,
 * being in the log.
 */
object AnalyseCw {

    enum class Genre { INDICATIF, CARRE, REPORT, PROCEDURE, AUTRE }

    /** A word: its text, what it is, how sure (callsigns: 0 = doubtful … 4 = certain). */
    class Mot(val texte: String, val genre: Genre, val score: Int = 0) {
        /** Worth showing as a callsign. */
        val coherent: Boolean get() = genre != Genre.INDICATIF || score >= 2
    }

    /** A callsign's body, without what is around it (EA5/F4IOZ, F4IOZ/P → F4IOZ); null if it does not look like one. */
    fun base(indicatif: String): String? = STRUCTURE.find(indicatif.trim().uppercase())?.groupValues?.get(1)

    private val STRUCTURE = Regex("^(?:[A-Z0-9]{1,4}/)?([A-Z0-9]{1,3}[0-9][A-Z]{1,4})(?:/[A-Z0-9]{1,4})?$")
    private val CARRE = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")
    private val REPORT = Regex("^[1-5][1-9N][1-9N]$")
    val PROCEDURE = setOf("CQ", "DE", "K", "KN", "BK", "TU", "73", "R", "RR", "QSL", "QRZ", "QRZ?", "SK", "<SK>", "AR", "<AS>",
        "TNX", "TKS", "GM", "GA", "GE", "GL", "UR", "RST", "QTH", "OP", "NAME", "PSE", "AGN", "HW", "HW?", "FB", "ES", "CFM", "CU", "SAT",
        "VVV", "TEST", "QRS", "QRQ", "=", "+", "?", "/")

    /**
     * The words of [texte], in order. [connus]: callsigns already in the log
     * (their base, without /P), a strong sign a decoded one is right.
     */
    fun analyse(texte: String, connus: Set<String> = emptySet()): List<Mot> {
        val mots = texte.uppercase().split(' ').filter { it.isNotEmpty() }
        val bases = mots.map { m -> STRUCTURE.find(m)?.groupValues?.get(1) }
        val compte = bases.filterNotNull().groupingBy { it }.eachCount()
        return mots.mapIndexed { i, m ->
            val base = bases[i]
            when {
                m in PROCEDURE -> Mot(m, Genre.PROCEDURE)
                REPORT.matches(m) -> Mot(m, Genre.REPORT)
                // A square before a callsign test: JN18 is not a call; a square has letters A to R only.
                CARRE.matches(m) -> Mot(m, Genre.CARRE)
                base != null && base.any { it.isLetter() } && base.length >= 3 -> {
                    var s = 0
                    if (Dxcc.entite(m) != null) s++
                    if (mots.getOrNull(i - 1) == "DE" || mots.getOrNull(i + 1) == "DE") s++
                    if ((compte[base] ?: 0) >= 2) s++
                    if (base in connus) s++
                    // No country's prefix: never sure, whatever else.
                    if (Dxcc.entite(m) == null) s = minOf(s, 1)
                    Mot(m, Genre.INDICATIF, s)
                }
                else -> Mot(m, Genre.AUTRE)
            }
        }
    }
}
