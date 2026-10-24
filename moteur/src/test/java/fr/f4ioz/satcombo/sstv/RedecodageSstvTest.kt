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
import kotlin.math.roundToInt

/** The whole-picture decoding ([RedecodageSstv]) on Robot 36 signals made here: clock drift, late start, noise. */
class RedecodageSstvTest {

    private val r36 = SstvMode.byName("Robot 36")!!
    private val rate = 12_000

    /** A slide-like picture: colour bars on top, a grey ramp, a dark band with white text-like blocks. */
    private fun mire(): IntArray {
        val w = 320; val h = 240
        val cols = intArrayOf(0xFF2040C0.toInt(), 0xFFE0E0E0.toInt(), 0xFFC03020.toInt(), 0xFF30A040.toInt(), 0xFFE0B020.toInt(), 0xFF101010.toInt(), 0xFF6090E0.toInt(), 0xFF808080.toInt())
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y < 80 -> cols[x * 8 / w]
                y < 160 -> { val v = x * 255 / (w - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                else -> if ((x / 12) % 2 == 0 && (y / 10) % 2 == 0) 0xFFF0F0F0.toInt() else 0xFF0A0A20.toInt()
            }
        }
    }

    /** The sound as if the transmitter's clock ran [ppm] fast (resampled), noise of [bruit] rms added. */
    private fun son(img: IntArray, ppm: Double = 0.0, bruit: Double = 0.0, graine: Long = 1, coupeMs: Double = 0.0): ShortArray {
        val brut = SstvEncoder.encode(r36, img, rate)
        val n = (brut.size / (1 + ppm * 1e-6)).toInt()
        val rnd = java.util.Random(graine)
        val debut = (coupeMs * rate / 1000).toInt()
        return ShortArray(n - debut) { k ->
            val i = k + debut
            val x = i * (1 + ppm * 1e-6)
            val a = x.toInt().coerceAtMost(brut.size - 1); val b = (a + 1).coerceAtMost(brut.size - 1); val f = x - a
            (brut[a] * (1 - f) + brut[b] * f + rnd.nextGaussian() * bruit).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Mean RGB gap over rows [y0, y1), columns all. */
    private fun ecart(a: IntArray, b: IntArray, y0: Int = 0, y1: Int = 240, x0: Int = 0, x1: Int = 320, dy: Int = 0): Double {
        var s = 0L; var n = 0L
        for (y in y0 until y1) { val yb = y + dy; if (yb !in 0 until 240) continue
            for (x in x0 until x1) { val p = a[y * 320 + x]; val q = b[yb * 320 + x]
                for (sh in intArrayOf(16, 8, 0)) { s += abs(((p shr sh) and 255) - ((q shr sh) and 255)); n++ } } }
        return s / n.toDouble()
    }

    @Test fun un_signal_propre_revient_tel_quel() {
        val img = mire()
        val r = RedecodageSstv.decode(son(img), rate, r36)
        assertNotNull(r)
        assertTrue(r!!.entete); assertTrue(r.complete); assertEquals(240, r.lignes)
        val e = ecart(img, r.pixels)
        assertTrue("écart $e", e < 6.0)
        // The picture's edges too (the first and last columns follow the porch).
        assertTrue("bord gauche ${ecart(img, r.pixels, x1 = 2)}", ecart(img, r.pixels, x1 = 2) < 15.0)
        assertTrue("bord droit ${ecart(img, r.pixels, x0 = 318)}", ecart(img, r.pixels, x0 = 318) < 15.0)
    }

    @Test fun une_horloge_decalee_ne_penche_pas_l_image() {
        val img = mire()
        for (ppm in doubleArrayOf(-2500.0, 800.0, 3000.0)) {
            val r = RedecodageSstv.decode(son(img, ppm), rate, r36)!!
            // A transmitter running fast: shorter lines.
            assertEquals("ppm $ppm", -ppm, r.derivePpm, 120.0)
            // The bottom rows as straight as the top ones.
            val e = ecart(img, r.pixels, y0 = 200)
            assertTrue("ppm $ppm écart en bas $e", e < 8.0)
        }
    }

    @Test fun commence_en_retard_sans_entete() {
        val img = mire()
        // 1.3 s cut: the header (0.91 s after 0.12 s of lead) and two lines lost.
        val r = RedecodageSstv.decode(son(img, 400.0, coupeMs = 120.0 + 910.0 + 2.4 * 150), rate, r36)!!
        assertTrue(!r.entete)
        // The picture moved up by the lines lost, as live.
        val e = ecart(img, r.pixels, y0 = 10, y1 = 200, dy = -3)
        val e2 = ecart(img, r.pixels, y0 = 10, y1 = 200, dy = -2)
        assertTrue("écart $e / $e2", minOf(e, e2) < 8.0)
    }

    private fun direct(pcm: ShortArray): IntArray {
        var px: IntArray? = null
        val d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { if (px == null) px = pixels.copyOf() }
        })
        var i = 0
        while (i < pcm.size) { val k = minOf(4096, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return px ?: IntArray(320 * 240)
    }

    /**
     * Bursts of noise (a fade, a spurious carrier: 0.6 s each, every 5 s)
     * and 16 ms of sound lost by the sound card: the lines between are where
     * they belong, every one, better than live.
     */
    @Test fun rafales_et_trou_dans_le_son() {
        val img = mire()
        val propre = son(img, 300.0, bruit = 1500.0, graine = 4)
        val rnd = java.util.Random(9)
        val debutImage = (0.12 + 0.91) * rate
        val rafales = (0 until 7).map { k -> (debutImage + (2.0 + k * 5.0) * rate).toInt() }
        for (r in rafales) for (i in r until minOf(propre.size, r + (0.6 * rate).toInt())) propre[i] = (rnd.nextGaussian() * 20000).roundToInt().coerceIn(-32768, 32767).toShort()
        // 16 ms lost at 20 s.
        val trou = (debutImage + 20.0 * rate).toInt()
        val pcm = propre.copyOfRange(0, trou) + propre.copyOfRange(trou + (0.016 * rate).toInt(), propre.size)
        val r = RedecodageSstv.decode(pcm, rate, r36)!!
        val dr = direct(pcm)
        // Rows away from the bursts (each burst spoils 4–5 rows), after the lost sound too.
        val touchees = rafales.map { ((it - debutImage) / rate / 0.150).toInt() }.flatMap { (it - 1)..(it + 6) }.toSet() +
            // and the line the lost sound cuts (its chroma shared with its neighbours).
            (132..134)
        fun e(px: IntArray) = (0 until 240).filter { it !in touchees }.map { y -> ecart(img, px, y0 = y, y1 = y + 1) }
        val eN = e(r.pixels); val eD = e(dr)
        println("rafales : direct moyenne ${eD.average()} pire ${eD.max()}, tout d'un coup moyenne ${eN.average()} pire ${eN.max()}")
        assertTrue("moyenne ${eN.average()} / direct ${eD.average()}", eN.average() < eD.average())
        assertTrue("pire ligne ${eN.max()}", eN.max() < 20.0 && eN.max() < eD.max())
    }

    /**
     * A jump beyond the ±25 ms search (33 and 60 ms lost at line 41, as
     * 20261024_034726): the 40 lines before it were filled from the lines
     * after and drawn shifted (a green band at the top). Each side now finds
     * its own pulses; the picture as received elsewhere is unchanged.
     */
    @Test fun un_grand_saut_du_son_recale_chaque_segment() {
        val img = mire()
        val debutImage = (0.12 + 0.91) * rate
        for (perduMs in doubleArrayOf(33.0, 60.0)) {
            val propre = son(img, -80.0, bruit = 800.0, graine = 6)
            val coupe = (debutImage + 41.3 * 0.150 * rate).toInt()
            val pcm = propre.copyOfRange(0, coupe) + propre.copyOfRange(coupe + (perduMs / 1000 * rate).toInt(), propre.size)
            val r = RedecodageSstv.decode(pcm, rate, r36)!!
            val sans = RedecodageSstv.decode(pcm, rate, r36, RedecodageSstv.Options(recaleSegments = false))!!
            val haut = ecart(img, r.pixels, y0 = 2, y1 = 40); val hautSans = ecart(img, sans.pixels, y0 = 2, y1 = 40)
            val bas = ecart(img, r.pixels, y0 = 46, y1 = 238)
            assertTrue("$perduMs ms : en haut $haut (sans recalage $hautSans), en bas $bas", haut < 8.0 && haut < hautSans / 2 && bas < 8.0)
            assertTrue(r.entete)
        }
        // A clean sound: the same picture with or without.
        val p = son(img, 300.0, bruit = 600.0)
        assertArrayEquals(RedecodageSstv.decode(p, rate, r36, RedecodageSstv.Options(recaleSegments = false))!!.pixels, RedecodageSstv.decode(p, rate, r36)!!.pixels)
    }

    @Test fun pas_d_image_dans_le_silence() {
        val rnd = java.util.Random(3)
        assertEquals(null, RedecodageSstv.decode(ShortArray(rate * 10) { (rnd.nextGaussian() * 3000).toInt().toShort() }, rate, r36))
    }

    /** The other modes the table knows: each decoded back from its own clean sound. */
    @Test fun les_autres_modes() {
        for (nom in listOf("Robot 72", "Martin M1", "Martin M2", "Scottie S1", "Scottie S2", "Scottie DX", "PD 50", "PD 90", "PD 120", "PD 180")) {
            val m = SstvMode.byName(nom)!!
            val img = IntArray(m.width * m.height) { i -> val x = i % m.width; val y = i / m.width
                if (y < m.height / 2) intArrayOf(0xFF2040C0.toInt(), 0xFFE0E0E0.toInt(), 0xFFC03020.toInt(), 0xFF30A040.toInt())[x * 4 / m.width]
                else { val v = x * 255 / (m.width - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v } }
            val r = RedecodageSstv.decode(SstvEncoder.encode(m, img, 24_000), 24_000, m)
            assertNotNull(nom, r)
            var s = 0L; var n = 0L
            for (y in 0 until m.height) for (x in 4 until m.width - 4) { val p = img[y * m.width + x]; val q = r!!.pixels[y * m.width + x]
                for (sh in intArrayOf(16, 8, 0)) { s += abs(((p shr sh) and 255) - ((q shr sh) and 255)); n++ } }
            println("$nom : écart ${s / n.toDouble()}, entête ${r!!.entete}, lignes ${r.lignes}")
            assertTrue("$nom écart ${s / n.toDouble()}", s / n.toDouble() < 8.0)
        }
    }
}
