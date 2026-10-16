/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SstvAccordTest {

    /** Lines of 9 ms of sync at [sync] Hz then 140 ms of picture at 1900 Hz, phase continuous. */
    private fun lignes(sync: Double, rate: Int, s: Double): ShortArray {
        var phase = 0.0
        return ShortArray((rate * s).toInt()) { i ->
            val t = (i.toDouble() / rate) % 0.149
            phase += 2 * PI * (if (t < 0.009) sync else 1900.0) / rate
            (8000 * sin(phase)).toInt().toShort()
        }
    }

    @Test fun `une synchro decalee de 100 Hz se lit +100`() {
        val a = SstvAccord(44_100)
        val p = lignes(1300.0, 44_100, 3.0)
        a.ajoute(p, p.size)
        assertEquals(100.0, a.ecartSyncHz!!.toDouble(), 10.0)
        assertTrue(a.niveau > 0.2f)
        assertEquals((SstvAccord.HAUTE_HZ - SstvAccord.BASSE_HZ) / SstvAccord.PAS_HZ + 1, a.spectre.size)
    }

    @Test fun `le bruit et un ton tenu ne donnent pas d'ecart`() {
        val r = java.util.Random(1)
        val a = SstvAccord(48_000)
        val p = ShortArray(144_000) { (r.nextGaussian() * 3000).toInt().toShort() }
        a.ajoute(p, p.size)
        assertNull(a.ecartSyncHz)
        val b = SstvAccord(48_000)
        val q = ShortArray(144_000) { (8000 * sin(2 * PI * 1250.0 * it / 48_000)).toInt().toShort() }
        b.ajoute(q, q.size)
        assertNull(b.ecartSyncHz)
        assertNull(SstvAccord.ecart(listOf(1200f, 1210f)))
    }

    @Test fun `une vraie image - la synchro est vue pres de 1200`() {
        val m = SstvMode.byName("PD 90")!!
        val img = IntArray(m.width * m.height) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        val pcm = SstvEncoder.encode(m, img, 44_100, m.blocks, 120.0, 60.0)
        val a = SstvAccord(44_100)
        a.ajoute(pcm, pcm.size)
        assertNotNull(a.ecartSyncHz)
        assertTrue("${a.ecartSyncHz}", kotlin.math.abs(a.ecartSyncHz!!) <= 20)
    }
}
