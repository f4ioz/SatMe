/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DetecteurLeilaTest {

    private val rate = 44_100

    /** [secondes] of sound: the note's frequency and level at each instant, with hiss. */
    private fun son(secondes: Double, bruit: Double = 0.02, seed: Long = 5, hz: (Double) -> Double, niveau: (Double) -> Double = { 0.3 }): ShortArray {
        val rnd = java.util.Random(seed)
        var phase = 0.0
        return ShortArray((secondes * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            phase += 2 * PI * hz(t) / rate
            ((niveau(t) * sin(phase) + bruit * rnd.nextGaussian()).coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
        }
    }

    private fun entendue(pcm: ShortArray): Boolean {
        val d = DetecteurLeila(rate)
        var i = 0
        while (i < pcm.size) { val n = minOf(2048, pcm.size - i); d.ecoute(pcm.copyOfRange(i, i + n), n, 1000L + i); i += n }
        return d.dernierMs > 0
    }

    @Test fun `une sirene qui monte et descend`() =
        assertTrue(entendue(son(6.0, hz = { t -> 900 + 400 * sin(2 * PI * t / 0.6) })))

    @Test fun `une sirene lente, deux tons en alternance`() {
        assertTrue(entendue(son(6.0, hz = { t -> 650 + 300 * sin(2 * PI * t / 1.5) })))
        assertTrue(entendue(son(6.0, hz = { t -> if ((t / 0.35).toInt() % 2 == 0) 700.0 else 1050.0 })))
    }

    @Test fun `une sirene dans le bruit fort`() =
        assertTrue(entendue(son(6.0, bruit = 0.08, hz = { t -> 1000 + 350 * sin(2 * PI * t / 0.8) })))

    @Test fun `une porteuse, le bruit seul`() {
        assertFalse(entendue(son(6.0, hz = { 1000.0 })))
        assertFalse(entendue(son(6.0, hz = { 1000.0 }, niveau = { 0.0 })))
    }

    @Test fun `de la CW`() {
        val manip = fr.f4ioz.satcombo.cw.DecodeurCw.manipulation("CQ CQ DE F4IOZ F4IOZ JN18 K", 22)
        val fins = manip.runningFold(0.0) { a, (_, ms) -> a + ms / 1000 }
        assertFalse(entendue(son(6.0, hz = { 700.0 }, niveau = { t -> val k = fins.indexOfLast { it <= t }; if (k in manip.indices && manip[k].first) 0.3 else 0.0 })))
    }

    @Test fun `FT8 et RTTY`() {
        val rnd = java.util.Random(9)
        val ft8 = IntArray(100) { rnd.nextInt(8) }
        assertFalse(entendue(son(6.0, hz = { t -> 1200 + 6.25 * ft8[(t / 0.16).toInt() % 100] })))
        val bits = IntArray(400) { rnd.nextInt(2) }
        assertFalse(entendue(son(6.0, hz = { t -> 1000 + 170.0 * bits[(t / 0.022).toInt() % 400] })))
    }

    @Test fun `une voix`() {
        // A note jumping about, its level coming and going as syllables.
        val rnd = java.util.Random(3)
        val notes = DoubleArray(200) { 300 + 1500 * rnd.nextDouble() }
        assertFalse(entendue(son(6.0, hz = { t -> notes[(t / 0.09).toInt() % 200] }, niveau = { t -> 0.3 * (0.5 + 0.5 * sin(2 * PI * t * 4.3)) })))
    }
}
