/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

/** Reception conditions for the test pattern: what they add, and that it decodes. */
class SstvConditionsTest {

    private val rate = 44_100

    /** A steady 1900 Hz tone at the encoder's level, [sec] long. */
    private fun tone(sec: Double) = ShortArray((sec * rate).toInt()) {
        (kotlin.math.sin(2 * Math.PI * 1900.0 * it / rate) * 26000).toInt().toShort()
    }

    private fun run(c: SstvConditions, x: ShortArray, seed: Long = 1L): ShortArray {
        val y = x.copyOf()
        val sim = ReceptionSim(c, rate, y.size, seed)
        var i = 0
        while (i < y.size) {
            val n = minOf(4096, y.size - i)
            val part = y.copyOfRange(i, i + n); sim.apply(part, n); part.copyInto(y, i); i += n
        }
        return y
    }

    /** SNR of [y] against the clean [x] scaled by the headroom, dB. */
    private fun snr(x: ShortArray, y: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0; var n = 0.0
        for (i in from until to) {
            val sig = x[i] * ReceptionSim.HEADROOM
            s += sig * sig; val d = y[i] - sig; n += d * d
        }
        return 10 * log10(s / n)
    }

    @Test
    fun un_signal_propre_n_est_pas_touche() {
        val x = tone(2.0)
        assertArrayEquals(x, run(SstvConditions.CLEAN, x))
    }

    @Test
    fun le_bruit_regle_est_celui_qu_on_mesure() {
        val x = tone(10.0)
        val s = snr(x, run(SstvConditions(snrDb = 12.0), x))
        assertEquals(12.0, s, 1.0)
    }

    @Test
    fun meme_reglage_meme_signal() {
        val x = tone(3.0)
        assertArrayEquals(run(SstvConditions.ISS_TYPICAL, x, 7), run(SstvConditions.ISS_TYPICAL, x, 7))
    }

    @Test
    fun le_passage_est_plus_bruite_a_l_aos_et_a_la_los() {
        val x = tone(20.0); val y = run(SstvConditions(snrDb = 20.0, passProfile = true), x)
        val n = x.size
        val bord = snr(x, y, 0, n / 20); val milieu = snr(x, y, n / 2 - n / 40, n / 2 + n / 40)
        assertTrue("bord $bord dB, milieu $milieu dB", milieu - bord > 4)
    }

    @Test
    fun les_coupures_sont_breves_et_presentes() {
        // A dropout leaves hiss only: the tone disappears. Count 5 ms stretches
        // with almost no 1900 Hz left.
        val x = tone(40.0); val y = run(SstvConditions(snrDb = 30.0, dropouts = true), x)
        val w = rate / 200
        var gaps = 0; var run = 0; var longest = 0
        for (k in 0 until x.size / w) {
            var c = 0.0
            for (i in k * w until (k + 1) * w) c += y[i] * x[i].toDouble()
            val lost = c / w < 0.2 * 26000.0 * 26000.0 * ReceptionSim.HEADROOM / 2
            if (lost) { run++; if (run == 1) gaps++; longest = maxOf(longest, run) } else run = 0
        }
        assertTrue("$gaps coupures en 40 s", gaps in 2..12)
        assertTrue("plus longue ${longest * 5} ms", longest * 5 <= 130)
    }

    @Test
    fun une_mire_pd120_en_passage_iss_typique_se_decode() {
        for (continu in listOf(false, true)) decodeTypique(continu)
    }

    private fun decodeTypique(continu: Boolean) {
        val mode = SstvMode.byName("PD 120")!!
        val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
        val img = IntArray(mode.width * mode.height) { 0xFF shl 24 or bars[(it % mode.width) * 8 / mode.width] }
        val src = SstvEncoder.Source(mode, img, rate)
        val sim = ReceptionSim(SstvConditions.ISS_TYPICAL, rate, src.totalSamples)
        var lines = 0; var complete = false
        val dec = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete2: Boolean) {
                lines = linesDone; complete = complete2
            }
        })
        dec.continuous = continu
        val chunk = ShortArray(4096)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            sim.apply(chunk, n)
            dec.feed(chunk, n)
        }
        dec.finish()
        assertTrue("continu=$continu : $lines/${mode.height} lignes", complete && lines == mode.height)
    }
}
