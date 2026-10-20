/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.aprs.Ax25

/**
 * Packet framing as cubesats send it at 1200 to 9600 bit/s (FSK, GMSK):
 * HDLC frames (flags 0x7E, zero bit stuffing, CRC-16), NRZI (a 0 is a
 * transition), then — the « G3RUH » modem — the self-synchronising
 * scrambler 1 + x¹² + x¹⁷, which keeps the line busy and DC-free.
 *
 * Transmitter: bits → stuffing → NRZI → scrambler. Receiver: the reverse.
 * Both the scrambler's and NRZI's outputs are blind to the FM's polarity:
 * an inverted line gives inverted descrambled bits, and NRZI reads the
 * transitions only.
 */
object G3ruh {

    /** The scrambler: y = x ⊕ y₋₁₂ ⊕ y₋₁₇. */
    class Embrouilleur {
        private var reg = 0
        fun bit(x: Int): Int {
            val y = (x xor (reg ushr 11) xor (reg ushr 16)) and 1
            reg = ((reg shl 1) or y) and 0x1FFFF
            return y
        }
    }

    /** The descrambler: x = y ⊕ y₋₁₂ ⊕ y₋₁₇ (resynchronised after 17 bits whatever the start). */
    class Desembrouilleur {
        private var reg = 0
        fun bit(y: Int): Int {
            val x = (y xor (reg ushr 11) xor (reg ushr 16)) and 1
            reg = ((reg shl 1) or (y and 1)) and 0x1FFFF
            return x
        }
    }

    /** NRZI read: no transition = 1, a transition = 0. */
    class Nrzi {
        private var avant = 0
        fun bit(b: Int): Int { val r = if (b == avant) 1 else 0; avant = b; return r }
    }

    /**
     * The line bits for [trames] (each with its FCS already, as [Ax25.encode]
     * gives them): [drapeauxAvant] flags first, one between frames,
     * [drapeauxApres] at the end; NRZI, then scrambled when [embrouille].
     */
    fun bits(trames: List<ByteArray>, embrouille: Boolean = true, drapeauxAvant: Int = 24, drapeauxApres: Int = 4): IntArray {
        val bruts = ArrayList<Int>(trames.sumOf { it.size * 10 } + 8 * (drapeauxAvant + drapeauxApres + trames.size))
        fun drapeau() { for (k in 0 until 8) bruts += (0x7E ushr k) and 1 }
        repeat(drapeauxAvant) { drapeau() }
        trames.forEachIndexed { i, t ->
            var uns = 0
            for (b in t) for (k in 0 until 8) {
                val bit = (b.toInt() ushr k) and 1
                bruts += bit
                if (bit == 1) { if (++uns == 5) { bruts += 0; uns = 0 } } else uns = 0
            }
            repeat(if (i == trames.lastIndex) drapeauxApres else 1) { drapeau() }
        }
        var niveau = 0
        val e = Embrouilleur()
        return IntArray(bruts.size) { k ->
            if (bruts[k] == 0) niveau = niveau xor 1
            if (embrouille) e.bit(niveau) else niveau
        }
    }
}

/**
 * HDLC frames out of a stream of bits (after NRZI): between two flags,
 * stuffed zeros removed, kept when the length is whole bytes and the
 * CRC-16 (X.25) is right. Frames up to [maxOctets] (cubesats send up to
 * a few hundred bytes; an SSDV packet in AX.25 is ~ 275).
 */
class DeframeurHdlc(private val maxOctets: Int = 2048, private val minOctets: Int = 17, private val surTrame: (ByteArray) -> Unit) {
    private var reg = 0
    private var uns = 0
    private val donnees = ByteArray(maxOctets + 2)
    private var nbBits = 0
    private var dedans = false

    /** Frames whose CRC was right, and wrong (length whole, size plausible). */
    var bonnes = 0; private set
    var crcFaux = 0; private set
    /** Bits read so far. */
    var compte = 0L; private set
    /**
     * The bit count when a signal was last sure: three flags back to back
     * (noise makes one now and then, three almost never) or a right frame.
     */
    var signe = Long.MIN_VALUE / 2; private set
    private var dernierDrapeau = -100L
    private var suite = 0

    fun bit(b: Int) {
        compte++
        reg = ((reg shl 1) or (b and 1)) and 0xFF
        if (reg == 0x7E) {
            suite = if (compte - dernierDrapeau == 8L) suite + 1 else 1
            dernierDrapeau = compte
            if (suite >= 3) signe = compte
            // The flag's first seven bits (0 then six 1s) went in as data.
            val n = nbBits - 7
            if (dedans && n > 0 && n % 8 == 0 && n / 8 >= minOctets) {
                val t = donnees.copyOf(n / 8)
                val fcs = (t[t.size - 2].toInt() and 0xFF) or ((t[t.size - 1].toInt() and 0xFF) shl 8)
                if (Ax25.crc(t, t.size - 2) == fcs) { bonnes++; signe = compte; surTrame(t) }
                // Counted only while a signal is sure: noise alone makes such « frames » all the time.
                else if (compte - signe < 4_000) crcFaux++
            }
            dedans = true; nbBits = 0; uns = 0
            return
        }
        if (!dedans) return
        if (b == 1) {
            if (++uns > 6) { dedans = false; return }   // abort, or noise
        } else {
            if (uns == 5) { uns = 0; return }           // the stuffed zero
            uns = 0
        }
        if (nbBits >= 8 * (maxOctets + 1)) { dedans = false; return }
        val i = nbBits ushr 3
        if (nbBits and 7 == 0) donnees[i] = 0
        if (b == 1) donnees[i] = (donnees[i].toInt() or (1 shl (nbBits and 7))).toByte()
        nbBits++
    }
}
