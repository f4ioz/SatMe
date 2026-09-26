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
 * FT8 message coding, without the radio part.
 *
 * FT8 sends **79 symbols** of 8-FSK at 6.25 baud and 6.25 Hz spacing: 12.64 s
 * of transmission in a 15 s slot. Three 7-symbol Costas arrays (start, middle,
 * end) are the sync markers. The remaining 58 symbols carry 174 bits: 77
 * message bits, 14 CRC bits, 83 parity bits.
 *
 * This file handles symbols, CRC and message unpacking. The 83 parity bits
 * are left to [Ldpc]. The rule for the whole chain: a message is shown only
 * when the CRC checks. A decoder that misses never fooled anyone; a decoder
 * that invents does.
 */
object Ft8 {

    /** Seven sync symbols, at the start, middle and end. */
    val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)

    const val SYMBOLES = 79
    const val SYMBOLES_DONNEES = 58
    const val BITS = 174
    /** Message + CRC, before the parity bits. */
    const val BITS_UTILES = 91
    const val BITS_MESSAGE = 77

    /** 6.25 Hz spacing, 6.25 baud: a symbol lasts 0.16 s. */
    const val ECART_HZ = 6.25
    const val DUREE_SYMBOLE_S = 0.16
    /** A transmission lasts 12.64 s in a 15 s slot. */
    const val DUREE_S = SYMBOLES * DUREE_SYMBOLE_S

    /**
     * Gray code, value → tone (used as `GRAY[v]` when encoding).
     *
     * Neighbouring tones differ by a single bit, so landing one tone off
     * corrupts one bit out of three instead of up to three.
     */
    private val GRAY = intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
    private val GRAY_INVERSE = IntArray(8).also { inv ->
        GRAY.forEachIndexed { valeur, ton -> inv[ton] = valeur }
    }

    /** The 58 data symbols to 174 bits. */
    fun symbolesVersBits(tons: IntArray): BooleanArray {
        val bits = BooleanArray(BITS)
        var i = 0
        donnees(tons).forEach { ton ->
            val v = GRAY_INVERSE[ton and 7]
            bits[i++] = (v shr 2) and 1 == 1
            bits[i++] = (v shr 1) and 1 == 1
            bits[i++] = v and 1 == 1
        }
        return bits
    }

    /** The 79 received symbols, minus the three Costas arrays. */
    fun donnees(tons: IntArray): IntArray {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        val out = IntArray(SYMBOLES_DONNEES)
        var j = 0
        for (i in 0 until SYMBOLES) {
            val repere = i < 7 || i in 36..42 || i >= 72
            if (!repere) out[j++] = tons[i]
        }
        return out
    }

    /** 174 bits back to 79 symbols, Costas included. */
    fun bitsVersSymboles(bits: BooleanArray): IntArray {
        require(bits.size == BITS) { "il faut $BITS bits" }
        val tons = IntArray(SYMBOLES)
        var b = 0
        var j = 0
        for (i in 0 until SYMBOLES) {
            tons[i] = when {
                i < 7 -> COSTAS[i]
                i in 36..42 -> COSTAS[i - 36]
                i >= 72 -> COSTAS[i - 72]
                else -> {
                    val v = (if (bits[b]) 4 else 0) or
                        (if (bits[b + 1]) 2 else 0) or
                        (if (bits[b + 2]) 1 else 0)
                    b += 3
                    j++
                    GRAY[v]
                }
            }
        }
        return tons
    }

    /**
     * How many of the 21 Costas symbols match: the sync criterion.
     *
     * 21/21 is ideal; lower scores are accepted and the CRC weeds them out
     * later.
     */
    fun scoreCostas(tons: IntArray): Int {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        var bons = 0
        for (i in 0 until 7) {
            if (tons[i] == COSTAS[i]) bons++
            if (tons[36 + i] == COSTAS[i]) bons++
            if (tons[72 + i] == COSTAS[i]) bons++
        }
        return bons
    }

    // ------------------------------------------------------------------ CRC

    /**
     * 14-bit CRC, polynomial 0x2757, over the 77 message bits followed by five
     * zeros.
     *
     * The odds of passing by chance are one in 16 384: that is the last guard
     * before a message is shown.
     */
    fun crc14(message: BooleanArray): Int {
        require(message.size >= BITS_MESSAGE) { "message trop court" }
        var reg = 0
        // 77 message bits, then five zeros: 82 bits.
        for (i in 0 until BITS_MESSAGE + 5) {
            val bit = if (i < BITS_MESSAGE && message[i]) 1 else 0
            reg = reg shl 1
            if (((reg shr 14) and 1) xor bit == 1) reg = reg xor 0x2757
            reg = reg and 0x3FFF
        }
        return reg
    }

    /** Do the 91 payload bits carry a matching CRC? */
    fun controleJuste(utiles: BooleanArray): Boolean {
        if (utiles.size < BITS_UTILES) return false
        var lu = 0
        for (i in BITS_MESSAGE until BITS_UTILES) {
            lu = (lu shl 1) or (if (utiles[i]) 1 else 0)
        }
        return lu == crc14(utiles)
    }

    /** Appends the CRC to the message: the inverse of [controleJuste]. */
    fun avecControle(message: BooleanArray): BooleanArray {
        val out = BooleanArray(BITS_UTILES)
        message.copyInto(out, 0, 0, BITS_MESSAGE)
        val c = crc14(message)
        for (i in 0 until 14) {
            out[BITS_MESSAGE + i] = (c shr (13 - i)) and 1 == 1
        }
        return out
    }

    // ------------------------------------------------------------ callsigns

    private const val A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val A3 = "0123456789"
    private const val A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Below this come tokens (CQ, DE, QRZ), not callsigns. */
    private const val JETONS = 2_063_592L
    private const val HACHES = 4_194_304L

    /**
     * A standard callsign packed in 28 bits.
     *
     * Six slots of different kinds (letter or digit, digit, letters) combined
     * in mixed radix.
     *
     * Returns `null` for anything else: compound callsigns, prefixes,
     * suffixes. Those travel as 22-bit hashes, which cannot be resolved
     * without having heard the callsign in clear — so nothing is shown rather
     * than a guess.
     */
    fun indicatifDepuis28(n: Long): String? {
        if (n < JETONS + HACHES) return null
        var v = n - JETONS - HACHES
        val c = CharArray(6)
        c[5] = A4[(v % 27).toInt()]; v /= 27
        c[4] = A4[(v % 27).toInt()]; v /= 27
        c[3] = A4[(v % 27).toInt()]; v /= 27
        c[2] = A3[(v % 10).toInt()]; v /= 10
        c[1] = A2[(v % 36).toInt()]; v /= 36
        c[0] = A1[(v % 37).toInt()]
        return String(c).trim().ifBlank { null }
    }

    /** The inverse: a standard callsign to its 28 bits. */
    fun indicatifVers28(indicatif: String): Long? {
        val s = cadre(indicatif.trim().uppercase()) ?: return null
        val i1 = A1.indexOf(s[0]); val i2 = A2.indexOf(s[1])
        val i3 = A3.indexOf(s[2]); val i4 = A4.indexOf(s[3])
        val i5 = A4.indexOf(s[4]); val i6 = A4.indexOf(s[5])
        if (i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0 || i6 < 0) return null
        var v = i1.toLong()
        v = v * 36 + i2; v = v * 10 + i3
        v = v * 27 + i4; v = v * 27 + i5; v = v * 27 + i6
        return v + JETONS + HACHES
    }

    /**
     * Aligns a callsign on the six slots.
     *
     * The digit must land in third position once padded ("F4IOZ" → " F4IOZ",
     * "G0ABC" likewise). A callsign whose first digit is neither second nor
     * third does not fit, which covers every compound callsign.
     */
    private fun cadre(s: String): String? {
        if (s.length !in 3..6) return null
        val pos = s.indexOfFirst { it.isDigit() }
        return when (pos) {
            1 -> " " + s.padEnd(5, ' ')
            2 -> s.padEnd(6, ' ')
            else -> null
        }.takeIf { it?.length == 6 }
    }

    // -------------------------------------------------------------- message

    /** Contents of a decoded message, unpacked. */
    data class Message(
        val brut: String,
        val appelant: String?,
        val appele: String?,
        val locator: String?,
        val rapportDb: Int?,
    )

    /**
     * Unpacks the 77 bits of an ordinary message.
     *
     * Only **type 1** is handled: two standard callsigns and a grid square or
     * report. Other types (contest, free text, compound callsigns) are
     * rejected rather than guessed. An approximate callsign is worse than none:
     * it would end up in a log.
     */
    fun deplie(message: BooleanArray): Message? {
        if (message.size < BITS_MESSAGE) return null
        val i3 = lisEntier(message, 74, 3).toInt()
        if (i3 != 1) return null

        val c1 = lisEntier(message, 0, 28)
        val c2 = lisEntier(message, 29, 28)
        // Bit 59, not 58. Type 1 layout is
        // c28 r1 c28 r1 R1 g15 i3 : 0-27, 28, 29-56, 57, **58**, 59-73, 74-76.
        // Read one bit early, the field swallows the "roger" bit and yields
        // exactly half the value (KO02 = 19402 came out as FH01 = 9701). Only
        // real stations showed it: a bench that writes and reads at the same
        // wrong offset cannot catch this.
        val g15 = lisEntier(message, 59, 15).toInt()

        val appele = jetonOuIndicatif(c1)
        val appelant = jetonOuIndicatif(c2)
        if (appele == null || appelant == null) return null

        var carre: String? = null
        var rapport: Int? = null
        var accuse: String? = null
        if (g15 < 32_400) {
            // Four-character grid square, mixed radix.
            val j = g15
            carre = "" + ('A' + j / (10 * 10 * 18)) +
                ('A' + (j / (10 * 10)) % 18) +
                ('0' + (j / 10) % 10) + ('0' + j % 10)
        } else {
            // Above the grid squares: acknowledgements and reports, all
            // counted from 32 400. Forgetting that offset shows +32 367
            // instead of -33.
            when (val code = g15 - 32_400) {
                1 -> Unit                       // nothing: no grid, no report
                2 -> accuse = "RRR"
                3 -> accuse = "RR73"
                4 -> accuse = "73"
                else -> if (code >= 5) rapport = code - 35
            }
        }

        val bout = carre ?: accuse
            ?: rapport?.let { (if (it >= 0) "+" else "") + it } ?: ""
        return Message(
            brut = listOf(appele, appelant, bout).filter { it.isNotBlank() }
                .joinToString(" "),
            appelant = appelant.takeIf { it != "CQ" && it != "QRZ" && it != "DE" },
            appele = appele.takeIf { it != "CQ" && it != "QRZ" && it != "DE" },
            locator = carre,
            rapportDb = rapport)
    }

    /** The three reserved tokens, then actual callsigns. */
    private fun jetonOuIndicatif(n: Long): String? = when (n) {
        0L -> "DE"
        1L -> "QRZ"
        2L -> "CQ"
        else -> indicatifDepuis28(n)
    }

    private fun lisEntier(bits: BooleanArray, debut: Int, longueur: Int): Long {
        var v = 0L
        for (i in 0 until longueur) {
            v = (v shl 1) or (if (bits[debut + i]) 1L else 0L)
        }
        return v
    }

    /** Writes an integer on `longueur` bits, for the test bench. */
    fun ecritEntier(bits: BooleanArray, debut: Int, longueur: Int, valeur: Long) {
        for (i in 0 until longueur) {
            bits[debut + i] = (valeur shr (longueur - 1 - i)) and 1L == 1L
        }
    }
}
