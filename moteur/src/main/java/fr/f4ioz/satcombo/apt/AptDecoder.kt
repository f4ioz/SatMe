/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.apt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of an APT line.
 *
 * NOAA satellites send images on 137 MHz as an AM 2400 Hz subcarrier. The rate
 * has been fixed since 1978: 4160 words/s, two lines/s, so 2080 words per line.
 * A line carries two images side by side — channel A (visible or near IR
 * depending on time of day) and channel B (thermal IR) — each preceded by a
 * sync burst and followed by a telemetry wedge.
 *
 * All offsets are constants in one place: the day a line is off by one word,
 * there is only one spot to re-read.
 *
 * No Android import: the full decode is unit-tested, which matters all the
 * more since NOAA passes do not come on demand.
 */
object Apt {

    /** Words per second, fixed by the standard. */
    const val WORD_RATE = 4160

    /** Words per line, hence two lines per second. */
    const val WORDS_PER_LINE = 2080

    /** AM subcarrier, Hz. */
    const val SUBCARRIER_HZ = 2400.0

    /** Sync burst length, in words. */
    const val SYNC_LEN = 39

    const val SYNC_A = 0
    const val SPACE_A = 39
    const val SPACE_LEN = 47
    const val VIDEO_A = 86
    const val VIDEO_LEN = 909
    const val TELEMETRY_A = 995
    const val TELEMETRY_LEN = 45
    const val SYNC_B = 1040
    const val SPACE_B = 1079
    const val VIDEO_B = 1126
    const val TELEMETRY_B = 2035

    /**
     * Sync A: seven pulses at 1040 Hz, four words per cycle. Four black words
     * before, black padding after, 39 words in all.
     */
    val SYNC_A_PATTERN: FloatArray = buildSync(high = 2, low = 2)

    /** Sync B: seven pulses at 832 Hz, five words per cycle. */
    val SYNC_B_PATTERN: FloatArray = buildSync(high = 3, low = 2)

    private fun buildSync(high: Int, low: Int): FloatArray {
        val out = FloatArray(SYNC_LEN)
        var i = 4
        repeat(7) {
            repeat(high) { if (i < SYNC_LEN) out[i] = 1f; i++ }
            i += low
        }
        return out
    }

    /** Extracts one channel's 909 words from a full line. */
    fun channel(line: FloatArray, b: Boolean): FloatArray {
        val from = if (b) VIDEO_B else VIDEO_A
        if (line.size < from + VIDEO_LEN) return FloatArray(VIDEO_LEN)
        return line.copyOfRange(from, from + VIDEO_LEN)
    }

    /**
     * The two contrast bounds, from image words only.
     *
     * Plain min/max stretching gives a washed-out image: one line of
     * interference fills the whole scale. The 1st and 99th percentiles are
     * used instead, burning 1 % of pixels at each end for usable contrast.
     * Sync bursts and telemetry are excluded: always pure black or white, they
     * would skew the bounds.
     */
    fun levels(lines: List<FloatArray>): FloatArray {
        if (lines.isEmpty()) return floatArrayOf(0f, 1f)
        val total = lines.size * VIDEO_LEN * 2
        val stride = max(1, total / 60_000)
        val out = FloatArray(total / stride + 4)
        var idx = 0
        var n = 0
        for (line in lines) {
            if (line.size < VIDEO_B + VIDEO_LEN) continue
            for (k in 0 until VIDEO_LEN) {
                if (idx++ % stride == 0 && n < out.size) out[n++] = line[VIDEO_A + k]
                if (idx++ % stride == 0 && n < out.size) out[n++] = line[VIDEO_B + k]
            }
        }
        if (n < 2) return floatArrayOf(0f, 1f)
        val used = out.copyOf(n)
        used.sort()
        val lo = used[(n * 0.01f).toInt().coerceIn(0, n - 1)]
        var hi = used[(n * 0.99f).toInt().coerceIn(0, n - 1)]
        if (hi <= lo) hi = lo + 1e-6f
        return floatArrayOf(lo, hi)
    }

    /** Maps a raw line to 0..255 with the given bounds. */
    fun gray(line: FloatArray, lo: Float, hi: Float): IntArray {
        val span = if (hi > lo) hi - lo else 1e-6f
        val out = IntArray(line.size)
        for (i in line.indices) {
            val v = (line[i] - lo) / span * 255f
            out[i] = when {
                v.isNaN() -> 0
                v < 0f -> 0
                v > 255f -> 255
                else -> v.toInt()
            }
        }
        return out
    }
}

/**
 * APT decoder: audio in, image lines out. Three stages.
 *
 * Demodulation: the 2400 Hz subcarrier's amplitude carries the image. Mix with
 * cos and sin at that frequency, low-pass, and the I/Q magnitude is the
 * envelope. Using both paths means the carrier phase need not be known, which
 * would be hopeless with an SDR dongle or a cable from a receiver.
 *
 * Resampling: the envelope comes at the sound card rate (usually 44.1 kHz) and
 * the image is read at 4160 words/s; linear interpolation lands on each word.
 *
 * Line alignment: each line starts with a sync burst, found by correlation.
 * The first search covers a whole line; after that only ±[DRIFT] words around
 * the expected spot — enough to follow a phone clock's drift, too little to be
 * thrown off by a burst of interference. When correlation collapses, timing is
 * held without re-syncing: one second of lost signal must not shift the rest
 * of the image.
 *
 * No Android dependency: everything is tested on synthetic signals, since NOAA
 * satellites do not come on demand.
 */
class AptDecoder(
    private val sampleRate: Int,
    var listener: Listener? = null
) {

    interface Listener {
        /** One full line of 2080 raw words, in arrival order. */
        fun onLine(index: Int, line: FloatArray)

        /** Line lock state and quality of the last burst. */
        fun onSync(locked: Boolean, quality: Float)
    }

    /** Lines output so far. */
    var lineCount: Int = 0
        private set

    /** Quality of the last accepted correlation, 0 to 1. */
    var quality: Float = 0f
        private set

    /** True when a line was found and timing is held. */
    var locked: Boolean = false
        private set

    // --------------------------------------------------------- demodulation

    private val taps: Int = 101
    private val fir: DoubleArray = lowpass(sampleRate, 2200.0, taps)
    private val bufI = DoubleArray(taps * 2)
    private val bufQ = DoubleArray(taps * 2)
    private var wp = 0

    // Rotating phasor: cheaper than a cos per sample, renormalised regularly
    // so rounding error does not shrink it.
    private val dc = cos(2.0 * PI * Apt.SUBCARRIER_HZ / sampleRate)
    private val ds = sin(2.0 * PI * Apt.SUBCARRIER_HZ / sampleRate)
    private var oc = 1.0
    private var os = 0.0
    private var spin = 0

    // ------------------------------------------------------ resampling

    private val step: Double = sampleRate.toDouble() / Apt.WORD_RATE
    private var nextPos = 0.0
    private var absIndex = 0L
    private var lastEnv = 0.0

    // ------------------------------------------------------------ line alignment

    private val wbuf = FloatArray(Apt.WORDS_PER_LINE * 6)
    private var wn = 0
    private var start = 0

    private val tpl = FloatArray(Apt.SYNC_LEN)
    private var tplNorm = 1.0

    init {
        var mean = 0f
        for (v in Apt.SYNC_A_PATTERN) mean += v
        mean /= Apt.SYNC_LEN
        var sum = 0.0
        for (i in 0 until Apt.SYNC_LEN) {
            tpl[i] = Apt.SYNC_A_PATTERN[i] - mean
            sum += tpl[i].toDouble() * tpl[i]
        }
        tplNorm = sqrt(max(sum, 1e-12))
    }

    /** Feeds audio to the decoder. Samples are consumed immediately. */
    fun feed(pcm: ShortArray, count: Int) {
        val n = min(count, pcm.size)
        for (i in 0 until n) {
            val s = pcm[i] / 32768.0

            // I/Q downconversion to baseband.
            val vi = s * oc
            val vq = -s * os
            val nc = oc * dc - os * ds
            os = os * dc + oc * ds
            oc = nc
            if (++spin >= 1024) {
                spin = 0
                val m = sqrt(oc * oc + os * os)
                if (m > 1e-9) { oc /= m; os /= m } else { oc = 1.0; os = 0.0 }
            }

            bufI[wp] = vi; bufI[wp + taps] = vi
            bufQ[wp] = vq; bufQ[wp + taps] = vq
            wp = if (wp + 1 == taps) 0 else wp + 1

            var ai = 0.0
            var aq = 0.0
            for (k in 0 until taps) {
                val h = fir[k]
                ai += h * bufI[wp + k]
                aq += h * bufQ[wp + k]
            }
            val env = sqrt(ai * ai + aq * aq)

            // At most one word per sample: the step is about 10.6.
            while (nextPos <= absIndex) {
                val f = (nextPos - (absIndex - 1)).coerceIn(0.0, 1.0)
                pushWord(lastEnv + (env - lastEnv) * f)
                nextPos += step
            }
            lastEnv = env
            absIndex++
        }
        drain()
    }

    /** Flushes remaining complete lines. Partial lines are dropped. */
    fun finish() {
        drain()
    }

    private fun pushWord(v: Double) {
        if (wn == wbuf.size) drain()
        if (wn == wbuf.size) { wn = 0; start = 0 } // safety: never get stuck
        wbuf[wn++] = v.toFloat()
    }

    /**
     * Normalised correlation between the expected burst and the words at
     * [off]. Normalised because signal level varies wildly between passes:
     * shape matters, not amplitude.
     */
    private fun corr(off: Int): Double {
        var mean = 0.0
        for (k in 0 until Apt.SYNC_LEN) mean += wbuf[off + k]
        mean /= Apt.SYNC_LEN
        var num = 0.0
        var den = 0.0
        for (k in 0 until Apt.SYNC_LEN) {
            val d = wbuf[off + k] - mean
            num += d * tpl[k]
            den += d * d
        }
        if (den <= 1e-12) return 0.0
        return num / (sqrt(den) * tplNorm)
    }

    private fun drain() {
        val line = Apt.WORDS_PER_LINE

        if (!locked) {
            // Lock needs two bursts: one alone can be chance, two exactly one
            // line apart much less so.
            if (wn - start < line * 3) return
            var best = -2.0
            var bestOff = start
            for (o in start until start + line) {
                val sc = corr(o) + corr(o + line)
                if (sc > best) { best = sc; bestOff = o }
            }
            if (best < LOCK_THRESHOLD * 2) {
                start += line
                compact()
                listener?.onSync(false, 0f)
                return
            }
            start = bestOff
            locked = true
            quality = (best / 2.0).toFloat()
            listener?.onSync(true, quality)
        }

        while (wn - start >= line) {
            val lo = max(0, start - DRIFT)
            val hi = min(start + DRIFT, wn - line)
            if (hi >= lo) {
                var b = -2.0
                var bi = start
                for (o in lo..hi) {
                    val c = corr(o)
                    if (c > b) { b = c; bi = o }
                }
                if (b > LOCK_THRESHOLD) {
                    start = bi
                    quality = b.toFloat()
                } else {
                    // Signal lost: hold timing rather than sync on noise.
                    quality = max(0f, quality * 0.8f)
                }
            }
            if (wn - start < line) break
            val out = FloatArray(line)
            System.arraycopy(wbuf, start, out, 0, line)
            listener?.onLine(lineCount, out)
            lineCount++
            start += line
        }
        compact()
    }

    private fun compact() {
        // Keep a margin behind so the next line's re-sync can still move
        // back a few dozen words.
        val shift = max(0, start - DRIFT * 2)
        if (shift > 0) {
            System.arraycopy(wbuf, shift, wbuf, 0, wn - shift)
            wn -= shift
            start -= shift
        }
    }

    private fun lowpass(fs: Int, cutHz: Double, n: Int): DoubleArray {
        val h = DoubleArray(n)
        val fc = cutHz / fs
        val m = (n - 1) / 2
        var sum = 0.0
        for (i in 0 until n) {
            val k = (i - m).toDouble()
            val sinc = if (k == 0.0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            h[i] = sinc * w
            sum += h[i]
        }
        if (sum != 0.0) for (i in 0 until n) h[i] /= sum
        return h
    }

    companion object {
        /** Below this, a found burst is no better than noise. */
        const val LOCK_THRESHOLD = 0.30

        /** Line-to-line re-sync window, in words. */
        const val DRIFT = 40

        /** Decodes a whole recording at once. Mostly for tests. */
        fun decodeAll(pcm: ShortArray, sampleRate: Int): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            val d = AptDecoder(sampleRate, object : Listener {
                override fun onLine(index: Int, line: FloatArray) { out.add(line) }
                override fun onSync(locked: Boolean, quality: Float) {}
            })
            d.feed(pcm, pcm.size)
            d.finish()
            return out
        }
    }
}
