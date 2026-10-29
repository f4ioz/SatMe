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

    /** Below this, FSK fits a 3 kHz SSB passband and is read from the USB audio. */
    const val FSK_LENTE_BAUD = 1_200.0

    /** The mode without a « -D » suffix (« USB-D »: USB with the rig's data mode). */
    private fun nu(mode: String?): String = mode.orEmpty().uppercase().trim().removeSuffix("-D").removeSuffix(" DATA")

    /** True when [mode] is received in FM. FT8/FT4 (MFSK) and PSK stay in SSB, as do slow FSK ([baud] under 1200) and SSDV/GENESIS. */
    fun surFm(mode: String?, baud: Double? = null): Boolean {
        val m = nu(mode)
        if (m.contains("MFSK") || m.contains("FT8") || m.contains("FT4")) return false
        if (enBluDonnees(mode, baud)) return false
        if (m == "SSDV") return true
        return SUR_FM.any { m.contains(it) }
    }

    /**
     * True when [mode] is narrow data received in USB with the rig's data
     * mode on (USB-D: the rear audio, the microphone muted): SSDV, GENESIS
     * FSK, FSK slower than 1200 bit/s, or a mode typed with « -D ».
     */
    fun donnees(mode: String?, baud: Double? = null): Boolean {
        val brut = mode.orEmpty().uppercase().trim()
        return brut.endsWith("-D") || brut.endsWith(" DATA") || enBluDonnees(mode, baud)
    }

    /**
     * Narrow data heard in SSB, then demodulated from the audio: SSDV and
     * AMSAT-EA's « GENESIS FSK » (SO-125, SO-127: 2-FSK at 200 or 800 bit/s,
     * 1125 or 1600 Hz shift, decoded from a USB receiver — an FM receiver's
     * discriminator loses them), and any FSK slower than 1200 bit/s.
     */
    private fun enBluDonnees(mode: String?, baud: Double?): Boolean {
        val m = nu(mode)
        if (m.contains("GENESIS")) return true
        val lent = baud != null && baud > 0 && baud < FSK_LENTE_BAUD
        // A transmitter named after what it carries (« SSDV »): slow or of unknown rate → the USB audio; 1200 bit/s and more: FM.
        if (m == "SSDV") return baud == null || lent
        val fsk = (m.contains("FSK") || m.contains("MSK")) && !m.contains("AFSK") && !m.contains("MFSK")
        return fsk && lent
    }

    /** The receiver mode as a word (« FM », « USB », « LSB », « CW »), for a list. */
    fun libelle(mode: String?, baud: Double? = null): String {
        val m = nu(mode)
        return when {
            surFm(mode, baud) -> "FM"
            m == "CW" || m.contains("CW") -> "CW"
            m.contains("LSB") -> "LSB"
            else -> "USB"
        } + if (donnees(mode, baud)) "-D" else ""
    }
}
