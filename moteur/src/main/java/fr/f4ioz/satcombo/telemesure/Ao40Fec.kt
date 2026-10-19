/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.meteor.Convolutif
import fr.f4ioz.satcombo.meteor.Derandomise
import fr.f4ioz.satcombo.meteor.ReedSolomon
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The coded telemetry format proposed by KA9Q for AO-40 (« Proposed Coded
 * AO-40 Telemetry Format », v1.2, January 2002), still used by the QO-100
 * middle beacon and the FUNcube family at 1200 bit/s:
 *
 *   256 user bytes → even bytes and odd bytes, two RS(160,128) codewords
 *   (the CCSDS (255,223) code shortened by 95 leading zeros, conventional
 *   basis) → the 320 bytes sent byte-interleaved (even, odd, even…), data
 *   then parity, XORed with the CCSDS PN sequence (x⁸+x⁷+x⁵+x³+1, all ones)
 *   → convolutional code r=1/2 K=7 (171, 133 octal; G2 inverted), MSB first,
 *   6 zero tail bits → 5132 symbols written by rows into an 80 × 65 block
 *   from its second row; the first row a 65-bit sync vector (x⁷+x³+1, all
 *   ones); read out by columns: 5200 channel symbols per frame.
 *
 * The receive side takes soft symbols in the order they were sent (positive
 * for a 1) and returns the 256 bytes when both codewords are corrected.
 */
object Ao40Fec {
    const val OCTETS = 256
    const val SYMBOLES = 5200
    const val LIGNES = 80
    const val COLONNES = 65
    /** Coded symbols: (2048 + 512 + 6) × 2. */
    const val CODES = 5132
    private const val PAS_BITS = 2566
    private const val RS_DONNEES = 128
    private const val RS_MOT = 160
    private const val RS_ZEROS = 95

    /** The 65 sync bits (0/1), first sent first. */
    val SYNC: IntArray = IntArray(COLONNES).also { v ->
        // x⁷ + x³ + 1, the register all ones at the start.
        var r = 0x7F
        for (i in 0 until COLONNES) {
            v[i] = (r shr 6) and 1
            val nb = ((r shr 6) xor (r shr 3)) and 1
            r = ((r shl 1) or nb) and 0x7F
        }
    }

    /** The sync as ±1, for the correlators. */
    val SYNC_SIGNE: FloatArray = FloatArray(COLONNES) { if (SYNC[it] == 1) 1f else -1f }

    /** Sent position [t] (0..5199): its row and column in the block. */
    private fun ligne(t: Int) = t % LIGNES
    private fun colonne(t: Int) = t / LIGNES

    /** For each coded symbol (0..5131), where it is sent. */
    private val ENVOI: IntArray = IntArray(CODES).also { e ->
        for (t in 0 until SYMBOLES) {
            val l = ligne(t)
            if (l == 0) continue
            val k = (l - 1) * COLONNES + colonne(t)
            if (k < CODES) e[k] = t
        }
    }

    /** Sent positions of the sync bits: one at the head of each column. */
    fun positionSync(i: Int): Int = i * LIGNES

    /** The 320 bytes before the convolutional code: RS-coded, interleaved, scrambled. */
    internal fun blocs(donnees: ByteArray): IntArray {
        require(donnees.size == OCTETS)
        val mots = Array(2) { m ->
            val d = IntArray(ReedSolomon.K)
            for (i in 0 until RS_DONNEES) d[RS_ZEROS + i] = donnees[2 * i + m].toInt() and 0xFF
            ReedSolomon.code(d)
        }
        val out = IntArray(2 * RS_MOT)
        for (i in 0 until RS_DONNEES) for (m in 0..1) out[2 * i + m] = donnees[2 * i + m].toInt() and 0xFF
        for (i in 0 until ReedSolomon.NPAR) for (m in 0..1) out[2 * (RS_DONNEES + i) + m] = mots[m][i]
        for (i in out.indices) out[i] = out[i] xor (Derandomise.SEQUENCE[i % 255].toInt() and 0xFF)
        return out
    }

    /** The 5200 channel bits (0/1) for 256 bytes, in the order they are sent. */
    fun code(donnees: ByteArray): IntArray {
        val octets = blocs(donnees)
        val bits = IntArray(PAS_BITS)
        for (i in octets.indices) for (b in 0 until 8) bits[8 * i + b] = (octets[i] shr (7 - b)) and 1
        val codes = IntArray(2 * PAS_BITS)
        Convolutif.code(bits, codes)
        for (k in 1 until CODES step 2) codes[k] = codes[k] xor 1
        val out = IntArray(SYMBOLES)
        for (i in 0 until COLONNES) out[positionSync(i)] = SYNC[i]
        for (k in 0 until CODES) out[ENVOI[k]] = codes[k]
        return out
    }

    /** A decoded frame and how hard it was. */
    class Resultat(
        val donnees: ByteArray,
        /** Bytes the two Reed-Solomon codewords corrected. */
        val octetsCorriges: Int,
        /** Channel symbols that were wrong (the frame coded again and compared). */
        val symbolesFaux: Int,
        /** Symbol signal to noise from the soft symbols, dB (the coded frame as reference). */
        val snrDb: Double,
    )

    /**
     * Decodes one frame: [soft] holds 5200 soft symbols from [debut] in the
     * order sent (positive for a 1; magnitude the confidence). Null when the
     * Reed-Solomon codes cannot correct it. [decodeur] is reused when given.
     */
    fun decode(soft: FloatArray, debut: Int = 0, decodeur: Convolutif.Decodeur = Convolutif.Decodeur()): Resultat? {
        // Scale to ±127 from the mean magnitude.
        var m = 0.0
        for (k in 0 until CODES) m += abs(soft[debut + ENVOI[k]])
        m /= CODES
        if (m <= 1e-12) return null
        val e = 48.0 / m
        decodeur.reinitialise()
        for (p in 0 until PAS_BITS) {
            val a = (soft[debut + ENVOI[2 * p]] * e).roundToInt().coerceIn(-127, 127)
            val b = -(soft[debut + ENVOI[2 * p + 1]] * e).roundToInt().coerceIn(-127, 127)
            decodeur.pas(a, b)
        }
        val bits = IntArray(PAS_BITS)
        decodeur.lis(bits, 0, final = true)
        val octets = IntArray(2 * RS_MOT)
        for (i in octets.indices) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[8 * i + b]
            octets[i] = v xor (Derandomise.SEQUENCE[i % 255].toInt() and 0xFF)
        }
        var corriges = 0
        val sortie = ByteArray(OCTETS)
        for (mot in 0..1) {
            val c = IntArray(ReedSolomon.N)
            for (i in 0 until RS_MOT) c[RS_ZEROS + i] = octets[2 * i + mot]
            val n = ReedSolomon.corrige(c)
            if (n < 0) return null
            // A correction in the zeros that shorten the code: not a codeword after all.
            for (i in 0 until RS_ZEROS) if (c[i] != 0) return null
            corriges += n
            for (i in 0 until RS_DONNEES) sortie[2 * i + mot] = c[RS_ZEROS + i].toByte()
        }
        // The frame coded again: which symbols arrived wrong, and the noise around the right ones.
        val ref = code(sortie)
        var faux = 0
        var s1 = 0.0; var s2 = 0.0
        for (t in 0 until SYMBOLES) {
            val v = soft[debut + t].toDouble() / m
            val r = if (ref[t] == 1) v else -v
            if (r < 0) faux++
            s1 += r; s2 += r * r
        }
        val moy = s1 / SYMBOLES
        val var_ = (s2 / SYMBOLES - moy * moy).coerceAtLeast(1e-9)
        return Resultat(sortie, corriges, faux, 10 * log10((moy * moy / var_).coerceAtLeast(1e-6)))
    }
}
