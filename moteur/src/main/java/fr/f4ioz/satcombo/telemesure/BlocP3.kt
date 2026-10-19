/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

/**
 * The uncoded Phase 3 telemetry block (AMSAT-DL « tlmspec »): the sync
 * 39 15 ED 30, 512 bytes, a 2-byte CRC (x¹⁶+x¹²+x⁵+1 from FFFF, MSB first,
 * sent high byte first: over the 514 bytes the register ends at 0), MSB
 * first at 400 bit/s. The QO-100 middle beacon sends these between its
 * coded frames.
 */
object BlocP3 {
    const val SYNC = 0x3915ED30
    const val OCTETS = 512
    const val TOTAL = OCTETS + 2

    fun crc(d: ByteArray, debut: Int = 0, n: Int = d.size): Int {
        var r = 0xFFFF
        for (i in debut until debut + n) {
            r = r xor ((d[i].toInt() and 0xFF) shl 8)
            repeat(8) { r = if (r and 0x8000 != 0) ((r shl 1) xor 0x1021) and 0xFFFF else (r shl 1) and 0xFFFF }
        }
        return r
    }

    /** The bits (0/1) sent for a 512-byte block: sync, block, CRC. */
    fun code(bloc: ByteArray): IntArray {
        require(bloc.size == OCTETS)
        val c = crc(bloc)
        val tout = ByteArray(4 + TOTAL)
        for (i in 0..3) tout[i] = (SYNC ushr (24 - 8 * i)).toByte()
        bloc.copyInto(tout, 4)
        tout[4 + OCTETS] = (c shr 8).toByte(); tout[5 + OCTETS] = c.toByte()
        return IntArray(tout.size * 8) { (tout[it / 8].toInt() shr (7 - it % 8)) and 1 }
    }

    /**
     * Finds blocks in a stream of hard bits: [pousse] one bit at a time; a
     * block comes out by [recu] with its CRC verdict. The sync is accepted
     * with up to two wrong bits, either polarity.
     */
    class Chercheur(private val recu: (ByteArray, Boolean) -> Unit) {
        private var registre = 0
        private var inverse = 0
        private var octets: ByteArray? = null
        private var nBits = 0

        fun reinitialise() { registre = 0; octets = null; nBits = 0 }

        val enCours: Boolean get() = octets != null

        fun pousse(bit: Int) {
            val o = octets
            if (o != null) {
                val b = (bit xor inverse) and 1
                val i = nBits / 8
                o[i] = ((o[i].toInt() shl 1) or b).toByte()
                nBits++
                if (nBits == TOTAL * 8) {
                    octets = null
                    recu(o.copyOf(OCTETS), crc(o) == 0)
                }
                return
            }
            registre = (registre shl 1) or (bit and 1)
            val d = Integer.bitCount(registre xor SYNC)
            if (d <= 2 || 32 - d <= 2) {
                inverse = if (d <= 2) 0 else 1
                octets = ByteArray(TOTAL); nBits = 0; registre = 0
            }
        }
    }

    /**
     * The block as text: 64-character lines (no line ends are sent), bit 7
     * (highlighting) dropped, anything not printable as a middle dot. A
     * telemetry « A » block has binary channels in its second half: only
     * its text part is shown.
     */
    fun texte(bloc: ByteArray, largeur: Int = 64): List<String> {
        val n = if (bloc.isNotEmpty() && (bloc[0].toInt() and 0x7F).toChar() in "AE") minOf(bloc.size, 256) else bloc.size
        val l = ArrayList<String>()
        var i = 0
        while (i < n) {
            val sb = StringBuilder()
            for (k in i until minOf(n, i + largeur)) {
                val c = bloc[k].toInt() and 0x7F
                sb.append(if (c in 0x20..0x7E) c.toChar() else '·')
            }
            l += sb.toString().trimEnd()
            i += largeur
        }
        while (l.isNotEmpty() && l.last().isBlank()) l.removeAt(l.size - 1)
        return l
    }
}
