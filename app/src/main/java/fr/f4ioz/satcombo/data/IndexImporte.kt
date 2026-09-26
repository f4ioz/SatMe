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
import fr.f4ioz.satcombo.domain.Indicatifs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imported prediction index.
 *
 * This holds **no** contacts, only hints: callsign, grid square, date,
 * satellite. The reference log stays local and unique; this is just a cache
 * of what a server knows beyond it. Two logs to reconcile is one too many.
 *
 * Ten thousand lines fit in a few hundred KB and reload at startup unnoticed;
 * a database would add a dependency and migrations for nothing.
 */
class IndexImporte(context: Context) {

    private val fichier = context.filesDir.resolve("index_indicatifs.json")

    fun charge(): List<Indicatifs.Contact> {
        if (!fichier.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(fichier.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Indicatifs.Contact(
                    indicatif = o.optString("c"),
                    locator = o.optString("g"),
                    quandMs = o.optLong("t"),
                    satellite = o.optString("s"),
                    nom = o.optString("n"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun enregistre(contacts: List<Indicatifs.Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(JSONObject().apply {
                put("c", c.indicatif); put("g", c.locator)
                put("t", c.quandMs); put("s", c.satellite); put("n", c.nom)
            })
        }
        runCatching { fichier.writeText(arr.toString()) }
    }

    fun vide() { runCatching { fichier.delete() } }
}
