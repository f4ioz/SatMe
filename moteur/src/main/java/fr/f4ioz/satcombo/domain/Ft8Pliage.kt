/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.math.BigInteger

/**
 * FT8/FT4 message packing: a text as typed (« CQ F4IOZ JN18 », « K1ABC
 * F4IOZ R-12 ») to the 77 bits sent — the inverse of [Ft8.deplie].
 *
 * **Provenance.** The field layouts come from the QEX July–August 2020
 * protocol description (K9AN, G4WJS, K1JT), which its authors put in the
 * public domain; nothing is taken from the WSJT-X source. Every layout here
 * is the one [Ft8.deplie] already reads, so a packed message always comes
 * back as itself through SatMe's own decoder (the tests check each type).
 *
 * Packed, in this order of preference:
 *
 * - **type 1** (`c28 r1 c28 r1 R1 g15 i3`): two standard callsigns or
 *   tokens (DE, QRZ, CQ, « CQ 123 », « CQ DX »), `/R` on either, a hashed
 *   one written `<…>`, and a grid, a report, `R` and a report, RRR, RR73,
 *   73 or nothing;
 * - **type 2**: the same with `/P`;
 * - **type 4** (`h12 c58 h1 r2 c1`): one compound callsign in clear
 *   (`PJ4/K1ABC`, `F4IOZ/MM`), the other as a 12-bit hash, RRR/RR73/73 or
 *   nothing, or « CQ » and the compound callsign;
 * - **free text** (type 0.0): up to 13 characters of
 *   ` 0-9 A-Z + - . / ?`.
 *
 * Anything else is refused (null): the contest, telemetry and DXpedition
 * types are not sent by SatMe, and the unassigned types are never claimed —
 * one of the conditions for using the names FT8 and FT4.
 */
object Ft8Pliage {

    /** Free text alphabet (42 characters), base 42, 13 characters in 71 bits. */
    const val A42 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
    const val TEXTE_MAX = 13

    private const val JETONS = 2_063_592L
    private const val GRILLES = 32_400

    /** The message as it would be sent: upper case, single spaces. */
    fun normalise(texte: String): String = texte.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The 77 bits of [texte], or null when no message type SatMe sends can carry it. */
    fun plie(texte: String): BooleanArray? {
        val t = normalise(texte)
        if (t.isEmpty()) return null
        return standard(t) ?: compose(t) ?: libre(t)
    }

    /** Which type [texte] packs to ("standard", "composé", "texte libre"), or null. */
    fun genre(texte: String): String? {
        val t = normalise(texte)
        if (t.isEmpty()) return null
        return when {
            standard(t) != null -> "standard"
            compose(t) != null -> "composé"
            libre(t) != null -> "texte libre"
            else -> null
        }
    }

    // ------------------------------------------------------------- type 1 and 2

    private class Champ28(val valeur: Long, val suffixe: String?)

    /** A 28-bit field: token, hash or standard callsign (with its /R or /P). */
    private fun champ28(mot: String, premier: Boolean): Champ28? {
        if (premier) when (mot) {
            "DE" -> return Champ28(0, null)
            "QRZ" -> return Champ28(1, null)
            "CQ" -> return Champ28(2, null)
        }
        if (premier && mot.startsWith("CQ ")) {
            val m = mot.removePrefix("CQ ")
            if (m.length == 3 && m.all { it.isDigit() }) return Champ28(3L + m.toInt(), null)
            if (m.length in 1..4 && m.all { it in 'A'..'Z' }) {
                // Left-aligned in four base-27 slots (" A..Z"): the decoder trims it back.
                val s = m.padEnd(4, ' ')
                var v = 0L
                for (c in s) v = v * 27 + (if (c == ' ') 0 else c - 'A' + 1)
                return Champ28(1_003L + v, null)
            }
            return null
        }
        if (mot.startsWith("<") && mot.endsWith(">")) {
            val c = mot.removePrefix("<").removeSuffix(">")
            if (Ft8Indicatifs.propre(c) == null) return null
            val h = Ft8Indicatifs.hache(c, 22) ?: return null
            return Champ28(JETONS + h, null)
        }
        val suffixe = when {
            mot.endsWith("/R") -> "/R"
            mot.endsWith("/P") -> "/P"
            else -> null
        }
        val base = if (suffixe != null) mot.dropLast(2) else mot
        val v = Ft8.indicatifVers28(base) ?: return null
        return Champ28(v, suffixe)
    }

    /** The g15 field and the R bit, for what follows both callsigns; null when not one of them. */
    private fun bout(mot: String?): Pair<Int, Boolean>? {
        if (mot == null) return GRILLES + 1 to false
        when (mot) {
            "RRR" -> return GRILLES + 2 to false
            "RR73" -> return GRILLES + 3 to false
            "73" -> return GRILLES + 4 to false
        }
        val r = mot.startsWith("R") && mot.length > 1 && (mot[1] == '+' || mot[1] == '-')
        val m = if (r) mot.substring(1) else mot
        if (Regex("[+-]\\d{1,2}").matches(m)) {
            val db = m.toInt()
            if (db !in RAPPORT_MIN..RAPPORT_MAX) return null
            return GRILLES + 35 + db to r
        }
        grille(m)?.let { return it to false }
        return null
    }

    /** A four-character grid square to its index (AA00 = 0, RR99 = 32 399), or null. */
    fun grille(g: String): Int? {
        if (!Regex("[A-R][A-R][0-9][0-9]").matches(g)) return null
        // « RR73 » looks like a square (in the Pacific): taken as the acknowledgement, as everyone reads it.
        if (g == "RR73") return null
        return (g[0] - 'A') * 1800 + (g[1] - 'A') * 100 + (g[2] - '0') * 10 + (g[3] - '0')
    }

    private fun standard(t: String): BooleanArray? {
        val mots = t.split(' ').toMutableList()
        // « CQ DX K1ABC FN42 », « CQ 123 … »: the CQ and its word are one field.
        if (mots.size >= 3 && mots[0] == "CQ" && Regex("[A-Z]{1,4}|\\d{3}").matches(mots[1])) {
            mots[0] = "CQ " + mots[1]; mots.removeAt(1)
        }
        if (mots.size !in 2..3) return null
        val c1 = champ28(mots[0], true) ?: return null
        val c2 = champ28(mots[1], false) ?: return null
        // A token stands only first, and a CQ carries a grid at most.
        if (c2.valeur < JETONS) return null
        val b = bout(mots.getOrNull(2)) ?: return null
        val estCq = c1.valeur in 2L until JETONS
        if (estCq && mots.size == 3 && b.first > GRILLES + 1) return null
        val suffixes = listOfNotNull(c1.suffixe, c2.suffixe).toSet()
        if (suffixes.size > 1) return null          // /R and /P cannot share one message
        val i3 = if (suffixes.singleOrNull() == "/P") 2L else 1L
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 0, 28, c1.valeur)
        m[28] = c1.suffixe != null
        Ft8.ecritEntier(m, 29, 28, c2.valeur)
        m[57] = c2.suffixe != null
        m[58] = b.second
        Ft8.ecritEntier(m, 59, 15, b.first.toLong())
        Ft8.ecritEntier(m, 74, 3, i3)
        return m
    }

    // ------------------------------------------------------------------ type 4

    /** A callsign that type 1 cannot carry but 58 bits can (compound, long). */
    private fun nonStandard(c: String): Boolean =
        Ft8.indicatifVers28(c) == null && Ft8Indicatifs.propre(c) == c && Ft8.indicatifVers58(c) != null

    private fun compose(t: String): BooleanArray? {
        val mots = t.split(' ')
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(m, 74, 3, 4L)
        if (mots.size == 2 && mots[0] == "CQ" && nonStandard(mots[1])) {
            Ft8.ecritEntier(m, 12, 58, Ft8.indicatifVers58(mots[1])!!)
            m[73] = true
            return m
        }
        if (mots.size !in 2..3) return null
        val r2 = when (mots.getOrNull(2)) { null -> 0L; "RRR" -> 1L; "RR73" -> 2L; "73" -> 3L; else -> return null }
        fun sansCrochets(s: String) = s.removePrefix("<").removeSuffix(">")
        val a = mots[0]; val b = mots[1]
        // The one in clear is the compound one; the other goes as a hash (brackets or not).
        val clairEnPremier = nonStandard(a) && !a.startsWith("<")
        val clairEnSecond = nonStandard(b) && !b.startsWith("<")
        if (clairEnPremier == clairEnSecond) return null
        val clair = if (clairEnPremier) a else b
        val autre = sansCrochets(if (clairEnPremier) b else a)
        if (Ft8Indicatifs.propre(autre) == null) return null
        val h12 = Ft8Indicatifs.hache(autre, 12) ?: return null
        Ft8.ecritEntier(m, 0, 12, h12.toLong())
        Ft8.ecritEntier(m, 12, 58, Ft8.indicatifVers58(clair)!!)
        // The decoder: bit 70 set, the clear callsign comes first and the hashed one second.
        m[70] = clairEnPremier
        Ft8.ecritEntier(m, 71, 2, r2)
        return m
    }

    // ------------------------------------------------------------- free text

    private fun libre(t: String): BooleanArray? {
        if (t.length > TEXTE_MAX || t.any { it !in A42 }) return null
        val s = t.padEnd(TEXTE_MAX, ' ')
        var n = BigInteger.ZERO
        val b42 = BigInteger.valueOf(42)
        for (c in s) n = n.multiply(b42).add(BigInteger.valueOf(A42.indexOf(c).toLong()))
        val m = BooleanArray(Ft8.BITS_MESSAGE)
        for (i in 0 until 71) m[i] = n.testBit(70 - i)
        // n3 = 0 and i3 = 0: bits 71 to 76 stay zero.
        return m
    }

    /** The free text of a type 0.0 message (13 characters, trailing spaces dropped), or null. */
    fun texteLibre(m: BooleanArray): String? {
        if (m.size < Ft8.BITS_MESSAGE) return null
        for (i in 71 until 77) if (m[i]) return null
        var n = BigInteger.ZERO
        for (i in 0 until 71) n = n.shiftLeft(1).let { if (m[i]) it.setBit(0) else it }
        val b42 = BigInteger.valueOf(42)
        val c = CharArray(TEXTE_MAX)
        for (i in TEXTE_MAX - 1 downTo 0) {
            val qr = n.divideAndRemainder(b42)
            c[i] = A42[qr[1].toInt()]
            n = qr[0]
        }
        if (n.signum() != 0) return null
        return String(c).trim().ifEmpty { null }
    }

    /** Reports sent: what g15 holds above the acknowledgements. */
    const val RAPPORT_MIN = -30
    const val RAPPORT_MAX = 49
}
