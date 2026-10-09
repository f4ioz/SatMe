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
 * The CCSDS pseudo-random sequence (x⁸+x⁷+x⁵+x³+1, all ones to start) the
 * transmitter adds to every frame after its sync word, so that long runs of
 * equal bits never reach the demodulator. Adding it again takes it away.
 */
object Derandomise {
    val SEQUENCE: ByteArray = ByteArray(255).also { out ->
        val s = IntArray(8) { 1 }
        for (k in 0 until 255) {
            var octet = 0
            repeat(8) {
                octet = (octet shl 1) or s[0]
                val nb = s[0] xor s[3] xor s[5] xor s[7]
                for (i in 0 until 7) s[i] = s[i + 1]
                s[7] = nb
            }
            out[k] = octet.toByte()
        }
    }

    /** Removes the sequence from [trame], from [debut], over [longueur] bytes. */
    fun applique(trame: ByteArray, debut: Int, longueur: Int) {
        for (i in 0 until longueur) {
            trame[debut + i] = (trame[debut + i].toInt() xor SEQUENCE[i % 255].toInt()).toByte()
        }
    }
}
