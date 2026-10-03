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
import java.io.File

/** Each picture keeps its own sound: just that sound decodes the picture again. */
class SstvSonTest {

    private fun barres(m: SstvMode) = IntArray(m.width * m.height) { i ->
        val x = i % m.width
        intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())[x * 4 / m.width]
    }

    @Test
    fun la_memoire_garde_le_son_recent_a_11_khz() {
        val m = SstvSon.Memoire(44_100, secondes = 2)
        assertEquals(4, m.facteur); assertEquals(11_025, m.rate)
        assertEquals(12_000, SstvSon.Memoire(48_000).rate)
        val pcm = ShortArray(44_100 * 3) { (it / 4 % 1000).toShort() }
        m.ajoute(pcm, pcm.size)
        // The first second is gone; the rest is there.
        assertEquals(0, m.extrait(0, 44_100L).size)
        val e = m.extrait(44_100L * 2, 44_100L * 3)
        assertEquals(11_025, e.size)
        assertEquals((22_050 % 1000).toShort(), e[0])
    }

    @Test
    fun le_wav_se_relit() {
        val f = File.createTempFile("son", ".wav")
        val pcm = ShortArray(1000) { (it * 31 - 15_000).toShort() }
        SstvSon.ecritWav(f, pcm, 11_025)
        val (lu, rate) = SstvSon.litWav(f)!!
        assertEquals(11_025, rate)
        assertArrayEquals(pcm, lu)
        f.delete()
    }

    @Test
    fun le_son_d_une_image_seule_la_redecode_et_fait_sa_video() {
        val m = SstvMode.byName("Robot 36")!!
        val rate = 44_100
        val silence = ShortArray(rate * 3)
        val signal = SstvTestSignal.encode(m, barres(m), rate)
        val tout = silence + signal + silence
        // As the hub does: sound kept, then decoded; the picture's sound taken at its end.
        val mem = SstvSon.Memoire(rate)
        var clip: ShortArray? = null
        lateinit var d: SstvDecoder
        d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                clip = mem.extrait(d.debutTrame, d.echantillons + rate / 2)
            }
        })
        val bloc = ShortArray(4096)
        var i = 0
        while (i < tout.size) {
            val n = minOf(bloc.size, tout.size - i)
            System.arraycopy(tout, i, bloc, 0, n); i += n
            mem.ajoute(bloc, n); d.feed(bloc, n)
        }
        d.finish()
        val c = clip!!
        // About the picture's length, not the whole recording.
        val secondes = c.size / mem.rate.toDouble()
        assertTrue("$secondes s", secondes > m.frameSeconds && secondes < m.frameSeconds + 3)

        // Decoded again at 11 kHz: the same picture.
        var complete = false
        var px: IntArray? = null
        val d2 = SstvDecoder(mem.rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete2: Boolean) {
                complete = complete2; px = pixels.copyOf()
            }
        })
        var j = 0
        while (j < c.size) { val n = minOf(1024, c.size - j); d2.feed(c.copyOfRange(j, j + n), n); j += n }
        d2.finish()
        assertTrue(complete)
        val rouge = px!![m.height / 2 * m.width + m.width / 8]
        assertTrue(((rouge shr 16) and 0xFF) > 180 && ((rouge shr 8) and 0xFF) < 80)

        // The video follows the picture as it arrives.
        val cal = SstvVideo.calendrier(c, mem.rate)
        assertTrue(cal.size > 10)
        assertEquals(m.height, cal.last().second)
        val milieu = SstvVideo.lignesA(cal, c.size / 2L, c.size.toLong(), m.height)
        assertTrue("$milieu", milieu in m.height / 4..m.height * 3 / 4)
        assertEquals(0, SstvVideo.lignesA(cal, 0, c.size.toLong(), m.height))
    }
}
