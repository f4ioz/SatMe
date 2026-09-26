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
         * 2 : ajout de l'agenda, des activations et de l'index de prédiction.
         *
         * Le numéro monte, mais la relecture reste compatible dans les deux
         * sens : une sauvegarde de version 1 n'a pas de bloc « fichiers » et
         * l'import s'en passe ; une sauvegarde de version 2 relue par une
         * ancienne application voit son bloc ignoré. Personne ne perd son
         * carnet parce qu'il a mis à jour dans le mauvais ordre.
         */
        const val VERSION = 2
        // Stores worth backing up (name -> included).
        private val STORES = listOf(
            "satcombo_settings",
            "satcombo_favorites",
            "satcombo_satconfig",
            "satcombo_sources",
            // Ajouté : l'agenda des passages retenus. C'est un choix de
            // l'opérateur, que rien ne permet de reconstituer — contrairement
            // au cache orbital ou à la base POTA, qui se retéléchargent.
            "satcombo_agenda"
        )

        /**
         * Les fichiers de données à emporter, avec leur nature.
         *
         * `qso_log.json` est le carnet : la donnée la plus irremplaçable de
         * l'application. `activations.json` porte les activations POTA, qui ne
         * se reconstituent pas non plus. `index_indicatifs.json` est l'index de
         * prédiction importé d'un ADIF — reconstituable par un nouvel import,
         * mais l'emporter évite à l'opérateur de retrouver son fichier.
         *
         * Volontairement absents : `tle_cache.txt` et `pota_region.json`, qui se
         * retéléchargent, et le dossier `qrv/` des photos, qui pèse trop lourd
         * pour un fichier de configuration destiné à voyager par courrier.
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

        // Le carnet garde sa clé historique « log », pour qu'une sauvegarde
        // faite aujourd'hui se relise par une version d'hier.
        runCatching {
            val f = context.filesDir.resolve(LOG_FILE)
            if (f.exists()) root.put("log", JSONArray(f.readText()))
        }

        // Les autres fichiers vont dans un bloc à part, indexé par nom : un
        // nouveau fichier s'ajoute à la liste sans toucher au format.
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

        // Les fichiers annexes d'abord : si l'un d'eux est illisible, on veut
        // quand même que le carnet passe.
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
