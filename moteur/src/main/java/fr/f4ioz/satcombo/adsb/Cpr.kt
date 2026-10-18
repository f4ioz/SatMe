/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Compact Position Reporting (17 bits each way), as the standard defines
 * it: 15 latitude zones per quadrant, an even (0) and an odd (1) grid.
 *
 * - **Global**: an even and an odd report a few seconds apart give the
 *   position anywhere on earth.
 * - **Local**: one report and a reference within half a zone (the
 *   station, the aircraft's last position) give it alone.
 *
 * Airborne reports span 360°, surface ones 90° (four times finer, so a
 * reference is always needed to pick the quadrant).
 */
object Cpr {

    const val NZ = 15
    private const val N17 = 131072.0

    /** The number of longitude zones at [lat] (1 to 59). */
    fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a < 1e-9) return 59
        if (a > 87.0) return 1
        if (a == 87.0) return 2
        val c = cos(PI / 180.0 * a)
        val x = 1.0 - (1.0 - cos(PI / (2.0 * NZ))) / (c * c)
        return floor(2.0 * PI / acos(x)).toInt()
    }

    private fun mod(a: Double, b: Double): Double = a - b * floor(a / b)

    private fun dlat(impair: Boolean, surface: Boolean) = (if (surface) 90.0 else 360.0) / (4 * NZ - (if (impair) 1 else 0))

    /** A position as sent: its 17-bit latitude and longitude for the even or odd grid. */
    fun encode(lat: Double, lon: Double, impair: Boolean, surface: Boolean = false): Pair<Int, Int> {
        val i = if (impair) 1 else 0
        val dl = dlat(impair, surface)
        val yz = floor(N17 * mod(lat, dl) / dl + 0.5)
        val rlat = dl * (yz / N17 + floor(lat / dl))
        val dlon = (if (surface) 90.0 else 360.0) / max(nl(rlat) - i, 1)
        val xz = floor(N17 * mod(lon, dlon) / dlon + 0.5)
        return (yz.toLong() and 0x1FFFF).toInt() to (xz.toLong() and 0x1FFFF).toInt()
    }

    /**
     * Global decoding: the even report ([lat0], [lon0]), the odd one, which
     * came last. Null when the two straddle a zone boundary (wait for another
     * pair). Surface reports need [ref] (the station) to choose the quadrant.
     */
    fun globale(lat0: Int, lon0: Int, lat1: Int, lon1: Int, impairDernier: Boolean,
                surface: Boolean = false, ref: Pair<Double, Double>? = null): Pair<Double, Double>? {
        if (surface && ref == null) return null
        val span = if (surface) 90.0 else 360.0
        val d0 = dlat(false, surface); val d1 = dlat(true, surface)
        val j = floor((59.0 * lat0 - 60.0 * lat1) / N17 + 0.5)
        var r0 = d0 * (mod(j, 60.0) + lat0 / N17)
        var r1 = d1 * (mod(j, 59.0) + lat1 / N17)
        if (!surface) { if (r0 >= 270.0) r0 -= 360.0; if (r1 >= 270.0) r1 -= 360.0 }
        if (surface) {
            // Northern answer or the one 90° south: the nearer to the reference.
            val la = ref!!.first
            if (abs(r0 - 90.0 - la) < abs(r0 - la)) r0 -= 90.0
            if (abs(r1 - 90.0 - la) < abs(r1 - la)) r1 -= 90.0
        }
        if (r0 < -90.0 || r0 > 90.0 || r1 < -90.0 || r1 > 90.0) return null
        if (nl(r0) != nl(r1)) return null
        val lat = if (impairDernier) r1 else r0
        val i = if (impairDernier) 1 else 0
        val n = nl(lat)
        val ni = max(n - i, 1)
        val dlon = span / ni
        val m = floor(((lon0 * (n - 1)) - (lon1 * n)) / N17 + 0.5)
        val xz = if (impairDernier) lon1 else lon0
        var lon = dlon * (mod(m, ni.toDouble()) + xz / N17)
        if (surface) {
            // Four answers 90° apart: the nearest to the reference.
            val lr = ref!!.second
            var meilleur = lon; var ecart = Double.MAX_VALUE
            for (k in -4..4) {
                val c = normLon(lon + 90.0 * k)
                val e = abs(normLon(c - lr))
                if (e < ecart) { ecart = e; meilleur = c }
            }
            lon = meilleur
        }
        return lat to normLon(lon)
    }

    /**
     * Local decoding of one report against [refLat], [refLon]: right when the
     * true position lies within half a zone (about 170 NM airborne) of it.
     */
    fun locale(yz: Int, xz: Int, impair: Boolean, refLat: Double, refLon: Double, surface: Boolean = false): Pair<Double, Double> {
        val i = if (impair) 1 else 0
        val span = if (surface) 90.0 else 360.0
        val dl = dlat(impair, surface)
        val j = floor(refLat / dl) + floor(0.5 + mod(refLat, dl) / dl - yz / N17)
        val lat = dl * (j + yz / N17)
        val dlon = span / max(nl(lat) - i, 1)
        val m = floor(refLon / dlon) + floor(0.5 + mod(refLon, dlon) / dlon - xz / N17)
        val lon = dlon * (m + xz / N17)
        return lat to normLon(lon)
    }

    fun normLon(l: Double): Double { var x = l; while (x >= 180.0) x -= 360.0; while (x < -180.0) x += 360.0; return x }
}
