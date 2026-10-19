/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HauteurVoixTest {

    companion object {
        /**
         * A voiced sound: the harmonics of [f0] (a function of time) up to 3.5 kHz,
         * falling 6 dB an octave, under a slow syllable envelope; [sansFondamentale]:
         * the first harmonics below 300 Hz taken out (an SSB passband).
         */
        fun voisee(rate: Int, s: Double, sansFondamentale: Boolean = false, f0: (Double) -> Double): FloatArray {
            val n = (rate * s).toInt()
            val x = FloatArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                val f = f0(t)
                phase += 2 * PI * f / rate
                var v = 0.0
                var k = 1
                while (k * f < 3500) {
                    if (!sansFondamentale || k * f >= 300) v += sin(k * phase + k * 0.7) / k
                    k++
                }
                x[i] = (0.25 * v * (0.6 + 0.4 * sin(2 * PI * 3.0 * t))).toFloat()
            }
            return x
        }

        fun mediane(l: List<Float>): Double = l.sorted().let { it[it.size / 2].toDouble() }
    }

    @Test fun `hauteur fixe trouvee a 2 pour cent pres`() {
        for (rate in intArrayOf(16_000, 22_050))
            for (f in doubleArrayOf(80.0, 120.0, 165.0, 210.0, 300.0)) {
                val c = HauteurVoix.suit(voisee(rate, 1.0) { f }, rate)
                val v = c.voisees()
                assertTrue("$rate Hz, $f Hz : ${v.size} trames voisées", v.size > c.n * 0.8)
                val m = mediane(v)
                assertEquals("$rate Hz, $f Hz", f, m, f * 0.02)
                // Every frame, not only the median.
                assertTrue("$rate Hz, $f Hz : trames écartées", v.count { abs(it - f) > f * 0.02 } < v.size * 0.05)
            }
    }

    @Test fun `sans la fondamentale, comme en BLU, la periode reste juste`() {
        for (f in doubleArrayOf(100.0, 130.0)) {
            val c = HauteurVoix.suit(voisee(16_000, 1.0, sansFondamentale = true) { f }, 16_000)
            assertEquals(f, mediane(c.voisees()), f * 0.02)
        }
    }

    @Test fun `une montee de 100 a 200 Hz est suivie`() {
        val c = HauteurVoix.suit(voisee(16_000, 1.0) { t -> 100.0 + 100.0 * t }, 16_000)
        var ok = 0; var n = 0
        for (i in 0 until c.n) if (c.f0[i] > 0) { n++; val voulu = 100.0 + 100.0 * i * c.pasS; if (abs(c.f0[i] - voulu) < voulu * 0.03) ok++ }
        assertTrue("$ok / $n", ok > n * 0.9 && n > 80)
    }

    @Test fun `silence et bruit ne sont pas voises`() {
        val r = java.util.Random(1)
        val bruit = FloatArray(16_000) { (r.nextGaussian() * 0.1).toFloat() }
        val c = HauteurVoix.suit(bruit, 16_000)
        assertTrue(c.voisees().size < c.n * 0.1)
        assertTrue(HauteurVoix.suit(FloatArray(16_000), 16_000).voisees().isEmpty())
    }

    @Test fun `profil moyenne et etendue`() {
        // Half a second at 100 Hz, half a second at 200 Hz: mean 141 Hz (geometric), spread 12 semitones.
        val x = voisee(16_000, 1.0) { t -> if (t < 0.5) 100.0 else 200.0 }
        val p = HauteurVoix.profil(HauteurVoix.suit(x, 16_000))
        assertEquals(141.4, p.f0Hz, 141.4 * 0.03)
        assertEquals(12.0, p.etendueDt, 0.8)
        assertTrue(p.fractionVoisee > 0.8)
    }
}
