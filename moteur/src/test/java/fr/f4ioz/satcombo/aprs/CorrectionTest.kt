/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/** Mending frames with one or two bits wrong; the shortest frames; other tones and speeds. */
class CorrectionTest {

    private val trame = Trame(Adresse("APRS"), Adresse("F4IOZ", 9), listOf(Adresse("WIDE1", 1)),
        "!4852.00N/00220.00E-SatMe essai de correction".toByteArray())

    /** The frame's HDLC bits between the flags, stuffing in. */
    private fun segment(o: ByteArray): BooleanArray = EncodeursFec.bitsHdlc(o).drop(8).dropLast(8).toBooleanArray()

    private fun abime(bits: BooleanArray, tons: List<Int>): BooleanArray = bits.copyOf().also { b ->
        for (t in tons) { b[t] = !b[t]; if (t + 1 < b.size) b[t + 1] = !b[t + 1] }
    }

    @Test
    fun un_ton_faux_au_bit_le_plus_faible_corrige() {
        val o = Ax25.encode(trame)
        val bits = segment(o)
        for (t in listOf(0, 3, 57, 200, bits.size - 2)) {
            val conf = FloatArray(bits.size) { 0.8f }.also { it[t] = 0.05f }
            val c = CorrecteurHdlc.corrige(SegmentHdlc(abime(bits, listOf(t)), conf), 1)
            assertArrayEquals("ton $t", o, c!!.octets)
            assertEquals(1, c.bits)
        }
    }

    @Test
    fun deux_tons_faux_seulement_en_mode_deux_bits() {
        val o = Ax25.encode(trame)
        val bits = segment(o)
        for (paire in listOf(listOf(40, 41), listOf(10, 300), listOf(100, 102))) {
            val conf = FloatArray(bits.size) { 0.8f }.also { c -> paire.forEach { c[it] = 0.1f } }
            val s = SegmentHdlc(abime(bits, paire), conf)
            assertNull(CorrecteurHdlc.corrige(s, 1))
            val c = CorrecteurHdlc.corrige(s, 2)
            assertArrayEquals("$paire", o, c!!.octets)
            assertEquals(2, c.bits)
        }
    }

    @Test
    fun sans_confiance_un_ton_cherche_partout() {
        val o = Ax25.encode(trame)
        val bits = segment(o)
        val c = CorrecteurHdlc.corrige(SegmentHdlc(abime(bits, listOf(123)), FloatArray(bits.size) { 1f }), 2)
        assertArrayEquals(o, c!!.octets)
    }

    @Test
    fun ton_faux_bien_entendu_pas_corrige() {
        // The demodulator was sure of the wrong bit: no guess there, nothing made up.
        val o = Ax25.encode(trame)
        val bits = segment(o)
        val conf = FloatArray(bits.size) { 0.8f }.also { it[5] = 0.1f; it[9] = 0.2f }
        assertNull(CorrecteurHdlc.corrige(SegmentHdlc(abime(bits, listOf(150)), conf), 2))
    }

    @Test
    fun bits_au_hasard_jamais_corriges() {
        val rnd = Random(5)
        var corrigees = 0
        var segments = 0
        val d = HdlcDecodeur(surEchec = { s ->
            segments++
            if (CorrecteurHdlc.corrige(s, 2) != null) corrigees++
        }) { }
        repeat(3_000_000) { d.bit(rnd.nextBoolean(), rnd.nextFloat()) }
        assertTrue("$segments segments", segments > 1000)
        assertEquals(0, corrigees)
    }

    @Test
    fun trame_ua_de_17_octets_passee_aux_octets_bruts() {
        // UA from F4IOZ to F1ABC: two addresses and a control byte, no PID, no info.
        val ua = Ax25.avecFcs(byteArrayOf(
            ('F'.code shl 1).toByte(), ('1'.code shl 1).toByte(), ('A'.code shl 1).toByte(), ('B'.code shl 1).toByte(),
            ('C'.code shl 1).toByte(), (' '.code shl 1).toByte(), 0x60,
            ('F'.code shl 1).toByte(), ('4'.code shl 1).toByte(), ('I'.code shl 1).toByte(), ('O'.code shl 1).toByte(),
            ('Z'.code shl 1).toByte(), (' '.code shl 1).toByte(), 0xE1.toByte(), 0x73))
        assertEquals(17, ua.size)
        val f = 22050
        val bruts = ArrayList<ByteArray>()
        val d = AfskDemodulateur(f) { }
        d.surOctets = { bruts += it }
        d.traite(Afsk.module(listOf(ua), f))
        assertArrayEquals(ua.copyOf(15), bruts.single())
        assertEquals(0, d.nombre)  // not APRS
        assertEquals("F4IOZ>F1ABC", Ax25.entete(bruts.single())!!.ligne())
    }

    @Test
    fun hf_300_bauds() {
        val f = 11025
        val r = ArrayList<Trame>()
        val d = AfskDemodulateur(f, ReglagesAfsk.HF_300) { r += it }
        d.traite(Afsk.module(listOf(Ax25.encode(trame)), f, marque = 1600.0, espace = 1800.0, baud = 300, drapeauxAvant = 8))
        assertEquals(listOf(trame), r)
        // The APRS demodulator does not hear it, and the other way round.
        val aprs = ArrayList<Trame>()
        AfskDemodulateur(f) { aprs += it }.traite(Afsk.module(listOf(Ax25.encode(trame)), f, marque = 1600.0, espace = 1800.0, baud = 300, drapeauxAvant = 8))
        assertTrue(aprs.isEmpty())
    }

    // ------------------------------------------------------------------ noisy audio

    private fun Random.gauss(): Double {
        var u: Double; var v: Double; var s: Double
        do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
    }

    private fun bruite(pcm: ShortArray, snrDb: Double, graine: Int): ShortArray {
        val rnd = Random(graine)
        val p = pcm.sumOf { it.toDouble() * it } / pcm.size
        val sigma = kotlin.math.sqrt(p / Math.pow(10.0, snrDb / 10))
        return ShortArray(pcm.size) { (pcm[it] + rnd.gauss() * sigma).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private fun trameNo(i: Int) = Trame(Adresse("APRS"), Adresse("F4IOZ", i % 16), listOf(Adresse("WIDE2", 1)),
        "!4852.${(10 + i % 80)}N/00220.00E-SatMe essai $i, une trame de taille ordinaire".toByteArray())

    /** Frames decoded out of [n] noisy ones at [snr] dB, for each correction level. */
    private fun mesure(snr: Double, n: Int, f: Int = 22050): IntArray {
        val ok = IntArray(3)
        for (i in 0 until n) {
            val t = trameNo(i)
            val pcm = bruite(Afsk.module(listOf(Ax25.encode(t)), f, niveau = 0.3, drapeauxAvant = 16) + ShortArray(f / 4), snr, 1000 + i)
            for ((k, c) in CorrectionBits.entries.withIndex()) {
                val r = ArrayList<Trame>()
                AfskDemodulateur(f, ReglagesAfsk(correction = c, fx25 = false, il2p = false)) { r += it }.traite(pcm)
                if (r.contains(t)) ok[k]++
                assertTrue("trame inventée à $snr dB : $r", r.all { it == t })
            }
        }
        return ok
    }

    @Test
    fun la_correction_gagne_des_trames_dans_le_bruit() {
        val ok = mesure(0.0, 30)
        assertTrue("${ok.toList()}", ok[1] >= ok[0] && ok[2] >= ok[1] && ok[2] > ok[0])
    }

    @Test
    fun bruit_seul_ne_donne_aucune_trame() {
        val f = 22050
        val rnd = Random(9)
        val d = AfskDemodulateur(f) { }
        val bloc = ShortArray(f)
        repeat(120) {
            for (i in bloc.indices) bloc[i] = (rnd.gauss() * 6000).toInt().coerceIn(-32768, 32767).toShort()
            d.traite(bloc)
        }
        assertEquals(StatsAfsk(), d.statistiques)
    }

    /**
     * On demand (`SATME_MESURE_APRS=200` in the environment, or `-Dsatme.mesureAprs=200`): the tables of the report — frames
     * decoded out of 200 per SNR, each correction level, FX.25 and IL2P; then
     * an hour of noise for false frames, and the CPU cost.
     */
    @Test
    fun mesures() {
        val reglage = System.getProperty("satme.mesureAprs") ?: System.getenv("SATME_MESURE_APRS")
        assumeTrue(reglage != null)
        val f = 22050
        val n = reglage!!.toIntOrNull() ?: 200
        println("SNR | AX.25 | +1 bit | +2 bits | FX.25 RS(144,128) | FX.25 RS(160,128) | IL2P")
        for (snr in listOf(-2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0)) {
            val ok = mesure(snr, n, f)
            val fec = IntArray(3)
            for (i in 0 until n) {
                val t = trameNo(i)
                val o = Ax25.encode(t)
                val tons = listOf(
                    EncodeursFec.tonsFx25(o, Fx25.CODES.first { it.numero == 2 }),
                    EncodeursFec.tonsFx25(o, Fx25.CODES.first { it.numero == 6 }),
                    EncodeursFec.tonsIl2p(EncodeursFec.il2p(Ax25.encodeSansFcs(t))))
                for ((k, tn) in tons.withIndex()) {
                    val pcm = bruite(Afsk.moduleTons(tn, f, 0.3) + ShortArray(f / 4), snr, 5000 + i)
                    val r = ArrayList<Trame>()
                    AfskDemodulateur(f) { r += it }.traite(pcm)
                    if (r.contains(t)) fec[k]++
                }
            }
            println("$snr dB | ${ok[0]} | ${ok[1]} | ${ok[2]} | ${fec[0]} | ${fec[1]} | ${fec[2]}")
        }
        val minutes = 60
        val reglages = listOf("comme avant (AX.25 seul)" to ReglagesAfsk(correction = CorrectionBits.AUCUNE, fx25 = false, il2p = false)) +
            CorrectionBits.entries.map { "FX.25 + IL2P + correction $it" to ReglagesAfsk(correction = it) }
        for ((nom, r) in reglages) {
            val rnd = Random(77)
            val d = AfskDemodulateur(f, r) { }
            var octets = 0
            d.surOctets = { octets++ }
            val bloc = ShortArray(f)
            var ns = 0L
            repeat(minutes * 60) {
                for (i in bloc.indices) bloc[i] = (rnd.gauss() * 6000).toInt().coerceIn(-32768, 32767).toShort()
                val t0 = System.nanoTime(); d.traite(bloc); ns += System.nanoTime() - t0
            }
            println("bruit $minutes min, $nom : ${d.statistiques.ligne()}, octets bruts $octets, " +
                "temps de calcul %.1f s (%.1f %% du temps réel)".format(ns / 1e9, ns / 1e9 / (minutes * 60) * 100))
        }
    }
}
