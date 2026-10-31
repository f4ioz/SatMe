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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Packets from a rig's 9600 output on a sound card (a TS-2000's ACC2 with
 * its external TNC speed at 9600 bps): made-up baseband, its noise, the
 * discriminator upside down, a DC offset, the card's clock off.
 */
class BandeBaseTest {

    private fun recoit(son: ShortArray, rate: Int, debit: Int, bloc: Int = 1920): RecepteurPaquets {
        val r = RecepteurPaquets(null)
        r.debit = debit
        r.contexte = { RecepteurPaquets.Contexte("Q0SAT", 99999, 435_000_000L, 435_002_000L, essai = true) }
        var k = 0
        while (k < son.size) {
            val n = minOf(bloc, son.size - k)
            r.traiteSon(son.copyOfRange(k, k + n), n, rate)
            k += n
        }
        return r
    }

    private fun trames(n: Int) = (1..n).map { SignalPaquets.trameTelemesure(it, "trame d'essai par la carte son") }

    @Test fun g3ruh9600A48kHzInverseDecaleBruite() {
        val t = trames(8)
        val son = SignalPaquets.bandeBase(G3ruh.bits(t), 48_000, 9_600, 12.0, graine = 3, inverse = true, dc = 0.08, horlogePpm = 300.0)
        val r = recoit(son, 48_000, 9_600)
        val l = r.trames.reversed()
        assertEquals(r.etat.toString(), 8, l.size)
        for (i in t.indices) assertArrayEquals(t[i].copyOf(t[i].size - 2), l[i].octets)
        assertTrue(l.all { it.g3ruh })
        assertEquals(RecepteurPaquets.SOURCE_SON, r.etat.source)
        assertEquals(48_000, r.etat.rate)
        // No AFC on this path: the frequency is the CAT's.
        assertEquals(0.0, r.etat.afcHz, 0.0)
        assertEquals(435_000_000L, l[0].frequenceHz)
    }

    @Test fun lesTroisCadencesDeCarte() {
        for ((rate, graine) in listOf(44_100 to 11L, 48_000 to 12L, 96_000 to 13L)) {
            val t = trames(4)
            val son = SignalPaquets.bandeBase(G3ruh.bits(t), rate, 9_600, 14.0, graine = graine, horlogePpm = -150.0)
            val r = recoit(son, rate, 9_600)
            assertEquals("$rate Hz : ${r.etat}", 4, r.trames.size)
        }
    }

    @Test fun ssdvJusquALImageComplete() {
        val p = SignalPaquets.paquetsMire(5)
        val bits = G3ruh.bits(p.map { SignalPaquets.trameSsdv(it) })
        val son = SignalPaquets.bandeBase(bits, 48_000, 9_600, 13.0, graine = 21, inverse = true, dc = -0.05, horlogePpm = 200.0)
        val r = recoit(son, 48_000, 9_600)
        assertEquals(r.etat.toString(), p.size, r.trames.size)
        val ph = r.assemblage.photos().single()
        assertTrue(ph.essai)
        assertEquals(p.size, ph.recus)
        assertTrue("manquants ${ph.manquants}", ph.complete)
        assertNotNull(r.jpeg(ph.id))
    }

    @Test fun gmsk4800Et2400() {
        val t = trames(3)
        val r48 = recoit(SignalPaquets.bandeBase(G3ruh.bits(t), 48_000, 4_800, 13.0, graine = 31, inverse = true, dc = 0.05, horlogePpm = 250.0), 48_000, 4_800)
        assertEquals(r48.etat.toString(), 3, r48.trames.size)
        // 2400 bit/s without scrambler (NRZI alone): flags hold one level, the envelope's middle stays right.
        val r24 = recoit(SignalPaquets.bandeBase(G3ruh.bits(t, embrouille = false), 48_000, 2_400, 12.0, graine = 32, passeHautHz = 0.0, dc = 0.1), 48_000, 2_400)
        assertEquals(r24.etat.toString(), 3, r24.trames.size)
        assertTrue(r24.trames.none { it.g3ruh })
    }

    @Test fun geoscanSurLaLigneInversee() {
        val jpeg = Geoscan.jpegEssai()
        val m = Geoscan.morceaux(0x0C, 77, jpeg)
        for (inverse in listOf(false, true)) {
            val son = SignalPaquets.bandeBase(Geoscan.bits(m), 48_000, 9_600, 16.0, graine = 41, inverse = inverse)
            val r = recoit(son, 48_000, 9_600)
            val geo = r.trames.filter { it.format == RecepteurPaquets.FORMAT_GEOSCAN }
            assertEquals("inverse=$inverse ${r.etat}", m.size, geo.size)
            assertNotNull(r.geoscanEssai.image(geo[0].geoscan!!)?.jpeg())
        }
    }

    @Test fun bruitSeulRienNePasse() {
        val son = SignalPaquets.bandeBase(IntArray(0), 48_000, 9_600, 0.0, graine = 51, silenceS = 5.0)
        val r = recoit(son, 48_000, 9_600)
        assertTrue(r.trames.isEmpty())
        assertTrue(r.etat.bits > 40_000)
    }

    /** The decoding against the SNR, printed for the record (not a pass/fail). */
    @Test fun performanceSelonLeRapportSignalSurBruit() {
        val t = trames(40)
        val bits = G3ruh.bits(t)
        val l = ArrayList<String>()
        for (snr in listOf(6.0, 8.0, 10.0, 12.0, 14.0)) {
            val son = SignalPaquets.bandeBase(bits, 48_000, 9_600, snr, graine = 60 + snr.toLong(), inverse = true, dc = 0.05, horlogePpm = 200.0)
            l += "Es/N0 ${snr.toInt()} dB : ${recoit(son, 48_000, 9_600).trames.size}/40"
        }
        println("Bande de base 9600, 48 kHz : " + l.joinToString(" · "))
        assertTrue(l.last(), l.last().endsWith(" 40/40"))
    }
}
