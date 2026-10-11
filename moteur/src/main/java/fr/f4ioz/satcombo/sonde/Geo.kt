/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry a radiosonde hunt needs.
 *
 * The RS41 does not send latitude: it sends its ECEF position (centimetres)
 * and velocity. Everything else is computed here. Pure maths, no Android, so
 * it is fully unit-tested on purpose: a sign error on one axis would send the
 * hunter to the wrong département.
 */
object Geo {

    /** WGS84 semi-major axis, metres. */
    const val A = 6_378_137.0

    /** WGS84 flattening. */
    const val F = 1.0 / 298.257223563

    /** Semi-minor axis, metres. */
    const val B = A * (1.0 - F)

    /** First eccentricity squared. */
    const val E2 = F * (2.0 - F)

    /** Mean Earth radius for ground distances, kilometres. */
    const val EARTH_KM = 6371.0088

    /** Geodetic position: lat/lon in degrees, altitude in metres. */
    data class Fix(val lat: Double, val lon: Double, val altM: Double)

    /** Local velocity: east, north, up, in m/s. */
    data class Enu(val east: Double, val north: Double, val up: Double) {
        /** Ground speed, m/s. */
        val groundMps: Double get() = hypot(east, north)

        /** Heading in degrees from north, 0..360. */
        val headingDeg: Double
            get() {
                if (groundMps < 1e-6) return 0.0
                val d = Math.toDegrees(atan2(east, north))
                return if (d < 0) d + 360.0 else d
            }
    }

    /**
     * ECEF to geodetic, Bowring's method.
     *
     * One pass gives sub-millimetre accuracy from sea level to 40 km: no loop,
     * no stop criterion, so decoding can never suddenly take longer than a
     * frame interval.
     */
    fun ecefToGeodetic(x: Double, y: Double, z: Double): Fix {
        val p = hypot(x, y)
        if (p < 1e-9) {
            // On the polar axis the general formula divides by zero.
            val lat = if (z >= 0) 90.0 else -90.0
            return Fix(lat, 0.0, abs(z) - B)
        }
        val ep2 = (A * A - B * B) / (B * B)
        val th = atan2(A * z, B * p)
        val st = sin(th)
        val ct = cos(th)
        val lat = atan2(z + ep2 * B * st * st * st, p - E2 * A * ct * ct * ct)
        val lon = atan2(y, x)
        val sl = sin(lat)
        val n = A / sqrt(1.0 - E2 * sl * sl)
        val alt = p / cos(lat) - n
        return Fix(Math.toDegrees(lat), Math.toDegrees(lon), alt)
    }

    /** ECEF velocity to local east/north/up at the given lat/lon (degrees). */
    fun ecefVelToEnu(latDeg: Double, lonDeg: Double,
                     vx: Double, vy: Double, vz: Double): Enu {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val sla = sin(la); val cla = cos(la)
        val slo = sin(lo); val clo = cos(lo)
        val e = -slo * vx + clo * vy
        val n = -sla * clo * vx - sla * slo * vy + cla * vz
        val u = cla * clo * vx + cla * slo * vy + sla * vz
        return Enu(e, n, u)
    }

    /** Ground distance in kilometres (haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * EARTH_KM * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Initial bearing from the first point to the second, degrees from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val d = Math.toDegrees(atan2(y, x))
        return (d + 360.0) % 360.0
    }

    private val ROSE = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")

    /** Same bearing as a 16-point compass rose (French letters: O = west). */
    fun compass(deg: Double): String {
        val d = ((deg % 360.0) + 360.0) % 360.0
        return ROSE[(((d + 11.25) / 22.5).toInt()) % 16]
    }

    /** GPS epoch (6 January 1980) in Unix milliseconds. */
    const val GPS_EPOCH_MS = 315_964_800_000L

    /**
     * GPS week + time of week to Unix time.
     *
     * Leap seconds are a parameter because they change: 18 since 2017, but a
     * sonde heard in ten years won't know. Recent firmware sends the full week
     * number; older ones send it modulo 1024, hence the correction.
     */
    fun gpsToUnixMs(week: Int, itowMs: Long, leapSeconds: Int = 18): Long {
        val w = if (week in 1..1023) week + 2048 else week
        return GPS_EPOCH_MS + w * 604_800_000L + itowMs - leapSeconds * 1000L
    }
}
