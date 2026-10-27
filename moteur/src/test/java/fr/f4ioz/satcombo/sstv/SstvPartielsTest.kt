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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * One picture sent, one picture kept: a fade or a jump of the sound in the
 * middle of a picture no longer cuts it in two (UmKA-1, 27/10: the top in one
 * partial picture, the bottom in another started 21 s later).
 */
class SstvPartielsTest {

    private val fs = 12_000
    private val r36 = SstvMode.byName("Robot 36")!!
    private val r72 = SstvMode.byName("Robot 72")!!

    /** Horizontal bands, each [BANDE] rows, of different greys: a row's place can be read back. */
    private fun mire(m: SstvMode): IntArray = IntArray(m.width * m.height) { i ->
        val y = i / m.width
        val v = 30 + ((y / BANDE) * 67) % 200
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun son(m: SstvMode) = SstvEncoder.encode(m, mire(m), fs)

    /** Where block 0 begins in [son]: the lead and the header. */
    private val debutBloc0 = ((120.0 + SstvDecoder.VIS_MS) * fs / 1000).toInt()

    private fun bruit(n: Int, seed: Long, niveau: Double = 6000.0): ShortArray {
        val r = java.util.Random(seed)
        return ShortArray(n) { (r.nextGaussian() * niveau).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** [x] with samples [a, b) replaced by receiver noise (a fade). */
    private fun fondu(x: ShortArray, a: Int, b: Int, seed: Long = 3): ShortArray {
        val n = bruit(b - a, seed)
        return ShortArray(x.size) { if (it in a until b) n[it - a] else x[it] }
    }

    private class Vu(val mode: String, val px: IntArray, val lignes: Int, val complete: Boolean, val trous: List<IntRange>)

    private fun decode(x: ShortArray, continu: Boolean = true): List<Vu> {
        val out = ArrayList<Vu>()
        lateinit var d: SstvDecoder
        d = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                out += Vu(mode.name, pixels.copyOf(), linesDone, complete, d.trous)
            }
        }).also { it.continuous = continu }
        val tout = bruit(fs / 2, 9, 300.0) + x + bruit(fs, 10, 300.0)
        var i = 0
        while (i < tout.size) { val k = minOf(2048, tout.size - i); d.feed(tout.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return out
    }

    private fun gris(px: IntArray, w: Int, y: Int): Int {
        var s = 0
        for (x in w / 4 until 3 * w / 4) s += (px[y * w + x] shr 8) and 0xFF
        return s / (w / 2)
    }

    /** Rows of [v] in [rangs] not the grey of their band in the picture sent (one row of slack at band edges). */
    private fun malPlacees(v: Vu, m: SstvMode, rangs: IntRange): List<Int> {
        val ref = mire(m)
        return rangs.filter { y ->
            if (y >= v.lignes) return@filter true
            val g = gris(v.px, m.width, y)
            (-1..1).none { d -> val yy = (y + d).coerceIn(0, m.height - 1); abs(g - gris(ref, m.width, yy)) < 18 }
        }
    }

    @Test
    fun un_fondu_de_dix_secondes_au_milieu_donne_une_image_et_un_trou() {
        val x = son(r36)
        val bloc = r36.blockMs * fs / 1000
        val a = debutBloc0 + (80 * bloc).toInt(); val b = a + 10 * fs
        val vus = decode(fondu(x, a, b))
        assertEquals("une seule image : " + vus.map { it.lignes }, 1, vus.size)
        val v = vus[0]
        assertTrue("jusqu'en bas : ${v.lignes}", v.lignes == r36.height)
        // The lost rows are marked, from the fade to where the picture went on.
        assertEquals(v.trous.toString(), 1, v.trous.size)
        val t = v.trous[0]
        assertTrue("trou $t", t.first in 76..84 && t.last in 140..160)
        // After it, every row where the picture sent has it.
        val mal = malPlacees(v, r36, (t.last + 3) until r36.height - 2)
        assertTrue("lignes mal placées après le trou : $mal", mal.size <= 3)
        assertTrue("le haut : " + malPlacees(v, r36, 2 until 76), malPlacees(v, r36, 2 until 76).size <= 2)
    }

    @Test
    fun sans_decodage_continu_l_image_reprend_aussi() {
        val x = son(r36)
        val bloc = r36.blockMs * fs / 1000
        val a = debutBloc0 + (60 * bloc).toInt(); val b = a + 9 * fs
        val vus = decode(fondu(x, a, b), continu = false)
        assertEquals(vus.map { it.lignes }.toString(), 1, vus.size)
        assertEquals(r36.height, vus[0].lignes)
    }

    @Test
    fun deux_vraies_images_de_suite_restent_deux() {
        val x = son(r36)
        val vus = decode(x + bruit(2 * fs, 5, 300.0) + x)
        assertEquals(2, vus.size)
        assertTrue(vus.all { it.complete && it.trous.isEmpty() && it.lignes == r36.height })
    }

    @Test
    fun une_image_d_un_autre_mode_apres_le_fondu_en_est_une_nouvelle() {
        val x = son(r36)
        val bloc = r36.blockMs * fs / 1000
        val coupe = debutBloc0 + (100 * bloc).toInt()
        // The Robot 36 fades for good at row 100; ten seconds later a Robot 72 starts, header and all.
        val y = x.copyOfRange(0, coupe) + bruit(10 * fs, 7) + son(r72)
        val vus = decode(y)
        assertEquals(vus.map { it.mode + ":" + it.lignes }.toString(), 2, vus.size)
        assertEquals("Robot 36", vus[0].mode); assertTrue(vus.map { it.mode + ":" + it.lignes + ":" + it.complete }.toString(), !vus[0].complete && vus[0].lignes in 95..160)
        assertEquals("Robot 72", vus[1].mode); assertTrue(vus[1].lignes.toString(), vus[1].complete)
    }

    @Test
    fun une_image_jamais_revenue_est_rendue_partielle() {
        val x = son(r36)
        val bloc = r36.blockMs * fs / 1000
        val coupe = debutBloc0 + (120 * bloc).toInt()
        val vus = decode(x.copyOfRange(0, coupe) + bruit(30 * fs, 8))
        assertEquals(1, vus.size)
        assertTrue("${vus[0].lignes}", !vus[0].complete && vus[0].lignes in 115..180 && vus[0].trous.isEmpty())
    }

    @Test
    fun un_saut_du_son_d_une_demi_ligne_est_rattrape() {
        // 88 ms of sound lost in the middle of a line (UmKA-1, 27/10): the next pulses 62 ms off the grid,
        // beyond the old wide search (±0.45 line, the pulse whole in it). Never found again, the picture
        // was given up 8 s later and its end came out a second picture.
        val x = son(r36)
        val bloc = r36.blockMs * fs / 1000
        val a = debutBloc0 + (97.25 * bloc).toInt()
        val y = x.copyOfRange(0, a) + x.copyOfRange(a + (0.088 * fs).toInt(), x.size)
        val vus = decode(y)
        assertEquals(vus.map { it.lignes }.toString(), 1, vus.size)
        val mal = malPlacees(vus[0], r36, 2 until r36.height - 3)
        assertTrue("lignes mal placées : $mal", mal.size <= 6)
    }

    companion object { const val BANDE = 10 }
}
