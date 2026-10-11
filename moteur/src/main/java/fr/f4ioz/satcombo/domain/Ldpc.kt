/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * The FT8/FT4 error-correcting code: (174, 91), i.e. 83 parity bits for 91
 * payload bits.
 *
 * Without it, only the 14-bit CRC was left: one wrong bit and the message was
 * lost. The code typically repairs about fifteen wrong bits — the difference
 * between hearing the strong stations and hearing the band.
 *
 * Each bit takes part in three parity checks, each check covers six or seven
 * bits, and the graph has no short cycles, so belief propagation works: each
 * check tells each of its bits what the others suggest, the bit sums up, and
 * we repeat until all checks pass or we give up and return nothing.
 *
 * **Normalized min-sum rather than sum-product.** The exact rule needs tanh at
 * every edge and every round; min-sum uses a minimum and a sign, losing a
 * fraction of a dB that a scale factor mostly recovers. A phone must process
 * about thirty candidates in under 2.5 s: this decides whether decoding
 * happens during the slot or after it.
 */
object Ldpc {

    /**
     * Min-sum scale factor.
     *
     * The minimum always overestimates a check's confidence; uncorrected, the
     * decoder convinces itself too fast and locks onto a wrong answer. 0.75 is
     * the usual value for codes of this degree.
     */
    private const val ECHELLE = 0.75f

    /** Result of a decode. */
    data class Resultat(
        /** The 174 corrected bits, or `null` if nothing converged. */
        val bits: BooleanArray?,
        /** How many checks are still violated: zero when good. */
        val restants: Int,
        /** How many rounds were needed. */
        val tours: Int
    )

    /**
     * Checks the 83 parity equations and returns how many are violated.
     *
     * Zero does not prove the message is right — a wrong codeword is still a
     * codeword — but it is necessary; the 14-bit CRC does the rest.
     */
    fun controlesViolés(bits: BooleanArray): Int {
        var mauvais = 0
        for (m in 0 until LdpcTables.M) {
            var parite = false
            for (n in LdpcTables.bitsDuControle[m]) parite = parite xor bits[n]
            if (parite) mauvais++
        }
        return mauvais
    }

    /**
     * Decodes from per-bit log-likelihoods.
     *
     * Convention: **positive means zero is likely**, negative means one, the
     * magnitude is the confidence. This is what [Ft8Signal.vraisemblances]
     * returns, and it must never flip along the way — a decoder fed inverted
     * signs quietly converges to the inverse of the message, with no warning.
     *
     * Returns `null` in [Resultat.bits] if no round satisfies every check.
     * **This is a real branch**: returning the most likely word despite
     * violated checks would be inventing, and an invented callsign ends up in
     * a log.
     */
    fun decode(vraisemblances: FloatArray, toursMax: Int = 30): Resultat {
        require(vraisemblances.size >= LdpcTables.N) {
            "il faut ${LdpcTables.N} vraisemblances"
        }
        val n = LdpcTables.N
        val m = LdpcTables.M

        // Messages on the edges, grouped by check.
        val versBit = Array(m) { FloatArray(LdpcTables.nbParContole[it]) }
        val total = FloatArray(n)
        val bits = BooleanArray(n)

        for (tour in 1..toursMax) {
            // --- bits to checks, then back ---
            for (i in 0 until n) total[i] = vraisemblances[i]
            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                for (j in liste.indices) total[liste[j]] += versBit[c][j]
            }

            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                val sortant = versBit[c]
                // A check's message to a bit must exclude what that bit told
                // it, or the belief feeds on itself.
                var signe = 1
                var min1 = Float.MAX_VALUE
                var min2 = Float.MAX_VALUE
                var argMin = 0
                for (j in liste.indices) {
                    val v = total[liste[j]] - sortant[j]
                    if (v < 0f) signe = -signe
                    val a = abs(v)
                    if (a < min1) { min2 = min1; min1 = a; argMin = j }
                    else if (a < min2) { min2 = a }
                }
                for (j in liste.indices) {
                    val v = total[liste[j]] - sortant[j]
                    val s = if (v < 0f) -signe else signe   // remove the bit's own sign
                    val ampleur = if (j == argMin) min2 else min1
                    sortant[j] = s * ECHELLE * ampleur
                }
            }

            // --- decision and check ---
            for (i in 0 until n) total[i] = vraisemblances[i]
            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                for (j in liste.indices) total[liste[j]] += versBit[c][j]
            }
            for (i in 0 until n) bits[i] = total[i] < 0f

            val mauvais = controlesViolés(bits)
            if (mauvais == 0) return Resultat(bits.copyOf(), 0, tour)
        }
        return Resultat(null, controlesViolés(bits), toursMax)
    }

    /**
     * Computes the 83 parity bits of a 91-bit message.
     *
     * Used by the test bench (corrupt a codeword, check it repairs), and later
     * for transmit.
     */
    fun encode(utiles: BooleanArray): BooleanArray {
        require(utiles.size >= LdpcTables.K) { "il faut ${LdpcTables.K} bits utiles" }
        val sortie = BooleanArray(LdpcTables.N)
        utiles.copyInto(sortie, 0, 0, LdpcTables.K)
        for (m in 0 until LdpcTables.M) {
            var parite = false
            val ligne = LdpcTables.generateur[m]
            for (k in 0 until LdpcTables.K) if (ligne[k] && utiles[k]) parite = !parite
            sortie[LdpcTables.K + m] = parite
        }
        return sortie
    }

    /** Likelihoods for a perfectly received codeword. */
    fun vraisemblancesParfaites(bits: BooleanArray, force: Float = 4f): FloatArray =
        FloatArray(LdpcTables.N) { if (bits[it]) -force else force }

    /** Number of differing bits between two words. */
    fun distance(a: BooleanArray, b: BooleanArray): Int {
        var d = 0
        for (i in 0 until min(a.size, b.size)) if (a[i] != b[i]) d++
        return d
    }
}
