/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.apt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * APT decoding, checked on a synthetic signal.
 *
 * A NOAA pass cannot be ordered on demand, so we build a full APT signal from
 * a known image (rising ramp on the left, falling on the right) and ask the
 * decoder to recover it.
 *
 * Proven: AM demodulation, resampling to 4160 words/s, and locking on the
 * sync burst. Not proven: behaviour on a real noisy signal with Doppler and
 * fading. Hence "beta" until someone decodes a real image.
 */
class AptDecoderTest {

    private val fs = 44_100

    // ------------------------------------------------------------- geometry

    @Test
    fun `la ligne fait bien 2080 mots repartis en deux canaux`() {
        assertEquals(2080, Apt.WORDS_PER_LINE)
        assertEquals(4160, Apt.WORD_RATE)
        // Channel A then B, each sync + space + video + telemetry.
        assertEquals(Apt.SPACE_A, Apt.SYNC_A + Apt.SYNC_LEN)
        assertEquals(Apt.VIDEO_A, Apt.SPACE_A + Apt.SPACE_LEN)
        assertEquals(Apt.TELEMETRY_A, Apt.VIDEO_A + Apt.VIDEO_LEN)
        assertEquals(Apt.SYNC_B, Apt.TELEMETRY_A + Apt.TELEMETRY_LEN)
        assertEquals(Apt.SPACE_B, Apt.SYNC_B + Apt.SYNC_LEN)
        assertEquals(Apt.VIDEO_B, Apt.SPACE_B + Apt.SPACE_LEN)
        assertEquals(Apt.TELEMETRY_B, Apt.VIDEO_B + Apt.VIDEO_LEN)
        assertEquals(Apt.WORDS_PER_LINE, Apt.TELEMETRY_B + Apt.TELEMETRY_LEN)
    }

    @Test
    fun `les salves portent sept creneaux`() {
        assertEquals(39, Apt.SYNC_A_PATTERN.size)
        assertEquals(39, Apt.SYNC_B_PATTERN.size)
        // Sync A: 1040 Hz, four words per cycle, two high.
        assertEquals(14f, Apt.SYNC_A_PATTERN.sum(), 0.001f)
        // Sync B: 832 Hz, five words per cycle, three high.
        assertEquals(21f, Apt.SYNC_B_PATTERN.sum(), 0.001f)
        // The leading words stay black in both.
        assertEquals(0f, Apt.SYNC_A_PATTERN[0], 0f)
        assertEquals(0f, Apt.SYNC_B_PATTERN[3], 0f)
    }

    // ------------------------------------------------------------- decoding

    @Test
    fun `un signal fabrique se decode en lignes`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        // The filter takes one line to settle and the last is truncated.
        assertTrue("lignes rendues : ${lines.size}", lines.size >= 11)
        assertEquals(Apt.WORDS_PER_LINE, lines[0].size)
    }

    @Test
    fun `le degrade du canal A se retrouve`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = false), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] < 60)
        assertTrue("milieu = ${g[454]}", g[454] in 90..165)
        assertTrue("bord droit = ${g[900]}", g[900] > 195)
    }

    @Test
    fun `le canal B descend quand le canal A monte`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = true), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] > 195)
        assertTrue("bord droit = ${g[900]}", g[900] < 60)
    }

    @Test
    fun `le calage ne depend pas de l-instant ou l-on commence a ecouter`() {
        // 7431 samples of leading silence: line start no longer falls on a
        // round boundary, the usual case on air.
        val lines = AptDecoder.decodeAll(signal(14, lead = 7_431), fs)
        assertTrue("lignes rendues : ${lines.size}", lines.size >= 10)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = false), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] < 60)
        assertTrue("bord droit = ${g[900]}", g[900] > 195)
    }

    @Test
    fun `la salve est reconnue franchement sur un signal propre`() {
        var q = 0f
        val d = AptDecoder(fs, object : AptDecoder.Listener {
            override fun onLine(index: Int, line: FloatArray) {}
            override fun onSync(locked: Boolean, quality: Float) { if (locked) q = quality }
        })
        val s = signal(10)
        d.feed(s, s.size)
        d.finish()
        assertTrue("qualité = $q", q > 0.6f)
        assertTrue(d.locked)
    }

    @Test
    fun `du bruit ne fabrique pas une image`() {
        val rnd = Random(7)
        val s = ShortArray(fs * 6) { (rnd.nextInt(-9000, 9000)).toShort() }
        val d = AptDecoder(fs)
        d.feed(s, s.size)
        d.finish()
        // Either no lock, or a clearly bad one: either way the caller knows
        // there is no image.
        assertTrue("qualité = ${d.quality}", !d.locked || d.quality < 0.55f)
    }

    @Test
    fun `les lignes sont numerotees sans trou`() {
        val seen = ArrayList<Int>()
        val d = AptDecoder(fs, object : AptDecoder.Listener {
            override fun onLine(index: Int, line: FloatArray) { seen.add(index) }
            override fun onSync(locked: Boolean, quality: Float) {}
        })
        val s = signal(10)
        // In small chunks, like audio capture.
        var i = 0
        val chunk = 4096
        while (i < s.size) {
            val n = minOf(chunk, s.size - i)
            d.feed(s.copyOfRange(i, i + n), n)
            i += n
        }
        d.finish()
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.indices.toList(), seen)
    }

    // ------------------------------------------------------------- contrast

    @Test
    fun `une ligne de parasites ne delave pas toute l-image`() {
        val clean = ArrayList<FloatArray>()
        repeat(300) { clean.add(rawLine()) }
        val lv0 = Apt.levels(clean)
        // One line at 100× the useful level over ~300 lines: under 1% of words,
        // so the percentile bounds exclude it.
        clean.add(FloatArray(Apt.WORDS_PER_LINE) { 100f })
        val lv1 = Apt.levels(clean)
        assertTrue("avant ${lv0[1]}, après ${lv1[1]}", lv1[1] < lv0[1] * 3f)
    }

    @Test
    fun `un tableau vide ne fait pas exploser le calcul de contraste`() {
        val lv = Apt.levels(emptyList())
        assertTrue(lv[1] > lv[0])
        val g = Apt.gray(FloatArray(10), lv[0], lv[1])
        assertEquals(10, g.size)
    }

    @Test
    fun `les valeurs hors bornes sont ramenees dans l-echelle`() {
        val g = Apt.gray(floatArrayOf(-5f, 0f, 0.5f, 1f, 12f), 0f, 1f)
        assertEquals(0, g[0])
        assertEquals(0, g[1])
        assertEquals(127, g[2])
        assertEquals(255, g[3])
        assertEquals(255, g[4])
    }

    // ------------------------------------------------------------- synthesis

    /** An image line as a NOAA satellite would send it. */
    private fun rawLine(): FloatArray {
        val w = FloatArray(Apt.WORDS_PER_LINE)
        for (k in 0 until Apt.SYNC_LEN) w[Apt.SYNC_A + k] = Apt.SYNC_A_PATTERN[k]
        for (k in 0 until Apt.VIDEO_LEN) w[Apt.VIDEO_A + k] = k / (Apt.VIDEO_LEN - 1f)
        for (k in 0 until Apt.TELEMETRY_LEN) w[Apt.TELEMETRY_A + k] = 0.5f
        for (k in 0 until Apt.SYNC_LEN) w[Apt.SYNC_B + k] = Apt.SYNC_B_PATTERN[k]
        for (k in 0 until Apt.SPACE_LEN) w[Apt.SPACE_B + k] = 1f
        for (k in 0 until Apt.VIDEO_LEN) w[Apt.VIDEO_B + k] = 1f - k / (Apt.VIDEO_LEN - 1f)
        for (k in 0 until Apt.TELEMETRY_LEN) w[Apt.TELEMETRY_B + k] = 0.5f
        return w
    }

    /** Modulates the lines onto a 2400 Hz subcarrier. */
    private fun signal(lineCount: Int, lead: Int = 0): ShortArray {
        val words = FloatArray(lineCount * Apt.WORDS_PER_LINE)
        val line = rawLine()
        for (l in 0 until lineCount) {
            System.arraycopy(line, 0, words, l * Apt.WORDS_PER_LINE, Apt.WORDS_PER_LINE)
        }
        val total = lead + (words.size.toLong() * fs / Apt.WORD_RATE).toInt() + 1
        val out = ShortArray(total)
        for (n in 0 until total) {
            val v = if (n < lead) 0f else {
                val wi = ((n - lead).toLong() * Apt.WORD_RATE / fs).toInt()
                if (wi < words.size) words[wi] else 0f
            }
            val a = 0.05 + 0.95 * v
            out[n] = (a * 12_000.0 * cos(2.0 * PI * Apt.SUBCARRIER_HZ * n / fs)).toInt().toShort()
        }
        return out
    }
}
