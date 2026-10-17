/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The software DVB-S2 receiver: its decoders alone, then through SatMe's modulator and a channel. */
class RecepteurS2Test {

    // ------------------------------------------------------------ BCH, LDPC

    @Test
    fun bch_corrige_jusqu_a_t_erreurs_et_refuse_au_dela() {
        val r = java.util.Random(4)
        for (courte in listOf(false, true)) for (t in listOf(Taux.R1_2, Taux.R2_3, Taux.R8_9)) {
            val m = Modcod(Constellation.QPSK, t, courte)
            val bch = Bch(m.kbch, m.kldpc, courte, m.t)
            val c = ByteArray(m.kldpc)
            for (k in 0 until m.kbch) c[k] = r.nextInt(2).toByte()
            bch.encode(c, c, m.kbch)
            val d = DecodeurBch.pour(m)
            assertEquals(0, d.corrige(c.copyOf()))
            for (n in listOf(1, 3, m.t)) {
                val e = c.copyOf()
                val pos = (0 until m.kldpc).shuffled(r).take(n)
                for (p in pos) e[p] = (e[p].toInt() xor 1).toByte()
                assertEquals("${m.libelle} $n erreurs", n, d.corrige(e))
                assertArrayEquals(c, e)
            }
            // t + 2 errors: never a silent wrong word passed as good.
            var refus = 0
            repeat(5) {
                val e = c.copyOf()
                for (p in (0 until m.kldpc).shuffled(r).take(m.t + 2)) e[p] = (e[p].toInt() xor 1).toByte()
                if (d.corrige(e) < 0) refus++ else assertTrue(!e.contentEquals(c))
            }
            assertTrue(refus >= 4)
        }
    }

    @Test
    fun ldpc_couche_corrige_le_bruit_et_s_arrete_tot() {
        val r = java.util.Random(5)
        val m = Modcod.de("QPSK 1/2")!!
        val ldpc = Ldpc(m.taux, false)
        val c = ByteArray(m.nldpc)
        for (k in 0 until m.kldpc) c[k] = r.nextInt(2).toByte()
        ldpc.encode(c, c, m.kldpc)
        val g = GrapheLdpc.de(m.taux, false)
        val dec = DecodeurLdpc()
        // Es/N0 1.5 dB, QPSK: soft values y = ±1 + n per bit, Es = 2, σ² = 1/(Es/N0) per dimension.
        val sigma = Math.sqrt(1 / Math.pow(10.0, 1.5 / 10))
        var fautes = 0
        val l = FloatArray(m.nldpc) { k -> ((1 - 2 * c[k]) + r.nextGaussian() * sigma).toFloat().also { if ((it < 0) != (c[k].toInt() == 1)) fautes++ } * (2 / (sigma * sigma)).toFloat() }
        assertTrue(fautes > 3000)
        val t0 = System.nanoTime()
        assertTrue(dec.decode(g, l, 50))
        val ms = (System.nanoTime() - t0) / 1e6
        for (k in 0 until m.nldpc) assertEquals((l[k] < 0), c[k].toInt() == 1)
        println("LDPC QPSK 1/2 normale, Es/N0 1,5 dB : $fautes bits faux corrigés en ${dec.iterations} itérations, ${"%.1f".format(ms)} ms")
    }

    // ------------------------------------------------------------ end to end, through the channel

    private class Resultat(val bons: Int, val faux: Int, val envoyes: Int, val e: EtatS2, val verrouS: Double, val tempsReel: Double)

    /** [secondes] of [c]'s signal through a receiver, fed by blocks as an SDR would. */
    private fun passe(c: CanalS2, secondes: Double): Resultat {
        val x = c.signal(secondes)
        val recu = Recu(c)
        val rx = RecepteurS2(c.fs, c.rs, recu.sortie, 3)
        var k = 0
        val b = FloatArray(2 * 16_384)
        var verrou = -1.0
        var ns = 0L
        while (k < x.size) {
            val n = minOf(b.size, x.size - k)
            System.arraycopy(x, k, b, 0, n)
            val t0 = System.nanoTime()
            rx.echantillons(b, n)
            ns += System.nanoTime() - t0
            k += n
            if (verrou < 0 && rx.etat().tramesDonnees > 0) verrou = k / 2 / c.fs
        }
        rx.vide()
        val e = rx.etat()
        rx.ferme()
        val (bons, faux, _) = recu.bilan()
        val r = Resultat(bons, faux, c.envoyes.size, e, verrou, ns / 1e9 / secondes)
        println("DVB-S2 reçu : $bons/${c.envoyes.size} paquets intacts, $faux faux, verrou à ${"%.3f".format(verrou)} s, " +
            "MER ${e.merDb?.let { "%.1f".format(it) }} dB, porteuse ${"%.0f".format(e.decalageHz)} Hz, trames ${e.tramesDonnees} (échecs ${e.ldpcEchecs}), " +
            "frontal ${"%.2f".format(r.tempsReel)} × le temps réel")
        return r
    }

    /** Every frame after the lock decoded (one failure allowed), no wrong packet, nearly all the stream after the first frames. */
    private fun attendBon(r: Resultat, parTrame: Int, avant: Int = 2) {
        assertEquals(0, r.faux)
        assertTrue("échecs LDPC ${r.e.ldpcEchecs}", r.e.ldpcEchecs <= 1)
        assertTrue("verrou ${r.verrouS}", r.verrouS > 0)
        assertTrue("${r.bons} / ${r.envoyes}", r.bons >= r.envoyes - (avant + 4) * parTrame)
    }

    @Test
    fun qpsk_1_2_pilotes_bruit_et_porteuse_decalee() {
        val m = Modcod.de("QPSK 1/2", pilotes = true)!!
        val r = passe(CanalS2(m, rs = 1e6, fs = 2.5e6, esN0Db = 2.0, decalageHz = 50e3), 0.6)
        attendBon(r, 21)
        assertEquals(50e3, r.e.decalageHz, 200.0)
        assertEquals(4, r.e.modcod)
        assertEquals(true, r.e.pilotes)
    }

    @Test
    fun debit_125_ks_porteuse_a_100_khz() {
        val r = passe(CanalS2(Modcod.de("QPSK 1/2", pilotes = true)!!, rs = 125e3, fs = 500e3, esN0Db = 4.0, decalageHz = -100e3), 2.2)
        attendBon(r, 21)
        assertEquals(-100e3, r.e.decalageHz, 100.0)
    }

    @Test
    fun horloge_a_50_ppm_retard_fractionnaire_bruit_de_phase() {
        val r = passe(CanalS2(Modcod.de("QPSK 2/3", pilotes = true)!!, rs = 1e6, fs = 2.5e6, esN0Db = 5.0, ppm = 50.0, retard = 0.37,
            bruitPhaseRad = 0.01, decalageHz = -20e3), 0.45)
        attendBon(r, 28)
    }

    @Test
    fun porteuse_qui_derive_de_1_khz_par_seconde() {
        val r = passe(CanalS2(Modcod.de("QPSK 1/2", pilotes = true)!!, rs = 333e3, fs = 1e6, esN0Db = 5.0, decalageHz = 20e3, deriveHzS = 1000.0), 1.5)
        attendBon(r, 21)
    }

    @Test
    fun sans_pilotes_qpsk_1_3_a_0_db() {
        val r = passe(CanalS2(Modcod.de("QPSK 1/3")!!, rs = 1e6, fs = 2.5e6, esN0Db = 0.0, decalageHz = 3e3), 0.75)
        attendBon(r, 14)
    }

    @Test
    fun qpsk_1_4_pilotes_a_moins_1_5_db() {
        val r = passe(CanalS2(Modcod.de("QPSK 1/4", pilotes = true)!!, rs = 1e6, fs = 2.5e6, esN0Db = -1.5, decalageHz = 3e3), 1.0)
        // Acquisition takes a few frames this low: the lock, then every frame.
        attendBon(r, 10, avant = 10)
    }

    @Test
    fun psk8_2_3_et_trames_courtes_avec_trames_vides() {
        attendBon(passe(CanalS2(Modcod.de("8PSK 2/3", pilotes = true)!!, rs = 1e6, fs = 2.5e6, esN0Db = 9.0, decalageHz = 10e3), 0.4), 28)
        val r = passe(CanalS2(Modcod.de("QPSK 3/4", courte = true)!!, rs = 250e3, fs = 1e6, esN0Db = 6.0, decalageHz = -30e3, remplissage = 0.5), 1.0)
        assertTrue(r.e.tramesVides > 0)
        attendBon(r, 7, avant = 4)
    }

    @Test
    fun le_bruit_seul_ne_donne_rien() {
        // A channel at −30 dB Es/N0: nothing to lock on; no packet may come out.
        val r = passe(CanalS2(Modcod.de("QPSK 1/2")!!, rs = 1e6, fs = 2.5e6, esN0Db = -30.0), 0.6)
        assertEquals(0, r.bons + r.faux)
        assertEquals(0L, r.e.paquets)
    }
}
