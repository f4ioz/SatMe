/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.CalibrationFrequence
import fr.f4ioz.satcombo.sdr.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class CalibrationFrequenceTest {

    /** A tone at [toneHz] in noise, sampled at [fs], as the page's spectrum: dB per bin, centre in the middle. */
    private fun spectre(toneHz: Double, fs: Double, n: Int = 4096, snrLin: Double = 0.05, graine: Long = 7): FloatArray {
        val r = java.util.Random(graine)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (k in 0 until n) {
            // Hann window: the tone's skirt stays near it.
            val w = 0.5 - 0.5 * cos(2 * PI * k / n)
            val ph = 2 * PI * toneHz * k / fs
            re[k] = w * (cos(ph) + snrLin * r.nextGaussian())
            im[k] = w * (sin(ph) + snrLin * r.nextGaussian())
        }
        Fft.transform(re, im)
        return FloatArray(n) { i ->
            val j = (i + n / 2) % n   // FFT shift: bin n/2 is DC
            (10 * log10(re[j] * re[j] + im[j] * im[j] + 1e-20)).toFloat()
        }
    }

    @Test fun `peak found within a tenth of a bin`() {
        val fs = 176_400.0; val n = 4096; val bin = fs / n
        for (tone in listOf(-31_234.5, 0.0, 12_345.6, 67_708.0 - 50_000.0)) {
            val p = CalibrationFrequence.pic(spectre(tone, fs, n), bin, tone + 800.0, 3_000.0)
            assertNotNull(p); p!!
            assertEquals("ton $tone", tone, p.decalageHz, bin * 0.15)
            assertTrue(p.snrDb > 20)
        }
    }

    @Test fun `window keeps a stronger signal outside out`() {
        val fs = 176_400.0; val n = 4096; val bin = fs / n
        val s = spectre(10_000.0, fs, n)
        // Searched around -20 kHz: only noise there.
        val p = CalibrationFrequence.pic(s, bin, -20_000.0, 2_000.0)
        assertTrue(p == null || p.snrDb < CalibrationFrequence.SNR_MIN_DB + 10)
        assertNull(CalibrationFrequence.pic(s, bin, 1e6, 100.0))
        assertNull(CalibrationFrequence.pic(FloatArray(512) { -90f }, bin, 0.0, 5_000.0))
    }

    @Test fun `two lobes give their middle`() {
        val fs = 176_400.0; val n = 4096; val bin = fs / n
        val a = spectre(5_000.0 - 200.0, fs, n, graine = 1)
        val b = spectre(5_000.0 + 200.0, fs, n, graine = 2)
        // Two tones of the same level, as the PSK beacon's two lobes.
        val s = FloatArray(n) { maxOf(a[it], b[it]) }
        val p = CalibrationFrequence.picDouble(s, bin, 4_000.0, 5_000.0)!!
        assertEquals(5_000.0, p.decalageHz, bin * 0.3)
        // A lone tone stays where it is.
        assertEquals(-2_000.0, CalibrationFrequence.picDouble(spectre(-2_000.0, fs, n), bin, -2_000.0, 3_000.0)!!.decalageHz, bin * 0.15)
    }

    @Test fun `ppm of a dongle`() {
        // A crystal 30 ppm fast: at 939.4 MHz the FCCH shows 28.18 kHz low.
        val f = CalibrationFrequence.fcchHz(22)!!.toDouble()
        assertEquals(939_467_708.0, f, 0.5)
        val ecart = -30e-6 * f
        assertEquals(30.0, CalibrationFrequence.ppmCle(0, ecart, f), 1e-6)
        // Already corrected by 25: the remaining 5 ppm add.
        assertEquals(30, CalibrationFrequence.ppmCleEntier(25, -5e-6 * f, f))
        assertEquals(CalibrationFrequence.PPM_CLE_MAX, CalibrationFrequence.ppmCleEntier(190, -50e-6 * f, f))
        assertEquals(-2.0, CalibrationFrequence.ppm(-1_478.0, 739e6), 1e-9)
    }

    @Test fun `GSM channels`() {
        assertEquals(935_000_000L, CalibrationFrequence.gsm900Hz(0))
        assertEquals(959_800_000L, CalibrationFrequence.gsm900Hz(124))
        assertEquals(925_200_000L, CalibrationFrequence.gsm900Hz(975))
        assertEquals(1_805_200_000L + CalibrationFrequence.FCCH_DECALAGE_HZ, CalibrationFrequence.fcchHz(512))
        assertNull(CalibrationFrequence.fcchHz(300))
    }

    @Test fun `real LNB oscillator, both injection sides`() {
        // LNB 26 kHz low: the beacon shows 26 kHz high on the nominal axis.
        assertEquals(9_749_974_000L, CalibrationFrequence.olReel(9_750_000_000L, 26_000.0))
        val vraiOl = 9_749_974_000L
        val fi = 10_489_750_000L - vraiOl
        val montre = fi + 9_750_000_000L
        assertEquals(vraiOl, CalibrationFrequence.olReel(9_750_000_000L, (montre - 10_489_750_000L).toDouble()))
        // High side: IF = LO − RF.
        val olHaut = 11_250_000_000L; val vraiHaut = 11_250_012_000L
        val fiH = vraiHaut - 10_489_750_000L
        val montreH = olHaut - fiH
        assertEquals(vraiHaut, CalibrationFrequence.olReel(olHaut, (montreH - 10_489_750_000L).toDouble()))
        assertTrue(CalibrationFrequence.olCredible(9_750_000_000L, vraiOl))
        assertTrue(!CalibrationFrequence.olCredible(9_750_000_000L, 9_700_000_000L))
    }
}
