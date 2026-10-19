/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CatDecode

/**
 * The rig's own scope, as CI-V `27 00` carries it (Icom's CI-V reference
 * guide): `27 00 <main 00 / sub 01> <part n> <of N>` (BCD); the first part
 * starts with the scope's mode (00 centre, 01 fixed, 02/03 scroll), two
 * 5-byte BCD frequencies (centre and ± half span, or lower and upper edge)
 * and an out-of-range byte; then come the points, 0…160 each. Over the
 * network a whole sweep usually comes in one part; over USB in several.
 */
class SpectrePoste {

    data class Balayage(
        val sub: Boolean, val basHz: Long, val hautHz: Long, val horsBande: Boolean,
        /** Levels 0…1. */
        val points: FloatArray, val ms: Long,
    )

    private var cours: ArrayList<Byte>? = null
    private var bas = 0L; private var haut = 0L; private var hors = false; private var sub = false

    @Volatile var dernier: Balayage? = null; private set
    @Volatile var balayages = 0L; private set

    private fun bcd(b: Byte): Int { val v = b.toInt() and 0xFF; return (v shr 4) * 10 + (v and 0x0F) }

    /** Five little-endian BCD bytes, any value (a span is far below a plausible dial frequency). */
    private fun bcd5(t: ByteArray, o: Int): Long? {
        var hz = 0L; var m = 1L
        for (i in 0 until 5) {
            val v = t[o + i].toInt() and 0xFF
            if ((v and 0x0F) > 9 || (v shr 4) > 9) return null
            hz += (v and 0x0F) * m; m *= 10; hz += (v shr 4) * m; m *= 10
        }
        return hz
    }

    /** One CI-V frame (FE FE … FD); true when it was a scope frame. */
    fun recois(trame: ByteArray, maintenant: Long = System.currentTimeMillis()): Boolean {
        if (trame.size < 10 || CatDecode.command(trame) != 0x27 || (trame[5].toInt() and 0xFF) != 0x00) return false
        val fin = trame.size - 1
        sub = (trame[6].toInt() and 0xFF) == 1
        val part = bcd(trame[7]); val parts = bcd(trame[8])
        var i = 9
        if (part == 1) {
            if (fin - i < 12) return true
            val mode = trame[i].toInt() and 0xFF
            val f1 = bcd5(trame, i + 1) ?: return true
            val f2 = bcd5(trame, i + 6) ?: return true
            if (mode == 0x00 || mode == 0x02) { bas = f1 - f2; haut = f1 + f2 } else { bas = f1; haut = f2 }
            hors = (trame[i + 11].toInt() and 0xFF) != 0
            i += 12
            cours = ArrayList()
        }
        val c = cours ?: return true
        while (i < fin) c += trame[i++]
        if (part >= parts && c.isNotEmpty()) {
            dernier = Balayage(sub, bas, haut, hors, FloatArray(c.size) { ((c[it].toInt() and 0xFF) / 160f).coerceIn(0f, 1f) }, maintenant)
            balayages++
            cours = null
        }
        return true
    }

    companion object {
        /** Scope on, and its data sent on CI-V (sent from controller [de] so the CAT does not see the acks). */
        fun active(radio: Int, de: Int, on: Boolean): List<ByteArray> {
            fun f(vararg d: Int) = (byteArrayOf(0xFE.toByte(), 0xFE.toByte(), radio.toByte(), de.toByte()) +
                d.map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte()))
            return if (on) listOf(f(0x27, 0x10, 0x01), f(0x27, 0x11, 0x01)) else listOf(f(0x27, 0x11, 0x00))
        }
    }
}
