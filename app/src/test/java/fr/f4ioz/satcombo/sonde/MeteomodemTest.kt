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
 * Le décodage des Meteomodem M10 et M20, celles de Météo-France.
 *
 * La M20 n'a pas de contrôle de redondance reproductible : c'est la
 * vraisemblance physique qui lui sert de garde-fou. La M10, elle, en a un — la
 * 18.7 l'a retrouvé — et il est exigé. Ces essais vérifient d'abord que le
 * garde-fou tient, ensuite seulement que les chiffres sont les bons.
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
        // Paires égales : des zéros. Paires différentes : des uns.
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
        // C'est la propriété qui permet de ne chercher la polarité qu'au moment
        // de la synchronisation : l'égalité de deux demi-bits survit à
        // l'inversion, donc les bits décodés aussi. L'ancien décodage dit
        // « Manchester » n'avait pas cette propriété, et choisissait sa phase
        // sur un comptage de paires plates — c'est-à-dire sur des données
        // parfaitement légitimes.
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
        assertEquals(323.13, d.headingDeg, 0.1)       // ouest-nord-ouest
    }

    @Test
    fun `une M20 trop haute est refusee`() {
        val f = SondeTestFrames.m20(LAT, LON, 60_000.0, 0.0, 0.0, 3.0)
        assertNull(Meteomodem.parseM20(f))
    }

    @Test
    fun `une M20 trop rapide est refusee`() {
        // Deux cent cinquante mètres par seconde au sol : aucun courant-jet.
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
        // Notre décodage de la M10 ne lit pas le nombre de satellites, et l'on
        // en tirait autrefois la conclusion que le point n'était jamais sûr.
        // C'était une faute : le chasseur perdait sur ce modèle le dernier point
        // fiable, l'éclatement et le point de chute, c'est-à-dire tout ce qui
        // sert sur le terrain. Ce qui garantit le point, ici, c'est l'horloge :
        // une trame qui porte une semaine et une heure GPS valables vient d'un
        // récepteur qui a fait le point.
        assertEquals(0, d.sats)
        assertTrue("le nombre de satellites doit être signalé comme inconnu", d.satsUnknown)
        assertTrue("une M10 horodatée doit être un point sûr", d.trusted)
    }

    @Test
    fun `une M10 sans horloge GPS ne passe pas pour un point sur`() {
        // Le revers de la médaille : si l'horloge ne dit rien, plus rien ne
        // garantit que le récepteur de la sonde avait fait le point, et la
        // trame ne doit pas servir de dernière position connue.
        val f = SondeTestFrames.m10(LAT, LON, ALT, east = 3.0, north = 4.0, up = 5.0,
            count = 778, week = 0, itowMs = 0L)
        val d = Meteomodem.parseM10(f, 401_000_000L, 55L)!!
        assertTrue("sans horloge, le point ne peut pas être sûr", !d.trusted)
    }

    @Test
    fun `les champs M10 ne se chevauchent pas`() {
        // Les décalages de la 18.6 étaient faux d'un bout à l'autre : le
        // compteur mordait sur la longitude, le numéro de série n'était qu'un
        // morceau de coordonnée. Ceux-ci viennent d'un vrai enregistrement, et
        // l'essai vérifie au moins qu'ils ne se marchent pas dessus.
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
        // Le vrai garde-fou de la M10, depuis la 18.7 : un octet changé au
        // milieu de la trame et tout est jeté, même si les coordonnées restent
        // parfaitement plausibles.
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
