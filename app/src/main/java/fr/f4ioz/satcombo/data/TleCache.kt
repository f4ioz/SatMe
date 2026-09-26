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
import java.io.File

/**
 * Disk cache of the last successful TLE fetch (raw 3LE text, re-parseable by
 * TleRepository.parse). Keyed by the enabled-source set so switching sources
 * doesn't serve mismatched data.
 */
class TleCache(context: Context) {
    private val file = File(context.filesDir, "tle_cache.txt")
    private val prefs = context.getSharedPreferences("satcombo_tle_cache", Context.MODE_PRIVATE)

    fun save(entries: List<TleEntry>, sourceIds: Set<String>) {
        if (entries.isEmpty()) return
        runCatching {
            file.writeText(buildString {
                entries.forEach { append(it.name).append('\n')
                    .append(it.line1).append('\n')
                    .append(it.line2).append('\n') }
            })
            prefs.edit()
                .putLong("ts", System.currentTimeMillis())
                .putStringSet("sources", sourceIds)
                .apply()
        }
    }

    /** Raw cached text, or null if absent. */
    fun loadRaw(): String? =
        runCatching { if (file.exists()) file.readText().ifBlank { null } else null }.getOrNull()

    fun ageMs(): Long? {
        val ts = prefs.getLong("ts", 0L)
        return if (ts > 0) System.currentTimeMillis() - ts else null
    }

    fun cachedSources(): Set<String> = prefs.getStringSet("sources", emptySet()).orEmpty()
}
