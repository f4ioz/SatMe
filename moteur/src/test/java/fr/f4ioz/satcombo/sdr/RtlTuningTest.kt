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
import kotlin.math.abs

/**
 * RTL2832U and R820T arithmetic, checked with no dongle attached.
 *
 * There is no RTL-SDR on the build machine. These tests are what separates
 * "the code looks right" from "the registers hold the right values": they
 * replay librtlsdr's computations on cases with known answers.
 */
class RtlTuningTest {

    // ------------------------------------------------------------- sample rates

    @Test
    fun rejette_les_debits_impossibles() {
        assertFalse(RtlTuning.rateSupported(200_000))
        assertFalse(RtlTuning.rateSupported(500_000))   // gap 300 k – 900 k
        assertFalse(RtlTuning.rateSupported(900_000))
        assertFalse(RtlTuning.rateSupported(3_300_000))
        assertTrue(RtlTuning.rateSupported(250_000))
        assertTrue(RtlTuning.rateSupported(1_058_400))
        assertTrue(RtlTuning.rateSupported(2_400_000))
    }

    @Test
    fun le_debit_reel_colle_au_debit_demande() {
        // SatMe's rate is 44100 × 24, so integer decimation lands exactly on
        // the SSTV engine's audio rate.
        val real = RtlTuning.actualRate(Dsp.RTL_RATE)
        assertTrue("débit réel $real trop loin de ${Dsp.RTL_RATE}",
            abs(real - Dsp.RTL_RATE) < 1.0)
    }

    @Test
    fun les_deux_bits_de_poids_faible_du_rapport_sont_nuls() {
        for (rate in intArrayOf(250_000, 1_024_000, 1_058_400, 2_048_000, 2_400_000)) {
            assertEquals(0, RtlTuning.resampRatio(rate) and 0x3)
        }
    }

    // ------------------------------------------------------- IF and ppm

    @Test
    fun la_fi_du_r820t_est_encodee_sur_22_bits_negatifs() {
        val r = RtlTuning.ifFreqRegs(RtlTuning.IF_FREQ)
        assertEquals(3, r.size)
        // The three bytes form a signed 22-bit integer.
        var v = (r[0] shl 16) or (r[1] shl 8) or r[2]
        if (v and 0x200000 != 0) v -= 0x400000
        val hz = -v.toDouble() * RtlTuning.XTAL / 4_194_304.0
        // Resolution is xtal / 2^22 ≈ 6.9 Hz: the best possible, and harmless
        // on a 3.57 MHz IF that the DDC then brings to zero.
        val pas = RtlTuning.XTAL / 4_194_304.0
        assertTrue("FI reconstruite $hz", abs(hz - RtlTuning.IF_FREQ) < pas)
        r.forEach { assertTrue(it in 0..255) }
        assertTrue(r[0] <= 0x3f)
    }

    @Test
    fun une_correction_nulle_ne_change_rien() {
        val r = RtlTuning.freqCorrectionRegs(0)
        assertEquals(0, r[0])
        assertEquals(0, r[1])
    }

    @Test
    fun la_correction_ppm_change_de_signe() {
        val plus = RtlTuning.freqCorrectionRegs(50)
        val moins = RtlTuning.freqCorrectionRegs(-50)
        // +50 ppm gives a negative offset, hence "high" bytes.
        assertTrue(plus[1] != moins[1])
        plus.forEach { assertTrue(it in 0..255) }
        moins.forEach { assertTrue(it in 0..255) }
        assertTrue(plus[1] <= 0x3f && moins[1] <= 0x3f)
    }

    // ---------------------------------------------------------------- FIR

    @Test
    fun le_filtre_dentree_tient_en_vingt_octets() {
        val p = RtlTuning.packFir()
        assertEquals(20, p.size)
        // The first eight coefficients are copied as is.
        for (i in 0 until 8) {
            assertEquals(RtlTuning.FIR_DEFAULT[i], p[i].toInt())
        }
        // The next eight are read back from the packed 12-bit values.
        for (j in 0 until 8 step 2) {
            val b0 = p[8 + j * 3 / 2].toInt() and 0xff
            val b1 = p[8 + j * 3 / 2 + 1].toInt() and 0xff
            val b2 = p[8 + j * 3 / 2 + 2].toInt() and 0xff
            val v0 = (b0 shl 4) or (b1 shr 4)
            val v1 = ((b1 and 0x0f) shl 8) or b2
            assertEquals(RtlTuning.FIR_DEFAULT[8 + j], sign12(v0))
            assertEquals(RtlTuning.FIR_DEFAULT[8 + j + 1], sign12(v1))
        }
    }

    private fun sign12(v: Int): Int = if (v and 0x800 != 0) v - 0x1000 else v

    // ------------------------------------------------------ tuning filter

    @Test
    fun la_table_de_filtres_choisit_la_bonne_tranche() {
        // 145.8 MHz + IF = 149.37 MHz: the 140 MHz range.
        assertEquals(140, RtlTuning.muxRange(149_370_000L).fromMHz)
        // 435 MHz + IF: the 310 MHz range.
        assertEquals(310, RtlTuning.muxRange(438_570_000L).fromMHz)
        // Very low: first range.
        assertEquals(0, RtlTuning.muxRange(10_000_000L).fromMHz)
        // Very high: last range.
        assertEquals(588, RtlTuning.muxRange(900_000_000L).fromMHz)
    }

    @Test
    fun la_table_est_ordonnee() {
        for (i in 1 until RtlTuning.MUX_RANGES.size) {
            assertTrue(RtlTuning.MUX_RANGES[i].fromMHz > RtlTuning.MUX_RANGES[i - 1].fromMHz)
        }
    }

    // ---------------------------------------------------------------- PLL

    @Test
    fun la_pll_accroche_la_sstv_de_liss() {
        // 145.800 MHz rest frequency plus the 3.57 MHz IF.
        val plan = RtlTuning.pllPlan(145_800_000L + RtlTuning.IF_FREQ)
        assertTrue("PLL non accrochable", plan.lockable)
        assertEquals(16, plan.mixDiv)
        assertEquals(3, plan.divNum)
        assertEquals(41, plan.nint)
        val err = abs(plan.achievedHz - (145_800_000L + RtlTuning.IF_FREQ))
        assertTrue("erreur de synthèse $err Hz", err < 500)
    }

    @Test
    fun la_pll_couvre_la_bande_uhf_satellite() {
        var hz = 435_000_000L
        while (hz <= 438_000_000L) {
            val lo = hz + RtlTuning.IF_FREQ
            val plan = RtlTuning.pllPlan(lo)
            assertTrue("non accrochable à $hz", plan.lockable)
            val err = abs(plan.achievedHz - lo)
            assertTrue("erreur $err Hz à $hz", err < 1000)
            hz += 100_000L
        }
    }

    @Test
    fun la_pll_couvre_la_bande_vhf_satellite() {
        var hz = 144_000_000L
        while (hz <= 146_000_000L) {
            val lo = hz + RtlTuning.IF_FREQ
            val plan = RtlTuning.pllPlan(lo)
            assertTrue("non accrochable à $hz", plan.lockable)
            val err = abs(plan.achievedHz - lo)
            assertTrue("erreur $err Hz à $hz", err < 1000)
            hz += 100_000L
        }
    }

    @Test
    fun le_pas_doppler_est_suivi_par_la_pll() {
        // ISS Doppler on VHF is about ±3.5 kHz. Two frequencies 3.5 kHz apart
        // must give two different plans, or the correction would be useless.
        val a = RtlTuning.pllPlan(149_370_000L)
        val b = RtlTuning.pllPlan(149_373_500L)
        assertTrue(a.achievedHz != b.achievedHz)
        assertTrue(abs((b.achievedHz - a.achievedHz) - 3500L) < 400L)
    }

    @Test
    fun le_diviseur_suit_laccord_fin_du_vco() {
        val ref = RtlTuning.pllPlan(149_370_000L, vcoFineTune = 2)
        val haut = RtlTuning.pllPlan(149_370_000L, vcoFineTune = 3)
        val bas = RtlTuning.pllPlan(149_370_000L, vcoFineTune = 1)
        assertEquals(ref.divNum - 1, haut.divNum)
        assertEquals(ref.divNum + 1, bas.divNum)
    }

    // --------------------------------------------------------------- gain

    @Test
    fun le_gain_nul_ne_leve_aucun_cran() {
        val (lna, mix) = RtlTuning.gainSplit(0)
        assertEquals(0, lna)
        assertEquals(0, mix)
    }

    @Test
    fun le_gain_maximal_sature_les_deux_etages() {
        val (lna, mix) = RtlTuning.gainSplit(500)
        assertTrue(lna in 0..15)
        assertTrue(mix in 0..15)
        assertTrue("gain maximal trop faible : lna=$lna mix=$mix", lna >= 14)
    }

    @Test
    fun le_gain_est_monotone() {
        var prev = -1
        for (g in RtlTuning.GAINS) {
            val (lna, mix) = RtlTuning.gainSplit(g)
            val crans = lna + mix
            assertTrue("gain $g : $crans crans après $prev", crans >= prev)
            prev = crans
            assertTrue(lna in 0..15)
            assertTrue(mix in 0..15)
        }
    }

    @Test
    fun tous_les_gains_annonces_sont_atteignables() {
        for (g in RtlTuning.GAINS) {
            val (lna, mix) = RtlTuning.gainSplit(g)
            var total = 0
            for (i in 1..lna) total += RtlTuning.LNA_STEPS[i]
            for (i in 1..mix) total += RtlTuning.MIXER_STEPS[i]
            // The split stacks steps until it exceeds the target: equality is
            // not required, only not stopping short.
            assertTrue("gain $g atteint seulement $total", total >= g || (lna == 15))
        }
    }
}
