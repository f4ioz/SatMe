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
 * What goes to the online log, and what must not go again.
 *
 * Wavelog, like Cloudlog, does not deduplicate: re-sending the whole log
 * would create one duplicate per contact per attempt, to be cleaned by hand.
 *
 * So the mark is **local and set afterwards**: a contact counts as uploaded
 * only once the server said it took it. Network failure, rejected key, cut
 * mid-batch — none of these mark, and the next attempt resumes.
 *
 * Oldest first: if the upload stops midway, what went is a continuous block,
 * not a log with holes.
 */
object EnvoiCarnet {

    /** A contact, reduced to what the rule looks at. */
    data class Fiche(
        val timeMs: Long,
        val indicatif: String,
        val envoyeMs: Long,
    )

    /** Outcome of an upload. */
    data class Bilan(
        val deposes: Int,
        val refuses: Int,
        val restants: Int,
    )

    /**
     * Contacts not yet uploaded, oldest first.
     *
     * A contact without a callsign never goes, same rule as the ADIF export:
     * the receiving log would reject or misfile a record with no CALL.
     */
    fun aDeposer(journal: List<Fiche>): List<Fiche> =
        journal
            .filter { it.indicatif.isNotBlank() && it.envoyeMs <= 0L }
            .sortedBy { it.timeMs }

    /** How many contacts are waiting, for the button label. */
    fun combienAttendent(journal: List<Fiche>): Int = aDeposer(journal).size

    /**
     * Outcome of an upload. [acceptes] holds the timestamps the server took.
     * [Bilan.restants] is everything not accepted, rejected ones included:
     * they go again on the next attempt.
     */
    fun bilan(journal: List<Fiche>, acceptes: Set<Long>, refuses: Int): Bilan {
        val attendaient = aDeposer(journal)
        val deposes = attendaient.count { it.timeMs in acceptes }
        return Bilan(
            deposes = deposes,
            refuses = refuses,
            restants = attendaient.size - deposes)
    }
}
