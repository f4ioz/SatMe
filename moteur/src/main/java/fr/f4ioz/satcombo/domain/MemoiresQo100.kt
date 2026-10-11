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
 * Frequency shortcuts for the QO-100 narrowband transponder (no Doppler, fixed
 * dish: finding the right spot in 492 kHz is the whole job).
 *
 * Two kinds, and the distinction matters:
 *
 * - **Band plan markers**, derived from [Qo100.SEGMENTS]. Not editable, not
 *   deletable: they are facts published by AMSAT-DL, not preferences.
 * - **Operator memories**, placed anywhere (weekly sked, a net frequency...).
 */
object MemoiresQo100 {

    /** A shortcut. [cle] points to the translated label for markers. */
    data class Memoire(
        val cle: String,
        val hz: Long,
        /** True for a band plan marker, false for an operator memory. */
        val fixe: Boolean,
        /** Name given by the operator. Empty for a marker. */
        val nom: String = "",
    ) {
        /** Text shown on the key. */
        fun libelle(traduit: (String) -> String): String =
            if (fixe) traduit("qo100_mem_$cle") else nom
    }

    /**
     * Markers derived from the band plan.
     *
     * A segment with an explicit marker (beacons, broadcast, emergency) gives
     * that; the others give their **start**, where you enter the segment. The
     * middle of a segment means nothing about where the activity is.
     */
    fun reperes(): List<Memoire> = Qo100.SEGMENTS.mapNotNull { s ->
        when {
            s.repereHz != null -> Memoire(s.cle, s.repereHz, fixe = true)
            // Working segments: enter from the bottom, slightly inset from the edge.
            s.usage == Qo100.Usage.CW ||
                s.usage == Qo100.Usage.NUMERIQUE ||
                s.usage == Qo100.Usage.PHONIE ||
                s.usage == Qo100.Usage.MIXTE ->
                Memoire(s.cle, s.basHz + 5_000L, fixe = true)
            else -> null
        }
    }

    /** Markers then operator memories, each group sorted by frequency. */
    fun toutes(posees: List<Memoire>): List<Memoire> =
        reperes().sortedBy { it.hz } + posees.sortedBy { it.hz }

    /**
     * Stores a new memory, or replaces the one already in its place.
     *
     * **Two memories less than 1 kHz apart are the same one.** With 2.7 kHz
     * signals, shortcuts 300 Hz apart are indistinguishable in use and would
     * only clutter the list.
     */
    fun pose(posees: List<Memoire>, hz: Long, nom: String): List<Memoire> {
        val propre = nom.trim().ifBlank { qoLibelleAuto(hz) }
        val sans = posees.filterNot { kotlin.math.abs(it.hz - hz) < 1_000L }
        return (sans + Memoire(cle = "", hz = hz, fixe = false, nom = propre))
            .sortedBy { it.hz }
    }

    fun retire(posees: List<Memoire>, hz: Long): List<Memoire> =
        posees.filterNot { it.hz == hz }

    /** Default name when the operator gives none: ".688". */
    private fun qoLibelleAuto(hz: Long): String =
        "." + ((hz / 1_000L) % 1_000L).toString().padStart(3, '0')
}
