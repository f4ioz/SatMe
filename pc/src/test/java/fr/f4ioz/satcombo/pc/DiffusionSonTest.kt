/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiffusionSonTest {

    @Test
    fun le_son_part_au_navigateur_qui_ecoute_a_la_moitie_du_debit() {
        val d = DiffusionSon()
        assertFalse(d.ecoute("sdr"))
        val recu = ByteArrayOutputStream()
        // A browser that goes after a few blocks: its stream ends, the listener goes with it.
        val sortie = object : java.io.OutputStream() {
            var n = 0
            override fun write(b: Int) { recu.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { recu.write(b, off, len); if (++n > 4) throw java.io.IOException("parti") }
        }
        val fil = Thread { d.sert("sdr", sortie) }.apply { start() }
        while (!d.ecoute("sdr")) Thread.sleep(5)
        val pcm = ShortArray(1000) { (it * 10).toShort() }
        repeat(5) { d.ajoute("sdr", pcm, pcm.size, 44_100); d.ajoute("carte", pcm, pcm.size, 48_000); Thread.sleep(20) }
        fil.join(3000)
        assertFalse(d.ecoute("sdr"))
        val b = recu.toByteArray()
        val corps = String(b, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        val rate = (b[corps].toInt() and 255) or ((b[corps + 1].toInt() and 255) shl 8) or ((b[corps + 2].toInt() and 255) shl 16)
        assertEquals(22_050, rate)
        // Two samples become one: 500 per block, the first ones averaged (0+10)/2, (20+30)/2…
        val s0 = (b[corps + 4].toInt() and 255) or (b[corps + 5].toInt() shl 8)
        val s1 = (b[corps + 6].toInt() and 255) or (b[corps + 7].toInt() shl 8)
        assertEquals(5, s0); assertEquals(25, s1)
        assertTrue(b.size - corps - 4 >= 2 * 500)
    }
}
