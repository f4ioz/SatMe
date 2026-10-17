/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The transmitters' I/Q correction: its maths, its keeping, and the closed loop on a simulated bench. */
class CorrectionIqTest {
    private val fs = 2_000_000.0

    /** A transmitter's impairment applied to [x] (full scale 1 in, shorts out), as a real one would add it. */
    private fun abime(x: ShortArray, n: Int, m: Array<DoubleArray>, dc: DoubleArray): ShortArray {
        val y = ShortArray(2 * n)
        for (k in 0 until n) {
            val i = x[2 * k] / 32767.0; val q = x[2 * k + 1] / 32767.0
            y[2 * k] = Math.round((m[0][0] * i + m[0][1] * q + dc[0]) * 32767).toInt().toShort()
            y[2 * k + 1] = Math.round((m[1][0] * i + m[1][1] * q + dc[1]) * 32767).toInt().toShort()
        }
        return y
    }

    /** A gain and phase imbalance shared between the two paths (not the correction's own shape). */
    private fun desequilibre(eps: Double, thetaDeg: Double): Array<DoubleArray> {
        val h = Math.toRadians(thetaDeg) / 2
        return arrayOf(doubleArrayOf((1 + eps) * cos(h), (1 + eps) * sin(h)), doubleArrayOf((1 - eps) * sin(h), (1 - eps) * cos(h)))
    }

    private fun analyse(y: ShortArray, n: Int, ton: Double) = AnalyseTonIq(fs).analyse(y, n, ton, 0.0, -ton)!!

    @Test
    fun neutre_rien_ne_change() {
        val c = CorrectionIq("pluto", "1")
        val e = EtageIq(c, 1_245_000_000L, 2_000_000L)
        assertEquals(1_245_000_000L, e.loHz)
        val b = ShortArray(8192) { (it * 37 % 20000 - 10000).toShort() }
        val avant = b.copyOf()
        e.applique(b)
        assertArrayEquals(avant, b)
        // A ppm alone moves the LO, not the samples.
        val p = EtageIq(CorrectionIq("pluto", "1", ParametresIq(ppm = 10.0)), 1_245_000_000L, 2_000_000L)
        assertEquals(Math.round(1_245_000_000 / (1 + 1e-5)), p.loHz)
        p.applique(b)
        assertArrayEquals(avant, b)
    }

    @Test
    fun desequilibre_connu_compense_sous_moins_50_dBc() {
        val n = 65_536
        val ton = fs / 16
        val m = desequilibre(0.05, 4.0)
        val dc = doubleArrayOf(0.012, -0.008)
        val x = ShortArray(2 * n).also { TonIq(ton, fs).remplis(it) }
        val brut = analyse(abime(x, n, m, dc), n, ton)
        assertTrue("fuite " + brut.fuiteDbc, brut.fuiteDbc > -40)
        assertTrue("image " + brut.imageDbc, brut.imageDbc > -40)
        val p = CompensationIq.pour(m, dc)
        val c = x.copyOf().also { EtageIq.corrige(it, n, p) }
        val r = analyse(abime(c, n, m, dc), n, ton)
        assertTrue("fuite " + r.fuiteDbc, r.fuiteDbc < -50)
        assertTrue("image " + r.imageDbc, r.imageDbc < -50)
        // And the correction's own shape undone by itself, near enough.
        val q = CompensationIq.pour(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)), doubleArrayOf(0.0, 0.0))
        assertTrue(q.iqNeutre || (Math.abs(q.gain) < 1e-12 && Math.abs(q.phaseDeg) < 1e-9 && Math.abs(q.dcI) < 1e-12))
    }

    @Test
    fun ppm_change_pendant_l_emission_par_un_melange() {
        val c = CorrectionIq("hackrf", "a", ParametresIq(ppm = 2.0))
        val f = 1_245_000_000L
        val e = EtageIq(c, f, fs.toLong())
        c.parametres = c.parametres.copy(ppm = 3.0)
        val n = 65_536
        val ton = 250_000.0
        val x = ShortArray(2 * n).also { TonIq(ton, fs).remplis(it) }
        e.applique(x)
        // One more ppm: the signal 1245 Hz lower, the LO unchanged.
        val r = AnalyseTonIq(fs).analyse(x, n, ton, 0.0, -ton)!!
        assertEquals(-1245.0, r.ecartHz, 5.0)
    }

    @Test
    fun gardee_par_appareil_et_numero() {
        val s = StockageMemoire()
        val a = CorrectionsIq(s)
        assertTrue(a.pour("pluto", "104473").parametres.neutre)
        assertNull(a.aAppliquer("hackrf", ""))
        val p = ParametresIq(0.0123, -0.0045, 0.031, -1.25, 4.5)
        a.enregistre("pluto", "104473", p)
        a.enregistre("hackrf", "A06063c8", ParametresIq(dcI = 0.001))
        val b = CorrectionsIq(s)
        assertEquals(p, b.pour("pluto", "104473").parametres)
        assertTrue(b.pour("pluto", "999").parametres.neutre)
        assertSame(b.pour("hackrf", "a06063c8"), b.aAppliquer("hackrf", ""))
        assertEquals(0.001, b.aAppliquer("hackrf", "")!!.parametres.dcI, 0.0)
        assertEquals(listOf("hackrf" to "a06063c8", "pluto" to "104473"), b.connues())
        // Bounds, and a text that is not one.
        assertEquals(ParametresIq.DC_MAX, ParametresIq(dcI = 5.0).borne().dcI, 0.0)
        assertEquals(0.0, ParametresIq(gain = Double.NaN).borne().gain, 0.0)
        assertNull(ParametresIq.lis("1;2"))
        assertEquals(p, ParametresIq.lis(p.texte()))
    }

    @Test
    fun boucle_fermee_converge_sur_un_banc_simule() {
        val c = CorrectionIq("pluto", "1")
        val canal = CanalIqSimule(c, bruit = 1e-4)
        val banc = BancIq(c, canal, fs, 200_000.0, 400_000.0, attenteMs = 0, paires = 32_768, dort = {})
        val cal = CalibrationIq(banc::mesure)
        val r = cal.lance(ParametresIq.NEUTRE)
        assertNotNull(r)
        r!!
        assertTrue("avant " + r.avant.fuiteDbc, r.avant.fuiteDbc > -40)
        assertTrue("fuite " + r.apres.fuiteDbc, r.apres.fuiteDbc < -50)
        assertTrue("image " + r.apres.imageDbc, r.apres.imageDbc < -50)
        // The crystal's error read from the tone: 3.2 ppm at 1245 MHz.
        assertEquals(3.2, r.apres.ecartHz / 1_245_200_000.0 * 1e6, 0.02)
        assertTrue(r.mesures < 60)
    }
}
