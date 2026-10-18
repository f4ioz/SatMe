/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import fr.f4ioz.satcombo.sonde.Geo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Aircraft scatter between two stations — a prediction, nothing more: the
 * aircraft near the middle of the great circle, high enough for both
 * stations to see them over the radio horizon, and how long each stays
 * there, from its speed and track held straight.
 *
 * - **Horizon** on a 4/3 earth: d = √(2·k·R·h₁) + √(2·k·R·h₂), about
 *   4.12 (√h₁ + √h₂) km with heights in metres.
 * - **Common volume**: within [Reglage.rayonKm] of the path's midpoint, and
 *   over both stations' horizons.
 * - **Doppler** at the band's frequency: the rate of change of the two
 *   slant ranges' sum (the bistatic path), over the wavelength.
 */
object DiffusionAvions {

    const val K = 4.0 / 3.0
    const val R_KM = 6371.0
    private const val PIED = 0.3048

    /** The bands offered and their usual narrowband frequency (Hz). */
    val BANDES = linkedMapOf("10G" to 10_368_100_000L, "13cm" to 2_320_200_000L, "23cm" to 1_296_200_000L, "2m" to 144_300_000L)

    data class Reglage(
        val aLat: Double, val aLon: Double, val aHauteurM: Double = 100.0,
        val bLat: Double, val bLon: Double, val bHauteurM: Double = 100.0,
        val bande: String = "10G",
        val rayonKm: Double = 100.0,
        /** Below this, an aircraft is not counted (ft). */
        val altMinPieds: Int = 3000,
        val horizonMin: Int = 15,
    )

    /** Each one's own sight of the aircraft: distance over the ground, elevation. */
    class Fenetre(val icao: String, val indicatif: String, val debutMs: Long, val finMs: Long, val ouverte: Boolean,
                  val altPieds: Int, val distMilieuKm: Double, val elA: Double, val elB: Double,
                  val dopplerHz: Double, val trajetKm: Double, val finConnue: Boolean)

    /** The great-circle midpoint. */
    fun milieu(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val f1 = Math.toRadians(lat1); val f2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val bx = cos(f2) * cos(dl); val by = cos(f2) * sin(dl)
        val f = atan2(sin(f1) + sin(f2), sqrt((cos(f1) + bx) * (cos(f1) + bx) + by * by))
        val l = Math.toRadians(lon1) + atan2(by, cos(f1) + bx)
        return Math.toDegrees(f) to Cpr.normLon(Math.toDegrees(l))
    }

    /** The radio horizon (km) between heights [h1M] and [h2M] in metres, on a 4/3 earth. */
    fun horizonKm(h1M: Double, h2M: Double): Double =
        sqrt(2 * K * R_KM * maxOf(0.0, h1M) / 1000.0) + sqrt(2 * K * R_KM * maxOf(0.0, h2M) / 1000.0)

    /** The elevation (deg) of a point [hM] high seen [dKm] away from [h0M], on a 4/3 earth. */
    fun elevationDeg(h0M: Double, hM: Double, dKm: Double): Double {
        if (dKm < 1e-6) return 90.0
        return Math.toDegrees(atan((hM - h0M) / 1000.0 / dKm - dKm / (2 * K * R_KM)))
    }

    /** The slant range (km): the chord over the 4/3 earth, close enough at these distances. */
    fun obliqueKm(h0M: Double, hM: Double, dKm: Double): Double {
        val dh = (hM - h0M) / 1000.0 - dKm * dKm / (2 * K * R_KM)
        return sqrt(dKm * dKm + dh * dh)
    }

    /** Where [lat], [lon] is after [km] along [routeDeg]. */
    fun avance(lat: Double, lon: Double, routeDeg: Double, km: Double): Pair<Double, Double> {
        val d = km / R_KM
        val f1 = Math.toRadians(lat); val t = Math.toRadians(routeDeg)
        val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(t))
        val l2 = Math.toRadians(lon) + atan2(sin(t) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
        return Math.toDegrees(f2) to Cpr.normLon(Math.toDegrees(l2))
    }

    /** In the common volume at [lat], [lon], [hM] high? */
    fun dedans(r: Reglage, mil: Pair<Double, Double>, lat: Double, lon: Double, hM: Double): Boolean {
        if (Geo.distanceKm(mil.first, mil.second, lat, lon) > r.rayonKm) return false
        if (Geo.distanceKm(r.aLat, r.aLon, lat, lon) > horizonKm(r.aHauteurM, hM)) return false
        return Geo.distanceKm(r.bLat, r.bLon, lat, lon) <= horizonKm(r.bHauteurM, hM)
    }

    private fun trajet(r: Reglage, lat: Double, lon: Double, hM: Double): Double =
        obliqueKm(r.aHauteurM, hM, Geo.distanceKm(r.aLat, r.aLon, lat, lon)) + obliqueKm(r.bHauteurM, hM, Geo.distanceKm(r.bLat, r.bLon, lat, lon))

    /** The windows of the aircraft [avions], soonest first; [maintenant] is now. */
    fun fenetres(r: Reglage, avions: List<TableAvions.Avion>, maintenant: Long): List<Fenetre> {
        val mil = milieu(r.aLat, r.aLon, r.bLat, r.bLon)
        val lambdaKm = 299_792.458 / (BANDES[r.bande] ?: BANDES.getValue("10G")).toDouble()
        val out = ArrayList<Fenetre>()
        val pasS = 5
        val nPas = r.horizonMin * 60 / pasS
        for (a in avions) {
            val la = a.lat ?: continue; val lo = a.lon ?: continue
            if (a.surface) continue
            val alt = a.altitudePieds ?: a.altitudeGnss ?: continue
            if (alt < r.altMinPieds) continue
            val hM = alt * PIED
            val v = a.vitesseNoeuds; val trk = a.routeDeg
            // Where it is now, from its last fix.
            val ageS = (maintenant - a.posMs) / 1000.0
            // A heading with an airspeed (subtypes 3, 4) stands in for the track: the wind ignored.
            val kmS = if (v != null && trk != null) v * 1.852 / 3600.0 else null
            val (l0, g0) = if (kmS != null) avance(la, lo, trk!!, kmS * ageS) else la to lo
            if (kmS == null) {
                // No ground track known: only whether it is in the volume now; how long, unknown.
                if (dedans(r, mil, l0, g0, hM)) out += fenetre(r, a, mil, l0, g0, hM, maintenant, maintenant, true, 0.0, lambdaKm, false)
                continue
            }
            var debut = -1
            for (k in 0..nPas) {
                val (lk, gk) = avance(l0, g0, trk!!, kmS * k * pasS)
                val d = dedans(r, mil, lk, gk, hM)
                if (d && debut < 0) debut = k
                if ((!d || k == nPas) && debut >= 0) {
                    val fin = if (d) k else k - 1
                    val milieuK = (debut + fin) / 2
                    val (lm, gm) = avance(l0, g0, trk, kmS * milieuK * pasS)
                    val (ld, gd) = avance(l0, g0, trk, kmS * debut * pasS)
                    val (ld2, gd2) = avance(l0, g0, trk, kmS * (debut * pasS + 1))
                    val dop = -(trajet(r, ld2, gd2, hM) - trajet(r, ld, gd, hM)) / lambdaKm
                    out += fenetre(r, a, mil, lm, gm, hM, maintenant + debut * pasS * 1000L, maintenant + fin * pasS * 1000L, debut == 0, dop, lambdaKm,
                        !(d && k == nPas))
                    debut = -1
                    break
                }
            }
        }
        return out.sortedBy { it.debutMs }
    }

    private fun fenetre(r: Reglage, a: TableAvions.Avion, mil: Pair<Double, Double>, lat: Double, lon: Double, hM: Double,
                        de: Long, jusque: Long, ouverte: Boolean, dop: Double, lambdaKm: Double, finConnue: Boolean): Fenetre {
        val dA = Geo.distanceKm(r.aLat, r.aLon, lat, lon); val dB = Geo.distanceKm(r.bLat, r.bLon, lat, lon)
        return Fenetre(a.hex(), a.indicatif, de, jusque, ouverte, (hM / PIED).toInt(), Geo.distanceKm(mil.first, mil.second, lat, lon),
            elevationDeg(r.aHauteurM, hM, dA), elevationDeg(r.bHauteurM, hM, dB), dop, trajet(r, lat, lon, hM), finConnue)
    }

    /** The geometry to draw: both ends, the midpoint, the radius, each one's horizon at [altRefPieds]. */
    fun geometrieJson(r: Reglage, altRefPieds: Int = 35_000): JSONObject {
        val mil = milieu(r.aLat, r.aLon, r.bLat, r.bLon)
        val h = altRefPieds * PIED
        return JSONObject().put("a", JSONArray(listOf(r.aLat, r.aLon))).put("b", JSONArray(listOf(r.bLat, r.bLon)))
            .put("milieu", JSONArray(listOf(Math.round(mil.first * 1e4) / 1e4, Math.round(mil.second * 1e4) / 1e4)))
            .put("rayonKm", r.rayonKm).put("km", Math.round(Geo.distanceKm(r.aLat, r.aLon, r.bLat, r.bLon)))
            .put("horizonAKm", Math.round(horizonKm(r.aHauteurM, h))).put("horizonBKm", Math.round(horizonKm(r.bHauteurM, h)))
            .put("altRef", altRefPieds)
            // Over this distance, no aircraft at the reference height sees both: the path is out of reach.
            .put("possible", Geo.distanceKm(r.aLat, r.aLon, r.bLat, r.bLon) <= horizonKm(r.aHauteurM, h) + horizonKm(r.bHauteurM, h))
    }

    fun json(f: Fenetre): JSONObject = JSONObject().put("icao", f.icao).put("indicatif", f.indicatif).put("de", f.debutMs).put("a", f.finMs)
        .put("ouverte", f.ouverte).put("alt", f.altPieds).put("milieuKm", Math.round(f.distMilieuKm))
        .put("elA", Math.round(f.elA * 10) / 10.0).put("elB", Math.round(f.elB * 10) / 10.0)
        .put("doppler", Math.round(f.dopplerHz)).put("trajetKm", Math.round(f.trajetKm)).put("finConnue", f.finConnue)
}
