/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.json.JSONArray

/**
 * Orbital elements as the sources hand them out, whatever their format:
 * OMM JSON (AMSAT's bulletin, CelesTrak, a SatMe GP server), SatNOGS DB's TLE
 * in JSON, or a plain TLE file. SatMe-PC's catalogue and the F4IOZ web site
 * read them the same way.
 */
object LectureElements {

    /** Elements of any source, the format recognised; usable ones only. */
    fun lis(texte: String): List<TleEntry> {
        val t = texte.trimStart()
        val brut = when {
            t.startsWith("[") && t.contains("\"tle1\"") -> tleSatnogs(texte)
            t.startsWith("[") || t.startsWith("{") -> runCatching { OmmParser.parse(texte) }.getOrDefault(emptyList())
            else -> tle(texte)
        }
        return brut.filter { fr.f4ioz.satcombo.domain.PassPredictor.elementsUtilisables(it) }
    }

    /** SatNOGS DB's elements: [{"tle0": name, "tle1", "tle2", …}]. */
    fun tleSatnogs(json: String): List<TleEntry> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching { TleEntry(o.optString("tle0").removePrefix("0 ").trim(), o.getString("tle1"), o.getString("tle2")) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** A TLE file: name, line 1, line 2, again and again. */
    fun tle(texte: String): List<TleEntry> {
        val l = texte.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val out = ArrayList<TleEntry>()
        var i = 0
        while (i + 2 < l.size) {
            if (l[i + 1].startsWith("1 ") && l[i + 2].startsWith("2 ")) {
                runCatching { TleEntry(l[i].trim(), l[i + 1], l[i + 2]) }.getOrNull()
                    ?.takeIf { it.catalogNumber > 0 }?.let { out += it }
                i += 3
            } else i++
        }
        return out
    }
}
