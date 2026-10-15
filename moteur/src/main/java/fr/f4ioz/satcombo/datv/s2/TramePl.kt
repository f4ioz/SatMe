/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Physical layer (5.4, 5.5): bit mapping, PLHEADER (SOF + PLS code,
 * π/2-BPSK), pilots, dummy PLFRAME, PL scrambling (Gold code n).
 * Symbols are complex, I and Q interleaved in a FloatArray, |s| = 1.
 */
object CouchePhysique {
    val R2 = (1.0 / sqrt(2.0)).toFloat()

    /** SOF, 26 bits. */
    const val SOF = 0x18D2E82
    /** What the 64 PLS bits are scrambled with (5.5.2.4). */
    const val BROUILLAGE_PLS = "0111000110011101100000111100100101010011010000100010110111111010"

    /** The (32,6) generator matrix's rows (figure 13b), first row first. */
    private val G = arrayOf(
        "01010101010101010101010101010101", "00110011001100110011001100110011", "00001111000011110000111100001111",
        "00000000111111110000000011111111", "00000000000000001111111111111111", "11111111111111111111111111111111")

    /** The 64-bit PLS code for [modcod] (5 bits) and [type] (2 bits), before its scrambling. */
    fun pls(modcod: Int, type: Int): IntArray {
        val b = IntArray(7) { k -> if (k < 5) (modcod shr (4 - k)) and 1 else if (k == 5) (type shr 1) and 1 else type and 1 }
        val y = IntArray(32)
        for (r in 0 until 6) if (b[r] == 1) for (j in 0 until 32) y[j] = y[j] xor (G[r][j] - '0')
        return IntArray(64) { k -> if (k % 2 == 0) y[k / 2] else y[k / 2] xor b[6] }
    }

    /** The PLHEADER's 90 bits: SOF then the scrambled PLS code. */
    fun enteteBits(modcod: Int, type: Int): IntArray {
        val p = pls(modcod, type)
        return IntArray(90) { k -> if (k < 26) (SOF shr (25 - k)) and 1 else p[k - 26] xor (BROUILLAGE_PLS[k - 26] - '0') }
    }

    /** The PLHEADER as 90 π/2-BPSK symbols into [s] from symbol [debut]. */
    fun entete(modcod: Int, type: Int, s: FloatArray, debut: Int) {
        val y = enteteBits(modcod, type)
        for (k in 0 until 90) {
            val v = R2 * (1 - 2 * y[k])
            // Odd symbols (1-based) on the diagonal, even ones on the other.
            if (k % 2 == 0) { s[2 * (debut + k)] = v; s[2 * (debut + k) + 1] = v }
            else { s[2 * (debut + k)] = -v; s[2 * (debut + k) + 1] = v }
        }
    }

    /** 8PSK, figure 10: the angle of each 3-bit word (MSB first). */
    private val ANGLES_8PSK = doubleArrayOf(PI / 4, 0.0, PI, 5 * PI / 4, PI / 2, 7 * PI / 4, 3 * PI / 4, 3 * PI / 2)
    val PSK8_I = FloatArray(8) { cos(ANGLES_8PSK[it]).toFloat() }
    val PSK8_Q = FloatArray(8) { sin(ANGLES_8PSK[it]).toFloat() }

    /** Maps [bits] (already interleaved for 8PSK) into [s] from symbol [debut]; returns the symbols written. */
    fun mappe(c: Constellation, bits: ByteArray, s: FloatArray, debut: Int): Int {
        var o = 2 * debut
        when (c) {
            Constellation.QPSK -> {
                var k = 0
                while (k < bits.size) {
                    s[o++] = R2 * (1 - 2 * bits[k]); s[o++] = R2 * (1 - 2 * bits[k + 1]); k += 2
                }
                return bits.size / 2
            }
            Constellation.PSK8 -> {
                var k = 0
                while (k < bits.size) {
                    val w = (bits[k].toInt() shl 2) or (bits[k + 1].toInt() shl 1) or bits[k + 2].toInt()
                    s[o++] = PSK8_I[w]; s[o++] = PSK8_Q[w]; k += 3
                }
                return bits.size / 3
            }
        }
    }

    /**
     * The PL scrambling sequence Rₙ(i) ∈ {0,1,2,3} for Gold code [n],
     * i = 0 … 66 419: from x (1+X⁷+X¹⁸) and y (1+Y⁵+Y⁷+Y¹⁰+Y¹⁸).
     */
    fun sequencePl(n: Int = 0, longueur: Int = 66_420): ByteArray {
        val p = (1 shl 18) - 1
        val x = ByteArray(p); val y = ByteArray(p)
        x[0] = 1
        for (i in 0 until 18) y[i] = 1
        for (i in 0 until p - 18) {
            x[i + 18] = (x[i + 7].toInt() xor x[i].toInt()).toByte()
            y[i + 18] = (y[i + 10].toInt() xor y[i + 7].toInt() xor y[i + 5].toInt() xor y[i].toInt()).toByte()
        }
        fun z(i: Int) = x[(i + n) % p].toInt() xor y[i].toInt()
        return ByteArray(longueur) { i -> (2 * z((i + 131_072) % p) + z(i)).toByte() }
    }

    val SEQUENCE_PL: ByteArray by lazy { sequencePl(0) }

    /** Multiplies symbols [debut] to [debut]+[n] of [s] by exp(jRπ/2), R from the sequence's start. */
    fun brouille(s: FloatArray, debut: Int, n: Int, r: ByteArray = SEQUENCE_PL) {
        for (k in 0 until n) {
            val a = 2 * (debut + k)
            val i = s[a]; val q = s[a + 1]
            when (r[k].toInt()) {
                1 -> { s[a] = -q; s[a + 1] = i }
                2 -> { s[a] = -i; s[a + 1] = -q }
                3 -> { s[a] = q; s[a + 1] = -i }
            }
        }
    }

    /** A dummy PLFRAME (header with MODCOD 0, 36 slots of unmodulated carrier, scrambled): 3330 symbols. */
    fun trameVide(): FloatArray {
        val s = FloatArray(2 * SYMBOLES_VIDE)
        entete(0, 0, s, 0)
        for (k in 90 until SYMBOLES_VIDE) { s[2 * k] = R2; s[2 * k + 1] = R2 }
        brouille(s, 90, SYMBOLES_VIDE - 90)
        return s
    }

    const val SYMBOLES_VIDE = 90 + 36 * 90

    /**
     * A PLFRAME from the XFECFRAME's symbols [xfec] (slots × 90): header,
     * the slots with a pilot block after every 16 (none where the next
     * header begins), scrambled after the header.
     */
    fun trame(m: Modcod, xfec: FloatArray): FloatArray {
        val s = FloatArray(2 * m.symboles)
        entete(m.code, m.type, s, 0)
        var o = 90
        val slots = m.slots
        for (sl in 0 until slots) {
            System.arraycopy(xfec, 2 * 90 * sl, s, 2 * o, 2 * 90)
            o += 90
            if (m.pilotes && (sl + 1) % 16 == 0 && sl + 1 < slots) {
                for (k in 0 until 36) { s[2 * o] = R2; s[2 * o + 1] = R2; o++ }
            }
        }
        check(o == m.symboles)
        brouille(s, 90, m.symboles - 90)
        return s
    }
}
