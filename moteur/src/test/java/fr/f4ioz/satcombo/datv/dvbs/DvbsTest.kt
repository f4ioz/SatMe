/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.dvbs

import fr.f4ioz.satcombo.datv.s2.ModulateurDvbs2
import fr.f4ioz.satcombo.datv.s2.RecepteurDvbs2
import fr.f4ioz.satcombo.datv.s2.Rolloff
import fr.f4ioz.satcombo.datv.s2.SourceTs
import fr.f4ioz.satcombo.sdr.Fft
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

class DvbsTest {

    /** Numbered packets with random payloads; [prets] ready at a time when set (null packets otherwise). */
    private class Paquets(seed: Long, var prets: Int = Int.MAX_VALUE) : SourceTs {
        val r = java.util.Random(seed)
        val envoyes = ArrayList<ByteArray>()
        override fun disponibles(): Int = prets
        override fun lis(dst: ByteArray) {
            val k = envoyes.size
            dst[0] = 0x47; dst[1] = 0x01; dst[2] = 0x00; dst[3] = (0x10 or (k and 15)).toByte()
            for (j in 4 until 188) dst[j] = r.nextInt().toByte()
            envoyes += dst.copyOf()
            if (prets != Int.MAX_VALUE) prets--
        }
    }

    // ------------------------------------------------------------ the blocks against the standard

    @Test
    fun dispersion_commence_par_00000011_et_a_la_periode_de_32767() {
        assertEquals(0x03, Dispersion.OCTETS[0].toInt() and 0xFF)
        assertEquals(0xF6, Dispersion.OCTETS[1].toInt() and 0xFF)
        val p = ByteArray(188); ModulateurDvbs.nul(p)
        Dispersion.applique(p, 0, 0)
        assertEquals(0xB8, p[0].toInt() and 0xFF)
        Dispersion.applique(p, 0, 3)
        assertEquals(0x47, p[0].toInt())
    }

    @Test
    fun rs_mots_divisibles_par_g_et_racines_alpha_0_a_15() {
        // g(x) of degree 16, monic, roots λ⁰…λ¹⁵.
        assertEquals(17, Rs204.G.size)
        assertEquals(1, Rs204.G[0])
        val r = java.util.Random(3)
        val m = ByteArray(188); val c = ByteArray(204)
        repeat(20) {
            r.nextBytes(m)
            Rs204.encode(m, 0, c)
            for (k in 0 until 188) assertEquals(m[k], c[k])
            for (i in 0 until 16) {
                var v = 0
                for (b in c) v = Gf256.mul(v, Gf256.EXP[i]) xor (b.toInt() and 0xFF)
                assertEquals("syndrome $i", 0, v)
            }
        }
    }

    @Test
    fun rs_le_recepteur_corrige_8_octets_et_refuse_9() {
        val rx = RecepteurDvbsEssai(TauxDvbs.R1_2)
        val g = java.util.Random(4)
        val m = ByteArray(188); val c = ByteArray(204)
        repeat(50) { essai ->
            g.nextBytes(m)
            Rs204.encode(m, 0, c)
            val faux = c.copyOf()
            val nb = 1 + essai % 8
            val pos = (0 until 204).shuffled(g).take(nb)
            for (p in pos) faux[p] = (faux[p].toInt() xor (1 + g.nextInt(255))).toByte()
            assertEquals(nb, rx.rs(faux))
            assertArrayEquals(c, faux)
        }
        var refus = 0
        repeat(30) {
            g.nextBytes(m); Rs204.encode(m, 0, c)
            for (p in (0 until 204).shuffled(g).take(12)) c[p] = (c[p].toInt() xor (1 + g.nextInt(255))).toByte()
            if (rx.rs(c) < 0) refus++
        }
        assertTrue("refus $refus / 30", refus >= 28)
    }

    @Test
    fun entrelaceur_et_desentrelaceur_retard_de_11_paquets() {
        val e = EntrelaceurDvbs()
        val n = 204 * 30
        val src = ByteArray(n) { (it * 7 + 3).toByte() }
        val bits = ByteArray(8 * n)
        for (i in 0 until n) { val v = e.pousse(src[i]).toInt(); for (j in 0 until 8) bits[8 * i + j] = ((v shr (7 - j)) and 1).toByte() }
        val d = RecepteurDvbsEssai(TauxDvbs.R1_2).desentrelace(bits)
        val retard = 11 * 17 * 12
        assertEquals(2244, retard)
        for (i in retard until n) assertEquals("octet $i", src[i - retard], d[i])
        // Sync bytes (every 204th) never delayed.
        val e2 = EntrelaceurDvbs()
        for (i in 0 until n) { val v = e2.pousse(src[i]); if (i % 204 == 0) assertEquals(src[i], v) }
    }

    @Test
    fun poinconnage_table_2_et_debits() {
        // Bits sent per period: 2·periode·CR⁻¹ … i.e. periode / CR.
        for (t in TauxDvbs.entries) {
            val n = t.x.count { it == '1' } + t.y.count { it == '1' }
            assertEquals(t.libelle, t.periode * t.den / t.num, n)
            assertEquals(t.libelle, t.num, t.periode)
        }
        // Rs × 2 × CR × 188/204: 333 kS/s 1/2 → 306.88 kbit/s, 1 MS/s 7/8 → 1.6127 Mbit/s.
        assertEquals(306_882.0, TauxDvbs.R1_2.debitTs(333_000.0), 1.0)
        assertEquals(1_612_745.0, TauxDvbs.R7_8.debitTs(1_000_000.0), 1.0)
        assertEquals(TauxDvbs.R3_4, TauxDvbs.de("QPSK 3/4"))
        assertEquals(TauxDvbs.R7_8, TauxDvbs.de("DVB-S QPSK 7/8"))
        assertEquals(null, TauxDvbs.de("QPSK 9/10"))
    }

    @Test
    fun codeur_reponse_impulsionnelle_171_133() {
        // A single 1 then zeros: X gives 171₈'s taps, Y 133₈'s, input first.
        val c = CodeurConvolutif(TauxDvbs.R1_2)
        val out = ByteArray(2)
        val xs = StringBuilder(); val ys = StringBuilder()
        for (k in 0 until 7) { c.bit(if (k == 0) 1 else 0, out, 0); xs.append(out[0]); ys.append(out[1]) }
        assertEquals("1111001", xs.toString())
        assertEquals("1011011", ys.toString())
    }

    // ------------------------------------------------------------ end to end

    private class Bout(val src: Paquets, val b: RecepteurDvbsEssai.Bilan, val mod: ModulateurDvbs)

    private fun boucle(t: TauxDvbs, paquets: Int, ebN0Db: Double? = null, sps: Int = 2, src: Paquets = Paquets(42), graine: Long = 9): Bout {
        val mod = ModulateurDvbs(t, sps, src)
        // Packets, plus the 11 null ones first, the interleaver's delay (11) and the filters' tails.
        val symboles = ((paquets + 24) * 816.0 / t.code).toInt() + 40
        val paires = symboles * sps
        val iq = ShortArray(2 * paires)
        mod.remplis(iq, paires)
        val x = FloatArray(iq.size) { iq[it].toFloat() }
        if (ebN0Db != null) {
            val g = java.util.Random(graine)
            val echelle = ModulateurDvbs2.PLEIN * Math.pow(10.0, -ModulateurDvbs2.RECUL_DB / 20)
            // Eb per useful bit (188 bytes): Es/N0 = Eb/N0 · 2 · CR · 188/204.
            val esN0 = Math.pow(10.0, ebN0Db / 10) * 2 * t.code * 188 / 204
            val sigma = echelle * sqrt(sps / (2 * esN0))
            for (k in x.indices) x[k] += (g.nextGaussian() * sigma).toFloat()
        }
        val s = RecepteurDvbs2(Rolloff.A35, sps).symboles(x)
        return Bout(src, RecepteurDvbsEssai(t).decode(s), mod)
    }

    /** Every packet decoded identical to the one sent, in order (the null ones skipped). */
    private fun verifie(o: Bout, attendus: Int): Int {
        val recus = o.b.paquets.filter { it.isNotEmpty() && !(it[1] == 0x1F.toByte() && it[2] == 0xFF.toByte()) }
        assertTrue("reçus ${recus.size} / $attendus", recus.size >= attendus)
        for (k in recus.indices) assertArrayEquals("paquet $k", o.src.envoyes[k], recus[k])
        return recus.size
    }

    @Test
    fun bout_en_bout_tous_les_taux_sans_bruit() {
        for (t in TauxDvbs.entries) {
            val o = boucle(t, 40)
            assertEquals(t.libelle, 0, o.b.paquetsFaux)
            assertEquals(t.libelle, 0, o.b.octetsCorriges)
            verifie(o, 40)
            assertEquals(0L, o.mod.ecretages)
        }
    }

    @Test
    fun bout_en_bout_sps_3_a_7() {
        for (sps in 3..7) verifie(boucle(TauxDvbs.R2_3, 20, sps = sps, ebN0Db = 8.0), 20)
    }

    @Test
    fun paquets_nuls_quand_le_flux_manque() {
        val src = Paquets(5, prets = 10)
        val o = boucle(TauxDvbs.R3_4, 30, src = src)
        assertTrue(o.mod.nuls > 15)
        assertEquals(10L, o.mod.paquets)
        assertTrue(o.mod.partVide() > 0.5)
        assertEquals(0, o.b.paquetsFaux)
        verifie(o, 10)
        // The null packets come out as null packets.
        assertTrue(o.b.paquets.count { it.size == 188 && it[1] == 0x1F.toByte() && it[2] == 0xFF.toByte() && it[100] == 0xFF.toByte() } > 10)
    }

    /** EN 300 421 table: Eb/N0 for QEF after RS (useful bit rate), the modem's implementation margin included. */
    private val qef = mapOf(TauxDvbs.R1_2 to 4.5, TauxDvbs.R2_3 to 5.0, TauxDvbs.R3_4 to 5.5, TauxDvbs.R5_6 to 6.0, TauxDvbs.R7_8 to 6.4)

    @Test
    fun bruit_qef_aux_seuils_de_la_norme_tous_les_taux() {
        for (t in TauxDvbs.entries) {
            val n = 300
            val o = boucle(t, n, ebN0Db = qef[t]!!)
            println("DVB-S ${t.libelle} à Eb/N0 ${qef[t]} dB : ${o.b.octetsCorriges} octets corrigés par le RS, ${o.b.paquetsFaux} paquets faux")
            assertEquals(t.libelle, 0, o.b.paquetsFaux)
            verifie(o, n)
        }
    }

    @Test
    fun bruit_fort_des_paquets_perdus() {
        // 3 dB under the standard's threshold the RS can no longer keep up: the test sees it.
        val o = boucle(TauxDvbs.R3_4, 150, ebN0Db = 2.5)
        val bons = o.b.paquets.count { it.isNotEmpty() && it[1] == 0x01.toByte() }
        assertTrue("paquets justes $bons / 150, faux ${o.b.paquetsFaux}", bons < 100)
    }

    // ------------------------------------------------------------ spectrum

    @Test
    fun spectre_largeur_et_epaules() {
        val sps = 8
        val mod = ModulateurDvbs(TauxDvbs.R3_4, sps, Paquets(1))
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
        fun f(k: Int) = (if (k < n / 2) k else k - n).toDouble() * sps / n
        val total = moyenne.sum()
        val bord = (1 + ModulateurDvbs.ALPHA) / 2
        val dedans = (0 until n).filter { kotlin.math.abs(f(it)) <= bord }.sumOf { moyenne[it] }
        assertTrue("${dedans / total}", dedans / total > 0.995)
        val centre = (0 until n).filter { kotlin.math.abs(f(it)) < 0.3 }.map { moyenne[it] }.average()
        fun db(k: Int) = 10 * log10(moyenne[k] / centre)
        val a3 = (0 until n / 2).first { db(it) < -3.0 }
        assertEquals(0.5, f(a3), 0.02)
        val epaule = (0 until n).filter { kotlin.math.abs(f(it)) >= bord + 0.1 }.maxOf { db(it) }
        println("DVB-S α=0,35 : ${"%.2f".format(dedans / total * 100)} % dans ±Rs(1+α)/2, -3 dB à ±${"%.3f".format(f(a3))} Rs, épaules ${"%.1f".format(epaule)} dB")
        assertTrue("épaules $epaule dB", epaule < -45)
        assertEquals(0L, mod.ecretages)
    }

    @Test
    fun debit_plusieurs_fois_le_temps_reel() {
        val rs = 1_000_000; val sps = 3
        val mod = ModulateurDvbs(TauxDvbs.R7_8, sps, Paquets(5))
        val bloc = ShortArray(2 * 65_536)
        mod.remplis(bloc, 65_536)
        val t0 = System.nanoTime()
        var paires = 0L
        while (System.nanoTime() - t0 < 1_000_000_000L) { mod.remplis(bloc, 65_536); paires += 65_536 }
        val s = (System.nanoTime() - t0) / 1e9
        val x = paires / s / (rs * sps)
        println("DVB-S 7/8 1 MS/s × $sps : ${"%.1f".format(x)} × le temps réel")
        assertTrue("$x × le temps réel", x > 1.5)
    }

    /** Lowest Eb/N0 (0.25 dB steps) with no packet lost over 400, for the report; run by hand. */
    @Test
    fun seuils_mesures() {
        if (System.getenv("DVBS_SEUILS") == null) return
        for (t in TauxDvbs.entries) {
            var e = qef[t]!!
            while (e > 0) {
                val o = boucle(t, 400, ebN0Db = e - 0.25, graine = 17)
                if (o.b.paquetsFaux > 0) break
                e -= 0.25
            }
            println("SEUIL DVB-S ${t.libelle} : $e dB (norme ${qef[t]} dB)")
        }
    }
}
