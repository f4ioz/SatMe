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

class DecoupeVoixTest {

    private val rnd = java.util.Random(7)
    private fun bruit(s: Double, niveau: Double = 0.01) = FloatArray((s * 16_000).toInt()) { (rnd.nextGaussian() * niveau).toFloat() }

    /** Something like speech: three formant-ish tones, syllables at 4 Hz, over the noise. */
    private fun voix(s: Double, niveau: Double = 0.2, t0: Int = 0): FloatArray = FloatArray((s * 16_000).toInt()) { i ->
        val t = (i + t0) / 16_000.0
        val syl = 0.5 + 0.5 * sin(2 * PI * 4 * t)
        val v = sin(2 * PI * 220 * t) * 0.5 + sin(2 * PI * 900 * t) * 0.3 + sin(2 * PI * 2300 * t) * 0.2
        (niveau * syl * syl * v + rnd.nextGaussian() * 0.01).toFloat()
    }

    private fun pousse(d: DecoupeVoix, x: FloatArray, squelch: Boolean? = null): List<DecoupeVoix.Segment> {
        val l = ArrayList<DecoupeVoix.Segment>()
        var i = 0
        while (i < x.size) { val n = minOf(1234, x.size - i); l += d.pousse(x.copyOfRange(i, i + n), n, squelch); i += n }
        return l
    }

    @Test fun `une phrase entre deux silences`() {
        val d = DecoupeVoix()
        val l = pousse(d, bruit(3.0) + voix(3.0) + bruit(3.0))
        assertEquals(1, l.size)
        val s = l[0]
        // Starts a little before the voice (3 s), lasts the voice plus some silence each side.
        assertTrue("début ${s.debut / 16_000.0}", s.debut / 16_000.0 in 2.6..3.2)
        assertTrue("durée ${s.dureeS}", s.dureeS in 3.0..4.2)
        assertTrue(s.voixDb > 10)
    }

    @Test fun `le bruit seul ne donne rien`() {
        val d = DecoupeVoix()
        assertTrue(pousse(d, bruit(20.0)).isEmpty())
        assertTrue(d.vide() == null)
    }

    @Test fun `une porteuse sans syllabes est jetee`() {
        val d = DecoupeVoix()
        val porteuse = FloatArray(16_000 * 4) { i -> (0.2 * sin(2 * PI * 1000 * i / 16_000.0) + rnd.nextGaussian() * 0.01).toFloat() }
        assertTrue(pousse(d, bruit(2.0) + porteuse + bruit(2.0)).isEmpty())
    }

    @Test fun `un claquement trop court est jete`() {
        val d = DecoupeVoix()
        assertTrue(pousse(d, bruit(2.0) + voix(0.3) + bruit(2.0)).isEmpty())
    }

    @Test fun `une longue tirade est coupee en morceaux de 25 s au plus`() {
        val d = DecoupeVoix()
        val l = pousse(d, bruit(1.0) + voix(60.0) + bruit(2.0))
        assertTrue("${l.size} morceaux", l.size >= 3)
        l.forEach { assertTrue(it.dureeS <= 25.01) }
        // Nothing lost between two pieces.
        for (k in 1 until l.size) assertEquals(l[k - 1].debut + l[k - 1].pcm.size, l[k].debut)
    }

    @Test fun `silencieux ferme c'est le silence`() {
        val d = DecoupeVoix()
        assertTrue(pousse(d, bruit(2.0) + voix(3.0) + bruit(2.0), squelch = false).isEmpty())
    }

    @Test fun `le souffle au dessus de 4 kHz n'est pas une voix`() {
        val d = DecoupeVoix()
        // White noise suddenly 20 dB louder (an FM receiver losing its signal): hiss, not speech.
        assertTrue(pousse(d, bruit(3.0, 0.005) + bruit(3.0, 0.05) + bruit(3.0, 0.005)).isEmpty())
    }

    @Test fun `normalise vers -20 dBFS sans ecreter`() {
        val x = FloatArray(16_000) { (0.001 * sin(2 * PI * 500 * it / 16_000.0)).toFloat() }
        val y = DecoupeVoix.normalise(x)
        val rms = sqrt(y.sumOf { it.toDouble() * it } / y.size)
        // +30 dB at most.
        assertTrue(rms in 0.02..0.03)
        val fort = FloatArray(1000) { if (it == 0) 0.9f else 0.001f }
        assertTrue(DecoupeVoix.normalise(fort).all { abs(it) <= 0.99f })
    }

    @Test fun `le capteur date les morceaux a l'horloge`() {
        val c = CapteurPhonie()
        val x = bruit(3.0) + voix(3.0) + bruit(3.0)
        // At 48 kHz, in blocks, the first block ending at t = 1 000 000 ms + 100 ms.
        val pcm = ShortArray(x.size * 3) { (x[it / 3] * 32767).toInt().toShort() }
        val l = ArrayList<CapteurPhonie.Piece>()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(4800, pcm.size - i)
            l += c.pousse(pcm.copyOfRange(i, i + n), n, 48_000, 1_000_000L + (i + n) / 48)
            i += n
        }
        assertEquals(1, l.size)
        assertTrue("${l[0].debutMs}", l[0].debutMs in 1_002_600L..1_003_200L)
    }
}
