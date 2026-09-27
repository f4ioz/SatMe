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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The decoder against signals it did not make.
 *
 * Every other SSTV test feeds [SstvEncoder], which shares the decoder's mode
 * table and colour formulas: a mistake common to both passes unseen (that is
 * how full-range colours went unnoticed). These signals come from a small
 * encoder written here from the published specifications, then degraded the
 * way a receiver degrades them: in-band noise, overdrive, mistuning.
 */
class SstvRobustesseTest {

    private val fs = 44_100

    // --- independent encoder ------------------------------------------------

    /** 8 vertical bars: white, yellow, cyan, green, magenta, red, blue, black. */
    private val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00,
        0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)

    private fun rgbAt(x: Int, w: Int) = bars[x * 8 / w]

    private class Tones {
        val f = ArrayList<Double>(); val ms = ArrayList<Double>()
        fun add(hz: Double, d: Double) { f += hz; ms += d }
        fun scan(v: DoubleArray, d: Double) = v.forEach { add(1500 + 800 * it.coerceIn(0.0, 255.0) / 255, d / v.size) }
        /**
         * [drift]: extra shift growing linearly from -drift to +drift.
         * [clockPpm]: the sender's clock runs that much slow, stretching time.
         */
        fun render(fs: Int, offset: Double = 0.0, drift: Double = 0.0, clockPpm: Double = 0.0): DoubleArray {
            val total = ms.sum()
            val tfs = fs * (1 + clockPpm * 1e-6)
            val out = DoubleArray((total / 1000 * tfs).toInt())
            var t = 0.0; var i = 0; var ph = 0.0
            for (k in f.indices) {
                t += ms[k]
                val j = minOf(out.size, (t / 1000 * tfs).toInt())
                while (i < j) {
                    val d = drift * (2.0 * i / out.size - 1)
                    ph += 2 * PI * (f[k] + offset + d) / fs; out[i++] = sin(ph)
                }
            }
            return out
        }
    }

    /** Studio-range YCrCb, as MMSSTV sends it. */
    private fun ycc(c: Int): DoubleArray {
        val r = (c shr 16 and 255).toDouble(); val g = (c shr 8 and 255).toDouble(); val b = (c and 255).toDouble()
        return doubleArrayOf(
            16 + (65.738 * r + 129.057 * g + 25.064 * b) / 256,
            128 + (112.439 * r - 94.154 * g - 18.285 * b) / 256,
            128 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256)
    }

    private fun vis(t: Tones, code: Int) {
        t.add(1900.0, 300.0); t.add(1200.0, 10.0); t.add(1900.0, 300.0); t.add(1200.0, 30.0)
        var ones = 0
        for (k in 0 until 7) { val one = (code shr k) and 1 == 1; if (one) ones++; t.add(if (one) 1100.0 else 1300.0, 30.0) }
        t.add(if (ones % 2 == 1) 1100.0 else 1300.0, 30.0)
        t.add(1200.0, 30.0)
    }

    private fun pd120(header: Boolean = true): Tones {
        val t = Tones(); t.add(1900.0, 200.0); if (header) vis(t, 95)
        val w = 640; val scan = 121.6
        val y = DoubleArray(w) { ycc(rgbAt(it, w))[0] }
        val cr = DoubleArray(w) { ycc(rgbAt(it, w))[1] }
        val cb = DoubleArray(w) { ycc(rgbAt(it, w))[2] }
        repeat(248) {
            t.add(1200.0, 20.0); t.add(1500.0, 2.08)
            t.scan(y, scan); t.scan(cr, scan); t.scan(cb, scan); t.scan(y, scan)
        }
        return t
    }

    private fun martinM1(header: Boolean = true): Tones {
        val t = Tones(); t.add(1900.0, 200.0); if (header) vis(t, 44)
        val w = 320
        val ch = { s: Int -> DoubleArray(w) { (rgbAt(it, w) shr s and 255).toDouble() } }
        val r = ch(16); val g = ch(8); val b = ch(0)
        repeat(256) {
            t.add(1200.0, 4.862); t.add(1500.0, 0.572)
            t.scan(g, 146.432); t.add(1500.0, 0.572)
            t.scan(b, 146.432); t.add(1500.0, 0.572)
            t.scan(r, 146.432); t.add(1500.0, 0.572)
        }
        return t
    }

    private fun robot36(header: Boolean = true): Tones {
        val t = Tones(); t.add(1900.0, 200.0); if (header) vis(t, 8)
        val w = 320
        val y = DoubleArray(w) { ycc(rgbAt(it, w))[0] }
        val cr = DoubleArray(w) { ycc(rgbAt(it, w))[1] }
        val cb = DoubleArray(w) { ycc(rgbAt(it, w))[2] }
        for (line in 0 until 240) {
            t.add(1200.0, 9.0); t.add(1500.0, 3.0); t.scan(y, 88.0)
            if (line % 2 == 0) { t.add(1500.0, 4.5); t.add(1900.0, 1.5); t.scan(cr, 44.0) }
            else { t.add(2300.0, 4.5); t.add(1900.0, 1.5); t.scan(cb, 44.0) }
        }
        return t
    }

    private fun robot72(): Tones {
        val t = Tones(); t.add(1900.0, 200.0)
        val w = 320
        val y = DoubleArray(w) { ycc(rgbAt(it, w))[0] }
        val cr = DoubleArray(w) { ycc(rgbAt(it, w))[1] }
        val cb = DoubleArray(w) { ycc(rgbAt(it, w))[2] }
        repeat(240) {
            t.add(1200.0, 9.0); t.add(1500.0, 3.0); t.scan(y, 138.0)
            t.add(1500.0, 4.5); t.add(1900.0, 1.5); t.scan(cr, 69.0)
            t.add(2300.0, 4.5); t.add(1900.0, 1.5); t.scan(cb, 69.0)
        }
        return t
    }

    private fun scottieS1(): Tones {
        val t = Tones(); t.add(1900.0, 200.0)
        val w = 320
        val ch = { s: Int -> DoubleArray(w) { (rgbAt(it, w) shr s and 255).toDouble() } }
        t.add(1200.0, 9.0)
        repeat(256) {
            t.add(1500.0, 1.5); t.scan(ch(8), 138.24)
            t.add(1500.0, 1.5); t.scan(ch(0), 138.24)
            t.add(1200.0, 9.0); t.add(1500.0, 1.5); t.scan(ch(16), 138.24)
        }
        return t
    }

    // --- degradations -------------------------------------------------------

    /** Adds noise limited to 300-3000 Hz (receiver audio), SNR in that band. */
    private fun bandNoise(x: DoubleArray, snrDb: Double, seed: Long): DoubleArray {
        val rnd = java.util.Random(seed)
        val white = DoubleArray(x.size) { rnd.nextGaussian() }
        val h = bandPass(301, 300.0, 3000.0)
        val n = DoubleArray(x.size)
        for (i in x.indices) {
            var s = 0.0
            for (k in h.indices) { val j = i - k; if (j >= 0) s += h[k] * white[j] }
            n[i] = s
        }
        val ps = x.sumOf { it * it } / x.size
        val pn = n.sumOf { it * it } / n.size
        val g = sqrt(ps / pn / Math.pow(10.0, snrDb / 10))
        return DoubleArray(x.size) { x[it] + g * n[it] }
    }

    /** Receiver noise alone, 300-3000 Hz, RMS 0.5 before the decode gain. */
    private fun pureNoise(n: Int, seed: Long): DoubleArray {
        val rnd = java.util.Random(seed)
        val white = DoubleArray(n) { rnd.nextGaussian() }
        val h = bandPass(301, 300.0, 3000.0)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in h.indices) { val j = i - k; if (j >= 0) s += h[k] * white[j] }
            out[i] = s
        }
        val rms = sqrt(out.sumOf { it * it } / n)
        return DoubleArray(n) { out[it] * 0.5 / rms }
    }

    private fun bandPass(n: Int, lo: Double, hi: Double): DoubleArray {
        val mid = (n - 1) / 2.0
        return DoubleArray(n) { k ->
            val x = k - mid
            val w = 0.54 - 0.46 * cos(2 * PI * k / (n - 1))
            val s = { fc: Double -> val wc = 2 * PI * fc / fs; if (x == 0.0) wc / PI else sin(wc * x) / (PI * x) }
            (s(hi) - s(lo)) * w
        }
    }

    private class Result(val mode: String?, val lines: Int, val complete: Boolean, val px: IntArray, val w: Int, val h: Int)

    private fun decode(x: DoubleArray, gain: Double = 0.5, continuous: Boolean = false): Result {
        val pad = DoubleArray(fs / 3)
        val all = pad + x + DoubleArray(fs / 2)
        val pcm = ShortArray(all.size) { (all[it] * gain).coerceIn(-1.0, 1.0).times(32767).toInt().toShort() }
        var res = Result(null, 0, false, IntArray(0), 0, 0)
        val dec = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (res.mode == null) res = Result(mode.name, linesDone, complete, pixels.copyOf(), mode.width, mode.height)
            }
        })
        dec.continuous = continuous
        var i = 0
        while (i < pcm.size) { val c = minOf(2048, pcm.size - i); dec.feed(pcm.copyOfRange(i, i + c), c); i += c }
        dec.finish()
        return res
    }

    /** Mean colour of bar [k] over its middle half, rows [r0, r1). */
    private fun barMean(r: Result, k: Int, r0: Int, r1: Int): IntArray {
        val x0 = k * r.w / 8 + r.w / 32; val x1 = (k + 1) * r.w / 8 - r.w / 32
        val s = LongArray(3); var n = 0
        for (y in r0 until r1) for (x in x0 until x1) {
            val p = r.px[y * r.w + x]
            s[0] += (p shr 16 and 255).toLong(); s[1] += (p shr 8 and 255).toLong(); s[2] += (p and 255).toLong(); n++
        }
        return IntArray(3) { (s[it] / n).toInt() }
    }

    /** Largest channel error over all eight bars. */
    private fun worstBarError(r: Result, r0: Int = r.h / 8, r1: Int = r.h - r.h / 8): Int {
        var worst = 0
        for (k in 0 until 8) {
            val m = barMean(r, k, r0, r1)
            val e = intArrayOf(bars[k] shr 16 and 255, bars[k] shr 8 and 255, bars[k] and 255)
            for (c in 0..2) worst = maxOf(worst, abs(m[c] - e[c]))
        }
        return worst
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun les_couleurs_suivent_la_plage_studio_de_la_norme() {
        // White is sent as Y = 235 and black as Y = 16. Read as full range,
        // white came out light grey and black dark grey, colours washed out.
        val r = decode(pd120().render(fs))
        assertEquals("PD 120", r.mode)
        val white = barMean(r, 0, 50, 450); val black = barMean(r, 7, 50, 450)
        assertTrue("blanc lu ${white.toList()}", white.all { it >= 245 })
        assertTrue("noir lu ${black.toList()}", black.all { it <= 10 })
        assertTrue("écart des barres ${worstBarError(r)}", worstBarError(r) <= 16)
    }

    @Test
    fun le_bord_droit_ne_prend_pas_la_couleur_du_top_suivant() {
        // The last pixels of a fast chroma scan used to average in the next
        // sync tone: a green (Robot 36) or blue (PD) stripe on a black edge.
        for (t in listOf(robot36(), pd120())) {
            val r = decode(t.render(fs))
            for (y in r.h / 8 until r.h - r.h / 8) for (x in r.w - 3 until r.w) {
                val p = r.px[y * r.w + x]
                val c = intArrayOf(p shr 16 and 255, p shr 8 and 255, p and 255)
                assertTrue("${r.mode} colonne $x ligne $y : ${c.toList()}", c.all { it <= 30 })
            }
        }
    }

    @Test
    fun un_signal_bruite_a_12_dB_donne_une_image_complete() {
        // 12 dB in the receiver's audio band is a usable picture for any
        // reference decoder. SatMe gave up after 32 lines of 496: one noise
        // spike broke the unbroken run of sync samples it required.
        for ((name, t) in listOf("PD 120" to pd120(), "Martin M1" to martinM1())) {
            val r = decode(bandNoise(t.render(fs), 12.0, 7))
            assertEquals(name, r.mode)
            assertTrue("$name : ${r.lines}/${r.h} lignes", r.complete && r.lines == r.h)
            assertTrue("$name : écart des barres ${worstBarError(r)}", worstBarError(r) <= 40)
        }
    }

    @Test
    fun l_en_tete_est_reconnu_a_6_dB() {
        // Read on the narrow reading, whose edge sits on the 1100/1300 Hz bits,
        // the header was lost below about 8 dB and the picture never started.
        val r = decode(bandNoise(pd120().render(fs), 6.0, 5))
        assertEquals("PD 120", r.mode)
        assertTrue("${r.lines}/${r.h}", r.complete)
    }

    @Test
    fun un_pd120_a_8_dB_reste_lisible() {
        // The weakest usable ISS picture. Longest-run sync search lost 190 of
        // 248 pulses here and the frame ran on false wide relocks; the matched
        // filter keeps the lines in place.
        val r = decode(bandNoise(pd120().render(fs), 8.0, 21))
        assertTrue("${r.lines}/${r.h} lignes", r.complete)
        // 12 with the matched filter, 33 with the longest-run search.
        assertTrue("écart des barres ${worstBarError(r)}", worstBarError(r) <= 20)
    }

    @Test
    fun une_entree_saturee_donne_une_image_complete() {
        // Twice full scale, hard-clipped: a sound card input set too hot.
        for ((name, t) in listOf("PD 120" to pd120(), "Martin M1" to martinM1())) {
            val r = decode(t.render(fs), gain = 2.0)
            assertTrue("$name saturé : ${r.lines}/${r.h} lignes", r.complete && r.lines == r.h)
            assertTrue("$name saturé : écart des barres ${worstBarError(r)}", worstBarError(r) <= 30)
        }
    }

    @Test
    fun les_lignes_pd_restent_alignees_malgre_l_horloge() {
        // The locked search window (±10 ms) could not hold a 20 ms PD sync
        // pulse: every locked search failed, and the frame survived on a wide
        // relock every fourth block. With a sound-card clock 300 ppm off, the
        // lines then jumped by about a pixel from one to the next.
        val r = decode(pd120().render(fs, clockPpm = 300.0))
        assertTrue("${r.lines}/${r.h}", r.complete)
        val edges = (40 until 440).map { y ->
            // White (blue 255) to yellow (blue 0): where blue crosses 128.
            var x = r.w / 8 - 20
            while (x < r.w / 8 + 20 && (r.px[y * r.w + x] and 255) > 128) x++
            x
        }
        val mean = edges.average()
        val jitter = sqrt(edges.sumOf { (it - mean) * (it - mean) } / edges.size)
        assertTrue("gigue du bord ${"%.2f".format(jitter)} px", jitter < 0.5)
    }

    @Test
    fun une_coupure_du_son_est_rattrapee_en_deux_lignes() {
        // Audio glitches, as a busy phone or a USB sound card makes them: 1.5 ms
        // played twice near line 30, 16 ms lost near line 100 (both seen on a
        // live capture). The fitted line kept predicting the old position and
        // rejected the new pulses as outliers: ten lines out of place.
        val x = robot36().render(fs)
        val t0 = ((200 + 910 + 1.0) / 1000 * fs).toInt()      // lead + header
        val line = 0.150 * fs
        val a = t0 + (30.5 * line).toInt(); val dup = (0.0015 * fs).toInt()
        val b = t0 + (100.5 * line).toInt(); val cut = (0.016 * fs).toInt()
        val y = x.copyOfRange(0, a) + x.copyOfRange(a - dup, a) + x.copyOfRange(a, b) + x.copyOfRange(b + cut, x.size)
        val r = decode(y)
        val edges = (0 until r.h).map { row ->
            var xx = r.w / 8 - 20
            while (xx < r.w / 8 + 20 && (r.px[row * r.w + xx] and 255) > 128) xx++
            xx
        }
        val median = edges.sorted()[edges.size / 2]
        val off = edges.count { kotlin.math.abs(it - median) > 2 }
        assertTrue("$off lignes décalées (${edges.withIndex().filter { kotlin.math.abs(it.value - median) > 2 }.map { it.index }})", off <= 6)
    }

    @Test
    fun le_decodage_continu_demarre_sans_en_tete() {
        // Continuous decoding: no header (lost in a fade,
        // or joined late), a regular train of sync pulses is enough. Robot 72
        // must not be read as a Robot 36 that missed every other pulse.
        for ((name, t) in listOf("PD 120" to pd120(false), "Martin M1" to martinM1(false),
                "Robot 36" to robot36(false), "Robot 72" to robot72(), "Scottie S1" to scottieS1())) {
            val r = decode(t.render(fs), continuous = true)
            assertEquals(name, r.mode)
            assertTrue("$name : ${r.lines}/${r.h} lignes", r.lines >= r.h * 8 / 10)
        }
    }

    @Test
    fun sans_la_coche_une_image_sans_en_tete_attend_le_bouton() {
        assertEquals(null, decode(pd120(false).render(fs)).mode)
    }

    @Test
    fun le_decodage_continu_ne_voit_pas_d_image_dans_le_bruit() {
        // A minute of receiver noise alone: a false start would archive
        // garbage and hold the decoder for two minutes.
        val r = decode(pureNoise(60 * fs, 11), continuous = true)
        assertEquals(null, r.mode)
    }

    @Test
    fun une_derive_de_frequence_est_suivie() {
        // Uncorrected Doppler on SSB: the tones slide 120 Hz during the frame.
        // Measured once on the header, the correction was only right at the
        // top; the sync pulses (1200 Hz, every line) now keep it right.
        val r = decode(pd120().render(fs, drift = 60.0))
        assertTrue("${r.lines}/${r.h}", r.complete)
        assertTrue("écart en haut ${worstBarError(r, 20, 80)}", worstBarError(r, 20, 80) <= 20)
        assertTrue("écart en bas ${worstBarError(r, 420, 480)}", worstBarError(r, 420, 480) <= 20)
    }

    @Test
    fun un_decalage_de_120_Hz_est_rattrape() {
        // SSB mistuning shifts every tone. The header was refused beyond
        // ±70 Hz; now the leader measures the shift and the picture is read
        // through it.
        for (off in listOf(-120.0, 120.0)) {
            val r = decode(pd120().render(fs, off))
            assertEquals("décalage $off", "PD 120", r.mode)
            assertTrue("décalage $off : ${r.lines}/${r.h}", r.complete)
            assertTrue("décalage $off : écart des barres ${worstBarError(r)}", worstBarError(r) <= 20)
        }
    }
}
