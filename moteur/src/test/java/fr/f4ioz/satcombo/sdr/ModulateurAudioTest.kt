/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.sstv.ProgrammeSstv
import fr.f4ioz.satcombo.sstv.SstvMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/** The audio modulator's spectrum: FM deviation (Bessel), occupied band, SSB sideband rejection, interpolation images. */
class ModulateurAudioTest {

    private val fa = 44_100
    private val l = 24
    private val fs = fa * l.toDouble()

    private fun ton(hz: Double, amp: Double = 0.999): (ShortArray) -> Int {
        var ph = 0.0
        return { b -> for (k in b.indices) { ph += 2 * PI * hz / fa; b[k] = (sin(ph) * amp * 32767).roundToInt().toShort() }; b.size }
    }

    /** Power spectrum (Hann, [frames] averaged) of [paires] pairs after [sauteS] seconds; bin k ↔ k·fs/n, centred. */
    private fun spectre(m: ModulateurAudio, n: Int = 1 shl 16, frames: Int = 1, sauteS: Double = 0.1): DoubleArray {
        val saut = ShortArray(2 * (sauteS * fs).toInt()); m.remplis(saut)
        val p = DoubleArray(n)
        val b = ShortArray(2 * n)
        repeat(frames) {
            m.remplis(b)
            val re = DoubleArray(n); val im = DoubleArray(n)
            for (k in 0 until n) { val w = 0.5 - 0.5 * cos(2 * PI * k / n); re[k] = b[2 * k] * w; im[k] = b[2 * k + 1] * w }
            Fft.transform(re, im)
            for (k in 0 until n) { val j = (k + n / 2) % n; p[j] += re[k] * re[k] + im[k] * im[k] }
        }
        return p
    }

    private fun bin(hz: Double, n: Int) = n / 2 + Math.round(hz / fs * n).toInt()

    /** Power within ±[demiHz] of [hz]. */
    private fun autour(p: DoubleArray, hz: Double, demiHz: Double = 60.0): Double {
        val n = p.size; val d = (demiHz / fs * n).toInt().coerceAtLeast(2)
        val c = bin(hz, n)
        return (c - d..c + d).sumOf { p[it.coerceIn(0, n - 1)] }
    }

    private fun db(x: Double) = 10 * log10(x)

    @Test
    fun fm_excursion_verifiee_au_premier_zero_de_la_porteuse() {
        // β = 2.405: J0 = 0 (no carrier), J1² = 0.2695 of the power in each first sideband.
        for (dev in listOf(2_500.0, 5_000.0)) {
            val fm = dev / 2.4048
            val p = spectre(ModulateurAudio(ModulationAudio.NBFM, fa, l, dev, source = ton(fm)))
            val tot = p.sum()
            val porteuse = db(autour(p, 0.0) / tot)
            val j1 = db(autour(p, fm) / tot)
            println("NBFM %.0f Hz : porteuse %.1f dB, J1 %.2f dB (attendu %.2f)".format(dev, porteuse, j1, db(0.2695)))
            assertTrue("porteuse $porteuse dB", porteuse < -35)
            assertEquals(db(0.2695), j1, 0.3)
            assertEquals(j1, db(autour(p, -fm) / tot), 0.3)
        }
    }

    @Test
    fun fm_enveloppe_constante_et_bande_occupee_d_une_image_sstv() {
        for (dev in listOf(2_500.0, 5_000.0)) {
            val prog = ProgrammeSstv(listOf(SstvMode.byName("Robot 36")!! to IntArray(320 * 240) { (it * 2654435761L.toInt()) and 0xFFFFFF }), fa)
            val m = ModulateurAudio(ModulationAudio.NBFM, fa, l, dev, source = prog)
            val b = ShortArray(2 * 100_000); m.remplis(b)
            for (k in 0 until 100_000) { val a = Math.hypot(b[2 * k].toDouble(), b[2 * k + 1].toDouble()) / ModulateurAudio.PLEIN; assertEquals(0.5, a, 0.002) }
            val p = spectre(m, frames = 20, sauteS = 1.0)
            val bw = occupee99(p)
            println("NBFM %.0f Hz : bande occupée 99 %% = %.1f kHz (Carson %.1f kHz)".format(dev, bw / 1000, 2 * (dev + 2300) / 1000))
            assertTrue("$bw Hz", bw <= 2 * (dev + 2_300) + 1_000)
        }
    }

    /** The narrowest band (Hz) centred on the power's middle holding 99 % of it. */
    private fun occupee99(p: DoubleArray): Double {
        val tot = p.sum(); val n = p.size
        var cumul = 0.0; var bas = 0
        while (cumul + p[bas] < 0.005 * tot) { cumul += p[bas]; bas++ }
        cumul = 0.0; var haut = n - 1
        while (cumul + p[haut] < 0.005 * tot) { cumul += p[haut]; haut-- }
        return (haut - bas) * fs / n
    }

    @Test
    fun blu_rejette_la_bande_opposee_de_plus_de_40_dB() {
        for (mod in listOf(ModulationAudio.USB, ModulationAudio.LSB)) for (f in listOf(400.0, 1_200.0, 1_900.0, 2_300.0, 2_700.0)) {
            val p = spectre(ModulateurAudio(mod, fa, l, source = ton(f)))
            val s = if (mod == ModulationAudio.USB) 1 else -1
            val voulu = autour(p, s * f)
            val oppose = autour(p, -s * f)
            val rej = db(voulu / oppose)
            val porteuse = db(voulu / autour(p, 0.0))
            // The interpolation's images: at the audio rate's multiples.
            val image = db(voulu / maxOf(autour(p, s * f + fa), autour(p, s * f - fa), autour(p, -s * f + fa)))
            println("%s %.0f Hz : bande opposée −%.1f dB, porteuse −%.1f dB, images −%.1f dB".format(mod, f, rej, porteuse, image))
            assertTrue("$mod $f : $rej dB", rej >= 40)
            assertTrue("$mod $f porteuse : $porteuse dB", porteuse >= 40)
            assertTrue("$mod $f images : $image dB", image >= 60)
            assertTrue("$mod $f : ${db(voulu / p.sum())} dB", db(voulu / p.sum()) > -0.2)
        }
    }

    @Test
    fun blu_une_image_sstv_tient_dans_2_7_kHz() {
        val prog = ProgrammeSstv(listOf(SstvMode.byName("Martin M2")!! to IntArray(320 * 256) { (it * 2654435761L.toInt()) and 0xFFFFFF }), fa)
        val m = ModulateurAudio(ModulationAudio.USB, fa, l, source = prog)
        val p = spectre(m, frames = 12, sauteS = 1.0)
        val n = p.size; val tot = p.sum()
        val dedans = (bin(300.0, n)..bin(2_800.0, n)).sumOf { p[it] }
        val negatif = (0 until n / 2).sumOf { p[it] }
        println("USB SSTV : %.3f %% dans 300–2800 Hz, bande négative −%.1f dB".format(100 * dedans / tot, db(tot / negatif)))
        assertTrue(dedans / tot > 0.995)
        assertTrue(db(tot / negatif) > 40)
        assertEquals(0L, m.ecretages)
    }

    @Test
    fun la_fin_de_la_source_finit_le_modulateur() {
        var reste = 4_410
        val m = ModulateurAudio(ModulationAudio.USB, fa, l) { b -> val n = minOf(reste, b.size); reste -= n; ton(1000.0)(b); n }
        val b = ShortArray(2 * 8192)
        var tours = 0
        while (!m.fini && tours < 100) { m.remplis(b); tours++ }
        assertTrue(m.fini)
        assertEquals(4_410L, m.lus)
        // After the end: silence in SSB.
        m.remplis(b)
        assertTrue(b.all { it.toInt() == 0 })
    }
}
