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
 * The live decoder's line period, after what broke it on real pictures
 * (ISS, 23–24/10/2026): a noisy stretch or a jump of the sound (about 33 ms
 * lost) read the period 1.5 % off, and every pulse after it was missed but
 * one in four (20261023_012254: mark 24, 85 elsewhere; 20261024_034726: 49).
 */
class SstvPeriodeTest {

    private val fs = 11_025
    private val r36 = SstvMode.byName("Robot 36")!!

    /** Black and white halves, the edge in the middle: where each row's edge lies tells its placement. */
    private fun moitie(m: SstvMode) = IntArray(m.width * m.height) { i -> if (i % m.width < m.width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    private class R(val px: IntArray, val lignes: Int, val trouves: BooleanArray, val vis: Boolean)

    private fun decode(pcm: ShortArray, m: SstvMode = r36): R {
        var px: IntArray? = null; var lignes = 0; var vis = false
        val trouves = BooleanArray(m.blocks)
        val d = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) { vis = true }
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { if (px == null) { px = pixels.copyOf(); lignes = linesDone } }
        })
        d.mesureLigne = { b, _, t, _, _ -> if (b in trouves.indices) trouves[b] = t }
        var i = 0
        while (i < pcm.size) { val k = minOf(2048, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return R(px!!, lignes, trouves, vis)
    }

    /** Each row's black-to-white edge, px (where the brightness crosses the middle). */
    private fun bords(r: R, m: SstvMode = r36): IntArray = IntArray(r.lignes) { y ->
        var x = m.width / 2 - 40
        while (x < m.width / 2 + 40 && ((r.px[y * m.width + x] shr 8) and 255) < 128) x++
        x
    }

    /** Gaussian noise of [rms] added over samples [a, b). */
    private fun bruit(pcm: ShortArray, a: Int, b: Int, rms: Double, graine: Long) {
        val rnd = java.util.Random(graine)
        for (i in a until minOf(b, pcm.size)) pcm[i] = (pcm[i] + rnd.nextGaussian() * rms).coerceIn(-32767.0, 32767.0).toInt().toShort()
    }

    /** Where line [k] starts in the encoded sound (samples): after the lead and the header. */
    private fun ligne(k: Double) = ((120.0 + SstvDecoder.VIS_MS + r36.firstBlockOffsetMs + k * r36.blockMs) * fs / 1000).toInt()

    @Test
    fun un_saut_de_33_ms_apres_un_passage_bruite_ne_piege_pas_la_periode() {
        // As 20261024_034726: noisy lines, then 33 ms of sound lost at line 41.
        val son = SstvTestSignal.encode(r36, moitie(r36), fs)
        bruit(son, ligne(28.0), ligne(44.0), 9000.0, 7)
        val coupe = ligne(41.3)
        val pcm = son.copyOfRange(0, coupe) + son.copyOfRange(coupe + (0.033 * fs).toInt(), son.size)
        val r = decode(pcm)
        assertTrue("en-tête", r.vis)
        val apres = (60 until r36.blocks).count { r.trouves[it] }
        assertTrue("impulsions trouvées après le saut : $apres/${r36.blocks - 60}", apres >= (r36.blocks - 60) * 95 / 100)
        val b = bords(r)
        val med = b.sorted()[b.size / 2]
        val decalees = (60 until r.lignes).count { abs(b[it] - med) > 2 }
        assertTrue("$decalees lignes décalées après le saut", decalees <= 3)
    }

    @Test
    fun des_impulsions_bruitees_isolees_ne_tordent_pas_la_periode() {
        // Short noise bursts every 20 lines (a fading FM signal): each one threw the old 4-pulse fit off.
        val son = SstvTestSignal.encode(r36, moitie(r36), fs)
        for (k in 20 until 230 step 20) bruit(son, ligne(k.toDouble()), ligne(k + 3.0), 12000.0, k.toLong())
        val r = decode(son)
        val ok = (0 until r36.blocks).filter { k -> (0..3).none { (k - it) % 20 == 0 && k - it >= 20 } }
        val trouves = ok.count { r.trouves[it] }
        assertTrue("impulsions trouvées hors des salves : $trouves/${ok.size}", trouves >= ok.size * 95 / 100)
    }

    /** The sound as if the transmitter's clock were [ppm] off (linear resampling). */
    private fun horloge(pcm: ShortArray, ppm: Double): ShortArray {
        val f = 1 + ppm * 1e-6
        val n = (pcm.size * f).toInt()
        return ShortArray(n) { i -> val x = i / f; val a = x.toInt().coerceAtMost(pcm.size - 2); val t = x - a; (pcm[a] * (1 - t) + pcm[a + 1] * t).toInt().toShort() }
    }

    @Test
    fun une_derive_de_moins_100_ppm_est_suivie() {
        // The drift measured on the owner's ISS pictures: −22 to −109 ppm. Unfollowed, a Robot 36 slants by 13 px.
        for (ppm in doubleArrayOf(-100.0, 100.0, -400.0)) {
            val r = decode(horloge(SstvTestSignal.encode(r36, moitie(r36), fs), ppm))
            assertEquals("$ppm ppm", r36.height, r.lignes)
            val b = bords(r)
            val haut = b.copyOfRange(5, 30).sorted()[12]; val bas = b.copyOfRange(210, 235).sorted()[12]
            assertTrue("$ppm ppm : bord en haut $haut, en bas $bas", abs(haut - bas) <= 1)
            assertTrue("$ppm ppm : impulsions ${r.trouves.count { it }}", r.trouves.count { it } >= r36.blocks - 3)
        }
    }

    @Test
    fun une_image_propre_reste_comme_avant() {
        // A clean picture decodes as before the change (main 591598d): mean error to the picture sent, levels.
        val avant = mapOf("Robot 36" to 4.90, "PD 120" to 4.12, "Martin M1" to 0.70, "Scottie S1" to 0.76, "PD 90" to 0.98, "Robot 72" to 3.18)
        for ((name, e0) in avant) {
            val m = SstvMode.byName(name)!!
            val img = IntArray(m.width * m.height) { i -> val x = i % m.width; val y = i / m.width; (0xFF shl 24) or ((x * 255 / m.width) shl 16) or ((y * 255 / m.height) shl 8) or (((x / 16 + y / 16) % 2) * 255) }
            var out: IntArray? = null
            val d = SstvDecoder(fs, object : SstvDecoder.Listener { override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { if (out == null) out = pixels.copyOf() } })
            val pcm = SstvTestSignal.encode(m, img, fs)
            var i = 0
            while (i < pcm.size) { val k = minOf(4096, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
            d.finish()
            var e = 0L
            for (j in img.indices) { val a = img[j]; val b = out!![j]; e += abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255)) }
            val err = e.toDouble() / img.size / 3
            assertTrue("$name : écart $err (avant $e0)", err <= e0 + 0.15)
        }
    }
}
