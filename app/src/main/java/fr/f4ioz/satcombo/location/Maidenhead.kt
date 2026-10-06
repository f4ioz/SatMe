/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.location

import kotlin.math.floor

/** Maidenhead grid locator <-> lat/lon (4 or 6 characters). */
object Maidenhead {

    /** Returns (lat, lon) at the centre of the square, or null if invalid. */
    fun toLatLon(locator: String): Pair<Double, Double>? {
        val s = locator.trim().uppercase()
        if (s.length != 4 && s.length != 6) return null
        if (s[0] !in 'A'..'R' || s[1] !in 'A'..'R') return null
        if (!s[2].isDigit() || !s[3].isDigit()) return null
        if (s.length == 6 && (s[4] !in 'A'..'X' || s[5] !in 'A'..'X')) return null

        var lon = (s[0] - 'A') * 20.0 - 180.0
        var lat = (s[1] - 'A') * 10.0 - 90.0
        lon += (s[2] - '0') * 2.0
        lat += (s[3] - '0') * 1.0
        if (s.length == 6) {
            lon += (s[4] - 'A') * (2.0 / 24) + (1.0 / 24)
            lat += (s[5] - 'A') * (1.0 / 24) + (1.0 / 48)
        } else {
            lon += 1.0
            lat += 0.5
        }
        return lat to lon
    }

    /**
     * Where a station was, from what a log keeps: one locator, or several for a
     * station on a line or a corner between squares ("JN06,JN16", "JN06/JN16",
     * as Wavelog and VUCC give them): the middle of the squares named. Null if
     * none can be read.
     */
    fun centre(texte: String): Pair<Double, Double>? {
        val l = texte.split(',', '/', ';', ' ').mapNotNull { toLatLon(it) }
        if (l.isEmpty()) return null
        if (l.size == 1) return l[0]
        // Longitudes taken next to the first one (squares either side of 180°).
        val lon0 = l[0].second
        val lon = l.map { (_, lo) -> lon0 + ((lo - lon0 + 540.0) % 360.0 - 180.0) }.average()
        return l.map { it.first }.average() to ((lon + 540.0) % 360.0 - 180.0)
    }

    /**
     * Bounds of a 2/4/6-char locator: [latMin, lonMin, latSpan, lonSpan] in degrees.
     */
    fun bounds(locator: String): DoubleArray? {
        val s = locator.trim().uppercase()
        if (s.length != 2 && s.length != 4 && s.length != 6) return null
        if (s[0] !in 'A'..'R' || s[1] !in 'A'..'R') return null
        var lon = (s[0] - 'A') * 20.0 - 180.0
        var lat = (s[1] - 'A') * 10.0 - 90.0
        var lonSpan = 20.0; var latSpan = 10.0
        if (s.length >= 4) {
            if (!s[2].isDigit() || !s[3].isDigit()) return null
            lon += (s[2] - '0') * 2.0; lat += (s[3] - '0') * 1.0
            lonSpan = 2.0; latSpan = 1.0
        }
        if (s.length == 6) {
            if (s[4] !in 'A'..'X' || s[5] !in 'A'..'X') return null
            lon += (s[4] - 'A') * (2.0 / 24); lat += (s[5] - 'A') * (1.0 / 24)
            lonSpan = 2.0 / 24; latSpan = 1.0 / 24
        }
        return doubleArrayOf(lat, lon, latSpan, lonSpan)
    }

    /** 6-character locator for a position. */
    fun fromLatLon(lat: Double, lon: Double): String {
        var lo = lon + 180.0
        var la = lat + 90.0
        val a = 'A' + floor(lo / 20).toInt().coerceIn(0, 17)
        val b = 'A' + floor(la / 10).toInt().coerceIn(0, 17)
        lo %= 20; la %= 10
        val c = '0' + floor(lo / 2).toInt().coerceIn(0, 9)
        val d = '0' + floor(la / 1).toInt().coerceIn(0, 9)
        lo %= 2; la %= 1
        val e = 'A' + floor(lo * 12).toInt().coerceIn(0, 23)
        val f = 'A' + floor(la * 24).toInt().coerceIn(0, 23)
        return "$a$b$c$d$e$f"
    }

    /** Distances (km) to the 4 borders of the enclosing 4-char square, plus the
     *  adjacent square in each direction. */
    data class BorderInfo(
        val northKm: Double, val southKm: Double, val eastKm: Double, val westKm: Double,
        val northSq: String, val southSq: String, val eastSq: String, val westSq: String
    )

    /** Great-circle distance in km. */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    /**
     * The neighbouring squares whose border is closer than [meters] — what an
     * operator standing a few steps from a grid line needs to announce, e.g.
     * "JN18cx / JN18cw". The 8 surrounding squares are tested at the same
     * precision as the locator itself (6 characters by default); a corner only
     * counts when BOTH its borders are within reach.
     *
     * Returns the squares sorted by distance, never including our own.
     */
    fun neighboursWithin(lat: Double, lon: Double, meters: Double, precision: Int = 6): List<String> {
        if (meters <= 0.0) return emptyList()
        val me = fromLatLon(lat, lon).let { if (precision >= 6) it else it.take(4) }
        val b = bounds(me) ?: return emptyList()
        val latMin = b[0]; val lonMin = b[1]
        val latMax = latMin + b[2]; val lonMax = lonMin + b[3]
        val km = meters / 1000.0

        val dN = haversineKm(lat, lon, latMax, lon)
        val dS = haversineKm(lat, lon, latMin, lon)
        val dE = haversineKm(lat, lon, lat, lonMax)
        val dW = haversineKm(lat, lon, lat, lonMin)
        val eps = 1e-9

        val found = ArrayList<Pair<String, Double>>()
        fun add(la: Double, lo: Double, d: Double) {
            if (d > km) return
            val sq = fromLatLon(la, lo).let { if (precision >= 6) it else it.take(4) }
            if (sq != me && found.none { it.first == sq }) found += sq to d
        }
        add(latMax + eps, lon, dN)
        add(latMin - eps, lon, dS)
        add(lat, lonMax + eps, dE)
        add(lat, lonMin - eps, dW)
        // Corners: both borders must be within the threshold.
        add(latMax + eps, lonMax + eps, maxOf(dN, dE))
        add(latMax + eps, lonMin - eps, maxOf(dN, dW))
        add(latMin - eps, lonMax + eps, maxOf(dS, dE))
        add(latMin - eps, lonMin - eps, maxOf(dS, dW))

        return found.sortedBy { it.second }.map { it.first }
    }

    /**
     * The neighbouring BIG squares (4 characters) whose border is closer than
     * [meters]. On the air, what a portable station announces near a grid line
     * is "JN18 or JN19" — the sub-square is a detail nobody chases. Each result
     * carries both spellings: the field itself ("JN19") and the 6-character
     * square just across the line ("JN19av"), so the caller prints whichever
     * precision the operator asked for. A corner only counts when BOTH its
     * borders are within reach, and our own square is never returned.
     */
    fun neighbourFields(lat: Double, lon: Double, meters: Double): List<Pair<String, String>> {
        if (meters <= 0.0) return emptyList()
        val me = fromLatLon(lat, lon).take(4)
        val b = bounds(me) ?: return emptyList()
        val latMin = b[0]; val lonMin = b[1]
        val latMax = latMin + b[2]; val lonMax = lonMin + b[3]
        val km = meters / 1000.0

        val dN = haversineKm(lat, lon, latMax, lon)
        val dS = haversineKm(lat, lon, latMin, lon)
        val dE = haversineKm(lat, lon, lat, lonMax)
        val dW = haversineKm(lat, lon, lat, lonMin)
        val eps = 1e-9

        val found = ArrayList<Triple<String, String, Double>>()
        fun add(la: Double, lo: Double, d: Double) {
            if (d > km) return
            val full = fromLatLon(la, lo)
            val sq = full.take(4)
            if (sq != me && found.none { it.first == sq }) found += Triple(sq, full, d)
        }
        add(latMax + eps, lon, dN)
        add(latMin - eps, lon, dS)
        add(lat, lonMax + eps, dE)
        add(lat, lonMin - eps, dW)
        add(latMax + eps, lonMax + eps, maxOf(dN, dE))
        add(latMax + eps, lonMin - eps, maxOf(dN, dW))
        add(latMin - eps, lonMax + eps, maxOf(dS, dE))
        add(latMin - eps, lonMin - eps, maxOf(dS, dW))

        return found.sortedBy { it.third }.map { it.first to it.second }
    }

    /**
     * One of the 8 squares touching ours: which way it lies ("N", "NE", ...),
     * its name in both spellings, and the distance to its nearest point — the
     * perpendicular to the border for a side, the corner itself for a diagonal.
     */
    data class Around(val dir: String, val square: String, val full: String, val km: Double)

    /**
     * The 8 surrounding big squares, closest first. The locator page shows them
     * all: standing near a corner, the second square is not always the one due
     * north, and an operator wants to see which line he is really close to.
     */
    fun aroundSquares(lat: Double, lon: Double): List<Around> {
        val me = fromLatLon(lat, lon).take(4)
        val b = bounds(me) ?: return emptyList()
        val latMin = b[0]; val lonMin = b[1]
        val latMax = latMin + b[2]; val lonMax = lonMin + b[3]
        val eps = 1e-9
        val dN = haversineKm(lat, lon, latMax, lon)
        val dS = haversineKm(lat, lon, latMin, lon)
        val dE = haversineKm(lat, lon, lat, lonMax)
        val dW = haversineKm(lat, lon, lat, lonMin)
        fun at(la: Double, lo: Double, d: Double, dir: String): Around {
            val full = fromLatLon(la, lo)
            return Around(dir, full.take(4), full, d)
        }
        return listOf(
            at(latMax + eps, lon, dN, "N"),
            at(latMin - eps, lon, dS, "S"),
            at(lat, lonMax + eps, dE, "E"),
            at(lat, lonMin - eps, dW, "W"),
            at(latMax + eps, lonMax + eps, haversineKm(lat, lon, latMax, lonMax), "NE"),
            at(latMax + eps, lonMin - eps, haversineKm(lat, lon, latMax, lonMin), "NW"),
            at(latMin - eps, lonMax + eps, haversineKm(lat, lon, latMin, lonMax), "SE"),
            at(latMin - eps, lonMin - eps, haversineKm(lat, lon, latMin, lonMin), "SW")
        ).sortedBy { it.km }
    }

    fun bordersOf(lat: Double, lon: Double): BorderInfo {
        val sq = fromLatLon(lat, lon).take(4)
        val b = bounds(sq)!!            // [latMin, lonMin, latSpan, lonSpan]
        val latMin = b[0]; val lonMin = b[1]
        val latMax = latMin + b[2]; val lonMax = lonMin + b[3]
        fun hav(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat/2) * kotlin.math.sin(dLat/2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon/2) * kotlin.math.sin(dLon/2)
            return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }
        val eps = 1e-6
        return BorderInfo(
            northKm = hav(lat, lon, latMax, lon),
            southKm = hav(lat, lon, latMin, lon),
            eastKm = hav(lat, lon, lat, lonMax),
            westKm = hav(lat, lon, lat, lonMin),
            northSq = fromLatLon(latMax + eps, lon).take(4),
            southSq = fromLatLon(latMin - eps, lon).take(4),
            eastSq = fromLatLon(lat, lonMax + eps).take(4),
            westSq = fromLatLon(lat, lonMin - eps).take(4)
        )
    }
}
