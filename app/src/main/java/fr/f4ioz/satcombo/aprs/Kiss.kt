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
 * KISS: how a TNC — built into a Kenwood TH-D72, TH-D74, TM-D710, or a
 * Mobilinkd — passes whole AX.25 frames over a serial line. The radio does
 * the modem, the keying and its own return to receive; the frames carry no
 * FCS (the TNC adds and checks it).
 *
 * Frame: FEND, command byte (0 = data on port 0), data with FEND/FESC
 * escaped, FEND.
 */
object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD

    /**
     * What puts a Kenwood TNC in packet mode ("cmd:" prompt) into KISS.
     * A TH-D72 in normal mode first gets "TN 2,<band>" (its PC command),
     * which starts the TNC in packet mode on that band.
     */
    const val ENTREE_KENWOOD = "\rKISS ON\rRESTART\r"

    /**
     * Frequency (Hz) in a Kenwood "FO" reply — TH-D72: "FO 0,0144800000,0,…",
     * band, then the frequency on ten digits.
     */
    fun frequenceFo(reponse: String): Long? =
        Regex("FO (\\d),(\\d{10})").find(reponse)?.groupValues?.get(2)?.toLongOrNull()

    /** Leaves KISS: a frame with the command byte 0xFF. */
    val SORTIE: ByteArray = byteArrayOf(FEND.toByte(), 0xFF.toByte(), FEND.toByte())

    /** An AX.25 frame without FCS, ready for the serial line. */
    fun trame(ax25: ByteArray, port: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream(ax25.size + 8)
        out.write(FEND)
        out.write((port and 0x0F) shl 4)
        for (b in ax25) when (b.toInt() and 0xFF) {
            FEND -> { out.write(FESC); out.write(TFEND) }
            FESC -> { out.write(FESC); out.write(TFESC) }
            else -> out.write(b.toInt())
        }
        out.write(FEND)
        return out.toByteArray()
    }
}

/**
 * Bytes from the serial line → data frames (AX.25 without FCS). Anything
 * outside a frame — the TNC's text prompts before KISS, noise — is ignored.
 */
class KissDecodeur(private val surTrame: (ByteArray) -> Unit) {
    private val tampon = java.io.ByteArrayOutputStream()
    private var dedans = false
    private var echappe = false

    fun octets(b: ByteArray, n: Int = b.size) {
        for (i in 0 until n) octet(b[i].toInt() and 0xFF)
    }

    private fun octet(o: Int) {
        if (o == Kiss.FEND) {
            if (dedans && tampon.size() > 1) {
                val t = tampon.toByteArray()
                // Command byte: low nibble 0 = data frame; other commands are TNC settings.
                if (t[0].toInt() and 0x0F == 0) surTrame(t.copyOfRange(1, t.size))
            }
            tampon.reset(); dedans = true; echappe = false
            return
        }
        if (!dedans) return
        if (echappe) {
            tampon.write(when (o) { Kiss.TFEND -> Kiss.FEND; Kiss.TFESC -> Kiss.FESC; else -> o })
            echappe = false
        } else if (o == Kiss.FESC) {
            echappe = true
        } else {
            tampon.write(o)
        }
        if (tampon.size() > 1024) { tampon.reset(); dedans = false }
    }
}
