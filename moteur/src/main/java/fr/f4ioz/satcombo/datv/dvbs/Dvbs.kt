/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.dvbs

/*
 * DVB-S (ETSI EN 300 421), the transmit side's channel coding: transport
 * multiplex adaptation and energy dispersal (4.4.1), Reed-Solomon outer code
 * (4.4.2), convolutional interleaving (4.4.3), inner convolutional code with
 * puncturing (4.4.4), QPSK (4.5).
 */

/**
 * The inner code rates (table 2): the puncturing matrix as X and Y rows
 * over one period, 1 = sent. Sent in the order X₁ Y₁ X₂ Y₂ … skipping the
 * punctured ones; the serial bits go alternately to I and Q.
 */
enum class TauxDvbs(val libelle: String, val num: Int, val den: Int, val x: String, val y: String) {
    R1_2("1/2", 1, 2, "1", "1"),
    R2_3("2/3", 2, 3, "10", "11"),
    R3_4("3/4", 3, 4, "101", "110"),
    R5_6("5/6", 5, 6, "10101", "11010"),
    R7_8("7/8", 7, 8, "1000101", "1111010");

    /** Puncturing period (input bits). */
    val periode: Int get() = x.length
    val code: Double get() = num.toDouble() / den

    /** The transport stream rate (bit/s, 188-byte packets) carried at [rs] symbols/s: Rs × 2 × CR × 188/204. */
    fun debitTs(rs: Double): Double = rs * 2.0 * num / den * 188.0 / 204.0

    /** "QPSK 3/4". */
    val nom: String get() = "QPSK $libelle"

    companion object {
        /** "QPSK 3/4", "3/4" or "DVB-S QPSK 3/4", or null. */
        fun de(s: String?): TauxDvbs? {
            val t = s?.trim()?.removePrefix("DVB-S")?.trim()?.removePrefix("QPSK")?.trim() ?: return null
            return entries.firstOrNull { it.libelle == t }
        }
    }
}

/** GF(256) with p(x) = x⁸ + x⁴ + x³ + x² + 1 (4.4.2). */
object Gf256 {
    const val POLY = 0x11D
    val EXP = IntArray(512)
    val LOG = IntArray(256)

    init {
        var v = 1
        for (i in 0 until 255) {
            EXP[i] = v; LOG[v] = i
            v = v shl 1
            if (v and 0x100 != 0) v = v xor POLY
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
}

/**
 * RS(204,188, T = 8), the shortened RS(255,239): g(x) = (x+λ⁰)(x+λ¹)…(x+λ¹⁵),
 * λ = 02h. The 51 leading zero bytes change nothing to the parity: the 188
 * bytes are encoded as they are, the 16 parity bytes after them.
 */
object Rs204 {
    const val N = 204
    const val K = 188
    const val PARITE = 16

    /** g's coefficients, highest degree first (g[0] = 1). */
    val G: IntArray = run {
        var g = intArrayOf(1)
        for (i in 0 until PARITE) {
            val r = Gf256.EXP[i]
            val n = IntArray(g.size + 1)
            for (j in g.indices) {
                n[j] = n[j] xor g[j]
                n[j + 1] = n[j + 1] xor Gf256.mul(g[j], r)
            }
            g = n
        }
        g
    }

    /** [src]'s 188 bytes from [o], then their parity, into [dst] (204 bytes). */
    fun encode(src: ByteArray, o: Int, dst: ByteArray) {
        val reg = IntArray(PARITE)
        for (k in 0 until K) {
            val b = src[o + k].toInt() and 0xFF
            dst[k] = b.toByte()
            val f = b xor reg[0]
            for (j in 0 until PARITE - 1) reg[j] = reg[j + 1] xor Gf256.mul(f, G[j + 1])
            reg[PARITE - 1] = Gf256.mul(f, G[PARITE])
        }
        for (j in 0 until PARITE) dst[K + j] = reg[j].toByte()
    }
}

/**
 * The energy dispersal sequence (1 + X¹⁴ + X¹⁵, loaded with 100101010000000
 * at each inverted sync byte): 1503 bytes, the first applied to the byte
 * after the inverted sync byte. During the 7 following sync bytes the
 * generator runs but its output is not applied.
 */
object Dispersion {
    val OCTETS: ByteArray = run {
        var r = 0b000000010101001 // cells 1, 4, 6, 8 set (cell k = bit k − 1)
        ByteArray(1503) {
            var v = 0
            repeat(8) {
                val o = ((r shr 13) xor (r shr 14)) and 1
                v = (v shl 1) or o
                r = ((r shl 1) or o) and 0x7FFF
            }
            v.toByte()
        }
    }

    /**
     * Packet [p] of its group of 8 (0: the sync byte inverted) in place, from
     * [o]: the sync byte inverted or kept, the 187 others XORed.
     */
    fun applique(paquet: ByteArray, o: Int, p: Int) {
        paquet[o] = if (p == 0) 0xB8.toByte() else 0x47
        val base = p * 188 - 1
        for (k in 1 until 188) paquet[o + k] = (paquet[o + k].toInt() xor OCTETS[base + k].toInt()).toByte()
    }
}

/**
 * The convolutional interleaver (Forney, I = 12, M = 17): byte k into branch
 * k mod 12, branch j a FIFO of j × 17 bytes; the sync bytes always on
 * branch 0. The FIFOs start full of zeros.
 */
class EntrelaceurDvbs {
    private val fifos = Array(I) { j -> ByteArray(j * M) }
    private val pos = IntArray(I)
    private var branche = 0

    fun pousse(b: Byte): Byte {
        val j = branche
        branche = if (j == I - 1) 0 else j + 1
        if (j == 0) return b
        val f = fifos[j]
        val p = pos[j]
        val sortie = f[p]
        f[p] = b
        pos[j] = if (p == f.size - 1) 0 else p + 1
        return sortie
    }

    companion object {
        const val I = 12
        const val M = 17
    }
}

/**
 * The inner code: K = 7, G1 = 171₈ (X), G2 = 133₈ (Y), the input's MSB first,
 * punctured to [taux]. Coded bits out one per byte, in the order sent.
 */
class CodeurConvolutif(val taux: TauxDvbs) {
    /** The last 6 inputs, the most recent in bit 5. */
    private var etat = 0
    private var phase = 0
    private val px = BooleanArray(taux.periode) { taux.x[it] == '1' }
    private val py = BooleanArray(taux.periode) { taux.y[it] == '1' }

    /** One input bit; the bits sent into [dst] from [o]; how many (0 to 2). */
    fun bit(u: Int, dst: ByteArray, o: Int): Int {
        // Register: u, then d1 (bit 5) … d6 (bit 0); 171₈ = 1111001, 133₈ = 1011011 (input first).
        val r = (u shl 6) or etat
        val x = Integer.bitCount(r and G1) and 1
        val y = Integer.bitCount(r and G2) and 1
        etat = r shr 1
        var n = 0
        if (px[phase]) dst[o + n++] = x.toByte()
        if (py[phase]) dst[o + n++] = y.toByte()
        phase = if (phase == taux.periode - 1) 0 else phase + 1
        return n
    }

    companion object {
        const val G1 = 0b1111001
        const val G2 = 0b1011011
    }
}
