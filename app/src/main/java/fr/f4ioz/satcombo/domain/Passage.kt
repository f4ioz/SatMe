/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * What belongs to the current pass ("have I already worked this one?").
 *
 * This was first decided by a duration — same satellite, under an hour — and
 * that was wrong both ways:
 *
 * - **Too wide.** A contact forty minutes earlier, between two passes, fell
 *   in the window: "✓ 1" flagged a callsign as already worked although the
 *   contact came from another pass, satellite at −70° elevation.
 * - **Too narrow.** RS-44 or the Molniyas stay up for hours. One hour cut
 *   the pass in the middle and showed a station worked twenty minutes
 *   earlier as new.
 *
 * A pass has no duration: it has an AOS and a LOS, which SGP4 already gives.
 * That window alone decides. Outside a pass the answer is empty and the
 * counter disappears.
 *
 * The elevation stored in the entry is a second lock: a contact logged with
 * the satellite below the horizon (bench tests) belongs to no pass.
 */
object Passage {

    /** A pass, from acquisition to loss of signal. */
    data class Fenetre(val debutMs: Long, val finMs: Long)

    /** A log entry, reduced to what the rule looks at. */
    data class Inscrit(
        val timeMs: Long,
        val satellite: String,
        val elevationDeg: Double,
        val indicatif: String,
    )

    /**
     * The pass containing [maintenant], if any. Windows come from the
     * prediction of the displayed satellite.
     */
    fun enCours(fenetres: List<Fenetre>, maintenant: Long): Fenetre? =
        fenetres.firstOrNull { maintenant >= it.debutMs && maintenant <= it.finMs }

    /**
     * Callsigns already worked during [fenetre], newest first, no duplicates.
     *
     * No window, empty list: better announce nothing than another pass.
     */
    fun indicatifs(
        journal: List<Inscrit>,
        satellite: String,
        fenetre: Fenetre?,
    ): List<String> {
        if (fenetre == null || satellite.isBlank()) return emptyList()
        return journal
            .filter {
                it.satellite == satellite &&
                    it.indicatif.isNotBlank() &&
                    it.elevationDeg >= 0.0 &&
                    it.timeMs >= fenetre.debutMs &&
                    it.timeMs <= fenetre.finMs
            }
            .sortedByDescending { it.timeMs }
            .map { it.indicatif }
            .distinct()
    }
}
