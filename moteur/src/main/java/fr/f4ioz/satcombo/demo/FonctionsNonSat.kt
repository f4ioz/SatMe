/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * The functions that are not about satellites (planes, ships, radiosondes):
 * their tabs shown only when ticked in Réglages › Ma station. Kept by the
 * server, so every browser sees the same menu.
 *
 * Off by default; the first start after this choice appeared keeps on those
 * the station shows it has used ([migre]), so nothing in use disappears.
 */
object FonctionsNonSat {

    /** The tabs concerned, in the rail's order: id → name. */
    val ONGLETS: Map<String, String> = linkedMapOf("sondes" to "Sondes", "avions" to "Avions", "ais" to "Navires")

    /** The tabs ticked (a set of [ONGLETS]' ids); absent until the first start decides. */
    const val CLE = "pc_non_sat"

    /** The tabs ticked; null before [migre] has decided. */
    fun choisis(st: Stockage): Set<String>? = st.getStringSet(CLE, null)?.filter { it in ONGLETS }?.toSet()

    /**
     * Decided once: the tabs ticked as kept, else those [utilise] says the
     * station has used (a setting written, a log, a receiver running),
     * stored. Later calls only read.
     */
    fun migre(st: Stockage, utilise: (String) -> Boolean): Set<String> {
        choisis(st)?.let { return it }
        val l = ONGLETS.keys.filter { runCatching { utilise(it) }.getOrDefault(false) }.toSet()
        st.edit().putStringSet(CLE, l).apply()
        return l
    }

    /** [id] ticked or not; false for any other id. */
    fun pose(st: Stockage, id: String, on: Boolean): Set<String> {
        if (id !in ONGLETS) return choisis(st).orEmpty()
        val l = choisis(st).orEmpty().let { if (on) it + id else it - id }
        st.edit().putStringSet(CLE, l).apply()
        return l
    }

    fun actif(st: Stockage, id: String): Boolean = id !in ONGLETS || id in choisis(st).orEmpty()

    /** [tous] without the tabs not ticked. */
    fun onglets(tous: List<String>, choisis: Set<String>): List<String> = tous.filter { it !in ONGLETS || it in choisis }

    /** Whether [cle] holds a value of any type (a setting the owner wrote). */
    fun present(st: Stockage, cle: String): Boolean =
        st.getString(cle, null) != null || st.getStringSet(cle, null) != null ||
            st.getInt(cle, Int.MIN_VALUE) != Int.MIN_VALUE || st.getLong(cle, Long.MIN_VALUE) != Long.MIN_VALUE ||
            st.getBoolean(cle, false) == st.getBoolean(cle, true) || !st.getFloat(cle, Float.NaN).isNaN()
}
