/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import fr.f4ioz.satcombo.phonie.HauteurVoixTest.Companion.mediane
import fr.f4ioz.satcombo.phonie.HauteurVoixTest.Companion.voisee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class DecaleHauteurTest {

    private fun db(x: FloatArray, de: Int = 0, a: Int = x.size) = 20 * log10(HauteurVoix.rms(x.copyOfRange(de, a)) + 1e-12)

    @Test fun `la hauteur suit le facteur, la duree ne bouge pas`() {
        for (rate in intArrayOf(16_000, 22_050))
            for (f in doubleArrayOf(110.0, 200.0))
                for (k in doubleArrayOf(0.7, 0.85, 1.2, 1.4)) {
                    val x = voisee(rate, 1.2) { f }
                    val y = DecaleHauteur.applique(x, rate, k)
                    assertEquals(x.size, y.size)
                    val m = mediane(HauteurVoix.suit(y, rate).voisees())
                    assertEquals("$rate Hz, $f Hz × $k", f * k, m, f * k * 0.03)
                    // The level stays (within 2 dB).
                    assertEquals("$rate Hz, $f Hz × $k : niveau", db(x), db(y), 2.0)
                }
    }

    @Test fun `les syllabes restent a leur place`() {
        // Voice from 0.4 to 0.8 s only: after a shift, still there (its energy's centre within 10 ms).
        val rate = 16_000
        val v = voisee(rate, 1.2) { 150.0 }
        for (i in v.indices) if (i < rate * 0.4 || i >= rate * 0.8) v[i] = 0f
        val y = DecaleHauteur.applique(v, rate, 1.3)
        fun centre(x: FloatArray): Double { var s = 0.0; var m = 0.0; for (i in x.indices) { val e = x[i].toDouble() * x[i]; s += e * i; m += e }; return s / m / rate }
        assertEquals(centre(v), centre(y), 0.01)
        assertTrue(db(y, 0, (rate * 0.35).toInt()) < db(y) - 30)
    }

    @Test fun `facteur variable dans le temps`() {
        // A flat 150 Hz bent up to 1.25 in its second half.
        val rate = 22_050
        val x = voisee(rate, 1.0) { 150.0 }
        val y = DecaleHauteur.applique(x, rate, null) { t -> if (t < 0.5) 1.0 else 1.25 }
        val c = HauteurVoix.suit(y, rate)
        val avant = mediane((10 until 40).map { c.f0[it] }.filter { it > 0 })
        val apres = mediane((60 until 90).map { c.f0[it] }.filter { it > 0 })
        assertEquals(150.0, avant, 150 * 0.03)
        assertEquals(187.5, apres, 187.5 * 0.03)
    }

    @Test fun `facteur un rend presque le meme son`() {
        val rate = 16_000
        val x = voisee(rate, 0.8) { 140.0 }
        val y = DecaleHauteur.applique(x, rate, 1.0)
        assertEquals(140.0, mediane(HauteurVoix.suit(y, rate).voisees()), 140 * 0.02)
        assertEquals(db(x), db(y), 1.0)
    }
}
