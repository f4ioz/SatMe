/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The bands map's PSKReporter layer: the reports grouped where they were
 * received (6° cells), and the paths that touch the visitor's QTH. The words
 * of the paths, settled once (the old map said the opposite of its help):
 * `heard` — a transmitter near the QTH was heard far away (« on vous
 * entend »); `hearing` — a receiver near the QTH heard a distant station
 * (« vous entendez »).
 */
object CartePropagation {

    fun psk(spots: List<PskReporter.Spot>, qth: String): JSONObject {
        val cellules = LinkedHashMap<Long, MutableList<Triple<Double, Double, PskReporter.Spot>>>()
        val parBande = HashMap<String, Int>()
        for (s in spots) {
            if (s.bande.isNotEmpty()) parBande.merge(s.bande, 1, Int::plus)
            val p = Geo.locator(s.rxLoc) ?: continue
            val k = (Math.rint(p.first / 6).toLong() + 100) * 1000 + Math.rint(p.second / 6).toLong() + 100
            cellules.getOrPut(k) { ArrayList() } += Triple(p.first, p.second, s)
        }
        val blobs = cellules.values.sortedByDescending { it.size }.take(160).map { c ->
            val snrs = c.mapNotNull { it.third.snr.toIntOrNull() }
            JSONObject().put("lat", Math.round(c.map { it.first }.average() * 100) / 100.0).put("lon", Math.round(c.map { it.second }.average() * 100) / 100.0)
                .put("count", c.size).put("snr", if (snrs.isEmpty()) JSONObject.NULL else Math.round(snrs.average() * 10) / 10.0)
                .put("bands", JSONObject(c.groupingBy { it.third.bande }.eachCount().filterKeys { it.isNotEmpty() }))
                .put("dxcc", JSONArray(c.mapNotNull { it.third.rxDxcc.ifEmpty { null } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }))
        }
        val chemins = JSONArray()
        val q = Geo.locator(qth)
        if (q != null) {
            fun pres(p: Pair<Double, Double>) = abs(p.first - q.first) < 3 && abs(Geo.ecartLon(p.second, q.second)) < 3
            for (s in spots) {
                if (chemins.length() >= 80) break
                val t = Geo.locator(s.txLoc) ?: continue
                val r = Geo.locator(s.rxLoc) ?: continue
                val (a, b, call, dir) = when {
                    pres(t) -> Quad(q, r, s.rx, "heard")
                    pres(r) -> Quad(t, q, s.tx, "hearing")
                    else -> continue
                }
                chemins.put(JSONObject().put("lat1", a.first).put("lon1", a.second).put("lat2", b.first).put("lon2", b.second)
                    .put("band", s.bande).put("snr", s.snr).put("call", call).put("dir", dir))
            }
        }
        return JSONObject().put("total", spots.size).put("by_band", JSONObject(parBande)).put("blobs", JSONArray(blobs)).put("qth_paths", chemins)
    }

    private data class Quad(val a: Pair<Double, Double>, val b: Pair<Double, Double>, val call: String, val dir: String)
}
