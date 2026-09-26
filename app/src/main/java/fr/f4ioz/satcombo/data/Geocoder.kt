/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class GeoResult(val label: String, val latDeg: Double, val lonDeg: Double)

/** City/place search via OpenStreetMap Nominatim. */
class Geocoder(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
) {
    suspend fun search(query: String): List<GeoResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val url = "https://nominatim.openstreetmap.org/search?q=" +
                    java.net.URLEncoder.encode(query, "UTF-8") + "&format=json&limit=6"
            val req = Request.Builder().url(url)
                .header("User-Agent", "SatCombo/3.4 amateur-radio app (F4IOZ)")
                .header("Accept-Language", "fr").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(r.body?.string().orEmpty())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    GeoResult(
                        label = o.optString("display_name"),
                        latDeg = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                        lonDeg = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
