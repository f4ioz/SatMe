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
import fr.f4ioz.satcombo.i18n.t

/** A selectable GP/TLE source. */
data class TleSource(val id: String, val label: String, val url: String)

object Sources {
    /**
     * All sources now use GP data in OMM JSON. CelesTrak exhausted 5-digit
     * catalog numbers (2026-07-11): new objects (100000+) are not published as
     * TLE at all, so JSON is the only future-proof format.
     * AMSAT publishes its own curated GP bulletin (with AMSAT_NAME designators).
     */
    // Computed so the localizable labels follow the active app language.
    val ALL get() = listOf(
        TleSource("amsat_gp", t("src_amsat_gp"),
            "https://newark192.amsat.org/gpdata/current/daily-bulletin.json"),
        TleSource("amateur", "Celestrak Amateur",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json"),
        TleSource("stations", "Celestrak Stations (ISS)",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json"),
        TleSource("visual", "Celestrak Visual",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=json"),
        TleSource("weather", t("src_weather"),
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=weather&FORMAT=json"),
        TleSource("cubesat", "Celestrak CubeSat",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=cubesat&FORMAT=json"),
    )
    // AMSAT only by default: one small curated download → much faster first
    // startup. Users can enable the Celestrak groups in Settings → Sources.
    val DEFAULT_IDS = setOf("amsat_gp")
    fun byIds(ids: Set<String>) = ALL.filter { it.id in ids }
}

/** Persists the user's enabled TLE source ids. */
class SourcesStore(context: Context) {
    private val prefs = context.getSharedPreferences("satcombo_sources", Context.MODE_PRIVATE)
    private val key = "enabled_ids"

    fun load(): Set<String> =
        prefs.getStringSet(key, Sources.DEFAULT_IDS)?.takeIf { it.isNotEmpty() }
            ?: Sources.DEFAULT_IDS

    fun save(ids: Set<String>) {
        prefs.edit().putStringSet(key, ids.ifEmpty { Sources.DEFAULT_IDS }).apply()
    }
}
