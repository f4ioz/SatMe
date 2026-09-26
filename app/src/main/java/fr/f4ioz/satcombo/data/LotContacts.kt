/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.FusionCarnet
import org.json.JSONArray
import org.json.JSONObject

/**
 * A batch of contacts, carried from one phone to another.
 *
 * The log's own format in a named envelope: no conversion, no loss. ADIF
 * cannot say where a grid square came from, or tell a typed square from a
 * suggested one — exactly the detail we want on the other phone.
 *
 * The envelope is versioned; unknown fields are ignored and missing ones take
 * their default, so one phone can be updated before the other.
 */
object LotContacts {

    const val VERSION = 1

    /** Serialises the chosen entries. */
    fun ecrit(entries: List<LogEntry>, station: String = ""): String {
        val racine = JSONObject()
        racine.put("app", "SatMe")
        racine.put("lot", VERSION)
        racine.put("exportedAt", System.currentTimeMillis())
        if (station.isNotBlank()) racine.put("station", station)
        val arr = JSONArray()
        entries.sortedByDescending { it.timeMs }.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timeMs); put("s", e.satName); put("c", e.catnum)
                put("az", e.azimuthDeg); put("el", e.elevationDeg); put("n", e.note)
                put("ml", e.myLocator); put("cs", e.callsign); put("tl", e.theirLocator)
                put("mg", e.myGrids)
                put("md", e.mode); put("rs", e.rstSent); put("rr", e.rstRcvd)
                put("dl", e.downlinkMhz); put("ul", e.uplinkMhz)
                put("lo", e.locatorOrigine)
            })
        }
        racine.put("contacts", arr)
        return racine.toString(2)
    }

    /** Reads a batch back; `null` if it is not one. */
    fun lit(json: String): List<LogEntry>? = runCatching {
        val racine = JSONObject(json)
        if (!racine.has("contacts")) return null
        val arr = racine.getJSONArray("contacts")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LogEntry(
                timeMs = o.getLong("t"),
                satName = o.optString("s"),
                catnum = o.optInt("c"),
                azimuthDeg = o.optDouble("az", 0.0),
                elevationDeg = o.optDouble("el", 0.0),
                myLocator = o.optString("ml"),
                myGrids = o.optString("mg"),
                callsign = o.optString("cs"),
                theirLocator = o.optString("tl"),
                note = o.optString("n"),
                mode = o.optString("md"),
                rstSent = o.optString("rs"),
                rstRcvd = o.optString("rr"),
                downlinkMhz = o.optDouble("dl", 0.0),
                uplinkMhz = o.optDouble("ul", 0.0),
                locatorOrigine = o.optString("lo"))
        }
    }.getOrNull()

    /** The fields a merge can fill in. */
    private fun champs(e: LogEntry): Map<String, String> = mapOf(
        "sat" to e.satName,
        "ml" to e.myLocator,
        "mg" to e.myGrids,
        "tl" to e.theirLocator,
        "n" to e.note,
        "md" to e.mode,
        "rs" to e.rstSent,
        "rr" to e.rstRcvd,
        "lo" to e.locatorOrigine,
        "az" to if (e.azimuthDeg != 0.0) e.azimuthDeg.toString() else "",
        "el" to if (e.elevationDeg != 0.0) e.elevationDeg.toString() else "",
        "dl" to if (e.downlinkMhz > 0.0) e.downlinkMhz.toString() else "",
        "ul" to if (e.uplinkMhz > 0.0) e.uplinkMhz.toString() else "",
    )

    private fun fiche(e: LogEntry) =
        FusionCarnet.Fiche(e.timeMs, e.callsign, e.catnum, champs(e))

    data class Bilan(val fondu: List<LogEntry>, val ajoutes: Int,
                     val completes: Int, val identiques: Int, val desaccords: Int)

    /**
     * Merges [entrant] into [local] without overwriting anything. The rule and its
     * tests live in the domain layer; this only converts entries back and forth.
     */
    fun fusionne(local: List<LogEntry>, entrant: List<LogEntry>): Bilan {
        val b = FusionCarnet.fusionne(local.map(::fiche), entrant.map(::fiche))
        val parCle = HashMap<Triple<Long, String, Int>, LogEntry>()
        (local + entrant).forEach {
            val k = Triple(it.timeMs / 1000L, it.callsign.trim().uppercase(), it.catnum)
            // Local first: it must never be replaced by the incoming entry.
            if (!parCle.containsKey(k)) parCle[k] = it
        }
        local.forEach {
            val k = Triple(it.timeMs / 1000L, it.callsign.trim().uppercase(), it.catnum)
            parCle[k] = it
        }
        val fondu = b.fondu.mapNotNull { f ->
            val k = Triple(f.timeMs / 1000L, f.indicatif.trim().uppercase(), f.catnum)
            val base = parCle[k] ?: return@mapNotNull null
            base.copy(
                satName = f.champs["sat"].orEmpty().ifBlank { base.satName },
                myLocator = f.champs["ml"].orEmpty().ifBlank { base.myLocator },
                myGrids = f.champs["mg"].orEmpty().ifBlank { base.myGrids },
                theirLocator = f.champs["tl"].orEmpty().ifBlank { base.theirLocator },
                note = f.champs["n"].orEmpty().ifBlank { base.note },
                mode = f.champs["md"].orEmpty().ifBlank { base.mode },
                rstSent = f.champs["rs"].orEmpty().ifBlank { base.rstSent },
                rstRcvd = f.champs["rr"].orEmpty().ifBlank { base.rstRcvd },
                locatorOrigine = f.champs["lo"].orEmpty().ifBlank { base.locatorOrigine },
                azimuthDeg = f.champs["az"]?.toDoubleOrNull() ?: base.azimuthDeg,
                elevationDeg = f.champs["el"]?.toDoubleOrNull() ?: base.elevationDeg,
                downlinkMhz = f.champs["dl"]?.toDoubleOrNull() ?: base.downlinkMhz,
                uplinkMhz = f.champs["ul"]?.toDoubleOrNull() ?: base.uplinkMhz)
        }
        return Bilan(fondu, b.ajoutes, b.completes, b.identiques, b.desaccords)
    }
}
