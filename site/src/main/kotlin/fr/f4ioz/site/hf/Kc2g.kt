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

/**
 * KC2G's ionospheric model (prop.kc2g.com: IRI recalibrated on the
 * ionosondes): the MUF(3000) and foF2 contours, GeoJSON. CC BY 4.0 — the map
 * says « © KC2G, CC BY 4.0 ». KC2G renders every 15 minutes: kept 15
 * minutes, the last one served when it does not answer. Only the contours
 * are passed on (lines and their value), rounded: a few kilobytes.
 */
class Kc2g(private val amont: Amont, horloge: () -> Long = System::currentTimeMillis) {

    private val memo = Memo<JSONObject>(amont, 15 * 60_000L, 5 * 60_000L, 2, horloge)

    /** The [couche] contours (`muf` or `fof2`), or `{"error": …}`. */
    fun couche(couche: String, attenteMs: Long = 10_000L): JSONObject {
        val fichier = FICHIERS[couche] ?: return JSONObject().put("error", "couche inconnue")
        val l = memo.lis(couche, attenteMs) { allege(JSONObject(amont.texte("https://prop.kc2g.com/renders/current/$fichier", 4 * 1024 * 1024))) }
        return l.valeur ?: JSONObject().put("error", l.erreur ?: "indisponible")
    }

    fun pose(couche: String, o: JSONObject) = memo.pose(couche, o)

    companion object {
        val FICHIERS = mapOf("muf" to "mufd-normal-now.geojson", "fof2" to "fof2-normal-now.geojson")

        /**
         * The features kept: LineString and MultiLineString (the old page drew the first only), their
         * `level-value` (MHz); coordinates rounded to 0.01°. Everything else of the feed is dropped.
         */
        fun allege(geo: JSONObject): JSONObject {
            val src = geo.optJSONArray("features") ?: JSONArray()
            val out = JSONArray()
            fun ligne(c: JSONArray): JSONArray {
                val a = JSONArray()
                for (i in 0 until c.length()) {
                    val p = c.optJSONArray(i) ?: continue
                    val lo = p.optDouble(0, Double.NaN); val la = p.optDouble(1, Double.NaN)
                    if (lo.isNaN() || la.isNaN()) continue
                    a.put(JSONArray().put(Math.round(lo * 100) / 100.0).put(Math.round(la * 100) / 100.0))
                }
                return a
            }
            for (i in 0 until minOf(src.length(), 2000)) {
                val f = src.optJSONObject(i) ?: continue
                val g = f.optJSONObject("geometry") ?: continue
                val v = f.optJSONObject("properties")?.optDouble("level-value", Double.NaN) ?: Double.NaN
                if (v.isNaN()) continue
                val lignes = when (g.optString("type")) {
                    "LineString" -> listOf(ligne(g.optJSONArray("coordinates") ?: JSONArray()))
                    "MultiLineString" -> g.optJSONArray("coordinates")?.let { m -> (0 until m.length()).mapNotNull { m.optJSONArray(it)?.let(::ligne) } }.orEmpty()
                    else -> emptyList()
                }
                for (l in lignes) if (l.length() >= 2) out.put(JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("level-value", v))
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", l)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", out)
        }
    }
}
