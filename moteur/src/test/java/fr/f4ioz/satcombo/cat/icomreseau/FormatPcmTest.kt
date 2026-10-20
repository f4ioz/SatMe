/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FormatPcmTest {

    /** Packets of a voice-like sound, laid out as [f]. */
    private fun paquets(f: FormatPcm16, n: Int = 60): List<ByteArray> {
        var t = 0
        val r = java.util.Random(3)
        return List(n) {
            val s = ShortArray(320) {
                t++
                val v = 6000 * sin(2 * PI * 300 * t / 16000.0) + 3000 * sin(2 * PI * 1100 * t / 16000.0) + r.nextGaussian() * 300
                v.toInt().coerceIn(-32768, 32767).toShort()
            }
            val b = ByteArray(f.decalage + 640)
            for (i in s.indices) {
                val v = (s[i].toInt() + if (f.nonSigne) 32768 else 0) and 0xFFFF
                val hi = (v shr 8).toByte(); val lo = v.toByte()
                if (f.grosBout) { b[f.decalage + 2 * i] = hi; b[f.decalage + 2 * i + 1] = lo } else { b[f.decalage + 2 * i] = lo; b[f.decalage + 2 * i + 1] = hi }
            }
            b
        }
    }

    @Test fun chaque_disposition_est_reconnue() {
        for (f in FormatPcm16.entries) {
            val d = DetecteurFormatPcm16()
            paquets(f).forEach { d.decode(it) }
            assertTrue("$f non tranché", d.sur)
            assertEquals(f, d.format)
        }
    }

    @Test fun le_silence_ne_tranche_rien() {
        val d = DetecteurFormatPcm16()
        repeat(100) { d.decode(ByteArray(640)) }
        assertEquals(false, d.sur)
        assertEquals(FormatPcm16.PETIT, d.format)
    }

    /** A 4 kHz tone (an IF folded) is flagged; voice-band sound is not. */
    @Test fun la_fi_est_reperee() {
        fun paquet(f: Double, t0: Int) = ByteArray(640).also { b ->
            for (i in 0 until 320) { val v = (8000 * sin(2 * PI * f * (t0 + i) / 16000.0)).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        }
        val a = DetecteurFormatPcm16(); repeat(400) { a.decode(paquet(1000.0, it * 320)) }
        val b = DetecteurFormatPcm16(); repeat(400) { b.decode(paquet(4000.0, it * 320)) }
        assertEquals(false, a.fi)
        assertEquals(true, b.fi)
    }
}
