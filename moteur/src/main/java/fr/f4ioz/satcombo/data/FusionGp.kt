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
 * Several sources' elements made one list, the same on the phone and the PC.
 */
object FusionGp {

    /** Elements dated more than an hour ahead are predictions for later (SupGP segments come every six hours). */
    const val AVANCE_MAX_MS = 3_600_000L

    /**
     * One satellite per catalogue number across the sources: the
     * **freshest elements** win, but the name (AMSAT's "AO-07"…) and the
     * frequencies come from the first source that had it. Keeping the
     * first source's elements put the ISS five minutes off with a
     * nine-day-old bulletin while a fresh one sat right next to it.
     * A picture satellite that came without a real name gets its own
     * ([SatellitesImages.renomme]).
     */
    fun fusionne(listes: List<List<TleEntry>>, maintenant: Long = System.currentTimeMillis()): List<TleEntry> {
        val vus = LinkedHashMap<Int, TleEntry>()
        for (liste in listes) for (e in liste) {
            val avant = vus[e.catalogNumber]
            if (avant == null) { vus[e.catalogNumber] = e; continue }
            if (prefere(avant, e, maintenant) === e) {
                vus[e.catalogNumber] = e.copy(name = avant.name,
                    uplinkHz = avant.uplinkHz ?: e.uplinkHz, downlinkHz = avant.downlinkHz ?: e.downlinkHz,
                    mode = avant.mode ?: e.mode)
            }
        }
        return vus.values.map(SatellitesImages::renomme)
    }

    /**
     * Of two element sets for one satellite, the one for now: the most
     * recent epoch **not in the future**. CelesTrak's SupGP gives the ISS
     * as sixty six-hour segments reaching two weeks ahead; "most recent"
     * picked the last one, valid in a fortnight, not today. When both are
     * ahead, the nearest.
     */
    fun prefere(a: TleEntry, b: TleEntry, maintenant: Long = System.currentTimeMillis()): TleEntry {
        val ea = a.epochMs ?: 0L; val eb = b.epochMs ?: 0L
        val futurA = ea > maintenant + AVANCE_MAX_MS; val futurB = eb > maintenant + AVANCE_MAX_MS
        return when {
            futurA != futurB -> if (futurA) b else a
            futurA -> if (eb < ea) b else a
            else -> if (eb > ea) b else a
        }
    }
}
