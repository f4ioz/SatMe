/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.i18n.I18n
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both CAT dialects, tested with hand-written bytes and no radio.
 *
 * Frame parsing used to live inside the USB calls, so the only check was to
 * plug in a radio and watch its front panel — which cannot tell a command
 * understood from one politely acknowledged and then discarded.
 */
class CatDecodeTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val radio = 0xA2
    private val ctrl = 0xE0

    @Test
    fun deux_trames_collees_se_separent_et_le_bruit_se_jette() {
        val buf = b(
            0x11, 0x22,                                        // leading noise
            0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD,                // ack
            0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD
        )
        val f = CatDecode.splitCiv(buf)
        assertEquals(2, f.size)
        assertEquals(CatDecode.ACK, CatDecode.command(f[0]))
        assertEquals(0x03, CatDecode.command(f[1]))
        // A three-0xFE preamble, sent by some radios, shifts nothing.
        assertEquals(1, CatDecode.splitCiv(b(0xFE, 0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD)).size)
    }

    @Test
    fun une_trame_tronquee_n_est_pas_rendue() {
        // Buffer ends mid-frame: return nothing rather than an incomplete
        // frequency that looks like a real one.
        val f = CatDecode.splitCiv(b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50))
        assertTrue(f.isEmpty())
        assertTrue(CatDecode.splitCiv(ByteArray(0)).isEmpty())
        assertEquals(-1, CatDecode.command(b(0xFE, 0xFE)))
    }

    @Test
    fun l_echo_du_bus_se_distingue_de_la_reponse_du_poste() {
        // CI-V is a single-wire bus: what we write comes back to us. Only the
        // swapped addresses tell echo from reply.
        val question = b(0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD)
        val reponse = b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD)
        assertTrue(CatDecode.isEcho(question, radio, ctrl))
        assertTrue(!CatDecode.isFromRadio(question, radio, ctrl))
        assertTrue(CatDecode.isFromRadio(reponse, radio, ctrl))
        assertTrue(!CatDecode.isEcho(reponse, radio, ctrl))
        // A frame for another radio on the bus is not ours.
        assertTrue(!CatDecode.isFromRadio(b(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0xFD), radio, ctrl))
    }

    @Test
    fun la_charge_utile_ignore_l_echo_et_les_accuses() {
        val frames = CatDecode.splitCiv(b(
            0xFE, 0xFE, 0xA2, 0xE0, 0x03, 0xFD,                              // our echo
            0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD,                              // ack
            0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x35, 0x35, 0x04, 0xFD // the reply
        ))
        assertEquals(3, frames.size)
        val p = CatDecode.payload(frames, radio, ctrl, 0x03)
        assertArrayEquals(b(0x00, 0x50, 0x35, 0x35, 0x04), p)
        assertTrue(CatDecode.isAck(frames, radio, ctrl))
        assertTrue(!CatDecode.isNak(frames, radio, ctrl))
        // A non-matching sub-command returns null, not the "roughly right"
        // next frame.
        val vfo = CatDecode.splitCiv(b(
            0xFE, 0xFE, 0xE0, 0xA2, 0x25, 0x01, 0x00, 0x90, 0x59, 0x45, 0x01, 0xFD))
        assertNull(CatDecode.payload(vfo, radio, ctrl, 0x25, 0x00))
        assertEquals(5, CatDecode.payload(vfo, radio, ctrl, 0x25, 0x01)!!.size)
    }

    @Test
    fun la_frequence_fait_l_aller_retour_en_bcd_petit_boutien() {
        // 435.530000 MHz, digits in pairs, reversed.
        assertArrayEquals(b(0x00, 0x00, 0x53, 0x35, 0x04), CatDecode.freqToBcdLe(435_530_000L))
        for (hz in listOf(145_800_000L, 435_500_000L, 1_296_100_000L, 29_600_000L)) {
            assertEquals(hz, CatDecode.bcdLeToFreq(CatDecode.freqToBcdLe(hz)))
        }
    }

    @Test
    fun une_lecture_invraisemblable_rend_null_plutot_qu_un_nombre() {
        // Nibbles that are not digits are not BCD, and reading them as such
        // would give a perfectly plausible frequency.
        assertNull(CatDecode.bcdLeToFreq(b(0xFF, 0x00, 0x53, 0x35, 0x04)))
        // Too short.
        assertNull(CatDecode.bcdLeToFreq(b(0x00, 0x00, 0x53)))
        // Zero hertz, which old code produced when reading its own echo
        // followed by nothing.
        assertNull(CatDecode.bcdLeToFreq(b(0x00, 0x00, 0x00, 0x00, 0x00)))
    }

    @Test
    fun le_ton_d_acces_est_gros_boutien_et_l_ancien_encodage_valait_885_hz() {
        // 88.5 Hz: three bytes, the first one zero.
        assertArrayEquals(b(0x00, 0x08, 0x85), CatDecode.toneToBcdBe(885))
        assertArrayEquals(b(0x00, 0x06, 0x70), CatDecode.toneToBcdBe(670))
        assertEquals(885, CatDecode.bcdBeToTone(b(0x00, 0x08, 0x85)))

        // The old bug: the encoder treated the tone like a frequency (reversed)
        // and sent 00 88 50. The radio reads 885.0 Hz — out of range, ignored,
        // yet acknowledged.
        val ancien = b(0x00, 0x88, 0x50)
        assertEquals(8850, CatDecode.bcdBeToTone(ancien))
        assertTrue("885 Hz aurait dû être hors plage",
            !CatDecode.toneInRange(CatDecode.bcdBeToTone(ancien)!!))
        assertTrue(CatDecode.toneInRange(885))
        assertTrue(CatDecode.toneInRange(CatDecode.TONE_MIN_TENTH))
        assertTrue(CatDecode.toneInRange(CatDecode.TONE_MAX_TENTH))
        assertTrue(!CatDecode.toneInRange(CatDecode.TONE_MAX_TENTH + 1))
        assertNull(CatDecode.bcdBeToTone(b(0x00, 0x0F, 0x85)))
    }

    @Test
    fun le_dialecte_yaesu_compte_par_dix_hertz_et_se_traduit() {
        // Eight big-endian BCD digits, 10 Hz units.
        assertArrayEquals(b(0x14, 0x58, 0x00, 0x00), CatDecode.yaesuFreq(145_800_000L))
        assertEquals(145_800_000L, CatDecode.yaesuFreqOf(b(0x14, 0x58, 0x00, 0x00)))
        // The 10 Hz step is real: the units digit is lost.
        assertEquals(435_500_000L, CatDecode.yaesuFreqOf(CatDecode.yaesuFreq(435_500_007L)))
        assertNull(CatDecode.yaesuFreqOf(b(0x1A, 0x58, 0x00, 0x00)))

        // Log descriptions: readable without the manual.
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xA2, 0xE0, 0x07, 0xD1, 0xFD)).contains("secondaire"))
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xE0, 0xA2, 0xFA, 0xFD)).contains("efus"))
        assertTrue(CatDecode.describeCiv(
            b(0xFE, 0xFE, 0xA2, 0xE0, 0x16, 0x5A, 0x01, 0xFD)).contains("satellite"))
        assertTrue(CatDecode.describeYaesu(
            b(0x00, 0x00, 0x00, 0x00, 0xF7), fromRig = false).contains("émission"))
        assertTrue(CatDecode.describeYaesu(
            b(0x80), fromRig = true, lastOp = 0xF7).contains("reçoit"))
    }

    /**
     * The frame journal follows the app language. It used to be French
     * whatever the setting; the numbers keep their decimal point either way.
     */
    @Test
    fun le_journal_suit_la_langue_de_l_application() {
        val envoi = b(0xFE, 0xFE, 0xA2, 0xE0, 0x05, 0x00, 0x00, 0x90, 0x45, 0x01, 0xFD)
        val accuse = b(0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD)
        try {
            I18n.apply("en", "")
            assertEquals("frequency ← 145.90000 MHz", CatDecode.describeCiv(envoi))
            assertEquals("acknowledged", CatDecode.describeCiv(accuse))
            assertEquals("rig receiving",
                CatDecode.describeYaesu(b(0x80), fromRig = true, lastOp = 0xF7))
            I18n.apply("fr", "")
            assertEquals("fréquence ← 145.90000 MHz", CatDecode.describeCiv(envoi))
            assertEquals("accusé de réception", CatDecode.describeCiv(accuse))
        } finally {
            I18n.apply("fr", "")
        }
    }
}
