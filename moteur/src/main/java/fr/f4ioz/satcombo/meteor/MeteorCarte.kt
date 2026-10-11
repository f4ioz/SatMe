/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import com.github.amsacode.predict4java.SatelliteFactory
import com.github.amsacode.predict4java.TLE
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coastlines and borders drawn on a METEOR picture, from the orbit.
 *
 * **Where a ground point falls in the picture.** The MSU-MR scans across
 * the track, at a constant angular rate, square to the satellite's velocity.
 * For a point on the ground:
 *
 *  - its **line** is the moment the satellite passes level with it: when
 *    the point lies in the plane square to the velocity through the
 *    satellite. That moment is found between two scans (the distance along
 *    the track changes sign) and interpolated;
 *  - its **column** is the angle between nadir and the point, in that plane,
 *    over the angle of one pixel.
 *
 * The scan time comes from the packets themselves (day and ms, UTC), so a
 * missing stretch of the pass shifts nothing.
 *
 * **Measured on the recording of a real pass** (METEOR-M2-4, 05/03/2026,
 * Alaska; the coastline matched against the land/sea edges of the picture):
 * the scan is square to the velocity in space (the satellite does not steer
 * in yaw; square to the ground track, the right side lands 80 km off), and
 * the 1568 pixels span ±58°, about 1.1 km a pixel under the satellite.
 * Those elements were seven months old: the 21 s along the track they still
 * needed are theirs, not the satellite's.
 */
object MeteorCarte {

    /** Half the scan, from nadir to the edge of the picture. */
    const val DEMI_ANGLE_DEG = 58.0

    /** Day 1 of the packets' clock is 1 January 2001 (UTC). */
    const val ORIGINE_JOURS_MS = 978_220_800_000L

    private const val A = 6378.137
    private const val E2 = 6.69437999014e-3
    private const val OMEGA = 7.2921159e-5

    /**
     * The satellite at each scan: Earth-fixed position (km) and the
     * direction it flies in space, seen in the Earth-fixed frame.
     */
    class Orbite(val periodeMs: Double, val pos: Array<DoubleArray>, val dir: Array<DoubleArray>) {
        val scans: Int get() = pos.size
    }

    /** The packets' time ([MsuMr] scans) as UTC ms. */
    fun utc(tPaquets: Long): Long = ORIGINE_JOURS_MS + tPaquets

    /**
     * The orbit over [scans] scans from [t0Utc] (ms), one every [periodeMs],
     * from the elements ([ligne1], [ligne2]). Null if they cannot be read.
     */
    fun orbite(ligne1: String, ligne2: String, t0Utc: Long, periodeMs: Double, scans: Int): Orbite? = runCatching {
        val sat = SatelliteFactory.createSatellite(TLE(arrayOf("METEOR", ligne1, ligne2)))
        fun ecefA(tMs: Long): DoubleArray {
            sat.calculateSatelliteVectors(Date(tMs))
            val p = sat.calculateSatelliteGroundTrack()
            return ecef(p.latitude, p.longitude, p.altitude)
        }
        val n = scans + 1
        val pos = Array(n) { DoubleArray(3) }
        val dir = Array(n) { DoubleArray(3) }
        for (i in 0 until n) {
            val t = t0Utc + (i * periodeMs).toLong()
            val p = ecefA(t)
            // Velocity over ±0.5 s, back into space: plus ω × r.
            val a = ecefA(t - 500); val b = ecefA(t + 500)
            val v = DoubleArray(3) { (b[it] - a[it]) }
            v[0] -= OMEGA * p[1]; v[1] += OMEGA * p[0]
            val nv = norme(v)
            pos[i] = p
            dir[i] = DoubleArray(3) { v[it] / nv }
        }
        Orbite(periodeMs, pos, dir)
    }.getOrNull()

    /** Geodetic latitude, longitude (radians) and height (km) to Earth-fixed km. */
    fun ecef(lat: Double, lon: Double, hKm: Double = 0.0): DoubleArray {
        val s = sin(lat)
        val n = A / sqrt(1 - E2 * s * s)
        return doubleArrayOf((n + hKm) * cos(lat) * cos(lon), (n + hKm) * cos(lat) * sin(lon), (n * (1 - E2) + hKm) * s)
    }

    /**
     * Where the ground point [g] (Earth-fixed km) falls: column (0..1567,
     * fractional) and line (8 per scan), or null if the picture does not see it.
     */
    fun pixel(o: Orbite, g: DoubleArray, limiteColonnes: Double = 4.0): DoubleArray? {
        fun f(i: Int): Double {
            val p = o.pos[i]; val u = o.dir[i]
            return (g[0] - p[0]) * u[0] + (g[1] - p[1]) * u[1] + (g[2] - p[2]) * u[2]
        }
        // On the near side of the Earth only.
        val milieu = o.pos[o.scans / 2]
        if (g[0] * milieu[0] + g[1] * milieu[1] + g[2] * milieu[2] <= 0) return null
        var lo = 0; var hi = o.scans - 1
        var flo = f(lo); var fhi = f(hi)
        if (flo * fhi > 0) return null
        while (hi - lo > 1) {
            val m = (lo + hi) / 2
            val fm = f(m)
            if ((fm > 0) == (flo > 0)) { lo = m; flo = fm } else { hi = m; fhi = fm }
        }
        val fr = if (flo == fhi) 0.0 else flo / (flo - fhi)
        val p = DoubleArray(3) { o.pos[lo][it] * (1 - fr) + o.pos[hi][it] * fr }
        val u = DoubleArray(3) { o.dir[lo][it] * (1 - fr) + o.dir[hi][it] * fr }
        normalise(u)
        // Nadir, made square to the velocity; across = velocity × nadir.
        val np = norme(p)
        val nad = DoubleArray(3) { -p[it] / np }
        val k = nad[0] * u[0] + nad[1] * u[1] + nad[2] * u[2]
        for (i in 0..2) nad[i] -= k * u[i]
        normalise(nad)
        val cr = doubleArrayOf(u[1] * nad[2] - u[2] * nad[1], u[2] * nad[0] - u[0] * nad[2], u[0] * nad[1] - u[1] * nad[0])
        val d = DoubleArray(3) { g[it] - p[it] }
        // Behind the horizon: the satellite must be above the point's horizon.
        if (-(d[0] * g[0] + d[1] * g[1] + d[2] * g[2]) <= 0) return null
        val bas = d[0] * nad[0] + d[1] * nad[1] + d[2] * nad[2]
        if (bas <= 0) return null
        val th = atan2(d[0] * cr[0] + d[1] * cr[1] + d[2] * cr[2], bas)
        val pas = Math.toRadians(DEMI_ANGLE_DEG) / (MsuMr.LARGEUR / 2)
        val x = MsuMr.LARGEUR / 2 - 0.5 + th / pas
        if (x < -limiteColonnes || x > MsuMr.LARGEUR - 1 + limiteColonnes) return null
        return doubleArrayOf(x, (lo + fr) * 8)
    }

    /**
     * Draws [traits] (each `lon, lat, lon, lat…` in degrees) on [img], made
     * from a picture of [lignes] lines, one pixel in [pas], turned half
     * round when [montant] — as [MeteorImage] builds it. Returns how many
     * segments were drawn.
     */
    fun trace(img: MeteorImage.Image, o: Orbite, traits: List<FloatArray>, lignes: Int,
              montant: Boolean, pas: Int, couleur: Int, opacite: Int = 230): Int {
        var n = 0
        val r = Math.toRadians(1.0)
        for (t in traits) {
            var prec: DoubleArray? = null
            var lonPrec = Double.NaN
            var j = 0
            while (j + 1 < t.size) {
                val lon = t[j].toDouble(); val lat = t[j + 1].toDouble()
                j += 2
                // A ring cut at ±180° runs along that meridian: not a coast.
                val coupe = !lonPrec.isNaN() && (kotlin.math.abs(lon - lonPrec) > 180.0 ||
                    (kotlin.math.abs(lon) > 179.99 && kotlin.math.abs(lonPrec) > 179.99))
                lonPrec = lon
                val q = pixel(o, ecef(lat * r, lon * r))
                if (q != null && q[1] < -4 || q != null && q[1] > lignes + 4) { prec = null; continue }
                if (q != null && prec != null && !coupe &&
                    kotlin.math.abs(q[0] - prec[0]) < 120 && kotlin.math.abs(q[1] - prec[1]) < 120) {
                    ligne(img, place(prec, img, montant, pas), place(q, img, montant, pas), couleur, opacite)
                    n++
                }
                prec = q
            }
        }
        return n
    }

    private fun place(q: DoubleArray, img: MeteorImage.Image, montant: Boolean, pas: Int): DoubleArray {
        val x = (q[0] + 0.5) / pas - 0.5; val y = (q[1] + 0.5) / pas - 0.5
        return if (montant) doubleArrayOf(img.largeur - 1 - x, img.hauteur - 1 - y) else doubleArrayOf(x, y)
    }

    /** A line one pixel wide, blended over the picture; outside parts are skipped. */
    private fun ligne(img: MeteorImage.Image, a: DoubleArray, b: DoubleArray, couleur: Int, opacite: Int) {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val pasN = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)).toInt() + 1
        val cr = (couleur ushr 16) and 0xFF; val cg = (couleur ushr 8) and 0xFF; val cb = couleur and 0xFF
        var dernier = -1
        for (s in 0..pasN) {
            val x = Math.round(a[0] + dx * s / pasN).toInt()
            val y = Math.round(a[1] + dy * s / pasN).toInt()
            if (x < 0 || y < 0 || x >= img.largeur || y >= img.hauteur) continue
            val i = y * img.largeur + x
            if (i == dernier) continue
            dernier = i
            val v = img.pixels[i]
            val r = (((v ushr 16) and 0xFF) * (255 - opacite) + cr * opacite) / 255
            val g = (((v ushr 8) and 0xFF) * (255 - opacite) + cg * opacite) / 255
            val bb = ((v and 0xFF) * (255 - opacite) + cb * opacite) / 255
            img.pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
        }
    }

    private fun norme(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun normalise(v: DoubleArray) { val n = norme(v); for (i in 0..2) v[i] /= n }
}
