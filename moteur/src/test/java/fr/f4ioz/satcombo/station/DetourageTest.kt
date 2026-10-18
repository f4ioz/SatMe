/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DetourageTest {

    private val BLANC = 0xFFFFFFFF.toInt()
    private val GRIS = 0xFF3A4048.toInt()

    /** A picture [l]×[h] of [fond] with rectangles drawn on it (x0, y0, x1, y1 exclusive, colour), a little noise as a JPEG has. */
    private fun image(l: Int, h: Int, fond: Int, vararg r: IntArray, bruit: Int = 3): IntArray {
        val rnd = Random(7)
        val px = IntArray(l * h) { fond }
        for (q in r) for (y in q[1] until q[3]) for (x in q[0] until q[2]) px[y * l + x] = q[4]
        return IntArray(px.size) { i ->
            val p = px[i]; val n = rnd.nextInt(-bruit, bruit + 1)
            fun c(v: Int) = (v + n).coerceIn(0, 255)
            (0xFF shl 24) or (c((p ushr 16) and 0xFF) shl 16) or (c((p ushr 8) and 0xFF) shl 8) or c(p and 0xFF)
        }
    }

    private fun alpha(r: Detourage.Resultat, x: Int, y: Int) = r.pixels[(y - r.y0) * r.l + (x - r.x0)] ushr 24

    @Test fun unObjetSurFondBlanc() {
        val px = image(200, 160, BLANC, intArrayOf(50, 40, 150, 120, GRIS))
        val r = Detourage.detoure(px, 200, 160)
        assertTrue(r.uniforme)
        // Cropped to the object with its margin.
        assertEquals(50 - Detourage.MARGE, r.x0); assertEquals(40 - Detourage.MARGE, r.y0)
        assertEquals(100 + 2 * Detourage.MARGE, r.l); assertEquals(80 + 2 * Detourage.MARGE, r.h)
        // The corners clear, the inside opaque, the edge softened.
        assertEquals(0, r.pixels[0] ushr 24)
        assertEquals(255, alpha(r, 100, 80))
        assertTrue(alpha(r, 50, 80) in 100..240)
    }

    @Test fun lesZonesClairesDansLObjetRestent() {
        // A white label inside the body (like a rig's): not reached from the border, small: kept.
        val px = image(240, 200, BLANC, intArrayOf(30, 30, 210, 170, GRIS), intArrayOf(60, 60, 90, 70, BLANC), intArrayOf(120, 140, 180, 150, BLANC))
        val r = Detourage.detoure(px, 240, 200)
        assertEquals(255, alpha(r, 75, 65))
        assertEquals(255, alpha(r, 150, 145))
        // A large hole through it (a handle's): removed although enclosed.
        val q = image(240, 200, BLANC, intArrayOf(20, 20, 220, 180, GRIS), intArrayOf(70, 60, 170, 140, BLANC))
        val s = Detourage.detoure(q, 240, 200)
        assertEquals(0, alpha(s, 120, 100))
        assertEquals(255, alpha(s, 40, 100))
    }

    @Test fun unObjetQuiToucheLeBord() {
        // The object cut by the picture's edge: the rest of the border is still white.
        val px = image(200, 150, BLANC, intArrayOf(0, 50, 120, 110, GRIS))
        val r = Detourage.detoure(px, 200, 150)
        assertTrue(r.uniforme)
        assertEquals(0, r.x0)
        assertEquals(255, alpha(r, 2, 80))
        assertEquals(0, alpha(r, r.x0 + r.l - 1, r.y0))
    }

    @Test fun unFondSombreEtUnFondNonUniforme() {
        val noir = 0xFF101010.toInt()
        val px = image(160, 120, noir, intArrayOf(40, 30, 120, 90, 0xFFE0C040.toInt()))
        val r = Detourage.detoure(px, 160, 120)
        assertTrue(r.uniforme)
        assertEquals(255, alpha(r, 80, 60))
        assertEquals(0, r.pixels[0] ushr 24)
        // A real scene: the border of many colours — said, and still done.
        val rnd = Random(3)
        val scene = IntArray(160 * 120) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        val s = Detourage.detoure(scene, 160, 120)
        assertFalse(s.uniforme)
        // The tolerance chosen by hand is kept, bounded.
        assertEquals(Detourage.TOLERANCE_MAX, Detourage.detoure(px, 160, 120, 500).tolerance)
    }

    @Test fun leHaloClairSEstompe() {
        // A pale fringe one pixel wide around the object (a JPEG's halo): nearly clear.
        val px = image(200, 160, BLANC, intArrayOf(49, 39, 151, 121, 0xFFECECEC.toInt()), intArrayOf(50, 40, 150, 120, GRIS), bruit = 0)
        val r = Detourage.detoure(px, 200, 160)
        assertTrue(alpha(r, 49, 80) < 60)
        assertEquals(255, alpha(r, 100, 80))
    }
}
