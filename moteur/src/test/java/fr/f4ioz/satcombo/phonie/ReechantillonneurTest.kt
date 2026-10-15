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
import kotlin.math.sqrt

class ReechantillonneurTest {

    private fun ton(hz: Double, rate: Int, s: Double) = ShortArray((rate * s).toInt()) { (16_000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    /** Fed in uneven blocks, as a sound card gives them. */
    private fun passe(r: Reechantillonneur, x: ShortArray): FloatArray {
        val l = ArrayList<Float>()
        var i = 0; var k = 0
        while (i < x.size) { val n = minOf(intArrayOf(441, 1000, 37, 4096)[k++ % 4], x.size - i); l += r.pousse(x.copyOfRange(i, i + n), n).toList(); i += n }
        return l.toFloatArray()
    }

    private fun rms(x: FloatArray, de: Int) = sqrt(x.drop(de).sumOf { it.toDouble() * it } / (x.size - de))

    /** Amplitude of [hz] in [x] at 16 kHz (a correlation). */
    private fun amplitude(x: FloatArray, hz: Double, de: Int): Double {
        var c = 0.0; var s = 0.0
        for (i in de until x.size) { c += x[i] * kotlin.math.cos(2 * PI * hz * i / 16_000); s += x[i] * sin(2 * PI * hz * i / 16_000) }
        return 2 * sqrt(c * c + s * s) / (x.size - de)
    }

    @Test fun `44 1 kHz vers 16 kHz garde la voix`() {
        val y = passe(Reechantillonneur(44_100), ton(1000.0, 44_100, 2.0))
        assertTrue("longueur ${y.size}", abs(y.size - 32_000) < 80)
        assertEquals(16_000 / 32768.0, amplitude(y, 1000.0, 400), 0.01)
    }

    @Test fun `48 kHz vers 16 kHz ne replie pas 12 kHz`() {
        // Without a low-pass, 12 kHz would come back as 4 kHz.
        val y = passe(Reechantillonneur(48_000), ton(12_000.0, 48_000, 1.0))
        assertTrue("rms ${rms(y, 400)}", rms(y, 400) < 0.002)
        val z = passe(Reechantillonneur(48_000), ton(3000.0, 48_000, 1.0))
        assertEquals(16_000 / 32768.0, amplitude(z, 3000.0, 400), 0.01)
    }

    @Test fun `8 kHz vers 16 kHz`() {
        val y = passe(Reechantillonneur(8_000), ton(700.0, 8_000, 1.0))
        assertTrue(abs(y.size - 16_000) < 40)
        assertEquals(16_000 / 32768.0, amplitude(y, 700.0, 400), 0.01)
    }

    @Test fun `16 kHz passe tel quel`() {
        val x = ton(500.0, 16_000, 0.1)
        val y = Reechantillonneur(16_000).pousse(x, x.size)
        assertEquals(x.size, y.size)
        assertEquals(x[100] / 32768f, y[100], 1e-6f)
    }
}
