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

/**
 * An FM receiver's noise once the satellite stops sending: dull, mostly
 * low, and its frequency reading dips under the sync tone often enough to
 * pass for sync pulses. On 22/10 (SAKHACUBE-CHOLBON, 13:08 pass) a picture
 * started on the last pulses of a transmission ran on that noise for 36 s
 * and was kept as « complete »: the pass's first picture, all noise, after
 * the sound.
 */
class SstvBruitFmTest {

    private val fs = 48_000
    private val R36 = SstvMode.byName("Robot 36")!!

    /** White noise through a one-pole low pass at [fcHz]: the discriminator's noise, de-emphasised. */
    private fun bruitFm(n: Int, fcHz: Double, seed: Long, rms: Double = 9_000.0): ShortArray {
        val r = java.util.Random(seed)
        val a = Math.exp(-2 * Math.PI * fcHz / fs)
        var y = 0.0
        val x = DoubleArray(n) { y = a * y + (1 - a) * r.nextGaussian(); y }
        val e = Math.sqrt(x.sumOf { it * it } / n)
        return ShortArray(n) { (x[it] / e * rms).coerceIn(-32767.0, 32767.0).toInt().toShort() }
    }

    private class Image(val lignes: Int, val complete: Boolean)

    private fun decode(pcm: ShortArray, force: SstvMode?, avant: (SstvDecoder) -> Unit = {}): Pair<SstvDecoder, List<Image>> {
        val l = ArrayList<Image>()
        val d = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { l += Image(linesDone, complete) }
        })
        d.continuous = true
        d.forcedMode = force
        avant(d)
        var i = 0
        while (i < pcm.size) { val n = minOf(960, pcm.size - i); d.feed(pcm.copyOfRange(i, i + n), n); i += n }
        return d to l
    }

    private fun mire(): ShortArray {
        val m = R36
        val img = IntArray(m.width * m.height) { i -> val x = i % m.width; if ((x / 40) % 2 == 0) 0x808080 else 0x202020 }
        return SstvTestSignal.encode(m, img, fs)
    }

    @Test
    fun une_image_lancee_sur_la_fin_d_une_emission_ne_court_pas_sur_le_bruit() {
        val s = mire()
        // The last 1.5 s of the transmission, then the receiver's noise.
        val queue = s.copyOfRange(s.size - fs * 3 / 2, s.size)
        val pcm = queue + bruitFm(fs * 45, 500.0, 7)
        for (force in listOf(R36, null)) {
            val (_, images) = decode(pcm, force)
            assertTrue("no picture of noise kept as complete ($force)", images.none { it.complete })
            // Its few real lines only: fewer than what a picture is kept from.
            assertTrue("lines: " + images.map { it.lignes }, images.all { it.lignes < R36.height / 8 })
        }
    }

    @Test
    fun une_image_qui_s_eteint_dans_le_bruit_s_arrete_a_ses_dernieres_lignes() {
        val s = mire()
        // The satellite sets halfway through the picture.
        val moitie = s.copyOfRange(0, s.size / 2)
        val (_, images) = decode(moitie + bruitFm(fs * 45, 500.0, 11), null)
        assertEquals(1, images.size)
        val i = images[0]
        assertTrue("partial", !i.complete)
        // About half its lines, not the noise after them.
        assertTrue("lines: " + i.lignes, i.lignes in 100..135)
    }

    @Test
    fun une_image_bruitee_mais_reelle_reste_entiere() {
        val s = mire()
        val b = bruitFm(s.size, 500.0, 3, rms = 1_500.0)
        val pcm = ShortArray(s.size) { (s[it] + b[it]).coerceIn(-32767, 32767).toShort() }
        val (_, images) = decode(pcm + ShortArray(fs), null)
        assertEquals(1, images.size)
        assertTrue(images[0].complete)
    }

    @Test
    fun un_faux_depart_ne_laisse_pas_son_accord() {
        // Noise alone, a tuning left by an earlier picture: after a long silence it is forgotten,
        // so the next header and the next train are read as they come (an FM tone never moves).
        val (d, _) = decode(bruitFm(fs * 70, 500.0, 5), R36) { it.recale(0.0, 180.0) }
        assertEquals(0f, d.correctionHz, 0.01f)
    }
}
