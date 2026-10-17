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
import kotlin.math.ln
import kotlin.math.tanh

/**
 * The parity-check matrix of one LDPC code (annex B/C), check by check:
 * check j holds the information bits whose accumulator address lands on j,
 * then p_j and p_{j−1} (the staircase). Built once per code, shared.
 */
class GrapheLdpc private constructor(val taux: Taux, val courte: Boolean) {
    val n: Int
    val k: Int
    val m: Int
    /** Check c's edges are [debuts][c] until [debuts][c+1] in [colonnes]. */
    val debuts: IntArray
    val colonnes: IntArray
    val degreMax: Int

    init {
        val mc = Modcod(Constellation.QPSK, taux, courte)
        n = mc.nldpc; k = mc.kldpc; m = n - k
        val q = mc.q
        val t = TablesLdpc.lignes(taux, courte) ?: error("pas de code LDPC ${taux.libelle} en trames courtes")
        val deg = IntArray(m)
        for (g in t.indices) for (x in t[g]) for (j in 0 until 360) deg[(x + j * q) % m]++
        for (c in 0 until m) deg[c] += if (c == 0) 1 else 2
        debuts = IntArray(m + 1)
        for (c in 0 until m) debuts[c + 1] = debuts[c] + deg[c]
        colonnes = IntArray(debuts[m])
        val pos = debuts.copyOf(m)
        for (g in t.indices) for (j in 0 until 360) {
            val v = g * 360 + j
            for (x in t[g]) { val c = (x + j * q) % m; colonnes[pos[c]++] = v }
        }
        for (c in 0 until m) {
            if (c > 0) colonnes[pos[c]++] = k + c - 1
            colonnes[pos[c]++] = k + c
        }
        degreMax = deg.max()
    }

    /** All checks satisfied by the hard decisions of [l] (negative: 1). */
    fun syndromeNul(l: FloatArray): Boolean {
        val col = colonnes; val deb = debuts
        var e = 0
        for (c in 0 until m) {
            var p = 0
            val fin = deb[c + 1]
            while (e < fin) { p = p xor java.lang.Float.floatToRawIntBits(l[col[e]]); e++ }
            if (p < 0) return false
        }
        return true
    }

    companion object {
        private val cache = ConcurrentHashMap<Int, GrapheLdpc>()
        fun de(taux: Taux, courte: Boolean): GrapheLdpc = cache.computeIfAbsent(taux.ordinal * 2 + (if (courte) 1 else 0)) { GrapheLdpc(taux, courte) }
    }
}

/**
 * LDPC decoding by belief propagation, layered (each check's messages used
 * at once by the next check): the check update by φ(x) = −ln tanh(x/2),
 * which is its own inverse, from a table. Soft values are log-likelihood
 * ratios (positive: bit 0), scaled to the noise. Stopped as soon as every
 * check holds. One decoder per thread: it keeps its memory between frames.
 */
class DecodeurLdpc {
    private var r = FloatArray(0)
    private var tmp = FloatArray(64)
    private var phis = FloatArray(64)

    /** Iterations used by the last call. */
    var iterations = 0; private set

    /**
     * Decodes in place: [l] holds the channel's LLRs on entry, the
     * posteriors on return. True when every check holds.
     */
    fun decode(g: GrapheLdpc, l: FloatArray, maxIterations: Int): Boolean {
        iterations = 0
        if (g.syndromeNul(l)) return true
        val ne = g.colonnes.size
        if (r.size < ne) r = FloatArray(ne)
        java.util.Arrays.fill(r, 0, ne, 0f)
        if (tmp.size < g.degreMax) { tmp = FloatArray(g.degreMax); phis = FloatArray(g.degreMax) }
        val rr = r; val t = tmp; val p = phis
        val col = g.colonnes; val deb = g.debuts
        for (it in 1..maxIterations) {
            iterations = it
            var e = 0
            var faux = 0
            for (c in 0 until g.m) {
                val s = e
                val fin = deb[c + 1]
                var somme = 0f; var signe = 0
                var i = 0
                while (e < fin) {
                    val v = l[col[e]] - rr[e]
                    t[i] = v
                    val b = java.lang.Float.floatToRawIntBits(v)
                    signe = signe xor b
                    val f = phi(java.lang.Float.intBitsToFloat(b and 0x7FFFFFFF))
                    p[i] = f; somme += f
                    e++; i++
                }
                signe = signe and Int.MIN_VALUE
                var parite = 0
                e = s; i = 0
                while (e < fin) {
                    val v = t[i]
                    val mag = phi(somme - p[i])
                    // The sign of the others = the product of all signs times this one's.
                    val sg = signe xor (java.lang.Float.floatToRawIntBits(v) and Int.MIN_VALUE)
                    val msg = java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(mag) or sg)
                    rr[e] = msg
                    val nv = v + msg
                    l[col[e]] = nv
                    parite = parite xor java.lang.Float.floatToRawIntBits(nv)
                    e++; i++
                }
                if (parite < 0) faux++
            }
            // Every check held as it was updated: confirmed on the final decisions.
            if (faux == 0 && g.syndromeNul(l)) return true
        }
        return false
    }

    companion object {
        private const val PAS = 128f
        private const val MAX = 24f
        /** φ on [0, MAX) by steps of 1/PAS, each at its step's middle. */
        private val TABLE = FloatArray((MAX * PAS).toInt()) { i -> phiExact((i + 0.5) / PAS) }
        /** The largest message's magnitude (beyond, the float's own limits). */
        private const val PLAFOND = 40f

        private fun phiExact(x: Double): Float = (-ln(tanh(x / 2))).toFloat()

        private const val LN2 = 0.6931472f
        /** ln(1 + (m + 0.5)/128): the mantissa's share of a logarithm. */
        private val LN_MANTISSE = FloatArray(128) { m -> ln(1 + (m + 0.5) / 128).toFloat() }

        /** φ(x), x ≥ 0: near 0, −ln(x/2) (tanh(x/2) ≈ x/2), from the float's exponent and mantissa. */
        fun phi(x: Float): Float {
            if (x < 1f / PAS) {
                if (x <= 1e-17f) return PLAFOND
                val b = java.lang.Float.floatToRawIntBits(x)
                val ln = (((b ushr 23) and 0xFF) - 127) * LN2 + LN_MANTISSE[(b ushr 16) and 0x7F]
                return minOf(PLAFOND, LN2 - ln)
            }
            val i = (x * PAS).toInt()
            return if (i >= TABLE.size) 0f else TABLE[i]
        }
    }
}
