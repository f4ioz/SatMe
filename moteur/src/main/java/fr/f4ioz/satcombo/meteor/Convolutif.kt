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
 * The CCSDS convolutional code (rate 1/2, constraint length 7, generators
 * 171 and 133 octal), and its soft-decision Viterbi decoder.
 *
 * The shift register keeps the newest bit in its lowest bit; the generators
 * are written for that order (0x4F and 0x6D are 171 and 133 read backwards).
 *
 * Soft symbols are signed bytes: positive for a 1, negative for a 0, the
 * magnitude the confidence; 0 knows nothing (an erased symbol).
 */
object Convolutif {
    const val G1 = 0x4F
    const val G2 = 0x6D
    const val ETATS = 64

    private fun parite(x: Int): Int = Integer.bitCount(x) and 1

    /** Two coded bits per data bit (G1 then G2), from the register [etat]. Returns the new register. */
    fun code(bits: IntArray, sortie: IntArray, etat: Int = 0): Int {
        var sr = etat
        for ((i, b) in bits.withIndex()) {
            sr = ((sr shl 1) or (b and 1)) and 0x7F
            sortie[2 * i] = parite(sr and G1)
            sortie[2 * i + 1] = parite(sr and G2)
        }
        return sr and 0x3F
    }

    /** The two coded bits for state [etat] (6 bits) and input [b], packed: G1 in bit 1, G2 in bit 0. */
    private val SORTIES = IntArray(128) { sr -> (parite(sr and G1) shl 1) or parite(sr and G2) }

    /**
     * A running Viterbi decoder: symbols go in by pairs, bits come out
     * [RETARD] steps later (the survivors have merged by then).
     */
    class Decodeur {
        private var metriques = IntArray(ETATS)
        private var suivantes = IntArray(ETATS)
        // One decision bit per state and per step: 64 states fit a Long.
        private var decisions = LongArray(1 shl 14)
        private var n = 0L          // steps taken
        private var sortis = 0L     // bits output

        fun reinitialise() { metriques.fill(0); n = 0; sortis = 0 }

        /**
         * One step: the soft symbols [a] (G1) and [b] (G2). The branch cost
         * is the disagreement with the symbol each branch expects.
         */
        fun pas(a: Int, b: Int) {
            val m = metriques; val s = suivantes
            var d = 0L
            // New state ns = ((old << 1) | bit) & 63; its two predecessors are old = ns >> 1 and (ns >> 1) | 32.
            for (ns in 0 until ETATS) {
                val bit = ns and 1
                val p0 = ns ushr 1
                val p1 = p0 or 32
                val o0 = SORTIES[(p0 shl 1) or bit]
                val o1 = SORTIES[(p1 shl 1) or bit]
                val c0 = m[p0] + cout(o0, a, b)
                val c1 = m[p1] + cout(o1, a, b)
                if (c1 < c0) { s[ns] = c1; d = d or (1L shl ns) } else s[ns] = c0
            }
            // Keep the metrics small.
            var min = Int.MAX_VALUE
            for (v in s) if (v < min) min = v
            for (i in 0 until ETATS) s[i] -= min
            metriques = s; suivantes = m
            decisions[(n and (decisions.size - 1).toLong()).toInt()] = d
            n++
        }

        /** Correlation with the expected symbols, as a cost: the best metric for Gaussian noise. */
        private fun cout(o: Int, a: Int, b: Int): Int =
            (if (o and 2 != 0) -a else a) + (if (o and 1 != 0) -b else b) + 254

        /** Bits ready: all but the last [RETARD] steps. Writes them to [sortie] from [debut]; returns how many. */
        fun lis(sortie: IntArray, debut: Int = 0, final: Boolean = false): Int {
            val jusqua = if (final) n else n - RETARD
            if (jusqua <= sortis) return 0
            // Trace back from the best state now.
            var etat = 0
            var best = Int.MAX_VALUE
            for (i in 0 until ETATS) if (metriques[i] < best) { best = metriques[i]; etat = i }
            var t = n - 1
            val combien = (jusqua - sortis).toInt()
            // Walk back to step jusqua - 1 without output.
            while (t >= jusqua) { etat = precedent(etat, t); t-- }
            for (k in combien - 1 downTo 0) {
                sortie[debut + k] = etat and 1
                etat = precedent(etat, t); t--
            }
            sortis = jusqua
            return combien
        }

        private fun precedent(etat: Int, t: Long): Int {
            val d = decisions[(t and (decisions.size - 1).toLong()).toInt()]
            return (etat ushr 1) or (if ((d ushr etat) and 1L != 0L) 32 else 0)
        }

        /** Steps whose bits are not out yet. */
        val enAttente: Long get() = n - sortis
    }

    /** How far back the survivors are taken: well beyond the 5 × K usually enough. */
    const val RETARD = 96L
}
