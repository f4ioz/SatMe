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
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class GifTest {

    /** A GIF LZW decoder, as viewers read it. */
    private fun delzw(d: ByteArray, tailleMin: Int, n: Int): ByteArray {
        val clear = 1 shl tailleMin; val eoi = clear + 1
        val out = ByteArray(n); var o = 0
        var pos = 0; var acc = 0; var nb = 0
        var largeur = tailleMin + 1
        val pre = IntArray(4096); val suf = IntArray(4096); val lon = IntArray(4096)
        for (i in 0 until clear) { suf[i] = i; lon[i] = 1 }
        var dispo = eoi + 1; var avant = -1
        fun lit(): Int { while (nb < largeur) { acc = acc or ((d[pos++].toInt() and 0xFF) shl nb); nb += 8 }
            val c = acc and ((1 shl largeur) - 1); acc = acc ushr largeur; nb -= largeur; return c }
        fun premier(c: Int): Int { var k = c; while (lon[k] > 1) k = pre[k]; return suf[k] }
        fun ecrit(c: Int) { val l = lon[c]; var k = c; for (i in l - 1 downTo 0) { out[o + i] = suf[k].toByte(); k = pre[k] }; o += l }
        while (true) {
            val c = lit()
            if (c == clear) { largeur = tailleMin + 1; dispo = eoi + 1; avant = -1; continue }
            if (c == eoi) break
            if (avant < 0) { ecrit(c); avant = c; continue }
            val f = if (c < dispo) premier(c) else premier(avant)
            if (dispo < 4096) { pre[dispo] = avant; suf[dispo] = f; lon[dispo] = lon[avant] + 1; dispo++
                if (dispo >= (1 shl largeur) && largeur < 12) largeur++ }
            ecrit(c); avant = c
        }
        assertEquals(n, o)
        return out
    }

    @Test
    fun le_lzw_se_relit_comme_un_lecteur_de_gif() {
        val r = Random(7)
        for (taille in listOf(1, 2, 300, 5_000, 120_000)) {
            // Long runs and noise, as a picture with its noisy lines.
            val ind = ByteArray(taille) { i -> if ((i / 900) % 2 == 0) (i / 40 % 7).toByte() else r.nextInt(256).toByte() }
            assertArrayEquals(ind, delzw(Gif.lzw(ind, 8), 8, taille))
        }
    }

    @Test
    fun une_animation_est_un_gif_qui_boucle() {
        val w = 32; val h = 24
        val px = IntArray(w * h) { i -> if (i % w < 16) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val pal = Gif.palette(px, listOf(0x101418))
        assertEquals(256, pal.size)
        assertTrue(pal.any { it == 0xFF0000 } && pal.any { it == 0x0000FF } && pal.any { it == 0x101418 })
        val o = ByteArrayOutputStream()
        val g = Gif.Ecrivain(o, w, h, pal)
        g.trame(px, 0, h, 10); g.trame(px, 5, 9, 10); g.fin()
        val b = o.toByteArray()
        assertEquals("GIF89a", String(b, 0, 6))
        assertEquals(w, (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8))
        assertTrue(String(b, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals(0x3B, b.last().toInt())
    }

    @Test
    fun les_etapes_suivent_la_reception_et_finissent_l_image() {
        val cal = listOf(1000L to 10, 2000L to 120, 3000L to 240)
        val e = SstvVideo.etapesGif(cal, 4000L, 240, images = 8)
        assertEquals(8, e.size)
        assertEquals(240, e.last())
        assertTrue(e.zipWithNext().all { (a, b) -> b >= a })
        // Nothing decoded: an even pace.
        assertEquals(listOf(60, 120, 180, 240), SstvVideo.etapesGif(emptyList(), 4000L, 240, images = 4))
    }
}
