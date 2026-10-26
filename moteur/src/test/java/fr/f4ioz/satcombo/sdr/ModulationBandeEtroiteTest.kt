/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.domain.MonteeQo100.PlanPluto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The narrow-band uplink's samples, as the Pluto would get them, demodulated
 * here: the NCO taken back out, decimated to the audio rate, then a product
 * detector (USB). The sideband, the carrier and the opposite sideband, the
 * level, the CW keying.
 */
class ModulationBandeEtroiteTest {

    private val audio = 48_000
    private val facteur = (PlanPluto.DEBIT / audio).toInt()

    /** [secondes] of baseband from [source], shifted by [ncoHz], as the transmitter takes it. */
    private fun bandeDeBase(source: (ShortArray) -> Int, secondes: Double, ncoHz: Double): ShortArray {
        val m = ModulateurAudio(ModulationAudio.USB, audio, facteur, niveau = 0.5, source = source)
        assertEquals(PlanPluto.DEBIT, m.debit)
        val n = (secondes * m.debit).toInt()
        val b = ShortArray(2 * n)
        val nco = MelangeurNco(m.debit, ncoHz)
        val bloc = 65_536
        var o = 0
        while (o < n) {
            val k = minOf(bloc, n - o)
            val t = ShortArray(2 * k)
            m.remplis(t, k); nco.applique(t, k)
            System.arraycopy(t, 0, b, 2 * o, 2 * k); o += k
        }
        return b
    }

    /** Back to complex audio: the shift removed, [facteur] pairs averaged (nulls on every image of the audio rate). */
    private fun redescend(b: ShortArray, ncoHz: Double): Pair<DoubleArray, DoubleArray> {
        val n = b.size / 2 / facteur
        val re = DoubleArray(n); val im = DoubleArray(n)
        val w = -2 * PI * ncoHz / PlanPluto.DEBIT
        for (k in 0 until n) {
            var a = 0.0; var c = 0.0
            for (j in 0 until facteur) {
                val idx = k * facteur + j
                val i = b[2 * idx].toDouble(); val q = b[2 * idx + 1].toDouble()
                val ph = w * idx
                val cs = cos(ph); val sn = sin(ph)
                a += i * cs - q * sn; c += i * sn + q * cs
            }
            re[k] = a / facteur; im[k] = c / facteur
        }
        return re to im
    }

    /** The complex amplitude at [f] Hz (signed: + above the carrier) over the steady part. */
    private fun raie(re: DoubleArray, im: DoubleArray, f: Double, debut: Int = audio / 10): Double {
        var a = 0.0; var c = 0.0
        val w = -2 * PI * f / audio
        val n = re.size - debut
        for (k in debut until re.size) {
            val cs = cos(w * k); val sn = sin(w * k)
            a += re[k] * cs - im[k] * sn; c += re[k] * sn + im[k] * cs
        }
        return hypot(a, c) / n
    }

    private fun db(x: Double, ref: Double) = 20 * log10(maxOf(x, 1e-9) / ref)

    private fun ton(hz: Double, niveau: Double = 0.9): (ShortArray) -> Int {
        var ph = 0.0
        return { b -> for (i in b.indices) { ph += 2 * PI * hz / audio; b[i] = (sin(ph) * niveau * 32767).roundToInt().toShort() }; b.size }
    }

    @Test fun `la BLU haute est du bon cote et sans porteuse`() {
        val nco = 700_000.0
        val (re, im) = redescend(bandeDeBase(ton(1_000.0), 0.6, nco), nco)
        val utile = raie(re, im, 1_000.0)
        // The upper sideband only: the opposite one and the carrier far below.
        assertTrue("bande opposée " + db(raie(re, im, -1_000.0), utile), db(raie(re, im, -1_000.0), utile) < -40)
        assertTrue("porteuse " + db(raie(re, im, 0.0), utile), db(raie(re, im, 0.0), utile) < -40)
        // The level: one tone at 0.9 of full scale gives the modulator's 0.5 × 0.9 of the DAC's range.
        assertEquals(0.5 * 0.9 * ModulateurAudio.PLEIN, utile, 0.05 * 0.5 * 0.9 * ModulateurAudio.PLEIN)
        // Nothing outside the 300–2800 Hz passband: a tone at 3.5 kHz is cut.
        val (re2, im2) = redescend(bandeDeBase(ton(3_500.0), 0.4, nco), nco)
        assertTrue(db(raie(re2, im2, 3_500.0), utile) < -30)
    }

    @Test fun `le detecteur de produit rend le son`() {
        val nco = 600_000.0
        val (re, _) = redescend(bandeDeBase(ton(1_500.0), 0.5, nco), nco)
        // The real part is the audio again: its 1 500 Hz tone, little else.
        val debut = audio / 10
        var s = 0.0; var c = 0.0; var e = 0.0
        for (k in debut until re.size) { s += re[k] * sin(2 * PI * 1_500.0 * k / audio); c += re[k] * cos(2 * PI * 1_500.0 * k / audio); e += re[k] * re[k] }
        val n = re.size - debut
        val ton = 2 * hypot(s, c) / n
        val rms = sqrt(e / n)
        // A pure tone: its amplitude is √2 × its RMS.
        assertEquals(1.0, ton / (rms * sqrt(2.0)), 0.02)
    }

    @Test fun `le signal est decale par le NCO la ou on l attend`() {
        val nco = 450_000.0
        val b = bandeDeBase(ton(1_000.0), 0.2, nco)
        // Taken back with the wrong shift (1 kHz off): the tone moves by as much.
        val (re, im) = redescend(b, nco - 1_000.0)
        val bon = raie(re, im, 2_000.0)
        assertTrue(db(raie(re, im, 1_000.0), bon) < -30)
        assertTrue(bon > 1_000)
    }

    @Test fun `la CW est une porteuse propre a 700 Hz manipulee`() {
        val cw = ManipulateurCw(audio, 20, 700.0)
        cw.touche(true)
        val nco = 500_000.0
        val (re, im) = redescend(bandeDeBase(cw, 0.4, nco), nco)
        val p = raie(re, im, 700.0)
        assertTrue(db(raie(re, im, -700.0), p) < -40)
        assertTrue(db(raie(re, im, 0.0), p) < -40)
        // Key down: a steady carrier.
        assertTrue(p > 0.3 * ModulateurAudio.PLEIN)
    }

    @Test fun `la manipulation du texte`() {
        val cw = ManipulateurCw(audio, 20, 700.0)
        val b = ShortArray(audio * 2)
        // Nothing queued: silence.
        cw(b); assertTrue(b.all { it.toInt() == 0 })
        cw.envoie("E")
        assertTrue(cw.occupe)
        cw(b)
        // A dot at 20 wpm: 60 ms of tone, ramps included.
        val haut = b.count { kotlin.math.abs(it.toInt()) > 1000 }
        assertTrue("$haut", haut in (0.035 * audio).toInt()..(0.065 * audio).toInt())
        assertTrue(!cw.occupe)
        // No clicks: the edges rise over the ramp, not at once.
        val premier = b.indexOfFirst { it.toInt() != 0 }
        assertTrue(kotlin.math.abs(b[premier + 20].toInt()) < 1000)
        cw.envoie("TEST"); cw.annule(); cw(b); assertTrue(b.all { it.toInt() == 0 })
    }

    @Test fun `le tampon ne manque jamais et ne prend pas de retard`() {
        val t = TamponAudio(audio, maxMs = 400, cibleMs = 120)
        val b = ShortArray(960)
        // Empty: silence, as many as asked.
        assertEquals(960, t(b)); assertTrue(b.all { it.toInt() == 0 })
        // A burst of 1 s: brought back to 120 ms.
        t.pousse(ShortArray(audio) { 1000 })
        assertEquals(120, t.retardMs)
        assertTrue(t.jetes > 0)
        // Another rate: resampled.
        t.vide(); t.pousse(ShortArray(4_410) { 0 }, 4_410, 44_100)
        assertEquals(100.0, t.retardMs.toDouble(), 3.0)
    }
}
