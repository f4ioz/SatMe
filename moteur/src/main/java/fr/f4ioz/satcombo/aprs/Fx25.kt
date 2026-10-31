/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

/**
 * FX.25: an AX.25 frame wrapped in a Reed-Solomon code block, from the
 * public specification "FX.25 Forward Error Correction Extension to AX.25
 * Link Protocol for Amateur Packet Radio", Stensat Group, draft 0.01.06
 * (September 2006).
 *
 * On the air: a 64-bit correlation tag (which code follows), then the code
 * block — the AX.25 frame exactly as an ordinary TNC sends it (flags, bit
 * stuffing, FCS), padded with flag bits to the block's size, then the check
 * bytes. Every byte low bit first, the whole NRZI-coded like AX.25: a
 * receiver that knows nothing of FX.25 still reads the frame inside.
 *
 * The field and code: GF(2^8) on 0x11D, first root α¹ — what the FX.25
 * equipment in use applies (the specification does not spell it out).
 */
object Fx25 {

    /** One code: its correlation tag, block size and check bytes. */
    class Code(val numero: Int, val etiquette: Long, val n: Int, val controle: Int) {
        val info: Int get() = n - controle
    }

    /** Table 1 of the specification: Tag_01 … Tag_0B (the others are reserved or undefined). */
    val CODES: List<Code> = listOf(
        Code(0x01, 0xB74DB7DF8A532F3EuL.toLong(), 255, 16),
        Code(0x02, 0x26FF60A600CC8FDEuL.toLong(), 144, 16),
        Code(0x03, 0xC7DC0508F3D9B09EuL.toLong(), 80, 16),
        Code(0x04, 0x8F056EB4369660EEuL.toLong(), 48, 16),
        Code(0x05, 0x6E260B1AC5835FAEuL.toLong(), 255, 32),
        Code(0x06, 0xFF94DC634F1CFF4EuL.toLong(), 160, 32),
        Code(0x07, 0x1EB7B9CDBC09C00EuL.toLong(), 96, 32),
        Code(0x08, 0xDBF869BD2DBB1776uL.toLong(), 64, 32),
        Code(0x09, 0x3ADB0C13DEAE2836uL.toLong(), 255, 64),
        Code(0x0A, 0xAB69DB6A543188D6uL.toLong(), 192, 64),
        Code(0x0B, 0x4A4ABEC4A724B796uL.toLong(), 128, 64),
    )

    /** The codes by check-byte count (16, 32, 64), first root 1. */
    private val codecs = HashMap<Int, ReedSolomon>()
    fun codec(controle: Int): ReedSolomon = synchronized(codecs) { codecs.getOrPut(controle) { ReedSolomon(controle, 1) } }

    /** Bits of difference allowed between a tag and what was heard (64 bits: noise matches ≈ never). */
    const val TOLERANCE = 8
}

/**
 * The receiving side of FX.25: takes the same NRZI-decoded bits as
 * [HdlcDecodeur], watches for a correlation tag, then gathers the code
 * block, mends it, and hands over each AX.25 frame with a right FCS found
 * in it, with the number of bytes the code mended.
 */
class Fx25Decodeur(private val surTrame: (octets: ByteArray, corriges: Int) -> Unit) {
    private var registre = 0L
    private var code: Fx25.Code? = null
    private var bloc = ByteArray(0)
    private var nbBits = 0

    /** A code block is being gathered (a frame may still come out of it). */
    val enCours: Boolean get() = code != null

    /** Blocks the code could not mend. */
    var echecs = 0
        private set

    fun bit(b: Boolean) {
        val c = code
        if (c == null) {
            registre = (registre ushr 1) or (if (b) Long.MIN_VALUE else 0L)  // low bit first
            for (k in Fx25.CODES) {
                if (java.lang.Long.bitCount(registre xor k.etiquette) <= Fx25.TOLERANCE) {
                    code = k; bloc = ByteArray(k.n); nbBits = 0
                    return
                }
            }
            return
        }
        if (b) {
            val i = nbBits ushr 3
            bloc[i] = (bloc[i].toInt() or (1 shl (nbBits and 7))).toByte()
        }
        if (++nbBits == c.n * 8) {
            code = null
            registre = 0L
            termine(c, bloc)
        }
    }

    private fun termine(c: Fx25.Code, bloc: ByteArray) {
        val corriges = Fx25.codec(c.controle).corrige(bloc, 0, c.n)
        if (corriges < 0) { echecs++; return }
        // The frame inside, read as an ordinary receiver would.
        val trouvees = ArrayList<ByteArray>()
        val hdlc = HdlcDecodeur { trouvees += it }
        for (i in 0 until c.info * 8) hdlc.bit((bloc[i ushr 3].toInt() ushr (i and 7)) and 1 == 1)
        for (t in trouvees) surTrame(t, corriges)
    }
}
