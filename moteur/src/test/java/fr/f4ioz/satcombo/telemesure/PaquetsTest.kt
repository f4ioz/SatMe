/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9600 bit/s packets: scrambler, NRZI, HDLC, then the whole chain from made-up baseband. */
class PaquetsTest {

    private fun lit(bits: IntArray, embrouille: Boolean): List<ByteArray> {
        val l = ArrayList<ByteArray>()
        val d = DeframeurHdlc { l += it }
        val e = G3ruh.Desembrouilleur(); val z = G3ruh.Nrzi()
        for (b in bits) d.bit(z.bit(if (embrouille) e.bit(b) else b))
        return l
    }

    @Test fun desembrouilleurInverseEtAutoSynchronise() {
        val r = java.util.Random(4)
        val x = IntArray(5000) { r.nextInt(2) }
        val e = G3ruh.Embrouilleur()
        val y = IntArray(x.size) { e.bit(x[it]) }
        // The receiver starts in another state: right after 17 bits.
        val d = G3ruh.Desembrouilleur()
        repeat(5) { d.bit(1) }
        val z = IntArray(y.size) { d.bit(y[it]) }
        for (k in 17 until x.size) assertEquals("bit $k", x[k], z[k])
        // An inverted line: inverted bits (NRZI then cancels it).
        val d2 = G3ruh.Desembrouilleur()
        val zi = IntArray(y.size) { d2.bit(1 - y[it]) }
        for (k in 17 until x.size) assertEquals(1 - x[k], zi[k])
    }

    @Test fun hdlcAvecBourrageEtInversion() {
        val t1 = Ax25.encode(Trame(Adresse("CQ"), Adresse("Q0SAT", 1), emptyList(), ByteArray(120) { 0xFF.toByte() }))
        val t2 = Ax25.encode(Trame(Adresse("CQ"), Adresse("Q0SAT", 1), emptyList(), "texte 7E ~~~~ fin".toByteArray()))
        for (emb in listOf(true, false)) {
            val bits = G3ruh.bits(listOf(t1, t2), embrouille = emb)
            assertEquals(2, lit(bits, emb).size)
            assertArrayEquals(t1, lit(bits, emb)[0])
            // FM's polarity unknown: the inverted line reads the same.
            val inv = IntArray(bits.size) { 1 - bits[it] }
            assertArrayEquals(t2, lit(inv, emb)[1])
        }
        // A wrong bit: the CRC drops the frame.
        val bits = G3ruh.bits(listOf(t2), embrouille = false)
        bits[300] = 1 - bits[300]
        assertTrue(lit(bits, false).isEmpty())
    }

    @Test fun enteteAx25Libre() {
        val t = Ax25.encode(Trame(Adresse("CQ"), Adresse("Q0SAT", 1), emptyList(), "abc".toByteArray()))
        val e = Ax25.entete(t.copyOf(t.size - 2))!!
        assertEquals("Q0SAT-1>CQ", e.ligne())
        assertTrue(e.ui); assertEquals(0xF0, e.pid)
        assertEquals("abc", String(e.info))
        assertNull(Ax25.entete(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
    }

    private fun recoit(bits: IntArray, debit: Int, decalageHz: Double, snrDb: Double, deviationHz: Double = SignalPaquets.DEVIATION_HZ, graine: Long = 1): RecepteurPaquets {
        val fs = RecepteurPaquets.FS_CIBLE
        val (re, im) = SignalPaquets.baseband(bits, fs, debit, decalageHz, snrDb, graine, deviationHz = deviationHz)
        val r = RecepteurPaquets(null)
        r.debit = debit
        r.contexte = { RecepteurPaquets.Contexte("Q0SAT", 99999, 435_000_000L, 435_002_000L, essai = true) }
        var k = 0
        while (k < re.size) {
            val n = minOf(4096, re.size - k)
            r.traiteBaseband(re.copyOfRange(k, k + n), im.copyOfRange(k, k + n), n, fs)
            k += n
        }
        return r
    }

    private fun trames(n: Int) = (1..n).map { SignalPaquets.trameTelemesure(it, "trame d'essai numero") }

    @Test fun g3ruh9600DecaleEtBruite() {
        val t = trames(6)
        val bits = G3ruh.bits(t)
        val r = recoit(bits, 9600, 2_500.0, 14.0)
        val l = r.trames.reversed()
        assertEquals(r.etat.toString(), 6, l.size)
        for (i in t.indices) assertArrayEquals(t[i].copyOf(t[i].size - 2), l[i].octets)
        assertTrue(l.all { it.g3ruh })
        assertEquals("Q0SAT-1", l[0].ax25!!.source.toString())
        // The AFC found the offset; the frequency heard has it, the corrected one has the Doppler off.
        assertTrue("${r.etat.afcHz}", kotlin.math.abs(r.etat.afcHz - 2_500) < 600)
        assertTrue(kotlin.math.abs(l.last().frequenceHz - 435_002_500L) < 600)
        assertTrue(kotlin.math.abs(l.last().frequenceCorrigeeHz - 435_004_500L) < 600)
    }

    @Test fun gmsk4800Et1200SansEmbrouilleur() {
        val t = trames(3)
        // GMSK: deviation a quarter of the bit rate.
        val r48 = recoit(G3ruh.bits(t), 4800, -1_200.0, 16.0, deviationHz = 1_200.0, graine = 5)
        assertEquals(r48.etat.toString(), 3, r48.trames.size)
        val r12 = recoit(G3ruh.bits(t, embrouille = false), 1200, 400.0, 16.0, deviationHz = 600.0, graine = 6)
        assertEquals(r12.etat.toString(), 3, r12.trames.size)
        assertTrue(r12.trames.none { it.g3ruh })
    }

    @Test fun ssdvDansLesTramesJusquALImage() {
        val p = SignalPaquets.paquetsMire(3).take(4)
        val bits = G3ruh.bits(p.map { SignalPaquets.trameSsdv(it) })
        val r = recoit(bits, 9600, 800.0, 16.0, graine = 9)
        assertEquals(4, r.trames.size)
        assertTrue(r.trames.all { t -> t.ssdv.map { it.first } == listOf("Q0SAT-3") })
        assertEquals(listOf(0, 1, 2, 3), r.trames.map { it.ssdv[0].second }.sorted())
        assertNotNull(r.jpeg("Q0SAT-3"))
    }

    @Test fun generateurIqDeBoutEnBout() {
        val rien = fr.f4ioz.satcombo.sdr.GenerateurIq.Signal(false, 0, 0.0)
        val r0 = fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages(porteuse = rien, cw = rien, blu = rien, fm = rien, ft8 = rien, sstv = rien,
            paquets = fr.f4ioz.satcombo.sdr.GenerateurIq.Signal(true, 60_000, 25.0))
        val g = fr.f4ioz.satcombo.sdr.GenerateurIq(r0, cadence = false)
        val r = RecepteurPaquets(null)
        r.prepare(fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble())
        r.contexte = { RecepteurPaquets.Contexte(essai = true) }
        val b = ShortArray(2 * 32_768)
        // Two bursts and a half: about 6 s.
        repeat((6.0 * fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE / 32_768).toInt()) {
            val n = g.read16(b, 0)
            r.traiteBloc(b.copyOf(n), 60_000.0 + 1_500.0)
        }
        g.close()
        // The receiver tuned 1.5 kHz off: the AFC takes it.
        assertTrue(r.etat.toString(), r.trames.size >= 14)
        assertTrue(r.trames.any { it.ax25?.source?.toString() == "Q0SAT-1" })
        val img = r.imagesJson().getJSONObject(0)
        assertEquals("Q0SAT-0", img.getString("cle"))
        assertTrue(img.getInt("recus") >= 12)
        assertTrue(img.getBoolean("essai"))
    }

    @Test fun debitSelonEmetteur() {
        assertEquals(9600, RecepteurPaquets.debitPour("GMSK", 9600.0))
        assertEquals(4800, RecepteurPaquets.debitPour("FSK AX.100 Mode 6", 4800.0))
        assertEquals(1200, RecepteurPaquets.debitPour("GFSK", 1200.0))
        assertNull(RecepteurPaquets.debitPour("AFSK", 1200.0))
        assertNull(RecepteurPaquets.debitPour("BPSK", 1200.0))
        assertNull(RecepteurPaquets.debitPour("GMSK", 115_200.0))
    }
}
