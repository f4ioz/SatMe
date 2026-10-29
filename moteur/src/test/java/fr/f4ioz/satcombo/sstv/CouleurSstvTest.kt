/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A known cast, shift or level spoilt on a test slide must be found again, and set right ([CouleurSstv]). */
class CouleurSstvTest {

    private val w = 320
    private val h = 256

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /**
     * A slide as the ISS sends them: a dark grey background, a grey ramp,
     * colour bars, a photo-like block, white letters, a red title. Neutral
     * areas and coloured edges both.
     */
    private fun mire(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        when {
            y in 8..28 -> { val v = 40 + x * 180 / w; rgb(v, v, v) }
            y in 40..100 && x in 16..304 -> when (((x - 16) / 36)) {
                0 -> rgb(200, 200, 40); 1 -> rgb(40, 190, 200); 2 -> rgb(40, 180, 50); 3 -> rgb(190, 50, 190)
                4 -> rgb(200, 40, 40); 5 -> rgb(40, 50, 200); 6 -> rgb(230, 140, 40); else -> rgb(120, 120, 120)
            }
            y in 112..190 && x in 30..200 -> rgb(70 + (x - 30) * 3 / 4 + if ((x / 12 + y / 12) % 2 == 0) 30 else 0, 60 + (y - 112), 140 - (x - 30) / 3)
            y in 112..190 && x in 214..300 -> if ((x / 7 + y / 9) % 2 == 0) rgb(230, 230, 230) else rgb(40, 60, 150)
            y in 200..214 && x in 30..290 && (x / 6) % 3 != 2 -> rgb(245, 245, 245)
            y in 222..240 && x in 60..250 && (x / 5) % 2 == 0 -> rgb(205, 30, 30)
            else -> rgb(6, 6, 6)
        }
    }

    private fun bruite(px: IntArray, sigma: Double, graine: Long = 7): IntArray {
        val r = java.util.Random(graine)
        return IntArray(px.size) { i -> val c = px[i]
            rgb(((c shr 16) and 255) + (r.nextGaussian() * sigma).toInt(), ((c shr 8) and 255) + (r.nextGaussian() * sigma).toInt(), (c and 255) + (r.nextGaussian() * sigma).toInt()) }
    }

    /** Mean |R|+|G|+|B| / 3 between two pictures. */
    private fun ecart(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) { val p = a[i]; val q = b[i]
            s += abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255)) }
        return s / 3.0 / a.size
    }

    @Test fun `a clean slide is measured clean and comes out unchanged`() {
        val m = mire()
        val a = CouleurSstv.analyse(m, w, h)
        assertTrue("cast ${a.dominanteCb}, ${a.dominanteCr}", a.dominante < 2.0)
        assertEquals(0.0, a.cbX, 0.01); assertEquals(0.0, a.crX, 0.01); assertEquals(0.0, a.cbY, 0.01); assertEquals(0.0, a.crY, 0.01)
        assertFalse(a.bruitDominant)
        val r = CouleurSstv.corrige(m, w, h, o = CouleurSstv.Options(saturation = true))
        assertTrue("plan ${r.plan}", r.plan.vide)
        assertArrayEquals(m, r.px)
    }

    @Test fun `a tuning error's cast is found, in hertz, and taken off`() {
        val m = mire()
        for (hz in listOf(25.0, -18.0)) {
            val abime = CouleurSstv.simule(m, w, h, hz = hz)
            val a = CouleurSstv.analyse(abime, w, h)
            val k = hz * CouleurSstv.NIVEAUX_PAR_HZ
            assertEquals("Cb at $hz Hz", k, a.dominanteCb, 1.5)
            assertEquals("Cr at $hz Hz", k, a.dominanteCr, 1.5)
            assertTrue("sure at $hz: $a", a.dominanteSure)
            assertNotNull(a.accordHz)
            assertEquals(hz, a.accordHz!!, 5.0)
            val r = CouleurSstv.corrige(abime, w, h)
            assertTrue(r.plan.dominante)
            val apres = CouleurSstv.analyse(r.px, w, h)
            assertTrue("left ${apres.dominante}", apres.dominante < 1.5)
            assertTrue("gap ${ecart(abime, m)} → ${ecart(r.px, m)}", ecart(r.px, m) < ecart(abime, m) / 2)
        }
    }

    @Test fun `a cast on one chroma only is no tuning error`() {
        val abime = CouleurSstv.simule(mire(), w, h, dCb = -10.0)
        val a = CouleurSstv.analyse(abime, w, h)
        assertEquals(-10.0, a.dominanteCb, 1.5); assertEquals(0.0, a.dominanteCr, 1.5)
        assertNull("${a.accordHz}", a.accordHz)
        val apres = CouleurSstv.analyse(CouleurSstv.corrige(abime, w, h).px, w, h)
        assertTrue("left ${apres.dominanteCb} ${apres.dominanteCr}", apres.dominante < 1.5)
    }

    @Test fun `a blue slide stays blue`() {
        // A slide mostly a saturated blue, a few white letters: the blue is no cast.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (y in 100..130 && x in 40..280 && (x / 6) % 3 != 2) rgb(240, 240, 240) else rgb(30, 70, 190) }
        val r = CouleurSstv.corrige(px, w, h)
        assertTrue("cast ${r.analyse.dominanteCb}, ${r.analyse.dominanteCr}", r.analyse.dominante < 2.0)
        assertTrue("${r.plan} ${ecart(r.px, px)}", ecart(r.px, px) < 1.0)
    }

    @Test fun `a chroma shift is found to a few tenths and set back`() {
        val m = mire()
        val abime = CouleurSstv.simule(m, w, h, cbX = 2.5, crX = 2.5, cbY = 0.0, crY = 1.5)
        val a = CouleurSstv.analyse(abime, w, h)
        assertEquals(2.5, a.cbX, 0.4); assertEquals(2.5, a.crX, 0.4)
        assertEquals(1.5, a.crY, 0.4); assertEquals(0.0, a.cbY, 0.4)
        val r = CouleurSstv.corrige(abime, w, h)
        assertTrue(r.plan.chroma)
        val apres = CouleurSstv.analyse(r.px, w, h)
        assertTrue("left ${apres.cbX} ${apres.crX} ${apres.crY}", abs(apres.cbX) <= 0.4 && abs(apres.crX) <= 0.4 && abs(apres.crY) <= 0.4)
        assertTrue("gap ${ecart(abime, m)} → ${ecart(r.px, m)}", ecart(r.px, m) < ecart(abime, m) * 0.6)
    }

    @Test fun `a shift to the left, Robot 36's chroma late on a noisy picture`() {
        val m = mire()
        val abime = bruite(CouleurSstv.simule(m, w, h, cbX = -3.0, crX = -3.0), 6.0)
        val a = CouleurSstv.analyse(abime, w, h)
        assertEquals(-3.0, a.cbX, 0.5); assertEquals(-3.0, a.crX, 0.5)
    }

    @Test fun `levels squeezed are stretched back to black and white`() {
        val m = mire()
        // The tone scale shrunk towards the middle, as a weak signal does.
        val abime = IntArray(m.size) { i -> val c = m[i]; fun s(v: Int) = 128 + (v - 128) * 6 / 10
            rgb(s((c shr 16) and 255), s((c shr 8) and 255), s(c and 255)) }
        val a = CouleurSstv.analyse(abime, w, h)
        val p = IntArray(256); abime.forEach { p[(it shr 8) and 255]++ }
        assertTrue("black ${a.noir} white ${a.blanc} " + p.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:${it.value}" }, a.noir > 50 && a.blanc < 200)
        val r = CouleurSstv.corrige(abime, w, h, o = CouleurSstv.Options(saturation = true))
        assertTrue(r.plan.niveaux)
        assertTrue(r.plan.gain in 1.4..1.6)
        val apres = CouleurSstv.analyse(r.px, w, h)
        assertTrue("black ${apres.noir} white ${apres.blanc}", apres.noir < 30 && apres.blanc > 205)
        assertTrue("gap ${ecart(abime, m)} → ${ecart(r.px, m)}", ecart(r.px, m) < ecart(abime, m) / 2)
    }

    @Test fun `noise is measured, and a picture of snow left alone`() {
        val m = mire()
        val a = CouleurSstv.analyse(bruite(m, 8.0), w, h)
        assertEquals(8.0 * 0.85, a.bruitY, 3.0)
        val r = java.util.Random(3)
        val neige = IntArray(w * h) { rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
        val n = CouleurSstv.corrige(neige, w, h)
        assertTrue(n.analyse.bruitDominant)
        assertTrue(n.plan.vide)
        assertArrayEquals(neige, n.px)
    }

    @Test fun `only the rows received are touched`() {
        val m = mire()
        val abime = CouleurSstv.simule(m, w, h, hz = 30.0)
        for (i in 200 * w until h * w) abime[i] = 0xFF000000.toInt()
        val r = CouleurSstv.corrige(abime, w, h, lignes = 200)
        for (i in 200 * w until h * w) assertEquals(0xFF000000.toInt(), r.px[i])
        assertTrue(r.plan.dominante)
    }

    @Test fun `each switch off leaves its step out`() {
        val abime = CouleurSstv.simule(mire(), w, h, hz = 25.0, cbX = 2.0, crX = 2.0)
        val p = CouleurSstv.corrige(abime, w, h, o = CouleurSstv.Options(dominante = false, chroma = true, niveaux = false)).plan
        assertFalse(p.dominante); assertTrue(p.chroma); assertFalse(p.niveaux)
        assertTrue(CouleurSstv.corrige(abime, w, h, o = CouleurSstv.Options(false, false, false, false)).plan.vide)
    }
}
