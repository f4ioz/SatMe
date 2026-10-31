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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The BB60 source against a fake API ([FauxBb60]): rates, tuning, levels, losses, errors. No library, no device. */
class Bb60Test {

    private fun attends(ms: Long = 5_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { if (System.currentTimeMillis() > fin) fail("trop long"); Thread.sleep(10) }
    }

    /** The tone's frequency (Hz) in [n] values of [b] at [fs]: the mean phase step. */
    private fun frequence(b: ShortArray, n: Int, fs: Double): Double {
        var s = 0.0
        for (k in 1 until n / 2) {
            val a = atan2(b[2 * k + 1].toDouble(), b[2 * k].toDouble()) - atan2(b[2 * k - 1].toDouble(), b[2 * k - 2].toDouble())
            s += Math.IEEEremainder(a, 2 * PI)
        }
        return s / (n / 2 - 1) / (2 * PI) * fs
    }

    @Test
    fun les_debits_du_bb60_et_ceux_qui_se_reechantillonnent() {
        val rtl = Bb60.plan(Dsp.RTL_RATE)
        assertEquals(16, rtl.decimation)
        assertEquals(2_500_000.0, rtl.natifHz, 0.0)
        assertEquals(0.9 * Dsp.RTL_RATE, rtl.bandeHz, 1.0)
        assertTrue(rtl.reechantillonne)
        // Its own rates: as they come, the widest filter.
        val exact = Bb60.plan(2_500_000)
        assertFalse(exact.reechantillonne); assertEquals(2e6, exact.bandeHz, 0.0)
        assertEquals(1, Bb60.plan(40_000_000).decimation)
        assertEquals(27e6, Bb60.plan(40_000_000).bandeHz, 0.0)
        // The DATV spectrum's 2.4 MS/s: from 5 MS/s, its 3.75 MHz filter narrowed to 2.16.
        val datv = Bb60.plan(2_400_000)
        assertEquals(8, datv.decimation); assertEquals(2.16e6, datv.bandeHz, 1.0)
        // Above 20 MS/s: from 40 (no faster rate).
        assertEquals(1, Bb60.plan(30_000_000).decimation)
        // Never below the slowest.
        assertEquals(8192, Bb60.plan(1_000).decimation)
    }

    @Test
    fun le_reechantillonneur_garde_la_frequence_et_le_niveau() {
        val fe = 2_500_000.0; val fs = Dsp.RTL_RATE.toDouble()
        val r = ReechantillonneurIq(fe, fs)
        val tous = ArrayList<Short>()
        var ph = 0.0
        // Blocks of uneven sizes: the history carried across them.
        for (taille in intArrayOf(5000, 3, 777, 20000, 1, 12345)) {
            val e = ShortArray(2 * taille)
            for (k in 0 until taille) { e[2 * k] = (10000 * cos(ph)).roundToInt().toShort(); e[2 * k + 1] = (10000 * sin(ph)).roundToInt().toShort(); ph += 2 * PI * 150_000 / fe }
            val s = ShortArray(r.maxSortie(e.size))
            val n = r.traite(e, e.size, s)
            assertEquals(0, n % 2)
            for (k in 0 until n) tous += s[k]
        }
        val total = 5000 + 3 + 777 + 20000 + 1 + 12345
        assertEquals(total * fs / fe, tous.size / 2.0, 4.0)
        val b = tous.toShortArray()
        assertEquals(150_000.0, frequence(b, b.size, fs), 50.0)
        for (k in 10 until b.size / 2) assertEquals(10000.0, hypot(b[2 * k].toDouble(), b[2 * k + 1].toDouble()), 150.0)
    }

    @Test
    fun il_recoit_au_debit_de_la_reception_le_ton_ou_il_est() {
        val f = FauxBb60()
        val s = Bb60Sdr.ouvre(f, reglages = Bb60Sdr.Reglages(referenceDbm = -40.0))
        try {
            assertTrue(s.iq16)
            assertEquals(Dsp.RTL_RATE.toDouble(), s.setSampleRate(Dsp.RTL_RATE), 0.0)
            // Nothing streams before a centre is asked.
            assertEquals(0, s.read16(ShortArray(16_384), 100))
            assertFalse(f.appels.contains("initie"))
            s.setCenterFreq(437_800_000L)
            val b = ShortArray(16_384)
            var n = 0
            attends { n = s.read16(b, 200); n > 0 }
            assertEquals(16_384, n)
            assertEquals(100_000.0, frequence(b, n, Dsp.RTL_RATE.toDouble()), 50.0)
            assertTrue(f.appels.containsAll(listOf("iq16", "reference(-40.0)", "gainAtten(-1,-1)", "centre(437800000)", "iq(16,952560)", "initie")))
            assertEquals("BB60D", s.appareil.modele)
            assertEquals(1_234_567L, s.appareil.serie)
            assertTrue(s.description, s.description.contains("2,500 MS/s → 1,058 MS/s"))
            attends { s.diagnostic != null }
            assertEquals(41.5f, s.diagnostic!!.temperatureC, 0f)
            assertTrue(s.horodatageNs > 0)
            assertTrue(s.octets > 0)
        } finally { s.close() }
        // Closed: the stream stopped, the device closed.
        attends { f.appels.contains("ferme(1)") }
        assertEquals(-1, s.read16(ShortArray(16), 10))
    }

    @Test
    fun un_nouvel_accord_relance_le_flux_avec_l_erreur_du_quartz() {
        val f = FauxBb60()
        val s = Bb60Sdr.ouvre(f)
        try {
            s.setSampleRate(Dsp.RTL_RATE)
            s.setCenterFreq(145_000_000L)
            attends { s.read16(ShortArray(16_384), 200) > 0 }
            s.setFreqCorrection(10)
            s.setCenterFreq(145_100_000L)
            attends { f.appels.contains("centre(" + Math.round(145_100_000 / (1 + 10e-6)) + ")") }
            attends { f.appels.count { it == "initie" } >= 2 }
            // The same centre again: nothing redone.
            val avant = f.appels.count { it == "initie" }
            s.setCenterFreq(145_100_000L)
            Thread.sleep(100)
            assertEquals(avant, f.appels.count { it == "initie" })
            // Out of its range: said, not sent.
            s.setCenterFreq(7_000_000_000L)
            attends { s.avertissement.contains("hors de la gamme") }
            assertFalse(f.appels.any { it.startsWith("centre(69999") })
            // The gain and attenuation forced: both sent.
            s.reglages = Bb60Sdr.Reglages(referenceDbm = -20.0, gain = 2, attenuation = 1)
            s.setCenterFreq(1_296_000_000L)
            attends { f.appels.contains("gainAtten(2,1)") }
        } finally { s.close() }
    }

    @Test
    fun debranche_il_attend_puis_reprend_seul() {
        val f = FauxBb60()
        val s = Bb60Sdr.ouvre(f, repriseMaxMs = 200L)
        try {
            s.setSampleRate(Dsp.RTL_RATE); s.setCenterFreq(437_000_000L)
            attends { s.read16(ShortArray(16_384), 200) > 0 }
            f.debranche = true
            attends { s.avertissement.contains("reconnexion") || s.avertissement.contains("nouvel essai") }
            assertNull(s.erreur)
            // Meanwhile nothing comes, without an error: the reception waits.
            Thread.sleep(300)
            while (s.read16(ShortArray(16_384), 10) > 0) {}
            assertEquals(0, s.read16(ShortArray(16_384), 50))
            assertTrue(s.ouvert)
            f.debranche = false
            attends(8_000) { s.reprises == 1 && s.read16(ShortArray(16_384), 100) > 0 }
            assertEquals("", s.avertissement)
            // Opened again on the same settings: the centre sent again.
            assertTrue(f.appels.count { it == "centre(437000000)" } >= 2)
            assertTrue(f.ouverts >= 2)
        } finally { s.close() }
    }

    @Test
    fun une_erreur_qui_n_est_pas_une_perte_arrete_la_reception() {
        val f = FauxBb60()
        val s = Bb60Sdr.ouvre(f)
        s.setSampleRate(Dsp.RTL_RATE); s.setCenterFreq(437_000_000L)
        attends { s.read16(ShortArray(16_384), 200) > 0 }
        f.prochaineLecture = Bb60.BANDE
        attends { s.erreur != null }
        assertTrue(s.erreur!!, s.erreur!!.contains("bande I/Q refusée"))
        attends { s.read16(ShortArray(16_384), 20) < 0 }
        attends { f.appels.contains("ferme(1)") }
    }

    @Test
    fun pertes_saturation_et_tension_basse_se_comptent() {
        val f = FauxBb60()
        f.tension = 4.2f
        val s = Bb60Sdr.ouvre(f)
        try {
            s.setSampleRate(2_500_000); s.setCenterFreq(10_489_750_000L - 9_750_000_000L)
            attends { s.read16(ShortArray(16_384), 200) > 0 }
            f.perte = true
            attends { s.pertesApi > 0 }
            f.perte = false
            f.prochaineLecture = Bb60.SATURATION_CAN
            attends { s.saturations == 1L }
            attends { s.avertissement.contains("tension USB basse") }
            assertTrue(s.perdus >= s.pertesApi)
        } finally { s.close() }
    }

    @Test
    fun aucun_bb60_branche_est_dit_en_francais() {
        val f = FauxBb60(presents = 0)
        try { Bb60Sdr.ouvre(f); fail("ouvert sans appareil") } catch (x: IOException) {
            assertTrue(x.message!!, x.message!!.startsWith("aucun BB60 trouvé"))
        }
        assertTrue(Bb60.message(Bb60.CONNEXION, "x").contains("débranché"))
        assertTrue(Bb60.perdu(Bb60.CONNEXION) && Bb60.perdu(Bb60.TRAMES) && !Bb60.perdu(Bb60.FREQUENCE))
    }

    @Test
    fun lu_en_octets_pour_qui_n_a_pas_16_bits() {
        val f = FauxBb60(amplitude = 32_000)
        val s = Bb60Sdr.ouvre(f)
        try {
            s.setSampleRate(2_500_000); s.setCenterFreq(437_000_000L)
            val b = ByteArray(8192)
            var n = 0
            attends { n = s.read(b, 200); n > 0 }
            assertEquals(8192, n)
            // Full scale ±32000 → bytes spread around 128.
            val v = b.take(n).map { it.toInt() and 0xFF }
            assertTrue(v.max() > 230 && v.min() < 25)
        } finally { s.close() }
    }
}
