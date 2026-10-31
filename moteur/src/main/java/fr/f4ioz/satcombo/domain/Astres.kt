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
import kotlin.math.tan

/**
 * The Sun and the Moon as rotor targets (Réglages › Rotor, « Soleil » /
 * « Lune »): where they are seen from the station, elevation raised by the
 * air's refraction, so a dish can be aligned on the Sun's noise (or the
 * Moon's, for a big one).
 *
 * The Sun: [SunCalc] (NOAA approximation, a few hundredths of a degree).
 * The Moon: the low-precision series of the Astronomical Almanac (the six
 * largest terms in longitude, four in latitude and parallax), good to about
 * 0.3° — a quarter of a 80 cm dish's lobe at 10 GHz, plenty for a first
 * alignment — then made topocentric: seen from the Earth's surface the Moon
 * is up to 1° lower than from its centre, which no dish can ignore.
 */
object Astres {

    enum class Astre(val cle: String, val nom: String) { SOLEIL("soleil", "Soleil"), LUNE("lune", "Lune") }

    fun astre(cle: String?): Astre? = Astre.values().firstOrNull { it.cle == cle?.trim()?.lowercase() }

    /** Where [a] is seen from ([latDeg], [lonDeg], [altM]) at [ms]: azimuth (true, clockwise from north), elevation with refraction. */
    fun azEl(a: Astre, latDeg: Double, lonDeg: Double, altM: Double, ms: Long): Pair<Double, Double> = when (a) {
        Astre.SOLEIL -> BruitSolaire.soleil(latDeg, lonDeg, ms)
        Astre.LUNE -> Lune.azElDeg(latDeg, lonDeg, altM, ms).let { (az, el) -> az to el + refraction(el) }
    }

    /** The air's refraction (degrees) at apparent elevation about [h] (Saemundsson's formula); none well below the horizon. */
    fun refraction(h: Double): Double = if (h > -1.0) 1.02 / tan(Math.toRadians(h + 10.3 / (h + 5.11))) / 60.0 else 0.0
}

/** The Moon's position, low precision (see [Astres]). */
object Lune {

    private fun s(d: Double) = sin(Math.toRadians(d))
    private fun c(d: Double) = cos(Math.toRadians(d))

    /** Julian centuries since J2000.0 (12:00 TT, 1 January 2000; TT − UT, about a minute, ignored). */
    fun siecles(ms: Long): Double = (ms / 86_400_000.0 + 2_440_587.5 - 2_451_545.0) / 36_525.0

    /**
     * Geocentric ecliptic longitude, latitude (degrees) and horizontal
     * parallax (degrees) at [ms].
     */
    fun ecliptique(ms: Long): DoubleArray {
        val t = siecles(ms)
        val lon = 218.32 + 481_267.881 * t +
            6.29 * s(135.0 + 477_198.87 * t) - 1.27 * s(259.3 - 413_335.36 * t) +
            0.66 * s(235.7 + 890_534.22 * t) + 0.21 * s(269.9 + 954_397.74 * t) -
            0.19 * s(357.5 + 35_999.05 * t) - 0.11 * s(186.5 + 966_404.03 * t)
        val lat = 5.13 * s(93.3 + 483_202.02 * t) + 0.28 * s(228.2 + 960_400.89 * t) -
            0.28 * s(318.3 + 6_003.15 * t) - 0.17 * s(217.6 - 407_332.21 * t)
        val par = 0.9508 + 0.0518 * c(135.0 + 477_198.87 * t) + 0.0095 * c(259.3 - 413_335.36 * t) +
            0.0078 * c(235.7 + 890_534.22 * t) + 0.0028 * c(269.9 + 954_397.74 * t)
        return doubleArrayOf(((lon % 360.0) + 360.0) % 360.0, lat, par)
    }

    /** Geocentric right ascension and declination (degrees) and distance (Earth radii) at [ms]. */
    fun equatoriale(ms: Long): DoubleArray {
        val (l, b, p) = ecliptique(ms).let { Triple(it[0], it[1], it[2]) }
        val eps = 23.439 - 0.013 * siecles(ms)
        // Unit vector in ecliptic coordinates, turned by the obliquity.
        val x = c(b) * c(l)
        val y = c(b) * s(l)
        val z = s(b)
        val ye = y * c(eps) - z * s(eps)
        val ze = y * s(eps) + z * c(eps)
        val ra = (Math.toDegrees(atan2(ye, x)) + 360.0) % 360.0
        val dec = Math.toDegrees(asin(ze.coerceIn(-1.0, 1.0)))
        return doubleArrayOf(ra, dec, 1.0 / s(p))
    }

    /** Greenwich mean sidereal time, degrees (the same as [SunCalc]'s). */
    fun tsmg(ms: Long): Double {
        val n = ms / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
        return (((18.697374558 + 24.06570982441908 * n) % 24.0) * 15.0 + 360.0) % 360.0
    }

    /**
     * Topocentric azimuth and elevation (degrees, no refraction) from
     * ([latDeg], [lonDeg], [altM]): the Moon's geocentric vector minus the
     * station's, in Earth radii, then the local east-north-up frame.
     */
    fun azElDeg(latDeg: Double, lonDeg: Double, altM: Double, ms: Long): Pair<Double, Double> {
        val (ra, dec, r) = equatoriale(ms).let { Triple(it[0], it[1], it[2]) }
        val lst = tsmg(ms) + lonDeg
        // The Moon in the Earth-fixed equatorial frame turning with the station (x towards its meridian).
        val ha = lst - ra
        val mx = r * c(dec) * c(ha)
        val my = -r * c(dec) * s(ha)
        val mz = r * s(dec)
        // The station (a sphere is enough: the flattening moves the Moon by under 0.01°).
        val rs = 1.0 + altM / 6_378_137.0
        val sx = rs * c(latDeg)
        val sz = rs * s(latDeg)
        val dx = mx - sx
        val dy = my
        val dz = mz - sz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // East, north, up.
        val est = dy / d
        val nord = (-s(latDeg) * dx + c(latDeg) * dz) / d
        val haut = (c(latDeg) * dx + s(latDeg) * dz) / d
        val az = (Math.toDegrees(atan2(est, nord)) + 360.0) % 360.0
        val el = Math.toDegrees(asin(haut.coerceIn(-1.0, 1.0)))
        return az to el
    }
}
