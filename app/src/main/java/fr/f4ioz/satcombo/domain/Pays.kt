/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Country outlines, for the QRV photo: the country silhouette over a photo of
 * the site, filled with a flag or a second image, with a dot where the station
 * is. Nothing is drawn here; this answers which country, which pieces of it to
 * trace, and where each point lands on screen.
 *
 * Degrees throughout and no Android dependency, so it can be unit-tested.
 */
object Pays {

    /**
     * A country and its rings, each flattened as lat, lon, lat, lon…
     *
     * Rings are sorted largest first (for France: mainland, then Corsica, then
     * overseas territories), so the main piece is simply the first.
     */
    class Contour(val code: String, val nom: String, val anneaux: List<DoubleArray>)

    /** Rectangle in degrees: south, west, north, east. */
    class Boite(val sud: Double, val ouest: Double, val nord: Double, val est: Double) {
        val hauteur: Double get() = nord - sud
        val largeur: Double get() = est - ouest
        val latMoyenne: Double get() = (nord + sud) / 2
    }

    /**
     * Is the point inside the ring? Horizontal ray casting. Holes are ignored:
     * Italy stays solid around the Vatican, which is fine on a 5 cm map.
     */
    fun dansAnneau(anneau: DoubleArray, lat: Double, lon: Double): Boolean {
        var dedans = false
        val n = anneau.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val lati = anneau[2 * i]; val loni = anneau[2 * i + 1]
            val latj = anneau[2 * j]; val lonj = anneau[2 * j + 1]
            if ((lati > lat) != (latj > lat) &&
                lon < (lonj - loni) * (lat - lati) / (latj - lati) + loni
            ) dedans = !dedans
            j = i
        }
        return dedans
    }

    fun dansPays(c: Contour, lat: Double, lon: Double): Boolean =
        c.anneaux.any { dansAnneau(it, lat, lon) }

    /**
     * The country containing the point.
     *
     * At sea or on a simplified coastline no ring matches, so fall back to the
     * nearest outline — otherwise an operator on a beach gets no map. The
     * ten-degree limit keeps the Atlantic from being assigned to Ireland.
     */
    fun trouve(contours: List<Contour>, lat: Double, lon: Double,
               seuilDeg: Double = 10.0): Contour? {
        contours.firstOrNull { dansPays(it, lat, lon) }?.let { return it }
        var meilleur: Contour? = null
        var d = Double.MAX_VALUE
        for (c in contours) for (a in c.anneaux) {
            val b = boite(listOf(a))
            val dist = distanceBoite(b, lat, lon)
            if (dist < d) { d = dist; meilleur = c }
        }
        return if (d <= seuilDeg) meilleur else null
    }

    private fun distanceBoite(b: Boite, lat: Double, lon: Double): Double {
        val dLat = max(0.0, max(b.sud - lat, lat - b.nord))
        val dLon = max(0.0, max(b.ouest - lon, lon - b.est)) * cos(Math.toRadians(lat))
        return kotlin.math.hypot(dLat, dLon)
    }

    fun boite(anneaux: List<DoubleArray>): Boite {
        var s = 90.0; var n = -90.0; var o = 180.0; var e = -180.0
        for (a in anneaux) {
            var i = 0
            while (i < a.size) {
                val lat = a[i]; val lon = a[i + 1]
                s = min(s, lat); n = max(n, lat); o = min(o, lon); e = max(e, lon)
                i += 2
            }
        }
        return Boite(s, o, n, e)
    }

    /**
     * Rings to draw around a point: the ring containing (or nearest to) it,
     * plus every ring in its neighbourhood. From Brittany this gives mainland
     * France **and Corsica** but not French Guiana or Réunion; from Guadeloupe,
     * the island alone. No territory list to maintain.
     */
    fun morceauxAutour(c: Contour, lat: Double, lon: Double,
                       voisinageDeg: Double = 12.0): List<DoubleArray> {
        if (c.anneaux.isEmpty()) return emptyList()
        val principal = c.anneaux.firstOrNull { dansAnneau(it, lat, lon) }
            ?: c.anneaux.minByOrNull { distanceBoite(boite(listOf(it)), lat, lon) }
            ?: return emptyList()
        val bp = boite(listOf(principal))
        return c.anneaux.filter { a ->
            a === principal || chevauche(bp, boite(listOf(a)), voisinageDeg)
        }
    }

    private fun chevauche(a: Boite, b: Boite, marge: Double): Boolean =
        b.ouest <= a.est + marge && b.est >= a.ouest - marge &&
            b.sud <= a.nord + marge && b.nord >= a.sud - marge

    /**
     * Placement of the outline in a screen frame.
     *
     * Longitude is compressed by cos(latitude), otherwise France looks a third
     * too wide (the classic degrees-to-pixels mistake). The scale is the same
     * on both axes so the country keeps its shape.
     */
    class Placement(
        val boite: Boite,
        val echelle: Double,
        val decalageX: Double,
        val decalageY: Double,
        val compression: Double,
    ) {
        fun x(lon: Double): Double = decalageX + (lon - boite.ouest) * compression * echelle
        fun y(lat: Double): Double = decalageY + (boite.nord - lat) * echelle
    }

    fun place(boite: Boite, cadreX: Double, cadreY: Double,
              cadreL: Double, cadreH: Double): Placement {
        val compression = cos(Math.toRadians(boite.latMoyenne)).coerceAtLeast(0.05)
        val l = (boite.largeur * compression).coerceAtLeast(1e-9)
        val h = boite.hauteur.coerceAtLeast(1e-9)
        val echelle = min(cadreL / l, cadreH / h)
        val restL = cadreL - l * echelle
        val restH = cadreH - h * echelle
        return Placement(boite, echelle, cadreX + restL / 2, cadreY + restH / 2, compression)
    }

    /** Is the country in Europe, as defined by the preloaded box? */
    fun enEurope(b: Boite): Boolean =
        b.nord > 34.0 && b.sud < 72.0 && b.est > -32.0 && b.ouest < 45.0

    /** Approximate ring area in square degrees, for ranking pieces. */
    fun aire(anneau: DoubleArray): Double {
        var s = 0.0
        val n = anneau.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += anneau[2 * i + 1] * anneau[2 * j] - anneau[2 * j + 1] * anneau[2 * i]
        }
        return abs(s) / 2
    }
}
