/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** An announced sked/activation from hams.at. */
data class SkedAlert(
    val callsign: String,
    val satNorad: Int,
    val satName: String,
    val mode: String?,
    val aosMs: Long,
    val losMs: Long,
    val grids: List<String>,
    val comment: String?,
    val url: String?,
    val workableStartMs: Long? = null,  // hams.at: my common-window start (authed)
    val workableEndMs: Long? = null,    // hams.at: my common-window end (authed)
    val maxElevationDeg: Double? = null,
    val isWorkable: Boolean? = null,
    val matchPercent: Int? = null
)

/** Fetches upcoming announced skeds from hams.at (public API). */
class SkedRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
) {
    @Volatile private var cache: List<SkedAlert> = emptyList()
    @Volatile private var fetchedAt = 0L
    @Volatile private var cachedToken = ""

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    suspend fun upcoming(token: String = "", force: Boolean = false): List<SkedAlert> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && cachedToken == token && now - fetchedAt < 5 * 60_000) return@withContext cache
        runCatching {
            val builder = Request.Builder()
                .url("https://hams.at/api/alerts/upcoming")
                .header("User-Agent", "SatMe/5.9 amateur-radio app (F4IOZ)")
                .header("Accept", "application/json")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            val req = builder.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext cache
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                val list = ArrayList<SkedAlert>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sat = o.optJSONObject("satellite") ?: continue
                    val grids = o.optJSONArray("grids")?.let { g ->
                        (0 until g.length()).map { g.getString(it) }
                    } ?: emptyList()
                    list.add(SkedAlert(
                        callsign = o.optString("callsign"),
                        satNorad = sat.optInt("number"),
                        satName = sat.optString("name"),
                        mode = o.optString("mode").takeIf { it.isNotBlank() && it != "null" },
                        aosMs = parse(o.optString("aos_at")),
                        losMs = parse(o.optString("los_at")),
                        grids = grids,
                        comment = o.optString("comment").takeIf { it.isNotBlank() && it != "null" },
                        url = o.optString("url").takeIf { it.isNotBlank() },
                        workableStartMs = o.optString("workable_start_at").takeIf { it.isNotBlank() && it != "null" }?.let { parse(it) },
                        workableEndMs = o.optString("workable_end_at").takeIf { it.isNotBlank() && it != "null" }?.let { parse(it) },
                        maxElevationDeg = if (o.isNull("max_elevation")) null else o.optDouble("max_elevation").takeIf { !it.isNaN() },
                        isWorkable = if (o.isNull("is_workable")) null else o.optBoolean("is_workable"),
                        matchPercent = if (o.isNull("match_percent")) null else o.optInt("match_percent")
                    ))
                }
                cache = list; fetchedAt = now; cachedToken = token
                list
            }
        }.getOrDefault(cache)
    }

    private fun parse(s: String): Long =
        runCatching { iso.parse(s)?.time ?: 0L }.getOrDefault(0L)
}
