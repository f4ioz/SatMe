/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.ciel

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.SunCalc
import fr.f4ioz.site.vue.Html.h
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * « Au-dessus de JN18FS » — the station's sky at one instant, worked out by
 * SatMe's engine: the home page's satellites that are up now (azimuth,
 * elevation, where they are over the Earth), the pass under way or the next
 * one (its AOS, LOS, highest point and track in the sky), the Sun. The home
 * page draws it at once as a sky dial (an SVG that needs no script); the
 * page's script then keeps it alive (satellite.js, the same elements).
 * Only real elements and real time: nothing here is invented.
 */
object CielStation {

    /** A satellite above the horizon now. */
    data class Vu(val nom: String, val catnum: Int, val az: Double, val el: Double, val lat: Double, val lon: Double, val altKm: Double, val distKm: Double)

    /** The sky at [t]: who is up, the passes from now on (the one under way first), the Sun's azimuth and elevation. */
    data class Instantane(val t: Long, val obs: Observer, val vus: List<Vu>, val passages: List<SatPass>, val soleilAz: Double, val soleilEl: Double) {
        /** The pass under way (the highest when several), null when none. */
        val enCours: SatPass? get() = passages.filter { it.aosEpochMs <= t && it.losEpochMs > t }.maxByOrNull { it.maxElevationDeg }
        /** The next pass to begin. */
        val prochain: SatPass? get() = passages.firstOrNull { it.aosEpochMs > t }
    }

    /**
     * The sky of [obs] at [t] for [sats]: positions from [predicteur], the passes given ([passages], those of the next hours,
     * sorted by AOS — the catalogue keeps them) without the ones already over.
     */
    fun instantane(predicteur: PassPredictor, sats: List<TleEntry>, obs: Observer, passages: List<SatPass>, t: Long): Instantane {
        val vus = sats.mapNotNull { e ->
            val p = runCatching { predicteur.positionAt(e, obs, t) }.getOrNull() ?: return@mapNotNull null
            if (p.elevationDeg <= 0.0 || p.elevationDeg.isNaN()) null
            else Vu(e.name, e.catalogNumber, norm360(p.azimuthDeg), p.elevationDeg, p.latDeg, p.lonDeg, p.altKm, p.rangeKm)
        }.sortedByDescending { it.el }
        val so = SunCalc.azElDeg(obs.latDeg, obs.lonDeg, Date(t))
        return Instantane(t, obs, vus, passages.filter { it.losEpochMs > t }.sortedBy { it.aosEpochMs }, norm360(so[0]), so[1])
    }

    fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    /** Where (az, el) falls on the dial: the zenith at the centre, the horizon at [r], north up and east on the right, as on a compass (the Ciel page's way). */
    fun polaire(az: Double, el: Double, r: Double): Pair<Double, Double> {
        val k = r * (1 - el.coerceIn(0.0, 90.0) / 90.0)
        val a = Math.toRadians(az)
        return k * sin(a) to -k * cos(a)
    }

    /** The countdown as the page's script writes it: « 2 h 05 » beyond an hour, else minutes and seconds « 07:30 ». */
    fun compte(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d h %02d", s / 3600, (s % 3600) / 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    /**
     * The dial as SVG (viewBox −120…120): the rings at 0°, 30° and 60°, the cardinal points, the next pass's (or the one
     * under way's) track, the satellites up now with their names, the Sun when it is up. Drawn again by the page's script.
     */
    fun svg(i: Instantane, titre: String): String {
        val r = 100.0
        val sb = StringBuilder()
        sb.append("<svg class=\"ciel-cadran\" viewBox=\"-120 -120 240 240\" role=\"img\" aria-label=\"").append(h(titre)).append("\" data-ciel-cadran>")
        sb.append("<defs><radialGradient id=\"cc-fond\"><stop class=\"cc-z\" offset=\"0\"/><stop class=\"cc-hz\" offset=\"1\"/></radialGradient></defs>")
        sb.append("<circle class=\"cc-fond\" r=\"100\" fill=\"url(#cc-fond)\"/>")
        for (el in listOf(30, 60)) sb.append("<circle class=\"cc-g\" r=\"").append(fmt(r * (1 - el / 90.0))).append("\"/>")
        sb.append("<circle class=\"cc-h\" r=\"100\"/><path class=\"cc-g\" d=\"M0-100V100M-100 0H100\"/>")
        for (k in 0 until 36) {
            val a = Math.toRadians(k * 10.0); val l = if (k % 9 == 0) 7.0 else if (k % 3 == 0) 4.5 else 2.5
            sb.append("<path class=\"cc-t\" d=\"M").append(fmt(100 * sin(a))).append(' ').append(fmt(-100 * cos(a))).append('L')
                .append(fmt((100 + l) * sin(a))).append(' ').append(fmt(-(100 + l) * cos(a))).append("\"/>")
        }
        for ((t, x, y) in listOf(Triple("N", 0, -112), Triple("E", 113, 0), Triple("S", 0, 112), Triple("O", -113, 0)))
            sb.append("<text class=\"cc-c\" x=\"").append(x).append("\" y=\"").append(y).append("\">").append(t).append("</text>")
        sb.append("<text class=\"cc-e\" x=\"3\" y=\"").append(fmt(-r * (1 - 30 / 90.0) + 8)).append("\">30°</text><text class=\"cc-e\" x=\"3\" y=\"")
            .append(fmt(-r * (1 - 60 / 90.0) + 8)).append("\">60°</text>")
        // The pass shown: under way, else the next.
        val p = i.enCours ?: i.prochain
        if (p != null && p.track.size >= 2) {
            sb.append("<polyline class=\"cc-trace").append(if (p === i.enCours) " encours" else "").append("\" points=\"")
            p.track.forEach { (az, el) -> val (x, y) = polaire(az, el, r); sb.append(fmt(x)).append(',').append(fmt(y)).append(' ') }
            sb.append("\"/>")
            val (x0, y0) = polaire(p.track.first().first, p.track.first().second, r)
            sb.append("<circle class=\"cc-aos\" cx=\"").append(fmt(x0)).append("\" cy=\"").append(fmt(y0)).append("\" r=\"2.6\"/>")
        }
        if (i.soleilEl > 0) {
            val (x, y) = polaire(i.soleilAz, i.soleilEl, r)
            sb.append("<g class=\"cc-soleil\" transform=\"translate(").append(fmt(x)).append(' ').append(fmt(y)).append(")\"><circle r=\"5\"/><title>Soleil · ")
                .append(i.soleilEl.toInt()).append("°</title></g>")
        }
        for (v in i.vus.take(12)) {
            val (x, y) = polaire(v.az, v.el, r)
            sb.append("<g class=\"cc-sat\" transform=\"translate(").append(fmt(x)).append(' ').append(fmt(y)).append(")\"><circle r=\"4.2\"/>").append(if (x > 35) "<text x=\"-7\" y=\"-6\" text-anchor=\"end\">" else "<text x=\"7\" y=\"-6\">")
                .append(h(v.nom)).append("</text></g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * What the page's script needs (`/api/ciel`, and in the page at first): the station, the satellites' elements, the
     * passes of the next hours with their tracks, the time of the server (its clock is the reference).
     */
    fun json(i: Instantane, locator: String, sats: List<TleEntry>): JSONObject {
        val o = JSONObject().put("t", i.t).put("locator", locator)
            .put("obs", JSONObject().put("lat", i.obs.latDeg).put("lon", i.obs.lonDeg))
            .put("soleil", JSONObject().put("az", r1(i.soleilAz)).put("el", r1(i.soleilEl)))
        o.put("sats", JSONArray(sats.map { JSONObject().put("nom", it.name).put("catnum", it.catalogNumber).put("l1", it.line1).put("l2", it.line2) }))
        o.put("passages", JSONArray(i.passages.take(12).map { p ->
            JSONObject().put("nom", p.satName).put("catnum", p.catalogNumber).put("aos", p.aosEpochMs).put("los", p.losEpochMs)
                .put("max", r1(p.maxElevationDeg)).put("azAos", r1(norm360(p.aosAzimuthDeg))).put("azLos", r1(norm360(p.losAzimuthDeg)))
                .put("trace", JSONArray(p.track.map { (az, el) -> JSONArray(listOf(r1(norm360(az)), r1(el))) }))
        }))
        o.put("vus", JSONArray(i.vus.map { v -> JSONObject().put("nom", v.nom).put("catnum", v.catnum).put("az", r1(v.az)).put("el", r1(v.el)) }))
        return o
    }

    /**
     * The coastline made light for the home page's globe and the contacts' map: rings as `[lon, lat, lon, lat…]`, a point
     * kept only [pas]° or more from the last kept, rings smaller than [minEtendue]° dropped (a few tens of KB instead of 300).
     */
    fun traitsLegers(anneaux: JSONArray, pas: Double = 0.45, minEtendue: Double = 1.2): JSONArray {
        val out = JSONArray()
        for (k in 0 until anneaux.length()) {
            val a = anneaux.optJSONArray(k) ?: continue
            if (a.length() < 8) continue
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            val l = ArrayList<Double>()
            var px = Double.NaN; var py = Double.NaN
            var j = 0
            while (j + 1 < a.length()) {
                val x = a.optDouble(j); val y = a.optDouble(j + 1)
                j += 2
                if (x.isNaN() || y.isNaN()) continue
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                if (px.isNaN() || kotlin.math.abs(x - px) + kotlin.math.abs(y - py) >= pas) { l += r1(x); l += r1(y); px = x; py = y }
            }
            if (x1 - x0 < minEtendue && y1 - y0 < minEtendue) continue
            if (l.size < 8) continue
            out.put(JSONArray(l))
        }
        return out
    }

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.1f", d)
    private fun r1(d: Double) = Math.round(d * 10) / 10.0
}
