/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * A SatMe GP server (github.com/f4ioz/SatMe-serveur): one server fetching AMSAT
 * and CelesTrak every two hours and serving every phone from its copy,
 * instead of each phone asking them. CelesTrak blocks addresses that ask too
 * often; the server asks for everyone, politely.
 *
 * The server keeps the sources' own groups, so each enabled source maps to
 * one address on it. The original address stays as a fallback when the
 * server does not answer: no elements at all would be worse.
 */
object ServeurGp {

    /** The author's server, used until the user sets another or empties the field. */
    const val DEFAUT = "https://gp.f4ioz.fr"

    /** Group name on the server for a SatMe source id. */
    fun groupe(sourceId: String): String = if (sourceId == "amsat_gp") "amsat" else sourceId

    /** "https://gp.exemple.org/" or "gp.exemple.org" → "https://gp.exemple.org". */
    fun normalise(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        return if (b.startsWith("http://") || b.startsWith("https://")) b else "https://$b"
    }

    /**
     * Addresses to try for one source: the server first, then the source
     * itself — unless [seul]: the server only, the source never asked.
     */
    fun adresses(base: String, s: TleSource, seul: Boolean = false): List<String> {
        val b = normalise(base)
        return when {
            b.isEmpty() -> listOf(s.url)
            seul -> listOf("$b/gp/${groupe(s.id)}.json")
            else -> listOf("$b/gp/${groupe(s.id)}.json", s.url)
        }
    }

    /** One satellite by catalogue number on the server. */
    fun catnr(base: String, n: Int): String = "${normalise(base)}/gp/catnr/$n.json"

    /** The server's group list, for the settings' test button. */
    fun index(base: String): String = "${normalise(base)}/gp/index.json"
}
