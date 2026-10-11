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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SstvNettoyageTest {

    private val w = 40
    private val h = 30

    private fun gris(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A soft vertical gradient with a vertical bar one pixel wide. */
    private fun image(): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (x == 20) gris(250) else gris(60 + y * 3)
    }

    @Test
    fun une_image_propre_ressort_inchangee() {
        val a = image()
        assertArrayEquals(a, SstvNettoyage.nettoie(a, w, h))
    }

    @Test
    fun une_ligne_perdue_est_refaite_avec_ses_voisines() {
        val a = image()
        for (x in 0 until w) a[10 * w + x] = gris(if (x % 2 == 0) 255 else 0)
        val b = SstvNettoyage.nettoie(a, w, h)
        val attendu = image()
        for (x in 0 until w) assertTrue(abs((b[10 * w + x] and 0xFF) - (attendu[10 * w + x] and 0xFF)) <= 2)
    }

    @Test
    fun deux_lignes_perdues_en_pd_aussi() {
        val a = image()
        for (y in 14..15) for (x in 0 until w) a[y * w + x] = gris(0)
        val b = SstvNettoyage.nettoie(a, w, h)
        val attendu = image()
        for (y in 14..15) for (x in 0 until w)
            assertTrue(abs((b[y * w + x] and 0xFF) - (attendu[y * w + x] and 0xFF)) <= 2)
    }

    @Test
    fun un_trait_de_bruit_disparait_la_barre_reste() {
        val a = image()
        for (x in 5..9) a[8 * w + x] = gris(255)
        val b = SstvNettoyage.nettoie(a, w, h)
        for (x in 5..9) assertTrue((b[8 * w + x] and 0xFF) < 120)
        for (y in 0 until h) assertEquals(250, b[y * w + 20] and 0xFF)
    }

    @Test
    fun les_lignes_non_recues_ne_sont_pas_touchees() {
        val a = image()
        for (y in 20 until h) for (x in 0 until w) a[y * w + x] = 0
        val b = SstvNettoyage.nettoie(a, w, h, lignes = 20)
        for (i in 20 * w until h * w) assertEquals(0, b[i])
    }
}
