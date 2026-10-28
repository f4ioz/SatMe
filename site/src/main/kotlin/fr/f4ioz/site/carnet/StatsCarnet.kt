/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.carnet

import fr.f4ioz.satcombo.location.Maidenhead
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The logbook counted and put on a map: how many contacts, callsigns,
 * countries (as the log names them — Wavelog's DXCC entity), squares,
 * satellites and bands; the farthest contact; and each square worked
 * (four characters: no one's house), with its contacts, its last callsign and
 * its distance from the station — what the logbook page's map draws as arcs
 * from the station. Worked out from the log only: nothing added.
 */
object StatsCarnet {

    data class Stats(
        val qso: Int,
        val indicatifs: Int,
        val pays: Int,
        val carres: Int,
        val satellites: Int,
        val bandes: Int,
        /** The farthest contact with a square, and how far (km); null when none has one. */
        val odx: Qso?,
        val odxKm: Int,
        /** The satellites by number of contacts, the most first. */
        val parSatellite: List<Pair<String, Int>>,
    )

    /** One square worked: its centre, its contacts (by satellite or not), its last callsign, its distance from the station (km). */
    data class Carre(val carre: String, val lat: Double, val lon: Double, val n: Int, val sat: Int, val dernier: String, val km: Int)

    fun stats(l: List<Qso>, latStation: Double?, lonStation: Double?): Stats {
        var odx: Qso? = null; var odxKm = 0
        if (latStation != null && lonStation != null) for (q in l) {
            val p = centre(q.carre) ?: continue
            val d = km(latStation, lonStation, p.first, p.second).roundToInt()
            if (d > odxKm) { odxKm = d; odx = q }
        }
        val sats = l.map { it.satellite }.filter { it.isNotEmpty() }
        return Stats(
            qso = l.size,
            indicatifs = l.map { it.indicatif }.distinct().size,
            pays = l.map { it.pays.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().size,
            carres = l.mapNotNull { q -> q.carre.takeIf { centre(it) != null } }.distinct().size,
            satellites = sats.distinct().size,
            bandes = l.map { it.bande }.filter { it.isNotEmpty() }.distinct().size,
            odx = odx, odxKm = odxKm,
            parSatellite = sats.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
        )
    }

    /** The squares worked (the newest contact first in [l]), at most [max], the most worked first. */
    fun carres(l: List<Qso>, latStation: Double?, lonStation: Double?, max: Int = 1500): List<Carre> {
        val parCarre = LinkedHashMap<String, MutableList<Qso>>()
        for (q in l) { if (centre(q.carre) == null) continue; parCarre.getOrPut(q.carre.uppercase()) { ArrayList() } += q }
        return parCarre.map { (c, qs) ->
            val (la, lo) = centre(c)!!
            Carre(c, la, lo, qs.size, qs.count { it.satellite.isNotEmpty() }, qs.maxByOrNull { it.quandMs }!!.indicatif,
                if (latStation != null && lonStation != null) km(latStation, lonStation, la, lo).roundToInt() else 0)
        }.sortedWith(compareByDescending<Carre> { it.n }.thenBy { it.carre }).take(max)
    }

    /** For the map's script: `[{g, la, lo, n, s, c, km}]`. */
    fun json(l: List<Carre>): JSONArray = JSONArray(l.map { c ->
        JSONObject().put("g", c.carre).put("la", Math.round(c.lat * 100) / 100.0).put("lo", Math.round(c.lon * 100) / 100.0)
            .put("n", c.n).put("s", c.sat).put("c", c.dernier).put("km", c.km)
    })

    /** A square of four characters' centre (a longer locator read by its square only). */
    fun centre(carre: String): Pair<Double, Double>? = carre.trim().takeIf { it.length >= 4 }?.let { Maidenhead.toLatLon(it.take(4)) }

    /** The great-circle distance (km). */
    fun km(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = Math.PI / 180
        val a = sin((la2 - la1) * r / 2).let { it * it } + cos(la1 * r) * cos(la2 * r) * sin((lo2 - lo1) * r / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
