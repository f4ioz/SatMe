/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

/**
 * The SSTV mode table.
 *
 * Every timing here comes from JL Barber N7CXI's "Proposal for SSTV Mode
 * Specifications" (Dayton 2000), cross-checked against the pySSTV and
 * colaclanth/sstv implementations. A wrong figure does not throw — it silently
 * produces a slanted or shredded picture, which is why the unit tests
 * re-synthesise a signal from this very table and decode it back.
 *
 * Everything is expressed as one *block* of transmitted audio. A block is one
 * image line for every mode except the PD family, where a block carries two
 * lines (the chroma is averaged over the pair and sent once).
 *
 * The block is anchored so that all the channels of a line stay inside it. For
 * Martin, Robot and PD the sync pulse opens the block. Scottie puts its sync
 * pulse *between* blue and red, so its block is anchored on the separator
 * before green and the sync sits in the middle — that keeps G, B and R of the
 * same line together instead of straddling two lines.
 */

/** What a timed segment of the block carries. */
enum class Role {
    RED, GREEN, BLUE,
    /** Luma of the first (or only) line of the block. */
    Y1,
    /** Luma of the second line of the block — PD family only. */
    Y2,
    /** R-Y (Cr). */
    CR,
    /** B-Y (Cb). */
    CB,
    /** Robot 36: one chroma per line, which one is told by the separator tone. */
    C_ALT
}

/** How the decoded channels are turned into pixels. */
enum class Family { RGB, YUV, ROBOT36, PD }

/** One timed slice of the block: [startMs] .. [startMs]+[durMs] carries [role]. */
data class Segment(val startMs: Double, val durMs: Double, val role: Role)

data class SstvMode(
    val vis: Int,
    val name: String,
    val width: Int,
    val height: Int,
    /** Duration of one whole block, sync included. */
    val blockMs: Double,
    /** Image lines carried by one block: 1, or 2 for the PD family. */
    val linesPerBlock: Int,
    /** Where the sync pulse starts inside the block. */
    val syncAtMs: Double,
    val syncMs: Double,
    val segments: List<Segment>,
    val family: Family,
    /**
     * Offset from the very first sync pulse found after the VIS header to the
     * start of block 0. Scottie sends a lone 9 ms "start sync" before the first
     * line, so its first block opens right after it; every other mode starts
     * its first block on that pulse.
     */
    val firstBlockOffsetMs: Double = 0.0
) {
    /** Number of blocks in a full frame. */
    val blocks: Int get() = (height + linesPerBlock - 1) / linesPerBlock

    /** Nominal frame duration in seconds, VIS excluded — used for the UI. */
    val frameSeconds: Double get() = blocks * blockMs / 1000.0

    companion object {

        // ---- Martin ------------------------------------------------------
        // sync 4.862 @1200, porch 0.572 @1500, then G, sep, B, sep, R, sep.
        private fun martin(vis: Int, name: String, scan: Double): SstvMode {
            val sync = 4.862; val sep = 0.572
            val g = sync + sep
            val b = g + scan + sep
            val r = b + scan + sep
            return SstvMode(
                vis = vis, name = name, width = 320, height = 256,
                blockMs = r + scan + sep, linesPerBlock = 1,
                syncAtMs = 0.0, syncMs = sync,
                segments = listOf(
                    Segment(g, scan, Role.GREEN),
                    Segment(b, scan, Role.BLUE),
                    Segment(r, scan, Role.RED)),
                family = Family.RGB)
        }

        // ---- Scottie -----------------------------------------------------
        // sep 1.5, G, sep 1.5, B, sync 9 @1200, porch 1.5, R.
        private fun scottie(vis: Int, name: String, scan: Double): SstvMode {
            val sync = 9.0; val sep = 1.5
            val g = sep
            val b = g + scan + sep
            val syncAt = b + scan
            val r = syncAt + sync + sep
            return SstvMode(
                vis = vis, name = name, width = 320, height = 256,
                blockMs = r + scan, linesPerBlock = 1,
                syncAtMs = syncAt, syncMs = sync,
                segments = listOf(
                    Segment(g, scan, Role.GREEN),
                    Segment(b, scan, Role.BLUE),
                    Segment(r, scan, Role.RED)),
                family = Family.RGB,
                firstBlockOffsetMs = sync)
        }

        // ---- Robot -------------------------------------------------------
        // 36: sync 9, porch 3, Y 88, sep 4.5, porch 1.5, chroma 44  = 150 ms
        private val ROBOT36 = run {
            val sync = 9.0; val porch = 3.0; val sep = 4.5; val cporch = 1.5
            val y = sync + porch                 // 12
            val c = y + 88.0 + sep + cporch      // 106
            SstvMode(8, "Robot 36", 320, 240, 150.0, 1, 0.0, sync,
                listOf(Segment(y, 88.0, Role.Y1), Segment(c, 44.0, Role.C_ALT)),
                Family.ROBOT36)
        }

        // 72: sync 9, porch 3, Y 138, sep 4.5, porch 1.5, R-Y 69,
        //     sep 4.5, porch 1.5, B-Y 69                              = 300 ms
        private val ROBOT72 = run {
            val sync = 9.0; val porch = 3.0; val sep = 4.5; val cporch = 1.5
            val y = sync + porch                       // 12
            val cr = y + 138.0 + sep + cporch          // 156
            val cb = cr + 69.0 + sep + cporch          // 231
            SstvMode(12, "Robot 72", 320, 240, 300.0, 1, 0.0, sync,
                listOf(Segment(y, 138.0, Role.Y1),
                       Segment(cr, 69.0, Role.CR),
                       Segment(cb, 69.0, Role.CB)),
                Family.YUV)
        }

        // ---- PD ----------------------------------------------------------
        // sync 20 @1200, porch 2.08 @1500, then Y(odd), R-Y, B-Y, Y(even),
        // each of duration t. Two image lines per block.
        private fun pd(vis: Int, name: String, w: Int, h: Int, t: Double): SstvMode {
            val sync = 20.0; val porch = 2.08
            val a = sync + porch
            return SstvMode(
                vis = vis, name = name, width = w, height = h,
                blockMs = a + 4 * t, linesPerBlock = 2,
                syncAtMs = 0.0, syncMs = sync,
                segments = listOf(
                    Segment(a, t, Role.Y1),
                    Segment(a + t, t, Role.CR),
                    Segment(a + 2 * t, t, Role.CB),
                    Segment(a + 3 * t, t, Role.Y2)),
                family = Family.PD)
        }

        /** Every mode SatMe can decode, in VIS order. */
        val ALL: List<SstvMode> = listOf(
            ROBOT36,
            ROBOT72,
            martin(40, "Martin M2", 73.216),
            martin(44, "Martin M1", 146.432),
            scottie(56, "Scottie S2", 88.064),
            scottie(60, "Scottie S1", 138.240),
            scottie(76, "Scottie DX", 345.600),
            pd(93, "PD 50", 320, 256, 91.520),
            pd(94, "PD 290", 800, 616, 228.800),
            pd(95, "PD 120", 640, 496, 121.600),
            pd(96, "PD 180", 640, 496, 183.040),
            pd(97, "PD 240", 640, 496, 244.480),
            pd(98, "PD 160", 512, 400, 195.584),
            pd(99, "PD 90", 320, 256, 170.240)
        )

        private val byVis: Map<Int, SstvMode> = ALL.associateBy { it.vis }

        fun byVis(vis: Int): SstvMode? = byVis[vis]

        fun byName(name: String): SstvMode? = ALL.firstOrNull { it.name == name }

        /** Longest block of any supported mode — sizes the decoder's buffer. */
        val longestBlockMs: Double = ALL.maxOf { it.blockMs }
    }
}

/** Tones shared by every mode. */
object SstvTone {
    const val SYNC = 1200.0
    const val PORCH = 1500.0
    const val BLACK = 1500.0
    const val WHITE = 2300.0
    const val CARRIER = 1900.0
    const val VIS_ONE = 1100.0
    const val VIS_ZERO = 1300.0

    /** Maps a scan tone to a 0..255 level. */
    fun level(freq: Double): Int {
        val v = (freq - BLACK) * 255.0 / (WHITE - BLACK)
        return when {
            v < 0.0 -> 0
            v > 255.0 -> 255
            else -> v.toInt()
        }
    }

    /** Inverse of [level] — used by the test signal generator. */
    fun tone(level: Int): Double =
        BLACK + level.coerceIn(0, 255) * (WHITE - BLACK) / 255.0
}
