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
 * Decoding Meteomodem M10 and M20 (the Météo-France sondes).
 *
 * The M20 has no reproducible checksum, so physical plausibility is its
 * guard. The M10 has one, and it is required. These tests check first that
 * the guard holds, only then that the numbers are right.
 */
class MeteomodemTest {

    private val LAT = 48.44425
    private val LON = -4.41238
    private val ALT = 18_000.0

    @Test
    fun `les entiers se lisent poids fort en tete`() {
        val f = byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0x1234, Meteomodem.beu16(f, 0))
        assertEquals(0x1234, Meteomodem.be16(f, 0))
        assertEquals(-1, Meteomodem.be16(f, 2))
        assertEquals(0xFFFF, Meteomodem.beu16(f, 2))
        assertEquals(0x1234FFFF.toInt(), Meteomodem.be32(f, 0))
        val neg = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, Meteomodem.be32(neg, 0))
    }

    @Test
    fun `le bi-phase lit un bit par paire de demi-bits`() {
        // Equal pairs: zeros. Different pairs: ones.
        val chips = byteArrayOf(1, 1, 0, 0, 1, 0, 0, 1)
        val out = ByteArray(8)
        assertEquals(4, Meteomodem.biphase(chips, 0, chips.size, out))
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[1].toInt())
        assertEquals(1, out[2].toInt())
        assertEquals(1, out[3].toInt())
    }

    @Test
    fun `le bi-phase ne change rien quand tout le flux est inverse`() {
        // This property lets polarity be resolved only at sync time: equality
        // of two half-bits survives inversion, so decoded bits do too. The old
        // "Manchester" decoding lacked it and picked its phase by counting flat
        // pairs — i.e. from perfectly legitimate data.
        val chips = byteArrayOf(1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1)
        val flip = ByteArray(chips.size) { ((chips[it].toInt() xor 1)).toByte() }
        val a = ByteArray(8)
        val b = ByteArray(8)
        val na = Meteomodem.biphase(chips, 0, chips.size, a)
        val nb = Meteomodem.biphase(flip, 0, flip.size, b)
        assertEquals(na, nb)
        for (k in 0 until na) assertEquals("bit $k", a[k].toInt(), b[k].toInt())
    }

    @Test
    fun `une M20 rend la position transmise`() {
        val f = SondeTestFrames.m20(LAT, LON, ALT, east = -9.0, north = 12.0, up = -5.5,
            sats = 11, serial = 4321)
        val d = Meteomodem.parseM20(f, 404_000_000L, 77L)
        assertNotNull(d)
        d!!
        assertEquals("M20", d.type)
        assertEquals("4321", d.serial)
        assertEquals(LAT, d.lat, 1e-6)
        assertEquals(LON, d.lon, 1e-6)
        assertEquals(ALT, d.altM, 0.01)
        assertEquals(15.0, d.speedMps, 0.01)          // hypot(-9, 12)
        assertEquals(-5.5, d.climbMps, 0.01)
        assertEquals(11, d.sats)
        assertEquals(404_000_000L, d.freqHz)
        assertEquals(77L, d.heardAtMs)
        assertTrue(d.trusted)
        assertTrue(d.descending)
        assertEquals(323.13, d.headingDeg, 0.1)       // north-west
    }

    @Test
    fun `une M20 trop haute est refusee`() {
        val f = SondeTestFrames.m20(LAT, LON, 60_000.0, 0.0, 0.0, 3.0)
        assertNull(Meteomodem.parseM20(f))
    }

    @Test
    fun `une M20 trop rapide est refusee`() {
        // 250 m/s ground speed: no jet stream does that.
        val f = SondeTestFrames.m20(LAT, LON, ALT, east = 250.0, north = 0.0, up = 0.0)
        assertNull(Meteomodem.parseM20(f))
    }

    @Test
    fun `une trame M20 tronquee ne rend rien`() {
        val f = SondeTestFrames.m20(LAT, LON, ALT, 0.0, 0.0, 3.0)
        assertNull(Meteomodem.parseM20(f.copyOf(Meteomodem.M20_LEN - 1)))
    }

    @Test
    fun `une M10 rend la position transmise`() {
        val f = SondeTestFrames.m10(LAT, LON, ALT, east = 3.0, north = 4.0, up = 5.0,
            count = 157, week = 2300, itowMs = 43_200_000L)
        val d = Meteomodem.parseM10(f, 401_000_000L, 55L)
        assertNotNull(d)
        d!!
        assertEquals("M10", d.type)
        assertEquals("803-2-10732", d.serial)
        assertEquals(157, d.frameNo)
        assertEquals(LAT, d.lat, 1e-6)
        assertEquals(LON, d.lon, 1e-6)
        assertEquals(ALT, d.altM, 0.01)
        assertEquals(5.0, d.speedMps, 0.01)
        assertEquals(5.0, d.climbMps, 0.01)
        assertEquals(Geo.gpsToUnixMs(2300, 43_200_000L), d.timeUtcMs)
        // We do not read the M10 satellite count. Treating that as "never
        // trusted" was wrong: the chaser lost the last trusted point, burst and
        // landing — everything useful in the field. Here the clock is the
        // guarantee: a valid GPS week and time means the receiver had a fix.
        assertEquals(0, d.sats)
        assertTrue("le nombre de satellites doit être signalé comme inconnu", d.satsUnknown)
        assertTrue("une M10 horodatée doit être un point sûr", d.trusted)
    }

    @Test
    fun `une M10 sans horloge GPS ne passe pas pour un point sur`() {
        // The flip side: with no clock, nothing guarantees a fix, and the frame
        // must not serve as last known position.
        val f = SondeTestFrames.m10(LAT, LON, ALT, east = 3.0, north = 4.0, up = 5.0,
            count = 778, week = 0, itowMs = 0L)
        val d = Meteomodem.parseM10(f, 401_000_000L, 55L)!!
        assertTrue("sans horloge, le point ne peut pas être sûr", !d.trusted)
    }

    @Test
    fun `les champs M10 ne se chevauchent pas`() {
        // Earlier offsets were wrong throughout: the counter overlapped the
        // longitude and the serial was a piece of a coordinate. These come from
        // a real recording; this at least checks they do not overlap.
        val m = Meteomodem.M10
        assertTrue(m.VE + 2 <= m.VN)
        assertTrue(m.VN + 2 <= m.VU)
        assertTrue(m.VU + 2 <= m.TOW)
        assertTrue(m.TOW + 4 <= m.LAT)
        assertTrue(m.LAT + 4 <= m.LON)
        assertTrue(m.LON + 4 <= m.ALT)
        assertTrue(m.ALT + 4 <= m.WEEK)
        assertTrue(m.WEEK + 2 <= m.SN)
        assertTrue(m.SN + 5 <= m.CNT)
        assertTrue(m.CNT + 1 <= m.CHECK)
        assertTrue(m.CHECK + 2 <= Meteomodem.M10_LEN)
        val f = SondeTestFrames.m10(LAT, LON, ALT, 0.0, 0.0, 2.0, count = 200)
        val d = Meteomodem.parseM10(f)!!
        assertEquals(200, d.frameNo)
        assertEquals(LON, d.lon, 1e-6)
    }

    @Test
    fun `une M10 dont la somme de controle est fausse est refusee`() {
        // The M10's real guard: one byte changed mid-frame and everything is
        // dropped, even if coordinates stay perfectly plausible.
        val f = SondeTestFrames.m10(LAT, LON, ALT, 1.0, 1.0, 2.0)
        assertNotNull(Meteomodem.parseM10(f))
        f[0x30] = (f[0x30].toInt() xor 0x01).toByte()
        assertNull(Meteomodem.parseM10(f))
    }

    @Test
    fun `les decalages M20 tiennent dans la trame`() {
        assertTrue(Meteomodem.M20.SATS < Meteomodem.M20_LEN)
        assertTrue(Meteomodem.M20.SERIAL + 2 <= Meteomodem.M20_LEN)
        assertTrue(Meteomodem.M20.LON + 4 <= Meteomodem.M20_LEN)
        assertTrue(Meteomodem.M10.WEEK + 2 <= Meteomodem.M10_LEN)
        assertTrue(Meteomodem.M10.CNT + 1 <= Meteomodem.M10_LEN)
    }

    @Test
    fun `une M20 est retrouvee au milieu d un tampon`() {
        val f = SondeTestFrames.m20(LAT, LON, ALT, 6.0, 8.0, -4.0, sats = 8)
        val buf = ByteArray(16 + f.size + 16)
        System.arraycopy(f, 0, buf, 16, f.size)
        val hit = Meteomodem.scan(buf, 0, buf.size, 404_000_000L, 5L)
        assertNotNull(hit)
        hit!!
        assertEquals(16 + Meteomodem.M20_LEN, hit.nextIndex)
        assertEquals("M20", hit.frame.type)
        assertEquals(LAT, hit.frame.lat, 1e-6)
        assertEquals(10.0, hit.frame.speedMps, 0.01)
    }

    @Test
    fun `un tampon vide ne rend rien`() {
        assertNull(Meteomodem.scan(ByteArray(256), 0, 256))
    }

    @Test
    fun `la largeur de filtre couvre les deux debits`() {
        assertTrue(Meteomodem.BANDWIDTH_HZ > Meteomodem.M20_BAUD * 2)
        assertTrue(Rs41.BANDWIDTH_HZ > Rs41.BAUD * 2)
    }
}
