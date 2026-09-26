/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M10 against a real frame from a real recording.
 *
 * [MeteomodemTest] builds the frames it decodes: it shows self-consistency,
 * not correctness, and M10 decoders passed it without ever decoding a real
 * sonde. Here the 101 bytes below were demodulated from an auto_rx reference
 * recording, and the app must read them as rs1729 does — same checksum,
 * position and serial.
 *
 * The other half goes further up: rebuild a half-bit stream with the test
 * pattern encoder, run it through sync search, and require the original frame
 * in both polarities. That link was missing: decoding started one half-bit
 * too far and produced endless FFs, unnoticed by any test.
 */
class MeteomodemM10Test {

    /** Parses a frame written in hex, as a decoder prints it. */
    private fun hex(s: String): ByteArray {
        val parts = s.trim().split(Regex("\\s+"))
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }

    /**
     * Burst 0 of the reference recording, 101 bytes.
     *
     * Position at the North Pole, longitude zero: a sonde on the bench, not in
     * flight. Fine — the decoder must not be pickier than the manufacturer.
     */
    private val reference = hex(
        "64 9F 20 00 00 00 00 00 00 00 00 00 46 50 3F FF " +
        "FF F4 00 00 00 00 00 02 49 F0 00 00 00 05 00 12 " +
        "08 00 00 00 00 00 00 00 00 00 00 00 00 00 A7 7B " +
        "C6 71 99 97 09 95 8C 09 01 00 27 24 00 00 00 19 " +
        "A5 F2 09 F2 09 62 03 12 5A 0B 01 00 08 00 13 00 " +
        "00 00 00 00 00 E4 0F 1A 00 82 01 FF 00 02 14 83 " +
        "DC 22 9D BA D6"
    )

    @Test
    fun la_trame_de_reference_a_la_bonne_longueur() {
        assertEquals(Meteomodem.M10_LEN, reference.size)
    }

    @Test
    fun la_somme_de_controle_de_la_trame_de_reference_tombe_juste() {
        // 0xBAD6 over the first 0x63 bytes. This checksum tells us we started
        // on the right half-bit: without it, a one-chip shift gives a
        // plausible but wrong frame.
        assertEquals(0xBAD6, Meteomodem.checkM10(reference, 0x63))
        assertTrue(Meteomodem.checkOkM10(reference))
    }

    @Test
    fun un_octet_retourne_casse_la_somme_de_controle() {
        val f = reference.copyOf()
        f[0x30] = (f[0x30].toInt() xor 1).toByte()
        assertTrue(!Meteomodem.checkOkM10(f))
        assertNull(Meteomodem.parseM10(f, 403_000_000L, 0L))
    }

    @Test
    fun la_trame_de_reference_rend_ce_que_rs1729_en_lit() {
        val f = Meteomodem.parseM10(reference, 403_000_000L, 1_700_000_000_000L)
        assertNotNull("trame de référence refusée", f)
        f!!
        assertEquals("M10", f.type)
        assertEquals(90.0, f.lat, 1e-5)
        assertEquals(0.0, f.lon, 1e-5)
        assertEquals(150.0, f.altM, 0.5)
        assertEquals("803-2-10732", f.serial)
        assertEquals(157, f.frameNo)
        assertEquals(403_000_000L, f.freqHz)
    }

    @Test
    fun la_semaine_et_l_heure_gps_sont_celles_de_l_enregistrement() {
        // TOW at 0x0A is 0x4650 ms, week 2048 at 0x20. The field is in
        // milliseconds, not seconds: m10ptu.c divides by 1000 right after
        // reading it.
        var tow = 0L
        for (k in 0 until 4) tow = (tow shl 8) or (reference[0x0A + k].toLong() and 0xff)
        assertEquals(18_000L, tow)
        val week = ((reference[0x20].toInt() and 0xff) shl 8) or
            (reference[0x21].toInt() and 0xff)
        assertEquals(2048, week)
    }

    @Test
    fun le_numero_de_serie_se_lit_sur_les_cinq_octets() {
        assertEquals("803-2-10732", Meteomodem.serialM10(reference))
        // The test pattern sends the same one, so demo and reference recording
        // describe the same sonde.
        val demo = ByteArray(Meteomodem.M10_LEN)
        for (k in Meteomodem.M10_SERIAL_DEMO.indices) {
            demo[Meteomodem.M10.SN + k] = Meteomodem.M10_SERIAL_DEMO[k]
        }
        assertEquals("803-2-10732", Meteomodem.serialM10(demo))
    }

    /** The M10 preamble: 1001 repeated. */
    private fun preamble(n: Int) =
        ByteArray(n) { (if ((it and 3) == 0 || (it and 3) == 3) 1 else 0).toByte() }

    /** Builds the full half-bit stream: preamble, sync, body. */
    private fun chipsOf(frame: ByteArray, invert: Boolean): ByteArray {
        val head = preamble(400)
        val sync = Meteomodem.M10_SYNC
        val cut = Meteomodem.M10_SYNC_TO_FRAME
        val body = SondeMire.biphaseEncode(
            SondeMire.bitsOf(frame, lsbFirst = false), sync[cut].toInt() and 1)
        val all = ByteArray(head.size + cut + body.size)
        System.arraycopy(head, 0, all, 0, head.size)
        for (k in 0 until cut) all[head.size + k] = sync[k]
        System.arraycopy(body, 0, all, head.size + cut, body.size)
        if (invert) for (k in all.indices) all[k] = (all[k].toInt() xor 1).toByte()
        return all
    }

    @Test
    fun le_flux_de_demi_bits_se_relit_dans_les_deux_polarites() {
        // An SDR dongle guarantees nothing about discriminator polarity:
        // depending on the carrier side, every half-bit comes out inverted.
        // Biphase-mark does not care — the sync search must not either.
        for (invert in listOf(false, true)) {
            val chips = chipsOf(reference, invert)
            val at = Meteomodem.findSync(chips, chips.size, 0)
            assertTrue("motif introuvable (invert=$invert)", at >= 0)
            val out = ByteArray(Meteomodem.M10_LEN)
            assertTrue("trame incomplète (invert=$invert)",
                Meteomodem.frameFromChips(chips, at, out))
            assertTrue("somme fausse (invert=$invert)", Meteomodem.checkOkM10(out))
            for (k in reference.indices) {
                assertEquals("octet $k (invert=$invert)", reference[k], out[k])
            }
        }
    }

    @Test
    fun le_motif_ne_se_declenche_pas_dans_le_preambule() {
        // The preamble is 1001 repeated and the first sixteen sync half-bits
        // match it; the second half differs. With two errors tolerated and five
        // differing positions, sync cannot lock inside the preamble.
        val head = preamble(400)
        assertEquals(-1, Meteomodem.findSync(head, head.size - 40, 0))
    }
}
