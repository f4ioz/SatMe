/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** SigMF files, the « remonter le temps » ring, the replay and its pace. */
class IqFichiersTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun meta(fmt: SigMF.Format = SigMF.Format.CI16, debit: Double = Dsp.RTL_RATE.toDouble()) = SigMF.Meta(
        fmt, debit, debutMs = 1_792_000_000_123L,
        captures = listOf(SigMF.Capture(0, 145_800_000L, 145_800_000L, 3_210.5), SigMF.Capture(52_920, 145_800_000L, 145_803_000L, -12.0)),
        materiel = "RTL-SDR R820T", enregistreur = "SatMe-PC essai", auteur = "F4IOZ", satellite = "ISS (ZARYA)", norad = 25544,
        mode = "NFM", oscillateurHz = 9_750_000_000L, aosMs = 1_791_999_000_000L, losMs = 1_792_000_600_000L, essai = true)

    @Test
    fun la_meta_sigmf_se_relit_telle_quelle() {
        val m = meta()
        val texte = SigMF.ecritMeta(m)
        assertTrue(texte.contains("\"core:datatype\": \"ci16_le\""))
        assertTrue(texte.contains("\"core:datetime\": \"2026-10-14T"))
        val r = SigMF.litMeta(texte)!!
        assertEquals(m, r)
        assertEquals(m.captures[1], r.captureA(60_000))
        assertEquals(m.captures[0], r.captureA(10))
    }

    @Test
    fun une_meta_etrangere_se_lit_aussi() {
        val t = """{"global":{"core:datatype":"cf32_le","core:sample_rate":48000,"core:version":"1.0.0"},
            "captures":[{"core:sample_start":0,"core:frequency":437.8e6,"core:datetime":"2026-10-18T10:00:00.123456Z"}],"annotations":[]}"""
        val r = SigMF.litMeta(t)!!
        assertEquals(SigMF.Format.CF32, r.format)
        assertEquals(48_000.0, r.debit, 0.0)
        assertEquals(437_800_000L, r.captures[0].frequenceHz)
        assertEquals(java.time.Instant.parse("2026-10-18T10:00:00.123Z").toEpochMilli(), r.debutMs)
        assertEquals(null, SigMF.litMeta("""{"global":{"core:datatype":"ri16_le","core:sample_rate":1}}"""))
    }

    @Test
    fun enregistre_puis_relu_echantillon_par_echantillon() {
        val d = File(tmp.root, "essai" + SigMF.EXT_DATA)
        val e = EnregistrementIq(d, SigMF.Format.CI16, limitePaires = 10_000)
        val envoye = ShortArray(2 * 9_000) { ((it * 37) % 65_000 - 32_500).toShort() }
        var o = 0
        while (o < envoye.size) {
            val n = minOf(1_234, envoye.size - o)
            e.ecrit16(envoye.copyOfRange(o, o + n), n, 145_800_000L, 145_800_000L, 0.0)
            o += n
        }
        e.close()
        assertEquals(9_000L, e.paires)
        SigMF.meta(d).writeText(SigMF.ecritMeta(meta().copy(captures = e.captures)))
        val (ouvert, pourquoi) = SourceFichierIq.meta(SigMF.meta(d))
        assertNotNull(pourquoi, ouvert)
        val s = SourceFichierIq(ouvert!!.first, ouvert.second, cadence = false)
        val lu = ShortArray(envoye.size)
        val b = ShortArray(2_000)
        var k = 0
        while (true) {
            val n = s.read16(b)
            if (n < 0) break
            System.arraycopy(b, 0, lu, k, n); k += n
        }
        assertEquals(envoye.size, k)
        assertArrayEquals(envoye, lu)
        assertEquals(145_800_000L, s.capture().frequenceHz)
    }

    @Test
    fun la_limite_arrete_lenregistrement() {
        val d = File(tmp.root, "court" + SigMF.EXT_DATA)
        val e = EnregistrementIq(d, SigMF.Format.CU8, limitePaires = 100)
        assertTrue(e.ecrit8(ByteArray(150), 150, 1, 1, 0.0))
        assertEquals(false, e.ecrit8(ByteArray(150), 150, 1, 1, 0.0))
        e.close()
        assertEquals(200L, d.length())
    }

    @Test
    fun lanneau_garde_exactement_les_dernieres_paires() {
        val cap = 1_000L
        val a = AnneauIq(File(tmp.root, "anneau.bin"), SigMF.Format.CI16, cap, debit = 1_000.0)
        // Pair p holds (p, -p): blocks of odd sizes, the ring turned over three and a half times.
        var p = 0
        while (p < 3_517) {
            val n = minOf(77, 3_517 - p)
            val s = ShortArray(2 * n) { if (it % 2 == 0) (p + it / 2).toShort() else (-(p + it / 2)).toShort() }
            // The frequency moves with the block starting at pair 3003 (39 × 77).
            a.ecrit16(s, s.size, if (p >= 3_000) 437_000_000L else 145_000_000L, 0, 0.0)
            p += n
        }
        assertEquals(3_517L, a.ecrites)
        assertEquals(cap, a.tenues)
        fun paires(o: ByteArrayOutputStream): IntArray {
            val b = o.toByteArray()
            return IntArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort().toInt() }
        }
        val tout = ByteArrayOutputStream()
        val x = a.exporte(tout, margePaires = 0)
        assertEquals(cap, x.paires)
        assertEquals(2_517L, x.debutPaire)
        val v = paires(tout)
        assertEquals(2 * cap.toInt(), v.size)
        for (k in 0 until cap.toInt()) { assertEquals(2_517 + k, v[2 * k]); assertEquals(-(2_517 + k), v[2 * k + 1]) }
        // The captures counted from the extract's start: 145 MHz at 0, 437 MHz from pair 3000.
        assertEquals(145_000_000L, x.captures.first().frequenceHz)
        assertEquals(0L, x.captures.first().echantillon)
        val c = x.captures.first { it.frequenceHz == 437_000_000L }
        assertEquals(3_003L - 2_517L, c.echantillon)
        // The last 300 only.
        val fin = ByteArrayOutputStream()
        a.exporte(fin, paires = 300, margePaires = 0)
        val w = paires(fin)
        assertEquals(600, w.size)
        assertEquals(3_217, w[0]); assertEquals(3_516, w[598])
        a.close()
        assertTrue(!a.fichier.exists())
    }

    @Test
    fun la_copie_se_fait_pendant_que_lanneau_tourne() {
        val a = AnneauIq(File(tmp.root, "a2.bin"), SigMF.Format.CU8, 50_000, debit = 10_000.0)
        val b = ByteArray(1_000) { it.toByte() }
        repeat(120) { a.ecrit8(b, b.size, 1, 1, 0.0) }
        val o = FileOutputStream(File(tmp.root, "x.sigmf-data"))
        // While copying, the receiver goes on: each piece written between two of the copy's.
        val x = a.exporte(o, margePaires = 5_000) { repeat(3) { a.ecrit8(b, b.size, 1, 1, 0.0) } }
        o.close()
        assertEquals(45_000L, x.paires)
        assertEquals(90_000L, File(tmp.root, "x.sigmf-data").length())
    }

    /** A fake clock: [dort] moves it on. */
    private class Horloge { var t = 1_000_000L; val dorts = ArrayList<Long>() }

    @Test
    fun la_relecture_suit_le_debit_et_la_vitesse() {
        val d = File(tmp.root, "rythme.cu8")
        d.writeBytes(ByteArray(2 * Dsp.RTL_RATE * 3) { 128.toByte() })
        val h = Horloge()
        val (o, _) = SourceFichierIq.meta(d, Dsp.RTL_RATE.toDouble(), 145_800_000L)
        val s = SourceFichierIq(o!!.first, o.second, horloge = { h.t }, cadence = true, dort = { h.t += it; h.dorts += it })
        assertEquals(3.0, s.dureeS, 1e-9)
        val b = ShortArray(2 * 10_584)    // 10 ms
        val t0 = h.t
        repeat(100) { assertEquals(b.size, s.read16(b)) }
        assertEquals(1_000.0, (h.t - t0).toDouble(), 2.0)
        s.vitesse = 4.0
        val t1 = h.t
        repeat(100) { s.read16(b) }
        assertEquals(250.0, (h.t - t1).toDouble(), 2.0)
        assertEquals(2.0, s.positionS, 1e-6)
        // Paused: nothing given, the position stays.
        s.enPause = true
        assertEquals(0, s.read16(b))
        assertEquals(2.0, s.positionS, 1e-6)
        s.enPause = false
        // Sought near the end, then the end: -1, unless looped.
        s.cherche(2.985)
        assertEquals(b.size, s.read16(b))
        assertTrue(s.read16(b) in 0 until b.size)
        assertEquals(-1, s.read16(b))
        s.boucle = true; s.cherche(2.985)
        repeat(3) { s.read16(b) }
        assertTrue(s.positionS < 0.02)
    }

    @Test
    fun un_autre_debit_est_ramene_a_celui_du_recepteur() {
        // 48 kHz, a tone at 6 kHz: after interpolation the spectrum puts it at +6 kHz.
        val fs = 48_000.0
        val n = 48_000
        val d = File(tmp.root, "ton.cf32")
        val bb = java.nio.ByteBuffer.allocate(8 * n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) { val ph = 2 * Math.PI * 6_000 * k / fs; bb.putFloat((0.5 * Math.cos(ph)).toFloat()); bb.putFloat((0.5 * Math.sin(ph)).toFloat()) }
        d.writeBytes(bb.array())
        val (o, _) = SourceFichierIq.meta(d, fs, 0)
        val s = SourceFichierIq(o!!.first, o.second, cadence = false)
        val chaine = RxChain()
        val b = ShortArray(2 * 16_384)
        val pcm = ShortArray(chaine.maxAudio(b.size))
        var tours = 0
        while (true) { val k = s.read16(b); if (k <= 0) break; chaine.process16(b, k, pcm, feedSpectrum = true); tours++ }
        assertTrue(tours > 50)
        assertEquals(6_000.0, chaine.peakOffsetHz(-80_000.0, 80_000.0), 100.0)
    }
}
