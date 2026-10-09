/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

/**
 * The CCSDS Reed-Solomon code (255, 223): up to 16 wrong bytes corrected per
 * codeword. Field GF(256) on x⁸+x⁷+x²+x+1; roots β^112 … β^143 of β = α¹¹.
 * Conventional representation (not the dual basis): that is how the METEOR
 * transmitters send it.
 *
 * A codeword is 255 bytes in the order they are sent, the first the highest
 * power; 223 of data, 32 of check.
 */
object ReedSolomon {
    const val N = 255
    const val K = 223
    const val NPAR = 32
    private const val FCR = 112
    private const val PRIM = 11

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x187
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun inv(a: Int): Int = EXP[255 - LOG[a]]
    /** β^e, any e. */
    private fun beta(e: Int): Int = EXP[Math.floorMod(PRIM * e, 255)]

    /** The generator polynomial, highest power first (33 coefficients). */
    private val GEN: IntArray by lazy {
        var g = intArrayOf(1)
        for (j in 0 until NPAR) {
            val r = beta(FCR + j)
            val ng = IntArray(g.size + 1)
            for (i in g.indices) {
                ng[i] = ng[i] xor g[i]
                ng[i + 1] = ng[i + 1] xor mul(g[i], r)
            }
            g = ng
        }
        g
    }

    /** The 32 check bytes for 223 data bytes (for tests, and to fill gaps). */
    fun code(donnees: IntArray): IntArray {
        require(donnees.size == K)
        val reste = IntArray(NPAR)
        for (d in donnees) {
            val f = d xor reste[0]
            for (i in 0 until NPAR - 1) reste[i] = reste[i + 1] xor mul(f, GEN[i + 1])
            reste[NPAR - 1] = mul(f, GEN[NPAR])
        }
        return reste
    }

    /**
     * Corrects [c] (255 bytes, 0..255) in place. Returns the number of bytes
     * corrected, or -1 when there are too many errors (left untouched).
     */
    /** Where the last correction was made (byte indexes), for diagnosis. */
    var dernieresPositions: List<Int> = emptyList(); private set

    fun corrige(c: IntArray): Int {
        dernieresPositions = emptyList()
        val s = IntArray(NPAR)
        var nul = true
        for (j in 0 until NPAR) {
            val r = beta(FCR + j)
            var v = 0
            for (x in c) v = mul(v, r) xor x
            s[j] = v
            if (v != 0) nul = false
        }
        if (nul) return 0
        // Berlekamp-Massey: the error locator Λ (lowest power first).
        var lambda = IntArray(NPAR + 1).also { it[0] = 1 }
        var b = IntArray(NPAR + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bb = 1
        for (n in 0 until NPAR) {
            var d = s[n]
            for (i in 1..l) d = d xor mul(lambda[i], s[n - i])
            if (d == 0) { m++; continue }
            val coef = mul(d, inv(bb))
            val t = lambda.copyOf()
            for (i in 0..NPAR - m) lambda[i + m] = lambda[i + m] xor mul(coef, b[i])
            if (2 * l <= n) { l = n + 1 - l; b = t; bb = d; m = 1 } else m++
        }
        if (l > NPAR / 2) return -1
        // Chien: byte at index p is the power 254 - p; its locator X = β^(254 - p).
        val positions = ArrayList<Int>()
        for (p in 0 until N) {
            val xInv = beta(-(N - 1 - p))
            var v = 0
            for (i in l downTo 0) v = mul(v, xInv) xor lambda[i]
            if (v == 0) positions += p
        }
        if (positions.size != l) return -1
        // Ω = S·Λ mod x^32.
        val omega = IntArray(NPAR)
        for (i in 0 until NPAR) {
            var v = 0
            for (k in 0..minOf(i, l)) v = v xor mul(lambda[k], s[i - k])
            omega[i] = v
        }
        // Forney: e = X^(1 - FCR) · Ω(X⁻¹) / Λ'(X⁻¹).
        dernieresPositions = positions
        for (p in positions) {
            val e = N - 1 - p
            val xInv = beta(-e)
            var num = 0
            for (i in NPAR - 1 downTo 0) num = mul(num, xInv) xor omega[i]
            var den = 0
            var i = 1
            while (i <= l) { den = den xor mul(lambda[i], powB(xInv, i - 1)); i += 2 }
            if (den == 0) return -1
            val err = mul(mul(num, inv(den)), beta(e * (1 - FCR)))
            c[p] = c[p] xor err
        }
        return positions.size
    }

    private fun powB(x: Int, n: Int): Int = if (n == 0) 1 else if (x == 0) 0 else EXP[(LOG[x] * n) % 255]
}
