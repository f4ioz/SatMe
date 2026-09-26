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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests born from confronting real sondes.
 *
 * Sonde decoding was first written and checked only against itself: the test
 * pattern built a frame, the decoder read it back, green. The
 * radiosonde_auto_rx reference recordings exposed three bugs at once that no
 * test could see, because pattern and decoder shared the same mistakes.
 *
 * The rule here: every test asserts a value *measured on air*, not one the
 * program gives itself. Comparing a program with itself only proves it is
 * consistent.
 */
class SondeRealSignalTest {

    private val RATE = 44_100.0

    // ------------------------------------------------------------ header

    @Test
    fun `l en-tete cherche est celui que la RS41 emet vraiment`() {
        // Constant taken from the air, not derived from the code.
        val surLAir = intArrayOf(0x10, 0xB6, 0xCA, 0x11, 0x22, 0x96, 0x12, 0xF8)
        assertEquals(surLAir.size, Rs41.HEADER_RAW.size)
        for (k in surLAir.indices) {
            assertEquals("octet $k de l'en-tête sur l'air",
                surLAir[k], Rs41.HEADER_RAW[k])
        }
        // With the mask removed it is the other constant — the one mistakenly
        // searched for in the received stream at one point.
        val desembrouille = intArrayOf(0x86, 0x35, 0xF4, 0x40, 0x93, 0xDF, 0x1A, 0x60)
        for (k in desembrouille.indices) {
            assertEquals("octet $k de l'en-tête désembrouillé",
                desembrouille[k], Rs41.HEADER[k])
        }
    }

    @Test
    fun `le decodeur trouve l en-tete tel qu il passe sur l air`() {
        val buf = ByteArray(64) { 0x5A }
        for (k in Rs41.HEADER_RAW.indices) buf[20 + k] = Rs41.HEADER_RAW[k].toByte()
        assertEquals(20, Rs41.findHeader(buf, 0, buf.size))
    }

    // ------------------------------------------------------- type byte

    @Test
    fun `l octet de type se lit apres la parite Reed-Solomon`() {
        assertEquals(0x38, Rs41.TYPE_AT)
        assertEquals(Rs41.TYPE_AT + 1, Rs41.BLOCKS_AT)
        // Eight header bytes, then 48 parity bytes: 8 + 48 = 0x38.
        assertEquals(Rs41.TYPE_AT, Rs41.HEADER.size + 48)
    }

    @Test
    fun `une parite quelconque ne trouble plus la lecture du type`() {
        val frame = SondeTestFrames.rs41(48.5, -4.0, 12_000.0,
            east = 3.0, north = 4.0, up = -5.0, sats = 9, serial = "P1234567")
        // Reed-Solomon parity is noise to SatMe, which does not correct errors.
        // Fill it with real junk — including byte 8, where the frame type was
        // once wrongly read from, in the middle of the parity.
        for (k in 0x08 until Rs41.TYPE_AT) frame[k] = ((k * 37 + 11) and 0xff).toByte()
        assertTrue("l'octet 8 doit être autre chose que 0x0F",
            (frame[8].toInt() and 0xff) != 0x0F)

        Rs41.descramble(frame)                  // as sent over the air
        val hit = Rs41.scan(frame, 0, frame.size, 404_000_000L, 1L)
        assertNotNull("la trame doit sortir malgré une parité quelconque", hit)
        assertEquals("P1234567", hit!!.frame.serial)
        assertEquals(48.5, hit.frame.lat, 1e-6)
    }

    // ------------------------------------------------------------ clock

    @Test
    fun `le compte de bits tient sur un signal bruite`() {
        val rnd = java.util.Random(20_240_618L)
        val bits = ByteArray(6_000) { rnd.nextInt(2).toByte() }
        val propre = SondeTestFrames.modulate(bits, RATE, Rs41.BAUD, leadingBits = 64)
        // Gaussian noise at half the useful amplitude: enough to cross zero
        // several times per symbol, which once derailed the clock recovery.
        val bruite = ShortArray(propre.size) {
            (propre[it] + Math.round(rnd.nextGaussian() * 5_000.0).toInt())
                .coerceIn(-32_000, 32_000).toShort()
        }
        val d = SondeDemod(RATE, Rs41.BAUD, 8_192)
        d.feedBits(bruite, bruite.size, null)

        val attendu = bruite.size / (RATE / Rs41.BAUD)
        val obtenu = d.bitsAvailable.toDouble()
        val ecart = kotlin.math.abs(obtenu - attendu) / attendu
        // The old clock produced 99.1%: 1% of bits lost, about 25 per 320-byte
        // frame, so never a whole frame. A 1% defect that costs 100% of the
        // result.
        assertTrue("bits produits : $obtenu pour $attendu attendus (écart ${ecart * 100} %)",
            ecart < 0.005)
    }

    @Test
    fun `une trame RS41 bruitee est encore retrouvee`() {
        val rnd = java.util.Random(1_866L)
        val lat = 48.44425; val lon = -4.41238; val alt = 18_300.0
        val frame = SondeTestFrames.rs41(lat, lon, alt,
            east = 12.0, north = -9.0, up = 5.0, sats = 11, serial = "S4351234")
        Rs41.descramble(frame)
        val bits = SondeTestFrames.bitsOf(frame, lsbFirst = true)
        val propre = SondeTestFrames.modulate(bits, RATE, Rs41.BAUD, leadingBits = 128)
        val bruite = ShortArray(propre.size) {
            (propre[it] + Math.round(rnd.nextGaussian() * 3_000.0).toInt())
                .coerceIn(-32_000, 32_000).toShort()
        }

        val d = SondeDemod(RATE, Rs41.BAUD, 4_096)
        d.feedBits(bruite, bruite.size, null)
        var hit: Rs41.Hit? = null
        for (off in 0 until 8) {
            val n = d.packBytes(off)
            hit = Rs41.scan(d.bytes, 0, n, 404_000_000L, 3L)
            if (hit != null) break
        }
        assertNotNull("aucune trame retrouvée sous le souffle", hit)
        assertEquals(lat, hit!!.frame.lat, 1e-6)
        assertEquals(lon, hit.frame.lon, 1e-6)
        assertEquals(alt, hit.frame.altM, 0.05)
    }

    // ---------------------------------------------------------------- M10

    @Test
    fun `la M10 tourne sur ses chips et non sur leur double`() {
        // 9616 is the chip rate, not the bit rate: the factor two is already
        // in. Doubling it again set the demodulator to 19232 chips/s, each chip
        // was read twice, and decoding saw only flat pairs.
        assertEquals(9_616.0, Meteomodem.M10_CHIP_RATE, 1e-9)
        assertEquals(4_808.0, Meteomodem.M10_BAUD, 1e-9)
        assertEquals(Meteomodem.M10_CHIP_RATE, SondeModel.M10.chipRate, 1e-9)
        assertEquals(Meteomodem.M10_BAUD, SondeModel.M10.baud, 1e-9)
        assertEquals(4.5861, SondeModel.M10.samplesPerChip(44_100), 1e-4)
        assertTrue("la M10 n'est pas un cas limite à 44,1 kHz",
            !SondeModel.M10.marginal(44_100))
    }

    @Test
    fun `un flux Manchester lu a la bonne cadence ne donne que des plages de un et deux`() {
        // Signature of a two-chips-per-bit code read at the right rate: run
        // lengths in the chip stream are only 1 or 2. That is what the real
        // recording shows, and what the test pattern must reproduce.
        val rnd = java.util.Random(910L)
        val bits = ByteArray(2_000) { rnd.nextInt(2).toByte() }
        val chips = SondeMire.biphaseEncode(bits)
        var plage = 1
        var maxPlage = 1
        for (k in 1 until chips.size) {
            if (chips[k] == chips[k - 1]) plage++ else { if (plage > maxPlage) maxPlage = plage; plage = 1 }
        }
        if (plage > maxPlage) maxPlage = plage
        assertEquals("plage la plus longue du flux de chips", 2, maxPlage)
        assertEquals("deux chips par bit", bits.size * 2, chips.size)
    }
}
