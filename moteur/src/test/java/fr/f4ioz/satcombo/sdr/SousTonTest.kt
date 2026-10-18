/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SousTonTest {

    private val rate = Dsp.RTL_RATE.toDouble()

    /** A voice-ish modulation, Hz of deviation: syllables of three formants between 300 and 2800 Hz, pauses between words. */
    private class Voix(seed: Int, private val crete: Double = 2_500.0) {
        private val r = Random(seed)
        private var reste = 0
        private val f = DoubleArray(3)
        private val ph = DoubleArray(3)
        private var muet = false
        fun suivant(fs: Double): Double {
            if (reste <= 0) {
                reste = ((0.08 + r.nextDouble() * 0.15) * fs).toInt()
                muet = r.nextDouble() < 0.2
                f[0] = 300.0 + r.nextDouble() * 500.0; f[1] = 800.0 + r.nextDouble() * 1_200.0; f[2] = 1_800.0 + r.nextDouble() * 1_000.0
            }
            reste--
            var s = 0.0
            for (k in 0..2) { ph[k] += 2 * PI * f[k] / fs; s += sin(ph[k]) * (0.5 - 0.12 * k) }
            return if (muet) 0.0 else s * crete / 1.0
        }
    }

    /**
     * [secondes] of narrow FM at the dongle's rate through a whole [RxChain],
     * [ton] Hz at [devTonHz] (0: none) plus [voix]; [snrBruit] the noise's
     * standard deviation per I and Q against a carrier of 1 (no carrier: [porteuse] false).
     * Returns the time (s) the tone was first reported, and the chain.
     */
    private fun fm(
        secondes: Double, ton: Double, devTonHz: Double = 500.0, voix: Voix? = null,
        bruit: Double = 0.05, porteuse: Boolean = true, chain: RxChain = RxChain().apply { mode = RxMode.NFM; subTones = true },
        vu: (Double, DetecteurSousTon.Etat, ShortArray, Int) -> Unit = { _, _, _, _ -> },
    ): RxChain {
        val bloc = 16_384
        val iq = ShortArray(2 * bloc)
        val out = ShortArray(chain.maxAudio(4 * bloc))
        val r = Random(7)
        var phase = 0.0; var phTon = 0.0
        val total = (secondes * rate).toLong()
        var k = 0L
        while (k < total) {
            for (j in 0 until bloc) {
                var dev = 0.0
                if (ton > 0) { dev += devTonHz * sin(phTon); phTon += 2 * PI * ton / rate }
                if (voix != null) dev += voix.suivant(rate)
                phase += 2 * PI * dev / rate
                val a = if (porteuse) 0.5 else 0.0
                iq[2 * j] = ((a * cos(phase) + bruit * gauss(r)) * 20_000).toInt().coerceIn(-32_000, 32_000).toShort()
                iq[2 * j + 1] = ((a * sin(phase) + bruit * gauss(r)) * 20_000).toInt().coerceIn(-32_000, 32_000).toShort()
            }
            k += bloc
            val p = chain.process16(iq, iq.size, out)
            vu(k / rate, chain.subTone, out, p)
        }
        return chain
    }

    private fun gauss(r: Random): Double {
        var s = 0.0
        repeat(6) { s += r.nextDouble() }
        return (s - 3.0) * 1.414
    }

    private fun premiere(t: Double, secondes: Double, voix: Voix?, dev: Double = 500.0): Double {
        var vu = -1.0
        fm(secondes, t, dev, voix) { s, e, _, _ -> if (vu < 0 && e.present) { assertEquals("ton lu", t, e.hz, 0.01); vu = s } }
        return vu
    }

    @Test
    fun le_ton_de_67_hz_sous_la_voix_est_lu_en_moins_d_une_seconde() {
        val t = premiere(67.0, 1.5, Voix(1))
        assertTrue("lu à $t s", t in 0.0..1.0)
    }

    @Test
    fun le_ton_reste_tenu_sous_la_voix_et_sa_deviation_est_mesuree() {
        var tenu = 0; var vus = 0; var dev = 0
        fm(4.0, 67.0, 500.0, Voix(2)) { s, e, _, _ -> if (s > 1.2) { vus++; if (e.hz == 67.0) { tenu++; dev = e.deviationHz } } }
        assertEquals("tenu tout du long", vus, tenu)
        assertTrue("déviation $dev", abs(dev - 500) < 100)
    }

    @Test
    fun les_tons_71_9_et_74_4_se_distinguent() {
        for (t in listOf(71.9, 74.4)) {
            var faux = false
            var vu = -1.0
            fm(2.0, t, 300.0, Voix(3)) { s, e, _, _ ->
                if (e.present && e.hz != t) faux = true
                if (vu < 0 && e.hz == t) vu = s
            }
            assertFalse("$t confondu", faux)
            assertTrue("$t lu à $vu s", vu in 0.0..1.0)
        }
    }

    @Test
    fun rien_sur_la_voix_seule() {
        fm(5.0, 0.0, voix = Voix(4)) { s, e, _, _ -> assertFalse("faux ton ${e.hz} à $s s", e.present) }
    }

    @Test
    fun rien_sur_le_bruit_seul() {
        fm(5.0, 0.0, bruit = 0.3, porteuse = false) { s, e, _, _ -> assertFalse("faux ton ${e.hz} à $s s", e.present) }
    }

    @Test
    fun rien_sur_une_porteuse_muette() {
        fm(3.0, 0.0) { s, e, _, _ -> assertFalse("faux ton ${e.hz} à $s s", e.present) }
    }

    @Test
    fun chaque_ton_de_la_table_est_reconnu() {
        val fs = Dsp.AUDIO_RATE.toDouble()
        for (t in DetecteurSousTon.TONS) {
            val d = DetecteurSousTon(fs)
            val buf = ShortArray(441)
            var ph = 0.0
            var vu = -1.0
            for (b in 0 until 120) {
                for (j in buf.indices) { buf[j] = (300 * 5.2 * sin(ph)).toInt().toShort(); ph += 2 * PI * t / fs }
                d.process(buf, buf.size)
                val e = d.etat
                if (e.present) assertEquals("ton $t", t, e.hz, 0.01)
                if (vu < 0 && e.present) vu = (b + 1) * 0.01
            }
            assertTrue("$t lu à $vu s", vu in 0.0..1.0)
            assertTrue("$t déviation ${d.etat.deviationHz}", abs(d.etat.deviationHz - 300) < 30)
        }
    }

    @Test
    fun le_ton_est_perdu_peu_apres_sa_fin() {
        val d = DetecteurSousTon()
        val buf = ShortArray(441)
        var ph = 0.0
        for (b in 0 until 150) {
            for (j in buf.indices) { buf[j] = (2_000 * sin(ph)).toInt().toShort(); ph += 2 * PI * 88.5 / 44_100 }
            d.process(buf, buf.size)
        }
        assertEquals(88.5, d.etat.hz, 0.01)
        var perdu = -1
        for (b in 0 until 100) {
            buf.fill(0); d.process(buf, buf.size)
            if (perdu < 0 && !d.etat.present) perdu = b
        }
        assertTrue("perdu après ${perdu * 10} ms", perdu in 0..40)
    }

    @Test
    fun le_silencieux_sur_sous_ton_ne_laisse_passer_que_le_bon_ton() {
        fun son(choisi: Double): Long {
            var e = 0L
            val chain = RxChain().apply { mode = RxMode.NFM; toneSquelchHz = choisi }
            fm(2.0, 67.0, 500.0, Voix(5), chain = chain) { s, _, out, p -> if (s > 1.2) for (k in 0 until p) e += abs(out[k].toInt()) }
            return e
        }
        assertTrue("67,0 choisi : du son", son(67.0) > 1_000_000)
        assertEquals("71,9 choisi : silence", 0L, son(71.9))
    }

    @Test
    fun hors_nfm_et_sans_demande_rien_ne_tourne() {
        val chain = RxChain().apply { mode = RxMode.NFM }
        fm(1.5, 67.0, chain = chain) { _, e, _, _ -> assertFalse(e.present) }
        assertFalse(chain.toneSquelchClosed)
        chain.mode = RxMode.USB
        chain.toneSquelchHz = 67.0
        assertFalse("pas de silencieux sur sous-ton en BLU", chain.toneSquelchClosed)
        assertFalse(chain.subTone.present)
    }

    @Test
    fun le_generateur_d_essai_porte_un_sous_ton() {
        val rien = GenerateurIq.Signal(false, 0, 0.0)
        val r = GenerateurIq.Reglages(porteuse = rien, cw = rien, blu = rien, ft8 = rien, sstv = rien).avec(mapOf("fmCtcss" to "88,5")::get)
        assertEquals(88.5, r.fmCtcssHz, 0.0)
        assertTrue(GenerateurIq.ecoutes(r).first { it.cle == "fm" }.nom.endsWith("CTCSS 88,5 Hz"))
        val g = GenerateurIq(r, cadence = false)
        val chain = RxChain().apply { mode = RxMode.NFM; subTones = true; offsetHz = r.fm.decalageHz.toDouble(); bandwidthHz = 12_000.0 }
        val b = ShortArray(2 * 16_384)
        val out = ShortArray(chain.maxAudio(4 * 16_384))
        var vu = -1.0
        var t = 0.0
        while (t < 2.0) {
            val n = g.read16(b)
            chain.process16(b, n, out)
            t += n / 2 / rate
            if (vu < 0 && chain.subTone.present) { assertEquals(88.5, chain.subTone.hz, 0.0); vu = t }
        }
        assertTrue("lu à $vu s", vu in 0.0..1.0)
        assertTrue("déviation ${chain.subTone.deviationHz}", abs(chain.subTone.deviationHz - 500) < 100)
    }

    @Test
    fun la_table_a_cinquante_tons() {
        assertEquals(50, DetecteurSousTon.TONS.size)
        assertEquals(67.0, DetecteurSousTon.normalise(67.04), 0.0)
        assertEquals(0.0, DetecteurSousTon.normalise(150.0), 0.0)
        assertEquals("67,0 Hz", DetecteurSousTon.libelle(67.0))
    }
}
