/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Operator-reported status from the AMSAT Live OSCAR Status page. */
enum class AmsatStatus { ACTIVE, BEACON, NOT_HEARD, CONFLICT, UNKNOWN }

data class AmsatReport(
    val name: String,          // e.g. "RS-44"
    val mode: String,          // e.g. "V/u"
    val recent: AmsatStatus,   // most recent reported cell
    val activeReports: Int     // count of ACTIVE cells over the window
)

/**
 * Scrapes https://www.amsat.org/status/ — the crowd-sourced "is this bird
 * actually being heard right now" page. Complements SatNOGS (which says a sat
 * is alive, not whether it's live this week). Results are cached to disk so the
 * last-known status is available offline.
 */
class AmsatStatusRepository(
    private val context: Context? = null,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val URL = "https://www.amsat.org/status/"
        private const val CACHE_FILE = "amsat_status.json"
        private const val FRESH_MS = 1 * 60 * 60 * 1000L  // 1 h
        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        private val COLOR = mapOf(
            "#648fff" to AmsatStatus.ACTIVE,
            "#ffb000" to AmsatStatus.BEACON,
            "#dc267f" to AmsatStatus.NOT_HEARD,
            "#fe6100" to AmsatStatus.CONFLICT
        )
        private val ROW = Regex("<tr>.*?</tr>", RegexOption.DOT_MATCHES_ALL)
        private val NAME = Regex(""">([A-Za-z0-9\-]+)_\[([^\]]+)]<""")
        private val CELL = Regex("""bgcolor="(#[0-9a-fA-F]{6})"""", RegexOption.IGNORE_CASE)
    }

    private fun cacheFile() = context?.filesDir?.resolve(CACHE_FILE)

    /** Age of the cached status in ms, or null if no cache. */
    fun cacheAgeMs(): Long? {
        val f = cacheFile() ?: return null
        if (!f.exists()) return null
        return System.currentTimeMillis() - f.lastModified()
    }

    /** Load last cached reports (offline fallback). */
    fun loadCache(): Map<String, AmsatReport> {
        val f = cacheFile() ?: return emptyMap()
        if (!f.exists()) return emptyMap()
        return runCatching {
            val obj = JSONObject(f.readText())
            val out = LinkedHashMap<String, AmsatReport>()
            obj.keys().forEach { k ->
                val o = obj.getJSONObject(k)
                out[k] = AmsatReport(o.getString("n"), o.getString("m"),
                    AmsatStatus.valueOf(o.getString("s")), o.getInt("a"))
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun saveCache(reports: Map<String, AmsatReport>) {
        val f = cacheFile() ?: return
        runCatching {
            val obj = JSONObject()
            reports.forEach { (k, r) ->
                obj.put(k, JSONObject().apply {
                    put("n", r.name); put("m", r.mode); put("s", r.recent.name); put("a", r.activeReports)
                })
            }
            f.writeText(obj.toString())
        }
    }

    /**
     * Get reports: use fresh cache if young enough, else fetch and cache. On
     * network failure, fall back to whatever cache exists.
     */
    suspend fun get(force: Boolean = false): Map<String, AmsatReport> {
        val age = cacheAgeMs()
        if (!force && age != null && age < FRESH_MS) return loadCache()
        val fetched = runCatching { fetch() }.getOrDefault(emptyMap())
        return if (fetched.isNotEmpty()) { saveCache(fetched); fetched }
        else loadCache()
    }

    /** Fetch and parse the status table. Returns reports keyed by upper-case name. */
    suspend fun fetch(): Map<String, AmsatReport> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL)
            .header("User-Agent", "SatMe/1.0 (amateur radio satellite app)")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyMap()
            resp.body?.string() ?: return@withContext emptyMap()
        }
        parse(body)
    }

    internal fun parse(html: String): Map<String, AmsatReport> {
        val out = LinkedHashMap<String, AmsatReport>()
        for (row in ROW.findAll(html).map { it.value }) {
            val nm = NAME.find(row) ?: continue
            val name = nm.groupValues[1]
            val mode = nm.groupValues[2]
            val statuses = CELL.findAll(row)
                .mapNotNull { COLOR[it.groupValues[1].lowercase()] }
                .toList()
            if (statuses.isEmpty()) continue
            val recent = statuses.first()
            val active = statuses.count { it == AmsatStatus.ACTIVE }
            val key = name.uppercase()
            val report = AmsatReport(name, mode, recent, active)
            // Multiple rows can share a base name (ISS_[FM], ISS_[VHF Digi]…).
            // Prefer a voice/FM entry, else the one with the most ACTIVE reports.
            val existing = out[key]
            if (existing == null || isBetter(report, existing)) out[key] = report
        }
        return out
    }

    /** Prefer FM/voice transponders, then the entry with more ACTIVE reports. */
    private fun isBetter(a: AmsatReport, b: AmsatReport): Boolean {
        val aVoice = a.mode.contains("FM", true) || a.mode.contains("U/v", true) ||
            a.mode.contains("V/u", true) || a.mode.contains("SSB", true)
        val bVoice = b.mode.contains("FM", true) || b.mode.contains("U/v", true) ||
            b.mode.contains("V/u", true) || b.mode.contains("SSB", true)
        if (aVoice != bVoice) return aVoice
        return a.activeReports > b.activeReports
    }
}
