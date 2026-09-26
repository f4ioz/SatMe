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
import android.content.SharedPreferences
import fr.f4ioz.satcombo.i18n.tf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports and imports the whole SatMe configuration as a single JSON document:
 * followed satellites, settings (QTH, CAT, display…), per-satellite tuning
 * (transponder choice, shifts, offsets) and the contact log.
 *
 * Deliberately NOT included: the orbital element cache (re-downloaded anyway)
 * and the POTA park database (large, region-specific, re-downloadable).
 *
 * The format is a plain map of SharedPreferences per store, so it survives new
 * settings being added later: unknown keys are simply carried through.
 */
class ConfigBackup(private val context: Context) {

    companion object {
        /**
         * 2: adds the agenda, activations and prediction index.
         *
         * Compatible both ways: a v1 backup has no "files" block and import does
         * without; an older app ignores the v2 block. Nobody loses their log by
         * updating in the wrong order.
         */
        const val VERSION = 2
        // Stores worth backing up (name -> included).
        private val STORES = listOf(
            "satcombo_settings",
            "satcombo_favorites",
            "satcombo_satconfig",
            "satcombo_sources",
            // The agenda of chosen passes: an operator choice nothing can rebuild,
            // unlike the orbital cache or POTA database.
            "satcombo_agenda"
        )

        /**
         * Data files to carry along.
         *
         * `qso_log.json` is the log, the most irreplaceable data; `activations.json`
         * cannot be rebuilt either; `index_indicatifs.json` could be re-imported from
         * ADIF, but carrying it saves finding the file again.
         *
         * Left out on purpose: `tle_cache.txt` and `pota_region.json` (re-downloaded)
         * and the `qrv/` photos (too heavy for a file meant to travel by email).
         */
        private val FICHIERS = listOf(
            "qso_log.json",
            "activations.json",
            "index_indicatifs.json"
        )
        private const val LOG_FILE = "qso_log.json"
    }

    /** Serialize everything to a JSON string. */
    fun export(): String {
        val root = JSONObject()
        root.put("app", "SatMe")
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val stores = JSONObject()
        for (name in STORES) {
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            stores.put(name, prefsToJson(p))
        }
        root.put("stores", stores)

        // The log keeps its historic "log" key so older versions can read it.
        runCatching {
            val f = context.filesDir.resolve(LOG_FILE)
            if (f.exists()) root.put("log", JSONArray(f.readText()))
        }

        // Other files go in a separate block keyed by name: a new file needs no
        // format change.
        val fichiers = JSONObject()
        for (nom in FICHIERS) {
            if (nom == LOG_FILE) continue
            runCatching {
                val f = context.filesDir.resolve(nom)
                if (f.exists()) fichiers.put(nom, f.readText())
            }
        }
        root.put("fichiers", fichiers)

        return root.toString(2)
    }

    /**
     * Restore from a JSON string produced by [export].
     * Returns a human-readable summary, or null if the document is invalid.
     */
    fun import(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("app") != "SatMe") return null

        var settingsCount = 0
        var favCount = 0
        val stores = root.optJSONObject("stores") ?: return null
        for (name in STORES) {
            val obj = stores.optJSONObject(name) ?: continue
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val n = jsonToPrefs(obj, p)
            if (name == "satcombo_favorites") favCount = n
            if (name == "satcombo_settings") settingsCount = n
        }

        // Side files first: if one is unreadable, the log must still go through.
        root.optJSONObject("fichiers")?.let { obj ->
            for (nom in FICHIERS) {
                if (nom == LOG_FILE) continue
                val contenu = obj.optString(nom, "")
                if (contenu.isNotEmpty()) runCatching {
                    context.filesDir.resolve(nom).writeText(contenu)
                }
            }
        }

        var logCount = 0
        root.optJSONArray("log")?.let { arr ->
            runCatching {
                context.filesDir.resolve(LOG_FILE).writeText(arr.toString())
                logCount = arr.length()
            }
        }
        return tf("config_restored", settingsCount, favCount, logCount)
    }

    /** SharedPreferences -> JSON, keeping type info so we can restore exactly. */
    private fun prefsToJson(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Set<*> -> {
                    e.put("t", "ss")
                    e.put("v", JSONArray(v.map { it.toString() }))
                }
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    /** JSON -> SharedPreferences. Returns how many keys were written. */
    private fun jsonToPrefs(o: JSONObject, p: SharedPreferences): Int {
        val ed = p.edit()
        var n = 0
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "ss" -> {
                    val arr = e.optJSONArray("v") ?: continue
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set += arr.optString(i)
                    ed.putStringSet(k, set)
                }
                else -> continue
            }
            n++
        }
        ed.apply()
        return n
    }

    /** Suggested file name for an export. */
    fun suggestedFileName(): String {
        val t = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "SatMe-config-$t.json"
    }
}
