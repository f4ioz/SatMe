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
     * Returns `null` for anything else: tokens, and the 22-bit hashes of
     * compound callsigns, which only [Ft8Indicatifs] can put a name on (a
     * callsign heard in clear before).
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
     * The area digit must land in third position once padded ("F4IOZ" →
     * " F4IOZ", "G0ABC" likewise). The third character is tried before the
     * second: in "2E0XYZ" the area digit is the "0", not the leading "2".
     * Anything else does not fit, which covers every compound callsign.
     */
    private fun cadre(s: String): String? {
        if (s.length !in 3..6) return null
        return when {
            s[2].isDigit() -> s.padEnd(6, ' ')
            s[1].isDigit() -> " " + s.padEnd(5, ' ')
            else -> null
        }?.takeIf { it.length == 6 }
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

    /** Unpacks with the shared table of callsigns heard ([Ft8Indicatifs.commun]). */
    fun deplie(message: BooleanArray): Message? = deplie(message, Ft8Indicatifs.commun)

    /**
     * Unpacks the 77 bits of a message.
     *
     * Handled: **types 1 and 2** (two callsigns and a grid square or report,
     * `/R` or `/P` on either), **type 4** (one compound callsign in clear,
     * the other hashed) and **type 0.1** (the DXpedition's double message).
     * Other types (contest, free text, telemetry) are rejected rather than
     * guessed. An approximate callsign is worse than none: it would end up in
     * a log.
     *
     * Every callsign read in clear goes into [connus]; a hashed one is shown
     * `<F/G4ABC>` when [connus] knows it, `<...>` otherwise — the brackets
     * stay, so the reader sees it came as a hash.
     */
    fun deplie(message: BooleanArray, connus: Ft8Indicatifs): Message? {
        if (message.size < BITS_MESSAGE) return null
        return when (lisEntier(message, 74, 3).toInt()) {
            1 -> deplieStandard(message, "/R", connus)
            2 -> deplieStandard(message, "/P", connus)
            4 -> deplieCompose(message, connus)
            0 -> if (lisEntier(message, 71, 3) == 1L) deplieExpedition(message, connus) else null
            else -> null
        }
    }

    /** Types 1 and 2: c28 r1 c28 r1 R1 g15 i3. */
    private fun deplieStandard(message: BooleanArray, suffixe: String, connus: Ft8Indicatifs): Message? {
        val c1 = lisEntier(message, 0, 28)
        val c2 = lisEntier(message, 29, 28)
        // Bit 59, not 58. Type 1 layout is
        // c28 r1 c28 r1 R1 g15 i3 : 0-27, 28, 29-56, 57, **58**, 59-73, 74-76.
        // Read one bit early, the field swallows the "roger" bit and yields
        // exactly half the value (KO02 = 19402 came out as FH01 = 9701). Only
        // real stations showed it: a bench that writes and reads at the same
        // wrong offset cannot catch this.
        val g15 = lisEntier(message, 59, 15).toInt()

        val appele = champ28(c1, message[28], suffixe, connus) ?: return null
        val appelant = champ28(c2, message[57], suffixe, connus) ?: return null

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

        // "RR73" sent in the square's place (a square in the middle of the Pacific): the acknowledgement,
        // as everyone reads it — else a station saying 73 is drawn 5 000 km away, a "new square" too.
        if (carre == "RR73") { carre = null; accuse = "RR73" }
        val bout = carre ?: accuse
            ?: rapport?.let { (if (it >= 0) "+" else "") + it } ?: ""
        return Message(
            brut = listOf(appele.texte, appelant.texte, bout).filter { it.isNotBlank() }
                .joinToString(" "),
            appelant = appelant.indicatif,
            appele = appele.indicatif,
            locator = carre,
            rapportDb = rapport)
    }

    /** Type 4: h12 c58 h1 r2 c1 — one compound callsign in clear, the other hashed. */
    private fun deplieCompose(message: BooleanArray, connus: Ft8Indicatifs): Message? {
        val h12 = lisEntier(message, 0, 12).toInt()
        val enClair = indicatifDepuis58(lisEntier(message, 12, 58)) ?: return null
        val hacheEnSecond = message[70]
        val r2 = lisEntier(message, 71, 2).toInt()
        val cq = message[73]
        connus.retiens(enClair)
        if (cq) return Message("CQ $enClair", enClair, null, null, null)
        val autre = champHache(h12, 12, connus)
        val clair = Champ(enClair, enClair)
        val (appele, appelant) = if (hacheEnSecond) clair to autre else autre to clair
        val bout = when (r2) { 1 -> "RRR"; 2 -> "RR73"; 3 -> "73"; else -> "" }
        return Message(
            brut = listOf(appele.texte, appelant.texte, bout).filter { it.isNotBlank() }.joinToString(" "),
            appelant = appelant.indicatif, appele = appele.indicatif, locator = null, rapportDb = null)
    }

    /**
     * Type 0.1: c28 c28 h10 r5 — the DXpedition answering two stations at
     * once: "K1ABC RR73; W9XYZ <KH1/KH7Z> -08". The sender is the hashed one.
     */
    private fun deplieExpedition(message: BooleanArray, connus: Ft8Indicatifs): Message? {
        val fini = champ28(lisEntier(message, 0, 28), false, "", connus)
            ?.takeIf { it.indicatif != null } ?: return null
        val suivant = champ28(lisEntier(message, 28, 28), false, "", connus)
            ?.takeIf { it.indicatif != null } ?: return null
        val dx = champHache(lisEntier(message, 56, 10).toInt(), 10, connus)
        val rapport = lisEntier(message, 66, 5).toInt() * 2 - 30
        return Message(
            brut = "${fini.texte} RR73; ${suivant.texte} ${dx.texte} " + (if (rapport >= 0) "+" else "") + rapport,
            appelant = dx.indicatif, appele = suivant.indicatif, locator = null, rapportDb = rapport)
    }

    /** A callsign field: what is shown, and the callsign itself (`null` for a token or an unknown hash). */
    private class Champ(val texte: String, val indicatif: String?)

    /**
     * A 28-bit field: a token (DE, QRZ, CQ, "CQ 123", "CQ DX"), a 22-bit
     * hash, or a standard callsign — shown with its suffix when [r] is set
     * (`/R` in type 1, `/P` in type 2). The callsign itself keeps no suffix:
     * it is the station, for the log and the flags.
     */
    private fun champ28(n: Long, r: Boolean, suffixe: String, connus: Ft8Indicatifs): Champ? {
        when {
            n == 0L -> return Champ("DE", null)
            n == 1L -> return Champ("QRZ", null)
            n == 2L -> return Champ("CQ", null)
            n < 1_003L -> return Champ("CQ " + (n - 3).toString().padStart(3, '0'), null)
            n < 1_003L + 27 * 27 * 27 * 27 -> {
                var v = n - 1_003L
                val c = CharArray(4)
                for (i in 3 downTo 0) { c[i] = A4[(v % 27).toInt()]; v /= 27 }
                val mot = String(c).trim()
                if (mot.isEmpty() || ' ' in mot) return null
                return Champ("CQ $mot", null)
            }
            n < JETONS -> return null
            n < JETONS + HACHES -> return champHache((n - JETONS).toInt(), 22, connus)
        }
        val base = indicatifDepuis28(n) ?: return null
        val entier = if (r) base + suffixe else base
        if (r) connus.retiens(base)
        connus.retiens(entier)
        return Champ(entier, base)
    }

    private fun champHache(h: Int, bits: Int, connus: Ft8Indicatifs): Champ {
        val s = connus.trouve(h, bits)
        return Champ(if (s != null) "<$s>" else "<...>", s)
    }

    /**
     * A 58-bit callsign (type 4): 11 characters of [Ft8Indicatifs.A38] in
     * base 38, left-aligned. `null` past 38^11 or for what does not look like
     * a callsign: a corrupted message that passed the CRC must not show one.
     */
    fun indicatifDepuis58(n: Long): String? {
        if (n < 0) return null
        var v = n
        val c = CharArray(11)
        for (i in 10 downTo 0) { c[i] = Ft8Indicatifs.A38[(v % 38).toInt()]; v /= 38 }
        if (v != 0L) return null
        val s = String(c).trimEnd()
        return Ft8Indicatifs.propre(s)?.takeIf { it == s }
    }

    /** The inverse: up to 11 characters to 58 bits. */
    fun indicatifVers58(indicatif: String): Long? {
        val s = indicatif.trim().uppercase()
        if (s.isEmpty() || s.length > 11) return null
        var v = 0L
        for (i in 0 until 11) {
            val k = Ft8Indicatifs.A38.indexOf(if (i < s.length) s[i] else ' ')
            if (k < 0) return null
            v = v * 38 + k
        }
        return v
    }

    /** A 22-bit hash in a 28-bit field: what a station sends for a callsign it cannot pack. */
    fun hacheVers28(indicatif: String): Long? = Ft8Indicatifs.hache(indicatif, 22)?.let { JETONS + it }

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
