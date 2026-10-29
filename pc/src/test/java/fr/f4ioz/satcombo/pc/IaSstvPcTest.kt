/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.IaSstv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The « IA »'s installation as the page sees it; and, with the files
 * already downloaded (SATME_IA_DIR=a folder holding `ia/`, as SatMe-PC's
 * own), the model run for real through its own class loader. Nothing is
 * downloaded here.
 */
class IaSstvPcTest {

    @Test fun `three files, their sizes and checksums, nothing there yet`() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-ia-" + System.nanoTime())
        try {
            val ia = IaSstvPc(d)
            assertFalse(ia.installe())
            val e = ia.etat()
            assertEquals(3, e.getJSONArray("fichiers").length())
            assertEquals(IaSstvPc.FICHIERS.sumOf { it.octets }, e.getLong("total"))
            assertTrue(IaSstvPc.FICHIERS.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.url.startsWith("https://") })
            // The model's revision is fixed: a file changed upstream is refused, not taken.
            assertTrue(IaSstvPc.FICHIERS.drop(1).all { IaSstvPc.REVISION in it.url })
            assertTrue(ia.fils() in 1..8)
        } finally { d.deleteRecursively() }
    }

    @Test fun `the real model denoises a noisy test picture`() {
        val dir = System.getenv("SATME_IA_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "ia").isDirectory)
        val ia = IaSstvPc(dir!!)
        assumeTrue(ia.installe())
        val w = 320; val h = 256
        val propre = IntArray(w * h) { i -> val x = i % w; val y = i / w
            val v = if ((x / 40 + y / 32) % 2 == 0) 200 else 40; (0xFF shl 24) or (v shl 16) or ((v + 10) shl 8) or (255 - v) }
        val r = java.util.Random(1)
        val bruite = IntArray(w * h) { i -> val c = propre[i]
            fun b(v: Int) = (v + (r.nextGaussian() * 20).toInt()).coerceIn(0, 255)
            (0xFF shl 24) or (b((c shr 16) and 255) shl 16) or (b((c shr 8) and 255) shl 8) or b(c and 255) }
        fun ecart(a: IntArray) = a.indices.sumOf { i -> abs(((a[i] shr 8) and 255) - ((propre[i] shr 8) and 255)).toLong() }.toDouble() / a.size
        val t0 = System.nanoTime()
        val sortie = IaSstv.applique(ia, bruite, w, h)
        val ms = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime(); IaSstv.applique(ia, bruite, w, h); val ms2 = (System.nanoTime() - t1) / 1e6
        println("IA: noise gap ${ecart(bruite)} → ${ecart(sortie)}, first ${ms.toInt()} ms (load included), then ${ms2.toInt()} ms on ${ia.fils()} threads")
        assertTrue(ecart(sortie) < ecart(bruite) / 2)
        ia.libere()
    }
}
