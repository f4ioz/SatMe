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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** One picture cleaned ([CorrectionSstv]) and copies put together ([FusionSstv]), on pictures made here. */
class CorrectionSstvTest {

    private val w = 320; private val h = 240

    /** A slide: dark sky, a coloured photo-like block (smooth gradients), white letters 3 px thick, a red title. */
    private fun diapo(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        when {
            y in 40..150 && x in 40..240 -> { val r = 80 + (x - 40) / 2; val g = 60 + (y - 40); val b = 120 - (x - 40) / 4; (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
            y in 170..185 && x in 30..290 && (x / 6) % 3 != 2 -> 0xFFF0F0F0.toInt()
            y in 200..215 && x in 60..250 && (x / 5) % 2 == 0 -> 0xFFD02020.toInt()
            else -> 0xFF0A0A18.toInt()
        }
    }

    private fun ecart(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) s += abs(((a[i] shr sh) and 255) - ((b[i] shr sh) and 255))
        return s / (a.size * 3.0)
    }

    /** What a weak Robot 36 signal does: green specks (chroma thrown to black), white and black dashes, a burst row. */
    private fun abime(src: IntArray, graine: Long, taux: Double = 0.03): IntArray {
        val r = java.util.Random(graine)
        val p = src.copyOf()
        for (i in p.indices) {
            val u = r.nextDouble()
            if (u < taux) p[i] = 0xFF10C020.toInt()
            else if (u < taux * 1.5) { val x = i % w; val y = i / w; for (k in 0 until 3) if (x + k < w) p[y * w + x + k] = if (r.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        }
        val rang = 20 + r.nextInt(200)
        for (x in 0 until w) p[rang * w + x] = (0xFF shl 24) or (r.nextInt(256) shl 16) or (r.nextInt(256) shl 8) or r.nextInt(256)
        return p
    }

    @Test fun une_image_propre_reste_propre() {
        val d = diapo()
        val c = CorrectionSstv.corrige(d, w, h)
        // The colour median moves a block's corner pixels at most: nearly nothing changes.
        val changes = d.indices.count { d[it] != c[it] }
        assertTrue("pixels changés $changes", changes < d.size / 200)
        assertTrue("écart ${ecart(d, c)}", ecart(d, c) < 0.3)

        val seules = CorrectionSstv.corrige(d, w, h, o = CorrectionSstv.Options(couleurDouce = false))
        val n = d.indices.count { d[it] != seules[it] }
        assertTrue("$n", n < d.size / 200)
    }

    @Test fun les_taches_s_en_vont() {
        val d = diapo(); val a = abime(d, 1)
        val c = CorrectionSstv.corrige(a, w, h)
        val avant = ecart(d, a); val apres = ecart(d, c)
        assertTrue("avant $avant, après $apres", apres < avant * 0.4)
        assertTrue(ClassementSstv.analyse(c, w, h).note > ClassementSstv.analyse(a, w, h).note)
    }

    @Test fun les_lignes_non_recues_ne_sont_pas_touchees() {
        val d = diapo(); val a = abime(d, 2)
        for (i in 100 * w until a.size) a[i] = 0xFF000000.toInt()
        val c = CorrectionSstv.corrige(a, w, h, lignes = 100)
        for (i in 100 * w until a.size) assertEquals(0xFF000000.toInt(), c[i])
    }

    // ------------------------------------------------------------------ fusion

    /** The slide moved up [dy] rows and sideways [dx] (a late start, a slipped line), the rows below black. */
    private fun decale(src: IntArray, dx: Int, dy: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w; val sy = y + dy; val sx = (x + dx).coerceIn(0, w - 1)
        if (sy in 0 until h) src[sy * w + sx] else 0xFF000000.toInt()
    }

    @Test fun le_calage_retrouve_le_decalage() {
        val d = diapo()
        val ref = FusionSstv.Copie(abime(d, 3), w, h)
        for ((dx, dy) in listOf(0 to 0, 3 to 0, -2 to 17, 1 to 42)) {
            val c = FusionSstv.Copie(decale(abime(d, 10L + dx + dy), dx, dy), w, h, h - maxOf(dy, 0))
            val cg = FusionSstv.cale(ref, c)
            assertNotNull(cg)
            // c's pixel (x - dx, y - dy) is the reference's (x, y).
            assertEquals("dx pour $dx,$dy", -dx, cg!!.dx); assertEquals("dy pour $dx,$dy", -dy, cg.dy)
        }
    }

    @Test fun la_fusion_bat_chaque_copie() {
        val d = diapo()
        val copies = (0 until 4).map { k ->
            val dy = intArrayOf(0, 6, 0, 23)[k]; val dx = intArrayOf(0, 0, 2, -1)[k]
            FusionSstv.Copie(decale(abime(d, 20L + k, taux = 0.05), dx, dy), w, h, h - dy)
        }
        val f = FusionSstv.fusionne(copies)
        assertEquals(4, f.utilisees)
        val eF = ecart(d, f.px)
        // Each copy put back in place to be compared fairly (the rows it lacks taken from the slide).
        val eCopies = copies.mapIndexed { k, c ->
            val dy = intArrayOf(0, 6, 0, 23)[k]; val dx = intArrayOf(0, 0, 2, -1)[k]
            ecart(d, IntArray(w * h) { i -> val x = i % w; val y = i / w; val yb = y - dy; val xb = (x - dx).coerceIn(0, w - 1)
                if (yb in 0 until c.lignes) c.px[yb * w + xb] else d[i] })
        }
        assertTrue("fusion $eF, copies $eCopies", eF < eCopies.min() * 0.5)
    }

    @Test fun une_seule_copie_revient_telle_quelle_et_un_doublon_compte_une_fois() {
        val d = abime(diapo(), 5)
        val un = FusionSstv.fusionne(listOf(FusionSstv.Copie(d, w, h)))
        assertArrayEquals(d, un.px)
        val f = FusionSstv.fusionne(listOf(FusionSstv.Copie(d, w, h), FusionSstv.Copie(d.copyOf(), w, h), FusionSstv.Copie(abime(diapo(), 6), w, h)))
        assertEquals(2, f.utilisees)
    }

    @Test fun une_autre_diapo_est_laissee_de_cote() {
        val d = diapo()
        val autre = IntArray(w * h) { i -> if ((i % w / 20 + i / w / 20) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF0000FF.toInt() }
        val f = FusionSstv.fusionne(listOf(FusionSstv.Copie(abime(d, 7), w, h), FusionSstv.Copie(autre, w, h), FusionSstv.Copie(abime(d, 8), w, h)))
        assertEquals(2, f.utilisees)
        assertEquals(null, f.calages[1])
    }
}
