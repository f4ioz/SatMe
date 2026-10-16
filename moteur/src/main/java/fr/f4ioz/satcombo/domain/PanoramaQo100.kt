/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The dongle's whole-band FFT on QO-100's fixed scale (the ruler's): what
 * the transponder looks like, wherever the dongle is tuned. The phone's
 * panorama projection, for a screen drawn elsewhere (the PC's page).
 */
object PanoramaQo100 {
    /** A column the dongle's window does not cover. */
    const val HORS = -999f

    /**
     * [pan] (dB per bin, the dongle's window centred on [centreHz] sky, its
     * width [etendueHz], negative when an inverting LNB turns it over) on
     * [colonnes] columns from the ruler's low end to its high end.
     *
     * Each column takes the **maximum** of its bins, never the mean: an SSB
     * signal fills two or three of the ~15 bins of a column and averaging
     * would drown it in noise. Null when the window hardly covers the ruler:
     * tuned elsewhere, the row would say nothing.
     */
    fun colonnes(pan: FloatArray, centreHz: Double, etendueHz: Double, colonnes: Int = 512,
                 basHz: Long = Qo100.REGLETTE_BAS_HZ, hautHz: Long = Qo100.REGLETTE_HAUT_HZ): FloatArray? {
        val n = pan.size
        if (n < 16 || etendueHz == 0.0 || centreHz <= 0.0 || colonnes < 8 || hautHz <= basHz) return null
        val hzParRaie = etendueHz / n
        val bas = basHz.toDouble()
        val haut = hautHz.toDouble()
        val out = FloatArray(colonnes)
        var utiles = 0
        for (c in 0 until colonnes) {
            val f0 = bas + (haut - bas) * c / colonnes
            val f1 = bas + (haut - bas) * (c + 1) / colonnes
            val i0 = n / 2.0 + (f0 - centreHz) / hzParRaie
            val i1 = n / 2.0 + (f1 - centreHz) / hzParRaie
            var a = floor(min(i0, i1)).toInt()
            var b = ceil(max(i0, i1)).toInt()
            if (b < 0 || a >= n) { out[c] = HORS; continue }
            a = a.coerceIn(0, n - 1)
            b = b.coerceIn(0, n - 1)
            var m = -160f
            for (k in a..b) if (pan[k] > m) m = pan[k]
            out[c] = m
            utiles++
        }
        return if (utiles < colonnes / 8) null else out
    }
}
