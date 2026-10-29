/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Geoscan satellites' frames and picture chunks — on signals made here from the public description only. */
class GeoscanTest {

    @Test fun pn9_et_crc() {
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x1D, 0x9A.toByte(), 0xED.toByte(), 0x85.toByte(), 0x33, 0x24), Geoscan.pn9(8))
        // CRC-16 0x8005 from 0xFFFF, not reflected (« CRC-16/CMS »): its check value.
        assertEquals(0xAEE7, Geoscan.crc16("123456789".toByteArray()))
    }

    @Test fun trames_et_image_relues() {
        val jpeg = Geoscan.jpegEssai()
        assertTrue(jpeg.size > 500)
        val m = Geoscan.morceaux(0x0C, 3, jpeg)
        val l = ArrayList<ByteArray>()
        val d = Geoscan.Deframeur { l += it }
        // A bit wrong in a sync word, the other polarity for half of them.
        val bits = Geoscan.bits(m)
        bits[8 * 8 + 5] = bits[8 * 8 + 5] xor 1
        for ((i, b) in bits.withIndex()) d.bit(if (i > bits.size / 2) b xor 1 else b)
        assertEquals(m.size, l.size)
        val im = Geoscan.Images(null) { 1_000L }
        // Out of order, the first chunk last: the picture waits for its start.
        for (o in l.drop(1)) im.ajoute(Geoscan.morceau(o)!!, satellite = "Geoscan-2")
        assertFalse(im.liste().single().complete)
        im.ajoute(Geoscan.morceau(l[0])!!)
        val i = im.liste().single()
        assertEquals("Geoscan-2", i.nomSat)
        assertTrue(i.complete)
        assertArrayEquals(jpeg, i.jpeg())
    }

    @Test fun un_morceau_manquant() {
        val jpeg = Geoscan.jpegEssai()
        val m = Geoscan.morceaux(0x09, 1, jpeg)
        val im = Geoscan.Images(null) { 1_000L }
        m.filterIndexed { k, _ -> k != 4 }.forEach { im.ajoute(Geoscan.morceau(it)!!) }
        val i = im.liste().single()
        assertFalse(i.complete)
        assertEquals(54, i.manquants())
        assertEquals("239Alferov", i.nomSat)
    }

    @Test fun par_le_recepteur_de_paquets_9600() {
        val jpeg = Geoscan.jpegEssai()
        val m = Geoscan.morceaux(0x0E, 7, jpeg)
        val fs = RecepteurPaquets.FS_CIBLE
        // A transmitter's preamble before the first frame (the demodulator's AFC and clock settle on it).
        val (re, im) = SignalPaquets.baseband(IntArray(400) { it and 1 } + Geoscan.bits(m), fs, 9_600, 1_200.0, 18.0, 1)
        val r = RecepteurPaquets(null)
        r.contexte = { RecepteurPaquets.Contexte("Geoscan-4", 64892) }
        r.debit = 9_600
        r.traiteBaseband(re, im, re.size, fs)
        val i = r.geoscan.liste().single()
        assertTrue("${i.morceaux.size}/${m.size} " + (0 until m.size).filter { k -> i.morceaux[0x10000L + 54 * k] == null }, i.complete)
        assertArrayEquals(jpeg, i.jpeg())
        assertEquals(RecepteurPaquets.FORMAT_GEOSCAN, r.trames.first().format)
    }
}
