/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.SerialLink

/**
 * An IC-7000 on a CI-V wire, in memory: for the tests and `tellusme --simulation`.
 * Echoes every frame (the one-wire bus does), answers 03/04/05/06/15 02 as the
 * rig does, FA for the rest. [tourneBouton] imitates the knob with « CI-V
 * Transceive » on (an unsolicited 00 frame to address 00). [debranche] makes it
 * silent. Never transmits — it has nothing to transmit with — but records any
 * 1C it would have been sent, so the tests can say none came.
 */
class SimulateurIc7000(private val adresse: Int = 0x70, var frequence: Long = 7_074_000L) : SerialLink {
    var mode = 0x01
    var filtre = 0x01
    var smetre = 85
    @Volatile var debranche = false
    val recues = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
    private val sortie = java.util.concurrent.LinkedBlockingQueue<Byte>()

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (debranche) return true
        for (f in CatDecode.splitCiv(bytes)) {
            recues += f
            pousse(f) // the bus echo
            if ((f[2].toInt() and 0xFF) != adresse) continue
            val cmd = f[4].toInt() and 0xFF
            val data = f.copyOfRange(5, f.size - 1)
            when (cmd) {
                0x03 -> repond(0x03, CatDecode.freqToBcdLe(frequence))
                0x04 -> repond(0x04, byteArrayOf(mode.toByte(), filtre.toByte()))
                0x05 -> { val hz = CatDecode.bcdLeToFreq(data); if (hz != null) { frequence = hz; ack() } else nak() }
                0x06 -> if (data.isNotEmpty() && (data[0].toInt() and 0xFF) in setOf(0, 1, 2, 3, 4, 5, 7, 8)) {
                    mode = data[0].toInt() and 0xFF; if (data.size > 1) filtre = data[1].toInt(); ack()
                } else nak()
                0x15 -> if (data.size == 1 && data[0].toInt() == 0x02) {
                    val v = smetre.coerceIn(0, 255)
                    repond(0x15, byteArrayOf(0x02, (v / 100).toByte(), (((v / 10) % 10 shl 4) or (v % 10)).toByte()))
                } else nak()
                else -> nak()
            }
        }
        return true
    }

    /** The knob turned, « CI-V Transceive » on: the rig tells everyone (00h). */
    fun tourneBouton(hz: Long) {
        frequence = hz
        pousse(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, adresse.toByte(), 0x00) + CatDecode.freqToBcdLe(hz) + byteArrayOf(0xFD.toByte()))
    }

    private fun repond(cmd: Int, data: ByteArray) =
        pousse(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), adresse.toByte(), cmd.toByte()) + data + byteArrayOf(0xFD.toByte()))
    private fun ack() = repond(0xFB, ByteArray(0))
    private fun nak() = repond(0xFA, ByteArray(0))
    private fun pousse(b: ByteArray) { for (x in b) sortie.put(x) }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (debranche) { sortie.clear(); return 0 }
        var n = 0
        while (n < buf.size) { val b = sortie.poll() ?: break; buf[n++] = b }
        return n
    }

    override fun close() {}
}
