/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import java.util.concurrent.ConcurrentHashMap

/**
 * GF(2^m) by log and antilog tables, α a root of the first BCH polynomial
 * of table 6a (m = 16) or 6b (m = 14), which is primitive.
 */
class CorpsGalois(val m: Int, primitif: IntArray) {
    val ordre = (1 shl m) - 1
    val exp = IntArray(2 * ordre + 2)
    val log = IntArray(1 shl m)

    init {
        var poly = 0
        for (e in primitif) if (e < m) poly = poly or (1 shl e)
        var x = 1
        for (i in 0 until ordre) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and (1 shl m) != 0) x = (x xor (1 shl m)) xor poly
        }
        check(x == 1) { "polynôme non primitif" }
        for (i in ordre until exp.size) exp[i] = exp[i - ordre]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    fun inv(a: Int): Int = exp[ordre - log[a]]
    /** α^e, any e ≥ 0. */
    fun puissance(e: Long): Int = exp[(e % ordre).toInt()]
}

/**
 * The BCH outer code decoded with its corrections (up to t errors): the
 * remainder by g(x), the syndromes S1…S2t from it, Berlekamp–Massey, then
 * Chien's search over the shortened word's positions only.
 * Bits one per byte, the first the highest degree (as [Bch] encodes them).
 */
class DecodeurBch private constructor(val courte: Boolean, val t: Int, val nbch: Int) {
    private val gf = if (courte) CORPS_COURT else CORPS_NORMAL
    private val bch = Bch(nbch - (if (courte) 14 else 16) * t, nbch, courte, t)
    private val r = bch.r
    private val mots = (r + 63) / 64
    private val masque = LongArray(mots).also { for (i in 0 until r) if (bch.generateur[i]) it[i / 64] = it[i / 64] or (1L shl (i % 64)) }

    init {
        // g(α^j) = 0 for j = 1…2t: the syndromes may be read on the remainder.
        for (j in 1..2 * t) {
            var v = 0
            for (i in r downTo 0) { v = gf.mul(v, gf.puissance(j.toLong())); if (bch.generateur[i]) v = v xor 1 }
            check(v == 0) { "BCH : α^$j n'est pas racine de g" }
        }
    }

    /** Corrects [bits] (nbch) in place: the errors corrected (0: none), or −1 when beyond t. */
    fun corrige(bits: ByteArray): Int {
        // x^r·c(x) mod g(x), the register as in the encoder.
        val reg = LongArray(mots)
        val haut = r - 1
        val hw = haut / 64; val hb = haut % 64
        for (k in 0 until nbch) {
            val fb = (bits[k].toInt() and 1) xor ((reg[hw] ushr hb).toInt() and 1)
            for (w in mots - 1 downTo 1) reg[w] = (reg[w] shl 1) or (reg[w - 1] ushr 63)
            reg[0] = reg[0] shl 1
            if (fb != 0) for (w in 0 until mots) reg[w] = reg[w] xor masque[w]
        }
        if (r % 64 != 0) reg[mots - 1] = reg[mots - 1] and ((1L shl (r % 64)) - 1)
        if (reg.all { it == 0L }) return 0
        // S_j = rem(α^j): rem(x) = Σ reg_i x^i.
        val s = IntArray(2 * t + 1)
        for (j in 1..2 * t) {
            var v = 0
            val aj = gf.puissance(j.toLong())
            for (i in r - 1 downTo 0) {
                v = gf.mul(v, aj)
                if ((reg[i / 64] ushr (i % 64)) and 1L != 0L) v = v xor 1
            }
            // The register holds x^r·c(x) mod g(x): its value at α^j is α^(jr)·c(α^j).
            s[j] = gf.mul(v, gf.puissance(gf.ordre.toLong() * j - (j.toLong() * r) % gf.ordre))
        }
        // Berlekamp–Massey.
        var lambda = IntArray(t + 2).also { it[0] = 1 }
        var b = IntArray(t + 2).also { it[0] = 1 }
        var l = 0
        var mDec = 1
        var bb = 1
        for (nn in 0 until 2 * t) {
            var d = s[nn + 1]
            for (i in 1..l) d = d xor gf.mul(lambda[i], s[nn + 1 - i])
            if (d == 0) { mDec++; continue }
            val coef = gf.mul(d, gf.inv(bb))
            val tLambda = lambda.copyOf()
            for (i in 0 until t + 2 - mDec) if (b[i] != 0) lambda[i + mDec] = lambda[i + mDec] xor gf.mul(coef, b[i])
            if (2 * l <= nn) { l = nn + 1 - l; b = tLambda; bb = d; mDec = 1 } else mDec++
        }
        if (l > t) return -1
        for (i in l + 1 until lambda.size) if (lambda[i] != 0) return -1
        // Chien: position p (x^p) is wrong when Λ(α^−p) = 0; bit index nbch − 1 − p.
        val termes = IntArray(l + 1) { lambda[it] }
        val pas = IntArray(l + 1) { i -> gf.puissance(((gf.ordre - i).toLong() * 1) % gf.ordre) }
        var trouves = 0
        val positions = IntArray(l)
        for (p in 0 until nbch) {
            var v = 0
            for (i in 0..l) v = v xor termes[i]
            if (v == 0) {
                if (trouves == l) return -1
                positions[trouves++] = p
            }
            for (i in 1..l) termes[i] = gf.mul(termes[i], pas[i])
        }
        if (trouves != l) return -1
        for (p in positions) { val k = nbch - 1 - p; bits[k] = (bits[k].toInt() xor 1).toByte() }
        return l
    }

    companion object {
        val CORPS_NORMAL by lazy { CorpsGalois(16, Bch.POLYS_NORMALES[0]) }
        val CORPS_COURT by lazy { CorpsGalois(14, Bch.POLYS_COURTES[0]) }
        private val cache = ConcurrentHashMap<Int, DecodeurBch>()

        fun pour(m: Modcod): DecodeurBch = cache.computeIfAbsent(m.taux.ordinal * 2 + (if (m.courte) 1 else 0)) { DecodeurBch(m.courte, m.t, m.kldpc) }
    }
}
