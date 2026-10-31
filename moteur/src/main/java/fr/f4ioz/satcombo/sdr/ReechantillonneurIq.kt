/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * I/Q pairs (signed 16-bit, I then Q) from one rate to any other, by cubic
 * (Catmull-Rom) interpolation between the four samples around each output
 * instant, carried from block to block.
 *
 * It does not filter: what comes in must already hold nothing above the
 * output's half rate — a receiver's own filter set narrower than the rate
 * asked (the BB60's, [Bb60.plan]), the input at least twice faster than the
 * output, and the interpolation's images stay far below the signal.
 */
class ReechantillonneurIq(val entreeHz: Double, val sortieHz: Double) {
    init { require(entreeHz > 0 && sortieHz > 0) { "débits invalides" } }

    /** Input samples per output sample. */
    private val pas = entreeHz / sortieHz
    /** The last three pairs of the previous block, then this block: floats, I then Q. */
    private var travail = FloatArray(0)
    private var historique = 0
    /** Where the next output falls, in pairs of [travail]. */
    private var position = 1.0

    /** Room enough for what [traite] gives from [n] values (shorts). */
    fun maxSortie(n: Int): Int = 2 * ((n / 2 + 3) / pas + 2).toInt()

    /** [n] values of [entree] (an even count) in, the values written to [sortie] back (an even count). */
    fun traite(entree: ShortArray, n: Int, sortie: ShortArray): Int {
        val paires = n / 2
        val total = historique + paires
        if (travail.size < 2 * total) travail = travail.copyOf(2 * total + 64)
        for (k in 0 until 2 * paires) travail[2 * historique + k] = entree[k].toFloat()
        var o = 0
        if (total >= 4) {
            while (true) {
                val p = floor(position).toInt()
                if (p + 2 >= total || o + 2 > sortie.size) break
                val t = (position - p).toFloat()
                for (c in 0..1) {
                    val y0 = travail[2 * (p - 1) + c]; val y1 = travail[2 * p + c]
                    val y2 = travail[2 * (p + 1) + c]; val y3 = travail[2 * (p + 2) + c]
                    val y = y1 + 0.5f * t * (y2 - y0 + t * (2f * y0 - 5f * y1 + 4f * y2 - y3 + t * (3f * (y1 - y2) + y3 - y0)))
                    sortie[o + c] = y.roundToInt().coerceIn(-32768, 32767).toShort()
                }
                o += 2
                position += pas
            }
        }
        // The last three pairs kept for the next block, the position moved with them.
        val garde = minOf(3, total)
        System.arraycopy(travail, 2 * (total - garde), travail, 0, 2 * garde)
        position -= (total - garde).toDouble()
        historique = garde
        return o
    }

    fun reset() { historique = 0; position = 1.0 }
}
