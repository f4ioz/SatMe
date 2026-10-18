/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Extended squitters built from the standard's bit layout, and the
 * magnitudes a receiver at 2 MS/s would see of them: for the tests and the
 * page's trial aircraft only — nothing here is ever sent on the air.
 */
object EncodeurAdsb {

    private fun entete(icao: Int, df: Int = 17, ca: Int = 5): ByteArray {
        val b = ByteArray(14)
        ModeS.ecrisBits(b, 0, 5, df.toLong())
        ModeS.ecrisBits(b, 5, 3, ca.toLong())
        ModeS.ecrisBits(b, 8, 24, icao.toLong())
        return b
    }

    private fun me(b: ByteArray, de: Int, n: Int, v: Long) = ModeS.ecrisBits(b, 32 + de, n, v)

    fun identification(icao: Int, indicatif: String, tc: Int = 4, cat: Int = 3): ByteArray {
        val b = entete(icao)
        me(b, 0, 5, tc.toLong()); me(b, 5, 3, cat.toLong())
        val s = indicatif.uppercase().padEnd(8).take(8)
        for (k in 0 until 8) me(b, 8 + 6 * k, 6, ModeS.codeCaractere(s[k]).toLong())
        ModeS.scelle(b, ModeS.LONG)
        return b
    }

    /** The 12-bit altitude field with the Q bit (25 ft steps). */
    fun alt12(pieds: Int): Int {
        val n = ((pieds + 1000) / 25).coerceIn(0, 2047)
        return ((n and 0x7F0) shl 1) or 0x10 or (n and 0x0F)
    }

    fun position(icao: Int, lat: Double, lon: Double, altPieds: Int, impair: Boolean, tc: Int = 11): ByteArray {
        val b = entete(icao)
        val (y, x) = Cpr.encode(lat, lon, impair)
        me(b, 0, 5, tc.toLong())
        me(b, 8, 12, alt12(altPieds).toLong())
        me(b, 21, 1, if (impair) 1 else 0)
        me(b, 22, 17, y.toLong()); me(b, 39, 17, x.toLong())
        ModeS.scelle(b, ModeS.LONG)
        return b
    }

    fun surface(icao: Int, lat: Double, lon: Double, impair: Boolean, mov: Int = 1, routeDeg: Double? = null): ByteArray {
        val b = entete(icao)
        val (y, x) = Cpr.encode(lat, lon, impair, surface = true)
        me(b, 0, 5, 6)
        me(b, 5, 7, mov.toLong())
        if (routeDeg != null) { me(b, 12, 1, 1); me(b, 13, 7, ((routeDeg / 360.0 * 128).roundToInt() and 127).toLong()) }
        me(b, 21, 1, if (impair) 1 else 0)
        me(b, 22, 17, y.toLong()); me(b, 39, 17, x.toLong())
        ModeS.scelle(b, ModeS.LONG)
        return b
    }

    /** Ground speed and track (subtype 1), vertical rate in ft/min. */
    fun vitesse(icao: Int, noeuds: Double, routeDeg: Double, verticalPiedsMin: Int): ByteArray {
        val b = entete(icao)
        me(b, 0, 5, 19); me(b, 5, 3, 1)
        val ve = noeuds * sin(Math.toRadians(routeDeg))
        val vn = noeuds * cos(Math.toRadians(routeDeg))
        me(b, 13, 1, if (ve < 0) 1 else 0); me(b, 14, 10, (abs(ve).roundToInt() + 1).toLong().coerceAtMost(1023))
        me(b, 24, 1, if (vn < 0) 1 else 0); me(b, 25, 10, (abs(vn).roundToInt() + 1).toLong().coerceAtMost(1023))
        me(b, 35, 1, 1)
        me(b, 36, 1, if (verticalPiedsMin < 0) 1 else 0)
        me(b, 37, 9, (abs(verticalPiedsMin) / 64 + 1).toLong().coerceAtMost(511))
        ModeS.scelle(b, ModeS.LONG)
        return b
    }

    /** A DF11 all-call reply (interrogator code 0). */
    fun allCall(icao: Int): ByteArray {
        val b = ByteArray(7)
        ModeS.ecrisBits(b, 0, 5, 11); ModeS.ecrisBits(b, 5, 3, 5); ModeS.ecrisBits(b, 8, 24, icao.toLong())
        ModeS.scelle(b, ModeS.COURT)
        return b
    }

    /** A DF4 altitude reply: its parity overlaid with the address. */
    fun altitudeCourte(icao: Int, pieds: Int): ByteArray {
        val b = ByteArray(7)
        ModeS.ecrisBits(b, 0, 5, 4)
        val n = ((pieds + 1000) / 25).coerceIn(0, 2047)
        val ac = ((n and 0x7E0) shl 2) or ((n and 0x10) shl 1) or 0x10 or (n and 0x0F)
        ModeS.ecrisBits(b, 19, 13, ac.toLong())
        ModeS.scelle(b, ModeS.COURT, icao)
        return b
    }

    /**
     * [trame] as magnitudes at 2 MS/s written into [out] from [debut]: the
     * preamble and the bits, pulses of [amplitude], starting [decalage]
     * of a sample late (the share spilled into the next sample).
     */
    fun module(trame: ByteArray, nBits: Int, out: FloatArray, debut: Int, amplitude: Float = 0.5f, decalage: Float = 0f) {
        fun impulsion(demiUs: Int) {
            val s = debut + demiUs
            out[s] += amplitude * (1f - decalage)
            out[s + 1] += amplitude * decalage
        }
        for (p in intArrayOf(0, 2, 7, 9)) impulsion(p)
        for (k in 0 until nBits) impulsion(16 + 2 * k + if (ModeS.bit(trame, k) == 1) 0 else 1)
    }
}
