/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le décodage de la Vaisala RS41.
 *
 * La règle qui gouverne ces essais : une trame abîmée doit rendre null, jamais
 * une position approchée. Mieux vaut un écran vide qu'un chasseur envoyé à
 * trente kilomètres du ballon.
 */
class Rs41Test {

    private val LAT = 48.5
    private val LON = -4.0
    private val ALT = 20_000.0

    @Test
    fun `le CRC est bien le CCITT a registre plein`() {
        // Valeur d'épreuve normalisée du CRC-16-CCITT-FALSE sur « 123456789 ».
        val d = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Rs41.crc16(d, 0, d.size))
        assertEquals(0xFFFF, Rs41.crc16(ByteArray(0), 0, 0))
    }

    @Test
    fun `les entiers se lisent poids faible en tete`() {
        val f = byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0x1234, Rs41.u16(f, 0))
        assertEquals(-1, Rs41.i16(f, 2))
        assertEquals(0xFFFF, Rs41.u16(f, 2))
        val g = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, Rs41.i32(g, 0))
        assertEquals(4_294_967_295L, Rs41.u32(g, 0))
    }

    @Test
    fun `le desembrouillage est sa propre reciproque`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, 0.0, 0.0, 5.0)
        val copy = f.copyOf()
        Rs41.descramble(copy)
        assertTrue(!copy.contentEquals(f))
        Rs41.descramble(copy)
        assertTrue(copy.contentEquals(f))
    }

    @Test
    fun `l en-tete sur l air est l en-tete masque`() {
        assertEquals(Rs41.HEADER.size, Rs41.HEADER_RAW.size)
        for (k in Rs41.HEADER.indices) {
            assertEquals(Rs41.HEADER[k] xor Rs41.MASK[k], Rs41.HEADER_RAW[k])
        }
        assertEquals(64, Rs41.MASK.size)
    }

    @Test
    fun `longueur annoncee par le type`() {
        assertEquals(Rs41.LEN_STD, Rs41.frameLength(0x0F))
        assertEquals(Rs41.LEN_EXT, Rs41.frameLength(0xF0))
        assertEquals(0, Rs41.frameLength(0x00))
        assertEquals(0, Rs41.frameLength(0x55))
    }

    @Test
    fun `les blocs sont retrouves avec leur CRC`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, 0.0, 0.0, 5.0)
        val b = Rs41.blocks(f)
        assertEquals(3, b.size)
        assertEquals(Rs41.BLK_STATUS, b[0].id)
        assertEquals(Rs41.BLK_GPS_TIME, b[1].id)
        assertEquals(Rs41.BLK_GPS_POS, b[2].id)
        assertTrue(b.all { it.crcOk })
    }

    @Test
    fun `une trame complete rend la position transmise`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, east = 12.0, north = -5.0, up = 4.5,
            sats = 9, serial = "P1234567", frameNo = 4242)
        val d = Rs41.parse(f, freqHz = 405_700_000L, nowMs = 1234L)
        assertNotNull(d)
        d!!
        assertEquals("RS41", d.type)
        assertEquals("P1234567", d.serial)
        assertEquals(4242, d.frameNo)
        assertEquals(LAT, d.lat, 1e-6)
        assertEquals(LON, d.lon, 1e-6)
        assertEquals(ALT, d.altM, 0.05)
        assertEquals(13.0, d.speedMps, 0.05)          // hypot(12, -5)
        assertEquals(4.5, d.climbMps, 0.05)
        assertEquals(9, d.sats)
        assertEquals(2.7, d.batteryV, 1e-9)
        assertEquals(405_700_000L, d.freqHz)
        assertEquals(1234L, d.heardAtMs)
        assertTrue(d.trusted)
        // Le cap : vers l'est-sud-est, puisqu'on file douze à l'est et cinq au sud.
        assertEquals(112.6, d.headingDeg, 0.5)
    }

    @Test
    fun `l heure GPS est reportee dans la trame`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, 0.0, 0.0, 5.0,
            week = 2300, itowMs = 43_200_000L)
        val d = Rs41.parse(f)!!
        assertEquals(Geo.gpsToUnixMs(2300, 43_200_000L), d.timeUtcMs)
    }

    @Test
    fun `un mauvais en-tete ne rend rien`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, 0.0, 0.0, 5.0)
        f[3] = (f[3].toInt() xor 0x01).toByte()
        assertNull(Rs41.parse(f))
    }

    @Test
    fun `un bloc de position abime est ignore plutot que cru`() {
        val f = SondeTestFrames.rs41(LAT, LON, ALT, 0.0, 0.0, 5.0)
        // On abîme un octet de coordonnée : le CRC du bloc ne passe plus, le
        // bloc est sauté, et sans position la trame n'apprend rien.
        val blocks = Rs41.blocks(f)
        val pos = blocks.first { it.id == Rs41.BLK_GPS_POS }
        f[pos.at + 2] = (f[pos.at + 2].toInt() xor 0x40).toByte()
        assertTrue(!Rs41.blocks(f).first { it.id == Rs41.BLK_GPS_POS }.crcOk)
        assertNull(Rs41.parse(f))
    }

    @Test
    fun `une position invraisemblable est refusee`() {
        // Cent kilomètres d'altitude : ce n'est plus un ballon météo.
        val f = SondeTestFrames.rs41(LAT, LON, 100_000.0, 0.0, 0.0, 5.0)
        assertNull(Rs41.parse(f))
    }

    @Test
    fun `la trame est retrouvee au milieu d un flux brouille`() {
        val frame = SondeTestFrames.rs41(LAT, LON, ALT, 8.0, 3.0, -6.0, sats = 7)
        Rs41.descramble(frame)                       // telle qu'elle passe sur l'air
        val buf = ByteArray(64 + frame.size + 32)
        for (k in 0 until 64) buf[k] = 0x55
        System.arraycopy(frame, 0, buf, 64, frame.size)
        val hit = Rs41.scan(buf, 0, buf.size, 404_000_000L, 99L)
        assertNotNull(hit)
        hit!!
        assertEquals(64 + Rs41.LEN_STD, hit.nextIndex)
        assertEquals(LAT, hit.frame.lat, 1e-6)
        assertEquals(LON, hit.frame.lon, 1e-6)
        assertEquals(-6.0, hit.frame.climbMps, 0.05)
        assertEquals(404_000_000L, hit.frame.freqHz)
    }

    @Test
    fun `un flux sans sonde ne rend rien`() {
        val buf = ByteArray(2048) { (it * 31 and 0xff).toByte() }
        assertNull(Rs41.scan(buf, 0, buf.size))
    }
}
