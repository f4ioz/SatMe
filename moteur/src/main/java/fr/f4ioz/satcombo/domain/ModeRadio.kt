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
 * Which receiver mode a catalogue mode needs. SatNOGS names the modulation,
 * not the radio setting: the ISS APRS digipeater is "AFSK", 9600-baud
 * packet "GMSK" or "FSK", the ISS pictures "SSTV" — all heard on an FM
 * receiver. Reading only "FM" in the name put the rig in USB for them.
 */
object ModeRadio {

    private val SUR_FM = listOf("FM", "AFSK", "APRS", "PACKET", "FSK", "GMSK", "MSK", "SSTV", "DSTAR", "D-STAR", "DV")

    /** True when [mode] is received in FM. FT8/FT4 (MFSK) and PSK stay in SSB. */
    fun surFm(mode: String?): Boolean {
        val m = mode.orEmpty().uppercase()
        if (m.contains("MFSK") || m.contains("FT8") || m.contains("FT4")) return false
        return SUR_FM.any { m.contains(it) }
    }
}
