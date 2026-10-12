/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The great circle between two stations, for a map: the shortest way, as a beam would go. */
object Orthodromie {
    /** [n] + 1 (lat, lon) points from the first station to the second, longitudes continuous from the first. */
    fun points(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 64): List<Pair<Double, Double>> {
        val r = Math.PI / 180
        val f1 = lat1 * r; val l1 = lon1 * r; val f2 = lat2 * r; val l2 = lon2 * r
        val d = 2 * asin(sqrt(sin((f2 - f1) / 2).let { it * it } + cos(f1) * cos(f2) * sin((l2 - l1) / 2).let { it * it }))
        if (d == 0.0) return listOf(lat1 to lon1)
        val out = ArrayList<Pair<Double, Double>>(n + 1)
        var ref = lon1
        for (i in 0..n) {
            val f = i.toDouble() / n
            val a = sin((1 - f) * d) / sin(d); val b = sin(f * d) / sin(d)
            val x = a * cos(f1) * cos(l1) + b * cos(f2) * cos(l2)
            val y = a * cos(f1) * sin(l1) + b * cos(f2) * sin(l2)
            val z = a * sin(f1) + b * sin(f2)
            var lon = atan2(y, x) / r
            while (lon - ref > 180) lon -= 360
            while (lon - ref < -180) lon += 360
            ref = lon
            out += atan2(z, sqrt(x * x + y * y)) / r to lon
        }
        return out
    }
}
