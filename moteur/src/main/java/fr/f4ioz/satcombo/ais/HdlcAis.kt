/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

/**
 * AIS framing on the air (ITU-R M.1371, annex 2): a training sequence of
 * alternating bits, the flag 01111110, the message and its 16-bit frame
 * check sequence with a 0 stuffed after every five 1s, the flag again; the
 * whole NRZI coded (a 0 is a change of level, a 1 none). Each byte goes
 * least significant bit first, so a message's bits (numbered MSB first)
 * are reversed eight by eight. The check is the CRC-16 of ITU-T X.25
 * (polynomial x¹⁶+x¹²+x⁵+1, reflected, preset to ones, sent inverted):
 * run over the message and its check, it leaves [RESIDU].
 */
object HdlcAis {
    const val RESIDU = 0xF0B8
    const val DRAPEAU = 0x7E
    /** Longest frame kept between two flags (bits after unstuffing): a 5-slot message and its check. */
    const val MAX_BITS = 1200

    /** The CRC register after [n] bits of [bits] in their order on the air. */
    fun crc(bits: BooleanArray, n: Int = bits.size, depart: Int = 0xFFFF): Int {
        var c = depart
        for (i in 0 until n) c = if ((c xor (if (bits[i]) 1 else 0)) and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1
        return c
    }

    /** Message bits (MSB first) ↔ the order on the air (each byte LSB first). */
    fun renverseOctets(b: BooleanArray): BooleanArray = BooleanArray(b.size) { i -> val k = i / 8 * 8; b.getOrElse(k + 7 - i % 8) { false } }

    /**
     * What goes on the air for [message] (its bits MSB first, a whole
     * number of bytes): training, flag, stuffed data and check, flag, a few
     * bits of buffer; NRZI coded from level [niveau].
     */
    fun trame(message: BooleanArray, entrainement: Int = 24, niveau: Boolean = false): BooleanArray {
        val donnees = renverseOctets(message)
        val fcs = crc(donnees).inv() and 0xFFFF
        val brut = ArrayList<Boolean>(donnees.size + 80)
        repeat(entrainement) { brut += it % 2 == 1 }
        val drapeau = { for (j in 0 until 8) brut += (DRAPEAU shr j) and 1 == 1 }
        drapeau()
        var uns = 0
        val ajoute = { b: Boolean ->
            brut += b
            if (b) { uns++; if (uns == 5) { brut += false; uns = 0 } } else uns = 0
        }
        donnees.forEach(ajoute)
        for (j in 0 until 16) ajoute((fcs shr j) and 1 == 1)
        drapeau()
        repeat(8) { brut += false }
        // NRZI: a 0 changes the level.
        var n = niveau
        return BooleanArray(brut.size) { i -> if (!brut[i]) n = !n; n }
    }

    /**
     * Bits from the demodulator (levels, before NRZI decoding) in, frames
     * out: [recu] gets each message (MSB first) whose check is right.
     */
    class Deframeur(private val recu: (BooleanArray) -> Unit) {
        private var avant = false
        private var registre = 0
        private var dans = false
        private var uns = 0
        private val tampon = BooleanArray(MAX_BITS + 16)
        private var n = 0
        /** Candidate frames of a whole number of bytes whose check was wrong. */
        var rejetees = 0; private set
        var bonnes = 0; private set
        /** Inside a frame (after a flag): the demodulator holds its tracking steadier. */
        val enTrame: Boolean get() = dans

        fun remet() { dans = false; n = 0; uns = 0; registre = 0 }

        fun niveau(b: Boolean) {
            val bit = b == avant
            avant = b
            registre = ((registre shl 1) or (if (bit) 1 else 0)) and 0xFF
            if (!dans) {
                // The flag goes LSB first: 0 1 1 1 1 1 1 0 reads 0x7E either way.
                if (registre == DRAPEAU) { dans = true; n = 0; uns = 0 }
                return
            }
            if (bit) {
                if (++uns == 6) {
                    // A flag (or an abort): the frame ended before its 0 and five 1s. Its last 0 opens the next one.
                    termine(n - 6)
                    dans = false
                    return
                }
            } else {
                val bourre = uns == 5
                uns = 0
                if (bourre) return
            }
            if (n >= tampon.size) { dans = false; return }
            tampon[n++] = bit
        }

        private fun termine(longueur: Int) {
            if (longueur < 56 || longueur % 8 != 0) return
            val d = tampon.copyOf(longueur)
            if (crc(d) != RESIDU) { rejetees++; return }
            bonnes++
            recu(renverseOctets(d.copyOf(longueur - 16)))
        }
    }
}
