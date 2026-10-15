/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

/*
 * From a BBFRAME to a FECFRAME: CRC-8 (5.1.4), BB scrambling (5.2.2), BCH
 * outer code (5.3.1), LDPC inner code (5.3.2), bit interleaver (5.3.3).
 * Bits are one per byte (0 or 1), first transmitted first.
 */

/** CRC-8, g(X) = X⁸+X⁷+X⁶+X⁴+X²+1, register cleared before each sequence, MSB first. */
object Crc8 {
    const val POLY = 0xD5
    private val TABLE = IntArray(256) { v ->
        var c = v
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor POLY) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    fun octets(b: ByteArray, debut: Int = 0, n: Int = b.size - debut): Int {
        var c = 0
        for (k in debut until debut + n) c = TABLE[c xor (b[k].toInt() and 0xFF)]
        return c
    }
}

/** The BB scrambling sequence (1 + X¹⁴ + X¹⁵, loaded with 100101010000000 at each BBFRAME). */
object BrouilleurBb {
    /** The sequence for the longest BBFRAME (bits), computed once. */
    val SEQUENCE: ByteArray by lazy { suite(58_192) }

    fun suite(n: Int): ByteArray {
        // Cell k of the figure is bit (k − 1).
        var r = 0b000000010101001 // cells 1, 4, 6, 8 set
        val s = ByteArray(n)
        for (i in 0 until n) {
            val o = ((r shr 13) xor (r shr 14)) and 1
            s[i] = o.toByte()
            r = ((r shl 1) or o) and 0x7FFF
        }
        return s
    }

    /** Scrambles [n] bits of [bits] in place. */
    fun applique(bits: ByteArray, n: Int) {
        val s = SEQUENCE
        for (i in 0 until n) bits[i] = (bits[i].toInt() xor s[i].toInt()).toByte()
    }
}

/**
 * The BCH outer code: g(x) the product of the first [t] polynomials of table
 * 6a (normal) or 6b (short frame); systematic, parity after the message.
 */
class Bch(val kbch: Int, val nbch: Int, courte: Boolean, t: Int) {
    /** Parity bits (nbch − kbch). */
    val r = nbch - kbch
    /** The generator's coefficients, g[i] of x^i. */
    val generateur: BooleanArray
    private val mots = (r + 63) / 64
    private val masque = LongArray(mots)

    init {
        var g = booleanArrayOf(true)
        val polys = if (courte) POLYS_COURTES else POLYS_NORMALES
        for (k in 0 until t) g = multiplie(g, polys[k])
        check(g.size - 1 == r) { "BCH : degré ${g.size - 1} au lieu de $r" }
        generateur = g
        for (i in 0 until r) if (g[i]) masque[i / 64] = masque[i / 64] or (1L shl (i % 64))
    }

    /** The [r] parity bits of [msg] (kbch bits) into [parite] from [debut], highest degree first. */
    fun encode(msg: ByteArray, parite: ByteArray, debut: Int) {
        val reg = LongArray(mots)
        val haut = r - 1
        for (k in 0 until kbch) {
            val fb = (msg[k].toInt() and 1) xor ((reg[haut / 64] ushr (haut % 64)).toInt() and 1)
            // Shift up one place.
            for (w in mots - 1 downTo 1) reg[w] = (reg[w] shl 1) or (reg[w - 1] ushr 63)
            reg[0] = reg[0] shl 1
            if (fb != 0) for (w in 0 until mots) reg[w] = reg[w] xor masque[w]
        }
        if (r % 64 != 0) reg[mots - 1] = reg[mots - 1] and ((1L shl (r % 64)) - 1)
        for (j in 0 until r) {
            val d = haut - j
            parite[debut + j] = ((reg[d / 64] ushr (d % 64)) and 1L).toByte()
        }
    }

    companion object {
        /** Table 6a, as exponent lists. */
        val POLYS_NORMALES: List<IntArray> = listOf(
            intArrayOf(0, 2, 3, 5, 16), intArrayOf(0, 1, 4, 5, 6, 8, 16), intArrayOf(0, 2, 3, 4, 5, 7, 8, 9, 10, 11, 16),
            intArrayOf(0, 2, 4, 6, 9, 11, 12, 14, 16), intArrayOf(0, 1, 2, 3, 5, 8, 9, 10, 11, 12, 16),
            intArrayOf(0, 2, 4, 5, 7, 8, 9, 10, 12, 13, 14, 15, 16), intArrayOf(0, 2, 5, 6, 8, 9, 10, 11, 13, 15, 16),
            intArrayOf(0, 1, 2, 5, 6, 8, 9, 12, 13, 14, 16), intArrayOf(0, 5, 7, 9, 10, 11, 16),
            intArrayOf(0, 1, 2, 5, 7, 8, 10, 12, 13, 14, 16), intArrayOf(0, 2, 3, 5, 9, 11, 12, 13, 16),
            intArrayOf(0, 1, 5, 6, 7, 9, 11, 12, 16))
        /** Table 6b. */
        val POLYS_COURTES: List<IntArray> = listOf(
            intArrayOf(0, 1, 3, 5, 14), intArrayOf(0, 6, 8, 11, 14), intArrayOf(0, 1, 2, 6, 9, 10, 14), intArrayOf(0, 4, 7, 8, 10, 12, 14),
            intArrayOf(0, 2, 4, 6, 8, 9, 11, 13, 14), intArrayOf(0, 3, 7, 8, 9, 13, 14), intArrayOf(0, 2, 5, 6, 7, 10, 11, 13, 14),
            intArrayOf(0, 5, 8, 9, 10, 11, 14), intArrayOf(0, 1, 2, 3, 9, 10, 14), intArrayOf(0, 3, 6, 9, 11, 12, 14),
            intArrayOf(0, 4, 11, 12, 14), intArrayOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 13, 14))

        fun multiplie(a: BooleanArray, exposants: IntArray): BooleanArray {
            val b = BooleanArray(exposants.max() + 1).also { for (e in exposants) it[e] = true }
            val c = BooleanArray(a.size + b.size - 1)
            for (i in a.indices) if (a[i]) for (j in b.indices) if (b[j]) c[i + j] = !c[i + j]
            return c
        }
    }
}

/**
 * The LDPC inner code of annex B/C: each group of 360 information bits
 * accumulated at {x + (m mod 360)·q} mod (n − k), then pᵢ ⊕= pᵢ₋₁.
 */
class Ldpc(val taux: Taux, val courte: Boolean) {
    val modcod = Modcod(Constellation.QPSK, taux, courte)
    val n = modcod.nldpc
    val k = modcod.kldpc
    val m = n - k
    val q = modcod.q
    /** The table's rows, one per group of 360 bits. */
    val lignes: Array<IntArray> = TablesLdpc.lignes(taux, courte) ?: error("pas de code LDPC ${taux.libelle} en trames courtes")

    init {
        check(lignes.size * 360 == k) { "table LDPC ${taux.libelle} : ${lignes.size} lignes pour k = $k" }
        check(360 * q == m)
    }

    /** The [m] parity bits of [info] (k bits) into [parite] from [debut]. */
    fun encode(info: ByteArray, parite: ByteArray, debut: Int) {
        val p = ByteArray(m)
        for (g in lignes.indices) {
            val ligne = lignes[g]
            val base = g * 360
            for (j in 0 until 360) {
                if (info[base + j].toInt() == 0) continue
                val d = j * q
                for (x in ligne) {
                    var a = x + d
                    if (a >= m) a -= m
                    p[a] = (p[a].toInt() xor 1).toByte()
                }
            }
        }
        var acc = 0
        for (i in 0 until m) { acc = acc xor p[i].toInt(); parite[debut + i] = acc.toByte() }
    }
}

/** The 8PSK block interleaver: written down columns, read across rows (5.3.3). */
object Entrelaceur {
    /** [bits] (n, 8PSK) to the order the mapper reads them, three by three. */
    fun psk8(bits: ByteArray, sortie: ByteArray, taux: Taux) {
        val n = bits.size
        val lignes = n / 3
        // 3/5: the first column read out third.
        val ordre = if (taux == Taux.R3_5) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
        var o = 0
        for (r in 0 until lignes) for (c in ordre) sortie[o++] = bits[c * lignes + r]
    }
}
