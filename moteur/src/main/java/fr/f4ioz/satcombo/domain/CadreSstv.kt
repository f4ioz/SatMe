/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.util.Locale

/**
 * Where the SSTV picture sits over the journal's replay and in its videos,
 * when the operator placed it with the mouse: its left [x] and top [y] as
 * shares of the view's width and height, its width [l] as a share of the
 * view's width (the picture keeps its 4:3). Shares, so it fits any
 * resolution and both formats (16:9, 3:4). Kept as "x,y,l"; none: the
 * picture places itself, in the top corner away from the satellite.
 *
 * The report's panels laid over the video ([OptionsJournal.Element]) are
 * placed the same way, each its own shape ([place]'s ratio, a smaller least
 * width for the logo).
 */
data class CadreSstv(val x: Float, val y: Float, val l: Float) {

    /** A rectangle in pixels. */
    data class Place(val x: Float, val y: Float, val l: Float, val h: Float)

    /**
     * Its place in a view [w]×[h] px: [ratio] wide for one high (the picture's
     * 4:3), [min]–[MAX] of the width, never taller than the view, inside it.
     */
    fun place(w: Float, h: Float, ratio: Float = 4f / 3f, min: Float = MIN): Place {
        val r = if (ratio.isFinite() && ratio > 0.05f) ratio else 4f / 3f
        val lw = minOf(l.coerceIn(min, MAX) * w, h * r)
        val lh = lw / r
        return Place((x * w).coerceIn(0f, maxOf(0f, w - lw)), (y * h).coerceIn(0f, maxOf(0f, h - lh)), lw, lh)
    }

    /** As kept and sent ("0.6100,0.0200,0.3500"). */
    fun texte(): String = "%.4f,%.4f,%.4f".format(Locale.US, x, y, l)

    companion object {
        /** Its smallest and largest width, shares of the view's. */
        const val MIN = 0.15f
        const val MAX = 0.9f

        /** From "x,y,l" (clamped, [min] the least width); null when empty, "auto" or unreadable: placed by itself. */
        fun lit(s: String?, min: Float = MIN): CadreSstv? {
            val v = s?.trim()?.split(',')?.map { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 3 && it.all { f -> f != null && f.isFinite() } }?.map { it!! } ?: return null
            return CadreSstv(v[0].coerceIn(0f, 1f), v[1].coerceIn(0f, 1f), v[2].coerceIn(min, MAX))
        }
    }
}
