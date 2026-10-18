/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.domain.Simplifie
import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared map's data (« Carte »): the coastlines SatMe already carries
 * (Natural Earth, public domain — the METEOR pictures' outlines), thinned
 * for a world map drawn in the browser without any tile server, and the
 * layers' points as JSON.
 */
object ApiCarte {

    /**
     * The land outlines of [texte] (rings of flat lon, lat, lon, lat… as in
     * `land.json`), thinned to [eps] degrees; rings smaller than [eps] gone.
     * Coordinates rounded to 0.01°.
     */
    fun traits(texte: String, eps: Double = 0.08): String {
        val a = JSONArray(texte)
        val out = StringBuilder("[")
        var premier = true
        for (k in 0 until a.length()) {
            val l = a.getJSONArray(k)
            if (l.length() < 8) continue
            val pts = List(l.length() / 2) { doubleArrayOf(l.getDouble(2 * it), l.getDouble(2 * it + 1)) }
            var xmin = 999.0; var xmax = -999.0; var ymin = 999.0; var ymax = -999.0
            for (p in pts) { xmin = minOf(xmin, p[0]); xmax = maxOf(xmax, p[0]); ymin = minOf(ymin, p[1]); ymax = maxOf(ymax, p[1]) }
            if (xmax - xmin < eps * 2 && ymax - ymin < eps * 2) continue
            val s = Simplifie.anneau(pts, eps)
            if (s.size < 3) continue
            if (!premier) out.append(',')
            premier = false
            out.append('[')
            s.forEachIndexed { i, p ->
                if (i > 0) out.append(',')
                out.append(Math.round(p[0] * 100) / 100.0).append(',').append(Math.round(p[1] * 100) / 100.0)
            }
            out.append(']')
        }
        return out.append(']').toString()
    }

    /** A point of a layer: its name, where, and what else the page shows. */
    fun point(nom: String, lat: Double, lon: Double, vararg plus: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("nom", nom).put("lat", Math.round(lat * 1e4) / 1e4).put("lon", Math.round(lon * 1e4) / 1e4)
        for ((k, v) in plus) if (v != null) o.put(k, v)
        return o
    }

    /** APRS stations with a position, newest first, at most [n]. */
    fun aprs(stations: List<fr.f4ioz.satcombo.aprs.AprsJeu.Station>, n: Int = 300): JSONArray =
        JSONArray(stations.take(n).map { point(it.indicatif, it.lat, it.lon, "t" to it.dernier, "iss" to it.viaIss, "carre" to it.carre) })
}
