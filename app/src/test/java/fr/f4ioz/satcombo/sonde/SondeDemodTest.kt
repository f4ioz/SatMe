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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le passage du son aux bits, puis aux octets.
 *
 * L'essai qui compte est le dernier : une trame RS41 fabriquée, modulée en
 * carré, repassée dans le démodulateur, et retrouvée intacte de l'autre côté.
 * Tant que celui-là passe, la chaîne complète — horloge, tranchage, alignement
 * d'octets, désembrouillage, CRC, coordonnées — tient debout.
 */
class SondeDemodTest {

    private val RATE = 44_100.0

    @Test
    fun `echantillons par bit`() {
        val d = SondeDemod(RATE, Rs41.BAUD)
        assertEquals(9.1875, d.samplesPerBit, 1e-12)
        assertTrue(!d.marginal)
        // La M10 module en bi-phase à marque : le démodulateur compte les chips,
        // 9616 par seconde, soit 4808 bits utiles. À 44 100 Hz cela fait 4,59
        // échantillons par chip, ce qui est confortable. La 18.5 croyait devoir
        // tourner à 19232 et n'en avait que 2,29 : c'est ce facteur deux, et
        // non la carte son, qui empêchait toute M10 de sortir.
        val m10 = SondeDemod(RATE, Meteomodem.M10_CHIP_RATE)
        assertEquals(44_100.0 / 9_616.0, m10.samplesPerBit, 1e-9)
        assertTrue(m10.samplesPerBit > 4.5)
        assertTrue(!m10.marginal)
        // Sur une carte son à huit kilohertz, en revanche, elle ne passe plus.
        assertTrue(SondeDemod(8_000.0, Meteomodem.M10_CHIP_RATE).marginal)
    }

    @Test
    fun `l amplitude cretes a cretes mesure la presence`() {
        val d = SondeDemod(RATE, Rs41.BAUD)
        assertEquals(0, d.swing(ShortArray(0), 0))
        assertEquals(20_000, d.swing(shortArrayOf(-10_000, 0, 10_000), 3))
        assertEquals(0, d.swing(shortArrayOf(7, 7, 7), 3))
    }

    @Test
    fun `assemblage des octets poids fort en tete`() {
        val d = SondeDemod(RATE, Meteomodem.M20_BAUD)
        // 0x45 = 0100 0101, 0x20 = 0010 0000
        val bits = byteArrayOf(0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0)
        assertEquals(2, d.packBytesMsb(bits, bits.size, 0))
        assertEquals(0x45, d.bytes[0].toInt() and 0xff)
        assertEquals(0x20, d.bytes[1].toInt() and 0xff)
        // Un décalage d'un bit doit donner autre chose, et un octet de moins.
        assertEquals(1, d.packBytesMsb(bits, bits.size, 1))
        assertEquals(0x8A, d.bytes[0].toInt() and 0xff)
    }

    @Test
    fun `les bits sortent dans l ordre ou ils sont entres`() {
        val d = SondeDemod(RATE, Rs41.BAUD, 256)
        val pattern = byteArrayOf(1, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 1, 1, 1, 0, 1,
            0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 1, 0, 1, 1)
        val pcm = SondeTestFrames.modulate(pattern, RATE, Rs41.BAUD, leadingBits = 32)
        d.feedBits(pcm, pcm.size, null)
        val chips = d.chipsCopy()
        // Le nombre de bits produits suit le nombre d'échantillons.
        val attendu = (pcm.size / 9.1875).toInt()
        assertTrue("bits produits : ${chips.size} au lieu de $attendu",
            kotlin.math.abs(chips.size - attendu) <= 2)
        // Et la suite envoyée se retrouve telle quelle dans ce qui sort.
        assertTrue("motif introuvable dans ${chips.size} bits", contains(chips, pattern))
    }

    @Test
    fun `un desaccord ne fausse pas le tranchage`() {
        // Cinq cents hertz à côté : le carré est décentré, et sans la moyenne
        // glissante un bit sur deux serait faux.
        val d = SondeDemod(RATE, Rs41.BAUD, 256)
        val pattern = byteArrayOf(1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 0, 0, 1, 0, 1)
        val pcm = SondeTestFrames.modulate(pattern, RATE, Rs41.BAUD, leadingBits = 64)
        val biased = ShortArray(pcm.size) { (pcm[it] + 4_000).toShort() }
        d.feedBits(biased, biased.size, null)
        assertTrue(contains(d.chipsCopy(), pattern))
    }

    @Test
    fun `le tampon de bits se rogne sans perdre la fin`() {
        val d = SondeDemod(RATE, Rs41.BAUD, 256)
        val pcm = SondeTestFrames.modulate(ByteArray(200) { (it and 1).toByte() },
            RATE, Rs41.BAUD, leadingBits = 0)
        d.feedBits(pcm, pcm.size, null)
        val before = d.chipsCopy()
        assertTrue(before.size > 100)
        d.trimTo(64)
        val after = d.chipsCopy()
        assertEquals(64, after.size)
        // Ce sont bien les derniers bits qui restent.
        for (k in 0 until 64) assertEquals(before[before.size - 64 + k], after[k])
        // Rogner plus large que le tampon ne fait rien.
        d.trimTo(1000)
        assertEquals(64, d.chipsCopy().size)
    }

    @Test
    fun `la remise a zero vide tout`() {
        val d = SondeDemod(RATE, Rs41.BAUD, 256)
        val pcm = SondeTestFrames.modulate(ByteArray(80) { 1 }, RATE, Rs41.BAUD)
        d.feedBits(pcm, pcm.size, null)
        assertTrue(d.bitsAvailable > 0)
        d.reset()
        assertEquals(0, d.bitsAvailable)
        assertEquals(0, d.packBytes(0))
    }

    @Test
    fun `une trame RS41 modulee est retrouvee entiere`() {
        val lat = 48.5; val lon = -4.0; val alt = 22_500.0
        val frame = SondeTestFrames.rs41(lat, lon, alt,
            east = 15.0, north = -8.0, up = -7.0, sats = 8, serial = "P0912345")
        Rs41.descramble(frame)                       // telle qu'elle passe sur l'air
        val bits = SondeTestFrames.bitsOf(frame, lsbFirst = true)
        val pcm = SondeTestFrames.modulate(bits, RATE, Rs41.BAUD, leadingBits = 96)

        val d = SondeDemod(RATE, Rs41.BAUD, 4096)
        d.feedBits(pcm, pcm.size, null)

        // L'alignement des octets est inconnu : c'est exactement ce que fait le
        // décodage en direct, on essaie les huit décalages possibles.
        var hit: Rs41.Hit? = null
        for (off in 0 until 8) {
            val n = d.packBytes(off)
            hit = Rs41.scan(d.bytes, 0, n, 404_000_000L, 42L)
            if (hit != null) break
        }
        assertNotNull("aucune trame retrouvée après démodulation", hit)
        hit!!
        assertEquals("P0912345", hit.frame.serial)
        assertEquals(lat, hit.frame.lat, 1e-6)
        assertEquals(lon, hit.frame.lon, 1e-6)
        assertEquals(alt, hit.frame.altM, 0.05)
        assertEquals(17.0, hit.frame.speedMps, 0.05)     // hypot(15, -8)
        assertEquals(-7.0, hit.frame.climbMps, 0.05)
        assertEquals(8, hit.frame.sats)
        assertTrue(hit.frame.trusted)
    }

    @Test
    fun `une trame M20 modulee est retrouvee entiere`() {
        val lat = 48.44425; val lon = -4.41238; val alt = 9_000.0
        val frame = SondeTestFrames.m20(lat, lon, alt,
            east = 20.0, north = 21.0, up = 4.0, sats = 10, serial = 4242)
        val bits = SondeTestFrames.bitsOf(frame, lsbFirst = false)
        val pcm = SondeTestFrames.modulate(bits, RATE, Meteomodem.M20_BAUD, leadingBits = 96)

        val d = SondeDemod(RATE, Meteomodem.M20_BAUD, 1024)
        d.feedBits(pcm, pcm.size, null)
        val chips = d.chipsCopy()

        var hit: Rs41.Hit? = null
        for (off in 0 until 8) {
            val n = d.packBytesMsb(chips, chips.size, off)
            hit = Meteomodem.scan(d.bytes, 0, n, 404_000_000L, 7L)
            if (hit != null) break
        }
        assertNotNull("aucune M20 retrouvée après démodulation", hit)
        hit!!
        assertEquals("4242", hit.frame.serial)
        assertEquals(lat, hit.frame.lat, 1e-6)
        assertEquals(lon, hit.frame.lon, 1e-6)
        assertEquals(alt, hit.frame.altM, 0.01)
        assertEquals(29.0, hit.frame.speedMps, 0.02)     // hypot(20, 21)
    }

    /** La suite [needle] apparaît-elle dans [hay] ? */
    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > hay.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
