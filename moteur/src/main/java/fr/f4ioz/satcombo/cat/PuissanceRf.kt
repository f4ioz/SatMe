/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * The RF power level of an Icom (CI-V `14 0A`): 0000 to 0255 as two BCD
 * bytes, most significant first (0128 → `01 28`). Shown in percent and in
 * approximate watts for the band, from the rig's full power there
 * (IC-9700: 100 W on 144 MHz, 75 W on 430 MHz, 10 W on 1200 MHz).
 * The scale is the rig's knob, not a measurement: the watts are an estimate.
 */
object PuissanceRf {

    const val MAX = 255

    /** The level as the frame carries it (`14 0A` then these two bytes). */
    fun encode(niveau: Int): ByteArray {
        val v = niveau.coerceIn(0, MAX)
        val c = v / 100; val d = v % 100
        return byteArrayOf(c.toByte(), (((d / 10) shl 4) or (d % 10)).toByte())
    }

    /** The level an answer's data carries (the two bytes after `0A`), null when it is not BCD in range. */
    fun decode(b: ByteArray, o: Int = 0): Int? {
        if (b.size < o + 2) return null
        fun chiffres(x: Int): Int? { val h = (x shr 4) and 0xF; val l = x and 0xF; return if (h > 9 || l > 9) null else h * 10 + l }
        val c = chiffres(b[o].toInt() and 0xFF) ?: return null
        val d = chiffres(b[o + 1].toInt() and 0xFF) ?: return null
        return (c * 100 + d).takeIf { it in 0..MAX }
    }

    /** Percent of the knob (0…100), rounded. */
    fun pourcent(niveau: Int): Int = Math.round(niveau.coerceIn(0, MAX) * 100.0 / MAX).toInt()

    /** The level for a percent of the knob. */
    fun niveau(pourcent: Int): Int = Math.round(pourcent.coerceIn(0, 100) * MAX / 100.0).toInt()

    /** The rig's full power on the band of [hz] (W), null when the band is not one it transmits on. */
    fun maxWatts(modele: String, hz: Long): Double? = when (modele) {
        "IC9700", "SIM" -> when (BandPlan.band(hz)) {
            BandPlan.Band.V -> 100.0
            BandPlan.Band.U -> 75.0
            BandPlan.Band.L -> 10.0
            else -> null
        }
        else -> null
    }

    /** About how many watts [niveau] gives on [hz]'s band, null when unknown. */
    fun watts(modele: String, niveau: Int, hz: Long): Double? =
        maxWatts(modele, hz)?.let { it * niveau.coerceIn(0, MAX) / MAX }

    /** [niveau] held under the cap (percent of the knob, 100: none). */
    fun plafonne(niveau: Int, plafondPct: Int): Int = minOf(niveau.coerceIn(0, MAX), niveau(plafondPct.coerceIn(0, 100)))

    /** The presets offered: percent of the knob. */
    val PRESETS = listOf(5, 10, 25, 50, 100)
}
