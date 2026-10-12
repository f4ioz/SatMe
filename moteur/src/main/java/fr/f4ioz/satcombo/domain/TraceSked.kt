/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One instant of a sked: each side's look angles, where the satellite is over. */
data class PointSked(val t: Long, val azMoi: Double, val elMoi: Double, val azAutre: Double, val elAutre: Double,
                     val lat: Double, val lon: Double, val altKm: Double)

/**
 * A sked replayed — the map and both skies, on the PC's page, in the PC's
 * video and in the phone's: from the first station's rise to the last
 * one's set, every few seconds. The drawing is each platform's; what to
 * draw is here, once.
 */
class TraceSked(val moi: Observer, val autre: Observer, val points: List<PointSked>) {

    /** The longitudes made continuous from mine: a track over the date line stays one line. */
    val lons: DoubleArray = DoubleArray(points.size).also { l ->
        var ref = moi.lonDeg
        for (i in points.indices) { l[i] = proche(points[i].lon, ref); ref = l[i] }
    }

    /** The other station's longitude, on the same side of the map as mine. */
    val lonAutre: Double = proche(autre.lonDeg, moi.lonDeg)

    /** The instant [t], interpolated (azimuths on the short way round, the longitude continuous). */
    fun instant(t: Long): PointSked {
        val p = points
        var k = 0
        while (k < p.size - 2 && p[k + 1].t <= t) k++
        val a = p[k]; val j = minOf(k + 1, p.size - 1); val b = p[j]
        val u = if (b.t > a.t) ((t - a.t).toDouble() / (b.t - a.t)).coerceIn(0.0, 1.0) else 0.0
        fun az(x: Double, y: Double): Double { var d = y - x; if (d > 180) d -= 360; if (d < -180) d += 360; return ((x + d * u) % 360 + 360) % 360 }
        fun li(x: Double, y: Double) = x + (y - x) * u
        return PointSked(t, az(a.azMoi, b.azMoi), li(a.elMoi, b.elMoi), az(a.azAutre, b.azAutre), li(a.elAutre, b.elAutre),
            li(a.lat, b.lat), li(lons[k], lons[j]), li(a.altKm, b.altKm))
    }

    companion object {
        private fun proche(lon: Double, ref: Double): Double { var x = lon; while (x - ref > 180) x -= 360; while (x - ref < -180) x += 360; return x }

        /** From [deMs] to [aMs] (both passes), a few hundred instants at most; null for a window that is not one. */
        fun calcule(predicteur: PassPredictor, sat: TleEntry, moi: Observer, autre: Observer, deMs: Long, aMs: Long): TraceSked? {
            if (aMs <= deMs || aMs - deMs > 6 * 3_600_000L) return null
            val pas = maxOf(2_000L, (aMs - deMs) / 400)
            val pts = ArrayList<PointSked>()
            var t = deMs
            while (t <= aMs) {
                val pA = runCatching { predicteur.positionAt(sat, moi, t) }.getOrNull()
                val pB = runCatching { predicteur.positionAt(sat, autre, t) }.getOrNull()
                if (pA != null && pB != null) pts += PointSked(t, pA.azimuthDeg, pA.elevationDeg, pB.azimuthDeg, pB.elevationDeg, pA.latDeg, pA.lonDeg, pA.altKm)
                t += pas
            }
            return TraceSked(moi, autre, pts)
        }

        /**
         * The earth that sees a satellite at [altKm] over ([lat], [lon]): (lat,
         * lon) points round it, the longitudes continuous. Around a pole the
         * circle spans the whole map: closed then along its top (or bottom)
         * edge, the two points of that edge last — a drawing strokes all but
         * them, fills all.
         */
        fun empreinte(lat: Double, lon: Double, altKm: Double): List<DoubleArray> {
            val rho = acos(6371.0 / (6371.0 + maxOf(150.0, altKm)))
            val f0 = Math.toRadians(lat)
            val out = ArrayList<DoubleArray>()
            for (bDeg in 0..360 step 4) {
                val br = Math.toRadians(bDeg.toDouble())
                val f = asin(sin(f0) * cos(rho) + cos(f0) * sin(rho) * cos(br))
                val dl = atan2(sin(br) * sin(rho) * cos(f0), cos(rho) - sin(f0) * sin(f))
                val l = lon + Math.toDegrees(dl)
                out += doubleArrayOf(Math.toDegrees(f), if (out.isEmpty()) l else proche(l, out.last()[1]))
            }
            val fin = out.last()[1]; val debut = out.first()[1]
            if (Math.abs(fin - debut) > 180) {
                val pole = if (lat >= 0) 85.0 else -85.0
                out += doubleArrayOf(pole, fin); out += doubleArrayOf(pole, debut)
            }
            return out
        }

        /** How many points of [empreinte]'s result are the circle itself (to stroke). */
        const val POINTS_CERCLE = 91
    }
}
