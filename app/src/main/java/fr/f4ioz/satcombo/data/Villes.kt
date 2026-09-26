/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import fr.f4ioz.satcombo.R
import org.json.JSONArray

/**
 * Populated places of France, to find one's bearings on a map: a park outline
 * alone is unrecognisable, three town names around it place it at a glance.
 *
 * GeoNames (CC BY), places of 200+ inhabitants, **sorted by decreasing
 * population** — the file order does the work: the first found in a window
 * are the most important. 25,187 entries, 727 KB.
 */
object Villes {

    class Ville(val nom: String, val lat: Double, val lon: Double)

    @Volatile private var cache: List<Ville>? = null

    fun charge(context: Context): List<Ville> {
        cache?.let { return it }
        val lu = runCatching {
            val txt = context.resources.openRawResource(R.raw.villes)
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            (0 until arr.length()).map { i ->
                val v = arr.getJSONArray(i)
                Ville(v.getString(0), v.getDouble(1), v.getDouble(2))
            }
        }.getOrDefault(emptyList())
        cache = lu
        return lu
    }

    /**
     * The [max] largest towns in the window: the file is sorted by population,
     * so take the first that fall inside and stop. One pass, no sorting.
     */
    fun dansFenetre(
        context: Context,
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double,
        max: Int = 6,
    ): List<Ville> {
        val out = ArrayList<Ville>(max)
        for (v in charge(context)) {
            if (v.lat in latMin..latMax && v.lon in lonMin..lonMax) {
                out.add(v)
                if (out.size >= max) break
            }
        }
        return out
    }
}
