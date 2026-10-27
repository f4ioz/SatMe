/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.station

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * openHPSDR Protocol 1 (« old protocol »), **receive only**: the frames the
 * site sends to the station's Brick2 (Hermes / ANAN-10E class, 1.8–60 MHz)
 * and the IQ it reads back.
 *
 * SAFETY — the radio can transmit. Nothing here can make it do so:
 *  - the Metis start command asks for the IQ stream only (0x01), never the
 *    wide-band or any transmit bit;
 *  - every C&C word sent keeps MOX/PTT (bit 0 of C0) at 0;
 *  - the TX payload of every EP2 frame (audio and IQ to the radio) is zeros.
 * The tests check these for every frequency and sequence number.
 */
object HpsdrProtocole {

    private val SYNC = byteArrayOf(0x7F, 0x7F, 0x7F)

    /** Sample rate → the C1 code of the C0=0x00 word. */
    val CODES_DEBIT = mapOf(48_000 to 0, 96_000 to 1, 192_000 to 2, 384_000 to 3)

    /** The Metis command (64 bytes): [demarre] true → the EP6 IQ stream (0x01) — never transmission; false → stop. */
    fun metis(demarre: Boolean): ByteArray = ByteArray(64).also { b ->
        b[0] = 0xEF.toByte(); b[1] = 0xFE.toByte(); b[2] = 0x04; b[3] = if (demarre) 0x01 else 0x00
    }

    /**
     * An EP2 frame (PC → radio, 1032 bytes): header `EF FE 01 02`, the sequence (u32 big-endian), two
     * 512-byte sub-frames each `7F 7F 7F` + C0..C4 + 504 zero bytes.
     *  - Sub-frame 1, C0 = 0x00 (configuration, MOX = 0): C1 = the rate code, C2..C4 = 0 (one receiver, duplex off).
     *  - Sub-frame 2, C0 = 0x02, C1..C4 = the frequency in Hz (u32 big-endian). In the openHPSDR P1
     *    specification 0x02 is the **TX** NCO and 0x04 the RX1 NCO; on this radio RX1 follows 0x02
     *    (checked on the hardware: « 0x04 n'accorde rien »). Most likely because the duplex bit (C4 bit 2
     *    of the 0x00 word) is left at 0: in simplex the receiver follows the TX NCO. MOX stays 0, so
     *    nothing is transmitted. Keep this as it is unless checked again on the radio.
     */
    fun ep2(seq: Long, codeDebit: Int, freqHz: Long): ByteArray {
        val b = ByteArray(1032)
        b[0] = 0xEF.toByte(); b[1] = 0xFE.toByte(); b[2] = 0x01; b[3] = 0x02
        for (i in 0..3) b[4 + i] = ((seq ushr (24 - 8 * i)) and 0xFF).toByte()
        val f = freqHz and 0xFFFFFFFFL
        val mots = arrayOf(
            byteArrayOf(0x00, (codeDebit and 0x03).toByte(), 0, 0, 0),
            byteArrayOf(0x02, ((f ushr 24) and 0xFF).toByte(), ((f ushr 16) and 0xFF).toByte(), ((f ushr 8) and 0xFF).toByte(), (f and 0xFF).toByte()),
        )
        for ((k, cc) in mots.withIndex()) {
            val o = 8 + k * 512
            System.arraycopy(SYNC, 0, b, o, 3)
            System.arraycopy(cc, 0, b, o + 3, 5)
            // b[o+8 .. o+511]: the TX payload, zeros, always.
        }
        return b
    }

    /**
     * An EP6 frame (radio → PC): `EF FE 01 06`, the sequence, two 512-byte sub-frames (sync, five status bytes,
     * 63 samples of I 24-bit, Q 24-bit, mic 16-bit) → 126 complex samples, as I and Q interleaved (raw
     * radio orientation, see [HpsdrSession] for the conjugation). Empty for anything else.
     */
    fun ep6(d: ByteArray, n: Int = d.size): FloatArray {
        if (n < 1032 || (d[0].toInt() and 0xFF) != 0xEF || (d[1].toInt() and 0xFF) != 0xFE || d[2].toInt() != 0x01 || d[3].toInt() != 0x06) return FloatArray(0)
        val out = FloatArray(2 * 126)
        var k = 0
        for (f in 0..1) {
            val o = 8 + f * 512
            if (d[o] != SYNC[0] || d[o + 1] != SYNC[1] || d[o + 2] != SYNC[2]) continue
            for (s in 0 until 63) {
                val p = o + 8 + s * 8
                out[k++] = s24(d, p).toFloat()
                out[k++] = s24(d, p + 3).toFloat()
            }
        }
        return if (k == out.size) out else out.copyOf(k)
    }

    /** A signed 24-bit big-endian integer. */
    fun s24(d: ByteArray, p: Int): Int {
        val v = ((d[p].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
        return if (v and 0x800000 != 0) v - 0x1000000 else v
    }
}

/**
 * Finding the radio on the LAN: the 63-byte discovery packet (`EF FE 02` +
 * zeros) to its address and/or broadcast on port 1024; the first answer
 * (`EF FE 02` idle, `EF FE 03` in use) says its MAC, firmware and board.
 */
object HpsdrDecouverte {

    data class Radio(val enLigne: Boolean, val occupee: Boolean = false, val ip: String = "", val mac: String = "", val firmware: String = "",
                     val carte: String = "", val erreur: String = "")

    val CARTES = mapOf(0x00 to "Atlas/Metis", 0x01 to "Hermes (ANAN-10/10E)", 0x02 to "Griffin", 0x04 to "Angelia (ANAN-100)",
        0x05 to "Orion (ANAN-100D)", 0x06 to "Hermes-Lite", 0x0A to "Orion MkII (ANAN-7000/8000)")

    fun paquet(): ByteArray = ByteArray(63).also { it[0] = 0xEF.toByte(); it[1] = 0xFE.toByte(); it[2] = 0x02 }

    /** A discovery answer read; null when it is not one. */
    fun reponse(d: ByteArray, n: Int, ip: String): Radio? {
        if (n < 11 || (d[0].toInt() and 0xFF) != 0xEF || (d[1].toInt() and 0xFF) != 0xFE) return null
        val etat = d[2].toInt() and 0xFF
        if (etat != 2 && etat != 3) return null
        val code = d[10].toInt() and 0xFF
        return Radio(true, etat == 3, ip, (3..8).joinToString(":") { "%02X".format(d[it].toInt() and 0xFF) },
            String.format(java.util.Locale.ROOT, "%.1f", (d[9].toInt() and 0xFF) / 10.0), CARTES[code] ?: "inconnu (0x%02X)".format(code))
    }

    /** Asks [hote] (if any) and the broadcast address (if [diffusion] or no host) on [port]; waits [attenteMs] for the first answer. */
    fun cherche(hote: String, port: Int, diffusion: Boolean, attenteMs: Int = 1500): Radio = try {
        DatagramSocket(null).use { s ->
            s.reuseAddress = true; s.broadcast = true; s.bind(InetSocketAddress(0)); s.soTimeout = attenteMs
            val p = paquet()
            if (hote.isNotEmpty()) s.send(DatagramPacket(p, p.size, InetAddress.getByName(hote), port))
            if (diffusion || hote.isEmpty()) s.send(DatagramPacket(p, p.size, InetAddress.getByName("255.255.255.255"), port))
            val b = ByteArray(1500)
            val fin = System.currentTimeMillis() + attenteMs
            var r: Radio? = null
            while (r == null && System.currentTimeMillis() < fin) {
                val dp = DatagramPacket(b, b.size)
                try { s.receive(dp) } catch (e: SocketTimeoutException) { break }
                r = reponse(dp.data, dp.length, dp.address.hostAddress)
            }
            r ?: Radio(false)
        }
    } catch (e: Exception) { Radio(false, erreur = (e.message ?: e.javaClass.simpleName).take(100)) }
}
