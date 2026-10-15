/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import fr.f4ioz.satcombo.sdr.Fft
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/** The DVB-S2 modulator, piece by piece against the standard, then end to end through a receiver. */
class Dvbs2Test {

    // ------------------------------------------------------------ CRC-8, scramblers

    @Test
    fun crc8_est_le_reste_de_x8_u_x_par_g() {
        val r = java.util.Random(1)
        repeat(50) {
            val b = ByteArray(1 + r.nextInt(200)).also { r.nextBytes(it) }
            // Independent: X⁸u(X) mod g(X) by long division, bit by bit.
            val bits = IntArray(b.size * 8 + 8)
            for (k in b.indices) for (j in 0 until 8) bits[8 * k + j] = (b[k].toInt() shr (7 - j)) and 1
            val g = intArrayOf(1, 1, 1, 0, 1, 0, 1, 0, 1) // X⁸ X⁷ X⁶ X⁵ X⁴ X³ X² X¹ X⁰
            for (k in 0 until b.size * 8) if (bits[k] == 1) for (j in 0..8) bits[k + j] = bits[k + j] xor g[j]
            var attendu = 0
            for (j in 0 until 8) attendu = (attendu shl 1) or bits[b.size * 8 + j]
            assertEquals(attendu, Crc8.octets(b))
        }
        assertEquals(0, Crc8.octets(ByteArray(0)))
    }

    @Test
    fun brouillage_bb_commence_par_00000011_et_suit_1_x14_x15() {
        val s = BrouilleurBb.suite(70_000)
        // Figure 5: the first output bits.
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 1), s.copyOf(8))
        // The register holds the past outputs: o(t) = o(t−14) ⊕ o(t−15).
        for (t in 15 until s.size) assertEquals(s[t - 14].toInt() xor s[t - 15].toInt(), s[t].toInt())
        // Maximal length: period 2¹⁵ − 1.
        for (t in 0 until 1000) assertEquals(s[t], s[t + 32767])
        assertFalse((1 until 32767).any { p -> (0 until 40).all { s[it] == s[it + p] } })
    }

    @Test
    fun brouillage_pl_code_0_depuis_les_polynomes() {
        val r = CouchePhysique.sequencePl(0)
        // Independent: the two registers of figure 15, bit 0 = x(i).
        var x = 1; var y = (1 shl 18) - 1
        val p = (1 shl 18) - 1
        val z = IntArray(p)
        for (i in 0 until p) {
            z[i] = (x xor y) and 1
            val nx = ((x shr 7) xor x) and 1
            val ny = ((y shr 10) xor (y shr 7) xor (y shr 5) xor y) and 1
            x = (x shr 1) or (nx shl 17); y = (y shr 1) or (ny shl 17)
        }
        // Both back to their start: maximal length.
        assertEquals(1, x); assertEquals((1 shl 18) - 1, y)
        for (i in 0 until 66_420) assertEquals(2 * z[(i + 131_072) % p] + z[i], r[i].toInt())
        // A real scrambling: the four values evenly used.
        val n = IntArray(4).also { c -> r.forEach { c[it.toInt()]++ } }
        n.forEach { assertTrue(it in 15_500..17_700) }
    }

    // ------------------------------------------------------------ BCH, LDPC

    @Test
    fun bch_les_mots_sont_divisibles_par_chaque_polynome() {
        val r = java.util.Random(2)
        for (courte in listOf(false, true)) for (t in Taux.entries) {
            val m = Modcod(Constellation.QPSK, t, courte)
            if (m.refus() != null) continue
            val bch = Bch(m.kbch, m.kldpc, courte, m.t)
            assertEquals(m.kldpc - m.kbch, (if (courte) 14 else 16) * m.t)
            val c = ByteArray(m.kldpc)
            for (k in 0 until m.kbch) c[k] = r.nextInt(2).toByte()
            bch.encode(c, c, m.kbch)
            val polys = if (courte) Bch.POLYS_COURTES else Bch.POLYS_NORMALES
            for (k in 0 until m.t) assertTrue("${m.libelle} g${k + 1}", BchVerif.divisible(c, m.kldpc, polys[k]))
            // One bit wrong: not a codeword.
            c[5] = (c[5].toInt() xor 1).toByte()
            assertFalse((0 until m.t).all { BchVerif.divisible(c, m.kldpc, polys[it]) })
        }
    }

    @Test
    fun ldpc_h_fois_c_nul_pour_les_21_codes() {
        val r = java.util.Random(3)
        for (courte in listOf(false, true)) for (t in Taux.entries) {
            val m = Modcod(Constellation.QPSK, t, courte)
            if (m.refus() != null) continue
            val ldpc = Ldpc(t, courte)
            val c = ByteArray(m.nldpc)
            for (k in 0 until m.kldpc) c[k] = r.nextInt(2).toByte()
            ldpc.encode(c, c, m.kldpc)
            assertTrue(m.libelle, syndromeNul(m, c))
            c[7] = (c[7].toInt() xor 1).toByte()
            assertFalse(syndromeNul(m, c))
        }
    }

    /** Independent parity check from the table: each check j sums its info bits, p_j and p_{j−1}. */
    private fun syndromeNul(m: Modcod, c: ByteArray): Boolean {
        val t = TablesLdpc.lignes(m.taux, m.courte)!!
        val r = m.nldpc - m.kldpc
        val q = m.q
        val s = IntArray(r)
        for (g in t.indices) for (j in 0 until 360) {
            val bit = c[g * 360 + j].toInt()
            if (bit == 1) for (x in t[g]) { val a = (x + j * q) % r; s[a] = s[a] xor 1 }
        }
        for (j in 0 until r) {
            s[j] = s[j] xor c[m.kldpc + j].toInt()
            if (j > 0) s[j] = s[j] xor c[m.kldpc + j - 1].toInt()
        }
        return s.all { it == 0 }
    }

    @Test
    fun tables_ldpc_conformes_aux_exemples_de_la_norme() {
        // Clause 5.3.2.1's example (rate 2/3) and annex B's (rate 1/2).
        assertArrayEquals(intArrayOf(0, 10491, 16043, 506, 12826, 8065, 8226, 2767, 240, 18673, 9279, 10579, 20928), TablesLdpc.lignes(Taux.R2_3, false)!![0])
        assertArrayEquals(intArrayOf(54, 9318, 14392, 27561, 26909, 10219, 2534, 8597), TablesLdpc.lignes(Taux.R1_2, false)!![0])
        assertArrayEquals(intArrayOf(55, 7263, 4635, 2530, 28130, 3033, 23830, 3651), TablesLdpc.lignes(Taux.R1_2, false)!![1])
        // Every address below n − k; each check of the normal codes of the same degree.
        for (courte in listOf(false, true)) for (t in Taux.entries) {
            val m = Modcod(Constellation.QPSK, t, courte)
            if (m.refus() != null) continue
            val l = TablesLdpc.lignes(t, courte)!!
            val r = m.nldpc - m.kldpc
            assertTrue(l.all { row -> row.all { it in 0 until r } })
            if (!courte) {
                val deg = IntArray(r)
                for (row in l) for (x in row) for (j in 0 until 360) deg[(x + j * m.q) % r]++
                assertEquals(m.libelle, 1, deg.distinct().size)
            }
        }
    }

    // ------------------------------------------------------------ PLHEADER, frames

    @Test
    fun entete_pl_sof_et_code_pls() {
        // The 128 PLS codewords: distinct, at least 32 apart (a first-order Reed-Muller code).
        val c = (0 until 128).map { CouchePhysique.pls(it shr 2, it and 3) }
        var dmin = 64
        for (a in 0 until 128) for (b in a + 1 until 128) dmin = minOf(dmin, (0 until 64).count { c[a][it] != c[b][it] })
        assertEquals(32, dmin)
        // MODCOD 4 (QPSK 1/2), TYPE 00: the third row of G, each bit twice.
        val g3 = "00001111000011110000111100001111"
        assertArrayEquals(IntArray(64) { g3[it / 2] - '0' }, CouchePhysique.pls(4, 0))
        // Pilots: every second bit complemented.
        assertArrayEquals(IntArray(64) { (g3[it / 2] - '0') xor (it % 2) }, CouchePhysique.pls(4, 1))
        // SOF 18D2E82: 01 1000 1101 0010 1110 1000 0010.
        val e = CouchePhysique.enteteBits(4, 0)
        assertEquals("01100011010010111010000010", e.take(26).joinToString(""))
        // Dummy frame: the PLS bits are the scrambling sequence itself.
        assertEquals(CouchePhysique.BROUILLAGE_PLS, CouchePhysique.enteteBits(0, 0).drop(26).joinToString(""))
        // π/2-BPSK: odd symbols at 45° (bit 0), even ones at 135°.
        val s = FloatArray(180)
        CouchePhysique.entete(4, 0, s, 0)
        assertTrue(s[0] > 0 && s[1] > 0)           // y1 = 0
        assertTrue(s[2] > 0 && s[3] < 0)           // y2 = 1: I = −(1/√2)(1−2y) > 0, Q = −I
        for (k in 0 until 90) assertEquals(1.0, (s[2 * k] * s[2 * k] + s[2 * k + 1] * s[2 * k + 1]).toDouble(), 1e-6)
    }

    @Test
    fun longueurs_de_trame_et_debits_de_la_norme() {
        assertEquals(32_490, Modcod(Constellation.QPSK, Taux.R1_2).symboles)
        assertEquals(33_282, Modcod(Constellation.QPSK, Taux.R1_2, pilotes = true).symboles)
        assertEquals(8_190, Modcod(Constellation.QPSK, Taux.R1_2, courte = true).symboles)
        assertEquals(8_370, Modcod(Constellation.QPSK, Taux.R1_2, courte = true, pilotes = true).symboles)
        assertEquals(21_690, Modcod(Constellation.PSK8, Taux.R2_3).symboles)
        assertEquals(22_194, Modcod(Constellation.PSK8, Taux.R2_3, pilotes = true).symboles)
        assertEquals(3_330, CouchePhysique.SYMBOLES_VIDE)
        // Table 13's spectral efficiencies (normal frames, no pilots) = the TS bits per symbol here.
        val eta = mapOf("QPSK 1/4" to 0.490243, "QPSK 1/3" to 0.656448, "QPSK 2/5" to 0.789412, "QPSK 1/2" to 0.988858,
            "QPSK 3/5" to 1.188304, "QPSK 2/3" to 1.322253, "QPSK 3/4" to 1.487473, "QPSK 4/5" to 1.587196, "QPSK 5/6" to 1.654663,
            "QPSK 8/9" to 1.766451, "QPSK 9/10" to 1.788612, "8PSK 3/5" to 1.779991, "8PSK 2/3" to 1.980636, "8PSK 3/4" to 2.228124,
            "8PSK 5/6" to 2.478562, "8PSK 8/9" to 2.646012, "8PSK 9/10" to 2.679207)
        for ((nom, e) in eta) assertEquals(nom, e, Modcod.de(nom)!!.debitTs(1.0), 1e-6)
        // Table 12's codes.
        assertEquals((1..17).toList(), Modcod.tous(false, false).map { it.code })
        assertEquals(null, Modcod.de("8PSK 4/5"))
        assertEquals(null, Modcod.de("QPSK 9/10", courte = true))
        // 333 kS/s QPSK 2/3 normal: 440 kbit/s.
        assertEquals(440_310.0, Modcod.de("QPSK 2/3")!!.debitTs(333_000.0), 1.0)
    }

    // ------------------------------------------------------------ end to end

    /** Packets of a counted PID with random payloads, and null packets now and then. */
    private class Paquets(seed: Long, val total: Int, val parSalve: Int = Int.MAX_VALUE) : SourceTs {
        val r = java.util.Random(seed)
        val envoyes = ArrayList<ByteArray>()
        var prets = 0
        private var appels = 0
        override fun disponibles(): Int {
            // A burst of [parSalve] packets every third frame asked: gaps in between.
            if (parSalve == Int.MAX_VALUE) prets = total - envoyes.size
            else if (appels++ % 3 == 0) prets += minOf(parSalve, total - envoyes.size - prets)
            return prets
        }
        override fun lis(dst: ByteArray) {
            val nul = envoyes.size % 7 == 3
            dst[0] = 0x47
            if (nul) { dst[1] = 0x1F; dst[2] = 0xFF.toByte(); dst[3] = 0x10; for (k in 4 until 188) dst[k] = 0xFF.toByte() }
            else { dst[1] = 0x01; dst[2] = 0x00; dst[3] = (0x10 or (envoyes.size and 15)).toByte(); for (k in 4 until 188) dst[k] = r.nextInt().toByte() }
            envoyes += dst.copyOf()
            prets--
        }
    }

    private fun boucle(m: Modcod, ro: Rolloff, sps: Int, paquets: Int, parSalve: Int = Int.MAX_VALUE, esN0Db: Double? = null): Pair<Paquets, RecepteurDvbs2.Bilan> {
        val src = Paquets(42, paquets, parSalve)
        val mod = ModulateurDvbs2(m, ro, sps, src)
        // Enough samples for every packet, plus a few dummy frames, plus the filters' tails.
        val trames = ((paquets * 1504L + m.dfl - 1) / m.dfl + 3) * (if (parSalve == Int.MAX_VALUE) 1 else 4)
        val paires = ((trames * m.symboles + 40) * sps).toInt()
        val iq = ShortArray(2 * paires)
        mod.remplis(iq, paires)
        val rx = RecepteurDvbs2(ro, sps)
        val x = FloatArray(iq.size) { iq[it].toFloat() }
        if (esN0Db != null) {
            val g = java.util.Random(9)
            val echelle = ModulateurDvbs2.PLEIN * Math.pow(10.0, -ModulateurDvbs2.RECUL_DB / 20)
            // Es/N0 = P·sps/σ² (complex noise power σ² per sample).
            val sigma = echelle * sqrt(sps / (2 * Math.pow(10.0, esN0Db / 10)))
            for (k in x.indices) x[k] += (g.nextGaussian() * sigma).toFloat()
        }
        return src to rx.decode(rx.symboles(x))
    }

    private fun verifieOctetPourOctet(src: Paquets, b: RecepteurDvbs2.Bilan) {
        assertTrue("paquets reçus ${b.paquets.size} / ${src.envoyes.size}", b.paquets.size >= src.envoyes.size - 1)
        for (k in 0 until b.paquets.size) assertArrayEquals("paquet $k", src.envoyes[k], b.paquets[k])
        assertEquals(0, b.crcPaquetsFaux)
    }

    @Test
    fun bout_en_bout_qpsk_1_2_normale() {
        val (src, b) = boucle(Modcod.de("QPSK 1/2")!!, Rolloff.A35, 3, 60)
        verifieOctetPourOctet(src, b)
        assertEquals(setOf(4), b.modcods)
        assertEquals(0, b.bitsCorriges)
    }

    @Test
    fun bout_en_bout_qpsk_3_4_courtes_pilotes_avec_trames_vides() {
        // The stream comes in bursts: dummy frames fill the gaps, nothing is lost.
        val (src, b) = boucle(Modcod.de("QPSK 3/4", courte = true, pilotes = true)!!, Rolloff.A20, 2, 70, parSalve = 13)
        verifieOctetPourOctet(src, b)
        assertTrue(b.tramesVides > 0)
        assertTrue(b.tramesDonnees >= 6)
    }

    @Test
    fun bout_en_bout_8psk_entrelaceur() {
        for (nom in listOf("8PSK 2/3", "8PSK 3/5")) {
            val (src, b) = boucle(Modcod.de(nom, courte = true, pilotes = true)!!, Rolloff.A25, 4, 40)
            verifieOctetPourOctet(src, b)
            assertEquals(1, b.modcods.size)
        }
    }

    @Test
    fun bout_en_bout_tous_les_taux_qpsk_courts() {
        for (t in Taux.entries.dropLast(1)) {
            val (src, b) = boucle(Modcod(Constellation.QPSK, t, courte = true), Rolloff.A35, 2, 12)
            verifieOctetPourOctet(src, b)
        }
    }

    @Test
    fun bout_en_bout_avec_bruit_le_ldpc_corrige() {
        // Es/N0 3 dB: QPSK hard decisions wrong by the hundred per frame; QPSK 1/2 (short) decodes.
        val (src, b) = boucle(Modcod.de("QPSK 1/2", courte = true)!!, Rolloff.A35, 2, 20, esN0Db = 3.0)
        assertEquals(0, b.ldpcEchecs)
        println("DVB-S2 bruit Es/N0 3 dB : ${b.bitsCorriges} bits corrigés sur ${b.tramesDonnees} trames, ${b.paquets.size} paquets intacts")
        assertTrue("bits corrigés : ${b.bitsCorriges}", b.bitsCorriges > 500)
        verifieOctetPourOctet(src, b)
    }

    // ------------------------------------------------------------ spectrum

    @Test
    fun spectre_largeur_et_epaules() {
        for (ro in Rolloff.entries) {
            val sps = 8
            val mod = ModulateurDvbs2(Modcod.de("QPSK 1/2")!!, ro, sps, Paquets(1, Int.MAX_VALUE))
            val n = 4096
            val moyenne = DoubleArray(n)
            val iq = ShortArray(2 * n)
            val fen = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
            repeat(80) {
                mod.remplis(iq, n)
                val re = DoubleArray(n) { iq[2 * it] * fen[it] }
                val im = DoubleArray(n) { iq[2 * it + 1] * fen[it] }
                Fft.transform(re, im)
                for (k in 0 until n) moyenne[k] += re[k] * re[k] + im[k] * im[k]
            }
            // Frequency of bin k in symbol rates: (k or k − n) · sps / n.
            fun f(k: Int) = (if (k < n / 2) k else k - n).toDouble() * sps / n
            val total = moyenne.sum()
            val bord = (1 + ro.alpha) / 2
            val dedans = (0 until n).filter { kotlin.math.abs(f(it)) <= bord }.sumOf { moyenne[it] }
            assertTrue("${ro.libelle} : ${dedans / total}", dedans / total > 0.995)
            // −3 dB at ±Rs/2 (RRC: half power at the Nyquist frequency).
            val centre = (0 until n).filter { kotlin.math.abs(f(it)) < 0.3 }.map { moyenne[it] }.average()
            fun db(k: Int) = 10 * log10(moyenne[k] / centre)
            val a3 = (0 until n / 2).first { db(it) < -3.0 }
            assertEquals(ro.libelle, 0.5, f(a3), 0.02)
            // Shoulders: 0.1 Rs beyond the edge, far down.
            val epaule = (0 until n).filter { kotlin.math.abs(f(it)) >= bord + 0.1 }.maxOf { db(it) }
            println("DVB-S2 α=${ro.libelle} : ${"%.2f".format(dedans / total * 100)} % de la puissance dans ±Rs(1+α)/2, -3 dB à ±${"%.3f".format(f(a3))} Rs, épaules ${"%.1f".format(epaule)} dB")
            assertTrue("épaules ${ro.libelle} : $epaule dB", epaule < -45)
            assertEquals(0L, mod.ecretages)
        }
    }

    // ------------------------------------------------------------ speed

    @Test
    fun debit_plusieurs_fois_le_temps_reel() {
        for ((rs, sps) in listOf(333_000 to 7, 1_000_000 to 3)) {
            val m = Modcod.de("QPSK 2/3")!!
            val mod = ModulateurDvbs2(m, Rolloff.A25, sps, Paquets(5, Int.MAX_VALUE))
            val bloc = ShortArray(2 * 65_536)
            mod.remplis(bloc, 65_536) // warm-up
            val secondes = 2.0
            val paires = (rs * sps * secondes).toLong()
            val t0 = System.nanoTime()
            var fait = 0L
            while (fait < paires) { mod.remplis(bloc, 65_536); fait += 65_536 }
            val dt = (System.nanoTime() - t0) / 1e9
            val facteur = secondes * fait / paires / dt
            println("DVB-S2 ${m.libelle} ${rs / 1000} kS/s ×$sps : ${"%.2f".format(rs * secondes * fait / paires / dt / 1e6)} Msymboles/s, " +
                "${"%.2f".format(fait / dt / 1e6)} M échantillons/s, ${"%.1f".format(facteur)} × le temps réel")
            assertTrue("seulement ${"%.1f".format(facteur)} × le temps réel", facteur > 2.0)
        }
    }
}
