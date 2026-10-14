/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cw

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DecodeurCwTest {

    /** [texte] keyed at [wpm] on a [ton] Hz note, soft edges, with noise ([bruit] of full scale). */
    private fun signal(texte: String, wpm: Int, ton: Double, rate: Int, bruit: Double, seed: Long = 7): ShortArray {
        val rnd = java.util.Random(seed)
        val l = ArrayList<Short>()
        val pre = (rate * 0.6).toInt()
        val manip = listOf(false to 600.0) + DecodeurCw.manipulation(texte, wpm) + listOf(false to 1500.0)
        var t = 0L
        val rampe = rate * 0.004
        for ((on, ms) in manip) {
            val n = (ms * rate / 1000).toInt()
            for (i in 0 until n) {
                val env = if (!on) 0.0 else minOf(1.0, i / rampe, (n - i) / rampe)
                val v = 0.3 * env * sin(2 * PI * ton * t / rate) + bruit * rnd.nextGaussian()
                l += (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                t++
            }
        }
        check(pre > 0)
        return l.toShortArray()
    }

    private fun decode(pcm: ShortArray, rate: Int, ton: Int = 0): String {
        val sb = StringBuilder()
        val d = DecodeurCw(rate, { sb.append(it) }, ton)
        var i = 0
        while (i < pcm.size) { val n = minOf(2048, pcm.size - i); d.ecoute(pcm.copyOfRange(i, i + n), n); i += n }
        return sb.toString().trim()
    }

    @Test fun `un appel propre a 20 WPM`() {
        val r = decode(signal("CQ CQ DE F4IOZ F4IOZ K", 20, 700.0, 48_000, 0.01), 48_000)
        assertTrue(r, r.endsWith("CQ DE F4IOZ F4IOZ K"))
    }

    @Test fun `le ton trouve tout seul, a 25 WPM avec du bruit`() {
        val r = decode(signal("TEST TEST DE EA4ABC IN80 73", 25, 850.0, 48_000, 0.06), 48_000)
        assertTrue(r, r.contains("EA4ABC IN80 73"))
    }

    @Test fun `lent, 12 WPM, a 22 kHz`() {
        val r = decode(signal("VVV DE G0ABC IO91", 12, 600.0, 22_050, 0.03), 22_050, ton = 600)
        assertTrue(r, r.contains("DE G0ABC IO91"))
    }

    @Test fun `bruit fort, puis un autre operateur plus rapide`() {
        val rate = 48_000
        val a = signal("CQ SAT DE F4IOZ", 16, 650.0, rate, 0.12, seed = 3)
        val b = signal("F4IOZ DE OH2XYZ KP20 TU", 28, 650.0, rate, 0.12, seed = 4)
        val r = decode(a + b, rate)
        assertTrue(r, r.contains("DE F4IOZ") && r.contains("OH2XYZ KP20"))
    }
}
