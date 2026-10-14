/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.SkedAlert
import kotlin.math.abs

/** What belongs to one pass: the skeds announced on hams.at for it, the contacts made during it. */
object SkedsPassage {

    /**
     * The skeds announced for this pass, soonest first: the same satellite,
     * the announced window (the common one when hams.at gives it) centred
     * within 50 min of the pass's middle — the phone's matching.
     */
    fun duPassage(skeds: List<SkedAlert>, catnum: Int, aosMs: Long, losMs: Long): List<SkedAlert> {
        val milieu = (aosMs + losMs) / 2
        return skeds.filter { s ->
            if (s.satNorad != catnum) return@filter false
            val de = s.workableStartMs ?: s.aosMs
            val a = s.workableEndMs ?: s.losMs
            abs((de + a) / 2 - milieu) < 50 * 60_000L
        }.sortedBy { it.workableStartMs ?: it.aosMs }
    }

    /** A little before AOS (calling early) to well after LOS (logged once the satellite has set). */
    const val AVANT_MS = 5 * 60_000L
    const val APRES_MS = 15 * 60_000L

    /** The contacts made on this pass, in time order: this satellite (by number, else by name), within the pass. */
    fun contacts(carnet: List<LogEntry>, catnum: Int, nom: String, aosMs: Long, losMs: Long): List<LogEntry> {
        fun n(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val k = n(nom)
        return carnet.filter { e ->
            (if (e.catnum > 0) e.catnum == catnum else k.isNotEmpty() && n(e.satName) == k) &&
                e.timeMs >= aosMs - AVANT_MS && e.timeMs <= losMs + APRES_MS
        }.sortedBy { it.timeMs }
    }
}
