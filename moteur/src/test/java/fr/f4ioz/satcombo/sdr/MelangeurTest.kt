/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.datv.DecalageHackrf
import fr.f4ioz.satcombo.datv.ReglesEmission
import fr.f4ioz.satcombo.datv.s2.Modcod
import fr.f4ioz.satcombo.datv.s2.ModulateurDvbs2
import fr.f4ioz.satcombo.datv.s2.Rolloff
import fr.f4ioz.satcombo.datv.s2.SourceTs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** The HackRF's LO beside the signal: the plan and the baseband shift. */
class MelangeurTest {

    @Test
    fun le_plan_du_decalage() {
        // 333 kS/s, roll-off 0.25: 416 kHz occupied, 500 kHz shift (0.6 × 416 is less), 7 samples per symbol as before.
        val p = DecalageHackrf.plan(1_255_000L, 333, 0.25, interieur = true)!!
        assertEquals(500_000L, p.decalageHz)
        assertEquals(1_254_500_000L, p.loHz)
        assertEquals(7, p.sps)
        assertEquals(2_331_000L, p.debit)
        assertEquals(1_416_250L, p.bandeHz)
        assertEquals(1_750_000L, HackrfEmission.filtre(p.bandeHz))
        // 1500 kS/s, 0.35: 2025 kHz occupied, 1215 kHz shift, the rate raised to 4 per symbol (6 Méch/s).
        val q = DecalageHackrf.plan(1_255_000L, 1500, 0.35, interieur = true)!!
        assertEquals(1_215_000L, q.decalageHz)
        assertEquals(4, q.sps)
        assertTrue(q.debit / 2 >= q.decalageHz + 2_025_000 / 2)
        // Near the bottom of 23 cm: the LO goes above the signal (its image too stays in the band).
        val r = DecalageHackrf.plan(1_240_500L, 333, 0.25, interieur = true)!!
        assertEquals(1_241_000_000L, r.loHz)
        assertEquals(-500_000L, r.decalageHz)
        // Into a dummy load: any frequency the HackRF tunes to.
        assertNotNull(DecalageHackrf.plan(433_000L, 333, 0.25, interieur = false))
        assertNull(DecalageHackrf.refus(1_255_000L, 2000, 0.35, interieur = true))
        assertTrue(DecalageHackrf.plan(1_255_000L, 2000, 0.35, true)!!.debit <= DecalageHackrf.DEBIT_MAX)
        assertTrue(DecalageHackrf.sps(125, 0.35, DecalageHackrf.decalageHz(125, 0.35)) >= ReglesEmission.sps(125))
    }

    @Test
    fun un_ton_deplace_de_500_khz() {
        val fs = 2_331_000.0
        val n = 4096
        val b = ShortArray(2 * n) { if (it % 2 == 0) 10_000 else 0 }
        val m = Melangeur(500_000.0, fs)
        // In two buffers: the phase carries on.
        val a1 = b.copyOfRange(0, n); val a2 = b.copyOfRange(n, 2 * n)
        m.applique(a1); m.applique(a2)
        val x = a1 + a2
        val s = spectre(x, n)
        val pic = s.indices.maxBy { s[it] }
        assertEquals(500_000.0, frequence(pic, n, fs), fs / n * 1.5)
        // Amplitude kept.
        for (k in 0 until n) assertEquals(10_000.0, Math.hypot(x[2 * k].toDouble(), x[2 * k + 1].toDouble()), 2.0)
    }

    @Test
    fun spectre_signal_sur_sa_frequence_fuite_hors_de_la_bande() {
        val p = DecalageHackrf.plan(1_255_000L, 333, 0.25, true)!!
        val mod = ModulateurDvbs2(Modcod.de("QPSK 2/3", false, true)!!, Rolloff.A25, p.sps, Aleatoire())
        val mel = Melangeur(p.decalageHz.toDouble(), p.debit.toDouble())
        val n = 4096
        val trames = 40
        val acc = DoubleArray(n)
        val b = ShortArray(2 * n)
        val i8 = ByteArray(2 * n)
        repeat(4) { mod.remplis(b, n) }   // the filter filled
        repeat(trames) {
            mod.remplis(b, n); mel.applique(b)
            HackrfEmission.versInt8(b, i8, b.size)
            // The HackRF's DC offset: a carrier at the LO, the centre of its baseband.
            val x = ShortArray(2 * n) { ((i8[it] + 3) * 256).toShort() }
            val s = spectre(x, n)
            for (k in 0 until n) acc[k] += s[k]
        }
        val fs = p.debit.toDouble()
        val occ = DecalageHackrf.occupeHz(333, 0.25)
        val dansBande = { f: Double -> abs(f - p.decalageHz) <= occ / 2 }
        // The signal's centre of power: on the wanted frequency (the shift), within a bin or two.
        var pt = 0.0; var pf = 0.0; var dedans = 0.0
        for (k in 0 until n) {
            val f = frequence(k, n, fs)
            if (k == 0) continue
            pt += acc[k]; pf += acc[k] * f
            if (dansBande(f)) dedans += acc[k]
        }
        assertEquals(p.decalageHz.toDouble(), pf / pt, 5_000.0)
        assertTrue("puissance dans la bande occupée ${dedans / pt}", dedans / pt > 0.97)
        // The LO line (bin 0): outside the occupied band, well clear of its edge, and standing out above what is around it.
        assertTrue(!dansBande(0.0))
        assertTrue(p.decalageHz - occ / 2 >= 250_000)
        val autour = (20..40).map { acc[it] }.average()
        assertTrue("raie de l'OL ${db(acc[0] / autour)} dB", db(acc[0] / autour) > 20)
        // And the band's centre stands far above the LO's surroundings: the leakage no longer sits in the signal.
        val centre = (-5..5).map { acc[bin(p.decalageHz.toDouble() + it * fs / n, n, fs)] }.average()
        assertTrue("signal ${db(centre / autour)} dB au-dessus", db(centre / autour) > 20)
    }

    private class Aleatoire : SourceTs {
        val r = java.util.Random(3)
        override fun disponibles() = 1000
        override fun lis(dst: ByteArray) { r.nextBytes(dst); dst[0] = 0x47; dst[1] = 0x01; dst[2] = 0x00 }
    }

    private fun db(x: Double) = 10 * log10(x)

    private fun frequence(k: Int, n: Int, fs: Double) = (if (k < n / 2) k else k - n) * fs / n

    private fun bin(f: Double, n: Int, fs: Double) = ((f / fs * n).roundToInt() + n) % n

    /** |FFT|² of [n] interleaved pairs, Hann window. */
    private fun spectre(x: ShortArray, n: Int): DoubleArray {
        val re = DoubleArray(n) { x[2 * it] * (0.5 - 0.5 * Math.cos(2 * Math.PI * it / n)) }
        val im = DoubleArray(n) { x[2 * it + 1] * (0.5 - 0.5 * Math.cos(2 * Math.PI * it / n)) }
        Fft.transform(re, im)
        return DoubleArray(n) { re[it] * re[it] + im[it] * im[it] }
    }
}
