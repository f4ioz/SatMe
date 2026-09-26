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
import fr.f4ioz.satcombo.domain.Pays
import org.json.JSONObject

/**
 * Country outline catalogue: 196 countries, 463 KB, bundled in the APK.
 *
 * Bundled on purpose: about 6 % of the APK, and no server, cache or degraded
 * mode to fail when portable without network — exactly when a QRV card is
 * made.
 *
 * Natural Earth 1:50M, Douglas-Peucker simplified (mainland France: 211
 * points). Loaded once and kept in memory: parsing the JSON is too costly to
 * redo on every screen open.
 */
object PaysStore {

    @Volatile private var cache: List<Pays.Contour>? = null

    fun tous(context: Context): List<Pays.Contour> {
        cache?.let { return it }
        val lu = runCatching { lis(context) }.getOrDefault(emptyList())
        cache = lu
        return lu
    }

    private fun lis(context: Context): List<Pays.Contour> {
        val texte = context.resources.openRawResource(R.raw.countries)
            .bufferedReader().use { it.readText() }
        val racine = JSONObject(texte)
        val out = ArrayList<Pays.Contour>(racine.length())
        val codes = racine.keys()
        while (codes.hasNext()) {
            val code = codes.next()
            val o = racine.getJSONObject(code)
            val arr = o.getJSONArray("r")
            val anneaux = ArrayList<DoubleArray>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val d = DoubleArray(a.length())
                for (j in 0 until a.length()) d[j] = a.getDouble(j)
                anneaux.add(d)
            }
            out.add(Pays.Contour(code, o.optString("n", code), anneaux))
        }
        // Largest first, so the first hit is right when a point falls in two
        // bounding boxes.
        return out.sortedByDescending { c -> c.anneaux.sumOf { Pays.aire(it) } }
    }

    /** The operator's country, and the pieces to draw around it. */
    fun autour(context: Context, lat: Double, lon: Double): Pair<Pays.Contour, List<DoubleArray>>? {
        val c = Pays.trouve(tous(context), lat, lon) ?: return null
        return c to Pays.morceauxAutour(c, lat, lon)
    }
}
