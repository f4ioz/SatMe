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
 * FT4 message coding.
 *
 * FT4 shares the upper stack with FT8 — same 77-bit message, same 14-bit CRC,
 * same (174, 91) code — and differs only in the physical layer: four tones
 * instead of eight, three times faster, and four Costas arrays instead of
 * three.
 *
 * **Provenance.** Everything here comes from the protocol description by
 * Steve Franke K9AN, Bill Somerville G4WJS and Joe Taylor K1JT in QEX,
 * July-August 2020, which the authors place **in the public domain**. Only
 * the WSJT-X source is GPL, and nothing is taken from it: values are copied
 * from the article, not the software.
 *
 * **Conditions attached** to using the name "FT4", not negotiable:
 *
 * - follow the protocol definition — source coding, error correction,
 *   modulation;
 * - **explicitly forbid robotic or unattended QSOs**;
 * - do not claim the still-unassigned message types.
 *
 * The second one is a design constraint, not a footnote: an automaton that
 * answered a call on its own would take away SatMe's right to use the name.
 */
object Ft4 {

    /**
     * The four Costas arrays, at the four corners of the frame.
     *
     * Four **different** arrays, whereas FT8 repeats the same one: distinct
     * patterns tell the receiver *where* in the frame it landed, not just that
     * it hit a marker.
     */
    val COSTAS_1 = intArrayOf(0, 1, 3, 2)
    val COSTAS_2 = intArrayOf(1, 0, 2, 3)
    val COSTAS_3 = intArrayOf(2, 3, 1, 0)
    val COSTAS_4 = intArrayOf(3, 2, 0, 1)

    const val SYMBOLES = 105
    const val SYMBOLES_DONNEES = 87
    const val BITS = 174
    const val BITS_UTILES = 91
    const val BITS_MESSAGE = 77

    /** 12000 / 576 = 20.8333 baud, and the same tone spacing in Hz. */
    const val DUREE_SYMBOLE_S = 576.0 / 12000.0

    /**
     * Frame: R, S1, A, S2, B, S3, C, S4, R.
     *
     * The 87 data symbols form three groups of 29. The R symbols at both ends
     * (tone 0) carry nothing: they ramp the amplitude up and down so the key
     * does not click.
     */
    private val REPERES: List<Pair<Int, IntArray>> = listOf(
        1 to COSTAS_1, 34 to COSTAS_2, 67 to COSTAS_3, 100 to COSTAS_4
    )

    /** True if this channel symbol carries no information. */
    fun estRepere(position: Int): Boolean {
        if (position == 0 || position == SYMBOLES - 1) return true   // ramps
        return REPERES.any { (debut, _) -> position in debut until debut + 4 }
    }

    /** Sync positions and expected tone, for the demodulator. */
    fun synchro(): List<Pair<Int, Int>> {
        val l = ArrayList<Pair<Int, Int>>(16)
        for ((debut, reseau) in REPERES) {
            for (i in 0 until 4) l.add((debut + i) to reseau[i])
        }
        return l
    }

    /**
     * FT4 Gray code, value → tone.
     *
     * The article gives it the other way round: tone 0 = 00, 1 = 01, 2 = 11,
     * 3 = 10. Inverted, that is this table — the first four values of the FT8
     * code.
     */
    private val GRAY = intArrayOf(0, 1, 3, 2)
    private val GRAY_INVERSE = IntArray(4).also { inv ->
        GRAY.forEachIndexed { valeur, ton -> inv[ton] = valeur }
    }

    /**
     * Scrambling sequence, applied to the 77 bits **before** CRC and parity.
     *
     * Without it a CQ message — a long run of zeros — would give a nearly
     * constant tone 0: a carrier, not a modulation. XOR is its own inverse, so
     * the receiver applies it again.
     */
    private const val BROUILLAGE =
        "0100101001011110100010" +
        "0110110100101100001000" +
        "1010011110010101010110" +
        "11111000101"

    private val MASQUE = BooleanArray(BITS_MESSAGE) { BROUILLAGE[it] == '1' }

    /** Applies — or removes, same operation — the scrambling. */
    fun brouille(message: BooleanArray): BooleanArray {
        require(message.size >= BITS_MESSAGE) { "message trop court" }
        val out = message.copyOf()
        for (i in 0 until BITS_MESSAGE) out[i] = out[i] xor MASQUE[i]
        return out
    }

    /** The 87 data symbols, extracted from the 105 received. */
    fun donnees(tons: IntArray): IntArray {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        val out = IntArray(SYMBOLES_DONNEES)
        var j = 0
        for (i in 0 until SYMBOLES) if (!estRepere(i)) out[j++] = tons[i]
        return out
    }

    /** 105 received symbols to 174 bits. */
    fun symbolesVersBits(tons: IntArray): BooleanArray {
        val bits = BooleanArray(BITS)
        var i = 0
        donnees(tons).forEach { ton ->
            val v = GRAY_INVERSE[ton and 3]
            bits[i++] = (v shr 1) and 1 == 1
            bits[i++] = v and 1 == 1
        }
        return bits
    }

    /** 174 bits back to 105 symbols, markers and ramps included. */
    fun bitsVersSymboles(bits: BooleanArray): IntArray {
        require(bits.size == BITS) { "il faut $BITS bits" }
        val tons = IntArray(SYMBOLES)
        var b = 0
        for (i in 0 until SYMBOLES) {
            if (i == 0 || i == SYMBOLES - 1) { tons[i] = 0; continue }  // ramps
            val repere = REPERES.firstOrNull { (debut, _) -> i in debut until debut + 4 }
            tons[i] = if (repere != null) {
                repere.second[i - repere.first]
            } else {
                val v = (if (bits[b]) 2 else 0) or (if (bits[b + 1]) 1 else 0)
                b += 2
                GRAY[v]
            }
        }
        return tons
    }

    /**
     * How many of the sixteen sync symbols match.
     *
     * Ramps are excluded: their amplitude varies, no clean tone is expected.
     */
    fun scoreCostas(tons: IntArray): Int {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        var bons = 0
        for ((position, attendu) in synchro()) if (tons[position] == attendu) bons++
        return bons
    }

    /**
     * CRC computed as for FT8, but on the **scrambled** message.
     *
     * Order matters: scramble, then CRC. The other way round gives a CRC that
     * matches nothing sent over the air.
     */
    fun avecControle(message: BooleanArray): BooleanArray =
        Ft8.avecControle(brouille(message))

    /** True if the 91 received bits form a consistent word. */
    fun controleJuste(utiles: BooleanArray): Boolean = Ft8.controleJuste(utiles)

    /** Recovers the 77 message bits from the 91 received bits. */
    fun message(utiles: BooleanArray): BooleanArray =
        brouille(utiles.copyOf(BITS_MESSAGE))
}
