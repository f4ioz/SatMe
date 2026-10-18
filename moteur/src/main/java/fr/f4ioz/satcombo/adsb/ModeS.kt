/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

/**
 * Mode S replies on 1090 MHz, as ICAO Annex 10 Vol. IV lays them out: 56
 * or 112 bits, the downlink format (DF) in the first five, the last 24 a
 * parity field — the remainder of the rest by the generator polynomial
 * x²⁴ + … (0x1FFF409), XORed with the address or interrogator code
 * depending on the format.
 *
 * Written from the standard's bit layout; nothing here transmits.
 */
object ModeS {

    /** The generator's 24 low bits (its x²⁴ term implied). */
    const val POLY = 0xFFF409

    const val LONG = 112
    const val COURT = 56

    /** A format's length in bits: DF 16 and above are long. */
    fun longueur(df: Int): Int = if (df >= 16) LONG else COURT

    /** The bit at [i] (0 = first sent). */
    fun bit(b: ByteArray, i: Int): Int = (b[i ushr 3].toInt() ushr (7 - (i and 7))) and 1

    /** Bits [de, de + n) as an unsigned number (n ≤ 32). */
    fun bits(b: ByteArray, de: Int, n: Int): Long {
        var v = 0L
        for (i in de until de + n) v = (v shl 1) or bit(b, i).toLong()
        return v
    }

    fun ecrisBits(b: ByteArray, de: Int, n: Int, v: Long) {
        for (k in 0 until n) {
            val i = de + k
            val un = (v ushr (n - 1 - k)) and 1L
            val m = 1 shl (7 - (i and 7))
            b[i ushr 3] = (if (un == 1L) b[i ushr 3].toInt() or m else b[i ushr 3].toInt() and m.inv()).toByte()
        }
    }

    /** The parity computed over the first [nBits] − 24 bits. */
    fun crc(b: ByteArray, nBits: Int): Int {
        var r = 0
        for (i in 0 until nBits - 24) {
            val haut = ((r ushr 23) and 1) xor bit(b, i)
            r = (r shl 1) and 0xFFFFFF
            if (haut == 1) r = r xor POLY
        }
        return r
    }

    /** The parity computed XORed with the one received: 0 for an extended squitter received whole. */
    fun syndrome(b: ByteArray, nBits: Int): Int = crc(b, nBits) xor bits(b, nBits - 24, 24).toInt()

    /** The frame with its parity field set (an extended squitter's, address 0 overlaid). */
    fun scelle(b: ByteArray, nBits: Int, superpose: Int = 0) {
        ecrisBits(b, nBits - 24, 24, (crc(b, nBits) xor superpose).toLong())
    }

    /** The syndrome of each single wrong bit, by length: bit index by syndrome. */
    private val UN_BIT: Map<Int, Map<Int, Int>> = listOf(LONG, COURT).associateWith { n ->
        val m = HashMap<Int, Int>()
        val z = ByteArray(n / 8)
        for (i in 0 until n) {
            z.fill(0)
            z[i ushr 3] = (1 shl (7 - (i and 7))).toByte()
            m[syndrome(z, n)] = i
        }
        m
    }

    /**
     * One wrong bit put right in a DF17/18 frame (the parity linear, each
     * single error gives its own syndrome): the bit fixed, or −1. The DF
     * field itself is never touched — a "fixed" format is a guess.
     */
    fun corrigeUnBit(b: ByteArray, nBits: Int): Int {
        val s = syndrome(b, nBits)
        if (s == 0) return -1
        val i = UN_BIT[nBits]?.get(s) ?: return -1
        if (i < 5) return -1
        b[i ushr 3] = (b[i ushr 3].toInt() xor (1 shl (7 - (i and 7)))).toByte()
        return i
    }

    // ---------------------------------------------------------------- fields

    /** The identification characters (6 bits each): letters 1–26, space 32, digits 48–57. */
    private const val JEU = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

    fun caractere(c: Int): Char = JEU[c and 63]

    fun codeCaractere(ch: Char): Int {
        val u = ch.uppercaseChar()
        return when (u) {
            in 'A'..'Z' -> u - 'A' + 1
            in '0'..'9' -> u - '0' + 48
            else -> 32
        }
    }

    /**
     * A 12-bit altitude (airborne position, bits ME 9–20): with the Q bit,
     * 25 ft steps from −1000 ft; without, the 100 ft Gillham code. Null: none.
     */
    fun altitude12(a: Int): Int? {
        if (a == 0) return null
        if (a and 0x10 != 0) {
            val n = ((a and 0xFE0) ushr 1) or (a and 0x0F)
            return n * 25 - 1000
        }
        // Without Q: insert the missing M position (zero) to make the 13-bit code.
        val ac13 = ((a and 0xFC0) shl 1) or (a and 0x3F)
        return gillham(ac13)
    }

    /**
     * A 13-bit AC field (DF 0, 4, 16, 20): M bit (metres) at 0x40, Q at
     * 0x10. Null when absent or not decodable.
     */
    fun altitude13(ac: Int): Int? {
        if (ac == 0) return null
        if (ac and 0x40 != 0) {
            // Metric: the other 12 bits in metres.
            val m = ((ac and 0x1F80) ushr 1) or (ac and 0x3F)
            return Math.round(m * 3.28084).toInt()
        }
        if (ac and 0x10 != 0) {
            val n = ((ac and 0x1F80) ushr 2) or ((ac and 0x20) ushr 1) or (ac and 0x0F)
            return n * 25 - 1000
        }
        return gillham(ac)
    }

    /**
     * The 100 ft Gillham (Gray) code of a 13-bit AC field, its bits in the
     * order sent: C1 A1 C2 A2 C4 A4 M B1 Q B2 D2 B4 D4.
     */
    fun gillham(ac13: Int): Int? {
        fun b(k: Int) = (ac13 ushr (12 - k)) and 1
        val c1 = b(0); val a1 = b(1); val c2 = b(2); val a2 = b(3); val c4 = b(4); val a4 = b(5)
        val b1 = b(7); val b2 = b(9); val d2 = b(10); val b4 = b(11); val d4 = b(12)
        // D1 is never used (it sits where M or Q is).
        // The 500 ft Gray code: D2 D4 A1 A2 A4 B1 B2 B4.
        val gris500 = (d2 shl 7) or (d4 shl 6) or (a1 shl 5) or (a2 shl 4) or (a4 shl 3) or (b1 shl 2) or (b2 shl 1) or b4
        val n500 = degris(gris500)
        // The 100 ft part: C1 C2 C4, a Gray code of its own over 1..5.
        var n100 = degris((c1 shl 2) or (c2 shl 1) or c4)
        if (n100 == 0 || n100 == 5 || n100 == 6) return null
        if (n100 == 7) n100 = 5
        // The 100 ft sense flips on odd 500 ft steps.
        if (n500 % 2 == 1) n100 = 6 - n100
        return n500 * 500 + n100 * 100 - 1300
    }

    private fun degris(g: Int): Int {
        var n = g; var s = g ushr 1
        while (s != 0) { n = n xor s; s = s ushr 1 }
        return n
    }

    /** The 4-digit identity (squawk) of a 13-bit ID field: C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4. */
    fun squawk(id13: Int): String {
        fun b(k: Int) = (id13 ushr (12 - k)) and 1
        val a = b(1) or (b(3) shl 1) or (b(5) shl 2)
        val bb = b(7) or (b(9) shl 1) or (b(11) shl 2)
        val c = b(0) or (b(2) shl 1) or (b(4) shl 2)
        val d = b(8) or (b(10) shl 1) or (b(12) shl 2)
        return "$a$bb$c$d"
    }
}
