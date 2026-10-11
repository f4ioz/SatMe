/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SSTV encoder: image in, audio out.
 *
 * Written from the published mode descriptions, not from the decoder's code,
 * so the two can disagree — that is what makes the encode/decode round-trip
 * test worth anything. Also used in the field: a test card played on one
 * phone and decoded on another checks the whole receive chain without a
 * satellite.
 *
 * Streams through [Source]: a PD 290 lasts almost five minutes, ~25 MB of PCM
 * if rendered at once. [encode] is for tests with short modes.
 */
object SstvEncoder {

    /**
     * Sample-by-sample transmission. Keeps the sine phase across reads: a
     * click at a buffer boundary looks like a spurious sync to a decoder.
     */
    class Source(
        private val mode: SstvMode,
        private val image: IntArray,
        private val sampleRate: Int,
        private val blocks: Int = mode.blocks,
        private val leadMs: Double = 120.0,
        /** A real transmitter does not drop the carrier on the last pixel. */
        private val trailMs: Double = 60.0
    ) {
        private val spms = sampleRate / 1000.0
        private val header = headerTones(mode)
        private val headerMs = header.sumOf { it.second }

        /** Total samples in the transmission. */
        val totalSamples: Int =
            ((leadMs + headerMs + blocks * mode.blockMs + trailMs) * spms).roundToInt()

        private var pos = 0
        private var phase = 0.0

        /** Progress 0..1, for the progress bar. */
        val progress: Float
            get() = if (totalSamples <= 0) 1f else (pos.toFloat() / totalSamples).coerceIn(0f, 1f)

        val done: Boolean get() = pos >= totalSamples

        /** Fills [buf]; returns samples written, 0 once finished. */
        fun read(buf: ShortArray): Int {
            val n = minOf(buf.size, totalSamples - pos)
            if (n <= 0) return 0
            for (k in 0 until n) {
                val t = (pos + k) / spms
                val f: Double = when {
                    t < leadMs -> 0.0                                 // silence
                    t < leadMs + headerMs -> headerTone(header, t - leadMs)
                    else -> {
                        val into = t - leadMs - headerMs
                        val b = (into / mode.blockMs).toInt()
                        if (b >= blocks) SstvTone.PORCH
                        else blockTone(mode, b, into - b * mode.blockMs, image)
                    }
                }
                if (f <= 0.0) {
                    buf[k] = 0
                } else {
                    phase += 2.0 * PI * f / sampleRate
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    buf[k] = (sin(phase) * 26000.0).roundToInt().toShort()
                }
            }
            pos += n
            return n
        }
    }

    /** Full transmission length, seconds. */
    fun seconds(mode: SstvMode, leadMs: Double = 120.0, trailMs: Double = 60.0): Double =
        (leadMs + headerTones(mode).sumOf { it.second } +
            mode.blocks * mode.blockMs + trailMs) / 1000.0

    /** Renders VIS header and image at once. Tests and short modes only; see [Source]. */
    fun encode(
        mode: SstvMode,
        image: IntArray,
        sampleRate: Int,
        blocks: Int = mode.blocks,
        leadMs: Double = 120.0,
        trailMs: Double = 60.0
    ): ShortArray {
        val src = Source(mode, image, sampleRate, blocks, leadMs, trailMs)
        val out = ShortArray(src.totalSamples)
        var off = 0
        val chunk = ShortArray(8192)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            System.arraycopy(chunk, 0, out, off, n)
            off += n
        }
        return out
    }

    // ------------------------------------------------------------------ header

    private fun headerTones(mode: SstvMode): List<Pair<Double, Double>> {
        val l = mutableListOf<Pair<Double, Double>>()
        l += 1900.0 to 300.0
        l += 1200.0 to 10.0
        l += 1900.0 to 300.0
        l += 1200.0 to 30.0                       // start bit
        var ones = 0
        for (b in 0 until 7) {
            val one = (mode.vis shr b) and 1 == 1
            if (one) ones++
            l += (if (one) 1100.0 else 1300.0) to 30.0
        }
        l += (if (ones % 2 == 1) 1100.0 else 1300.0) to 30.0     // even parity
        l += 1200.0 to 30.0                       // stop bit
        // Scottie prefixes the picture with a lone sync pulse.
        if (mode.firstBlockOffsetMs > 0.0) l += 1200.0 to mode.firstBlockOffsetMs
        return l
    }

    private fun headerTone(tones: List<Pair<Double, Double>>, t: Double): Double {
        var acc = 0.0
        for ((f, d) in tones) {
            acc += d
            if (t < acc) return f
        }
        return tones.last().first
    }

    // ------------------------------------------------------------------- image

    private fun blockTone(m: SstvMode, block: Int, t: Double, img: IntArray): Double {
        if (t >= m.syncAtMs && t < m.syncAtMs + m.syncMs) return SstvTone.SYNC
        for (seg in m.segments) {
            if (t >= seg.startMs && t < seg.startMs + seg.durMs) {
                val x = (((t - seg.startMs) / seg.durMs) * m.width).toInt()
                    .coerceIn(0, m.width - 1)
                return SstvTone.tone(channel(m, seg.role, block, x, img))
            }
        }
        return gapTone(m, block, t)
    }

    /** Porches and separators. Only the Robot family varies them. */
    private fun gapTone(m: SstvMode, block: Int, t: Double): Double {
        if (m.family == Family.ROBOT36 || m.family == Family.YUV) {
            for (seg in m.segments) {
                if (seg.role != Role.CR && seg.role != Role.CB && seg.role != Role.C_ALT) continue
                if (t >= seg.startMs - 1.5 && t < seg.startMs) return 1900.0
                if (t >= seg.startMs - 6.0 && t < seg.startMs - 1.5) {
                    val isCb = when (seg.role) {
                        Role.CB -> true
                        Role.C_ALT -> block % 2 == 1
                        else -> false
                    }
                    return if (isCb) 2300.0 else 1500.0
                }
            }
        }
        return SstvTone.PORCH
    }

    private fun channel(m: SstvMode, role: Role, block: Int, x: Int, img: IntArray): Int {
        val rowA = block * m.linesPerBlock
        val rowB = rowA + 1
        return when (role) {
            Role.RED -> comp(m, img, rowA, x, 16)
            Role.GREEN -> comp(m, img, rowA, x, 8)
            Role.BLUE -> comp(m, img, rowA, x, 0)
            Role.Y1 -> luma(m, img, rowA, x)
            Role.Y2 -> luma(m, img, rowB, x)
            Role.CR ->
                if (m.linesPerBlock == 2) (cr(m, img, rowA, x) + cr(m, img, rowB, x)) / 2
                else cr(m, img, rowA, x)
            Role.CB ->
                if (m.linesPerBlock == 2) (cb(m, img, rowA, x) + cb(m, img, rowB, x)) / 2
                else cb(m, img, rowA, x)
            // Robot 36 sends R-Y on even lines and B-Y on odd ones.
            Role.C_ALT -> if (block % 2 == 0) cr(m, img, rowA, x) else cb(m, img, rowA, x)
        }
    }

    private fun comp(m: SstvMode, img: IntArray, row: Int, x: Int, shift: Int): Int {
        val r = row.coerceIn(0, m.height - 1)
        return (img[r * m.width + x] shr shift) and 0xFF
    }

    private fun luma(m: SstvMode, img: IntArray, row: Int, x: Int): Int =
        SstvTone.luma(comp(m, img, row, x, 16), comp(m, img, row, x, 8), comp(m, img, row, x, 0))

    private fun cb(m: SstvMode, img: IntArray, row: Int, x: Int): Int =
        SstvTone.cb(comp(m, img, row, x, 16), comp(m, img, row, x, 8), comp(m, img, row, x, 0))

    private fun cr(m: SstvMode, img: IntArray, row: Int, x: Int): Int =
        SstvTone.cr(comp(m, img, row, x, 16), comp(m, img, row, x, 8), comp(m, img, row, x, 0))
}
