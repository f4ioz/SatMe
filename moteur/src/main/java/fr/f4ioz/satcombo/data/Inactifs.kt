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
 * Satellites with nothing left on the air, from the SatNOGS transmitter list.
 *
 * SatNOGS no longer says "dead" of a satellite: it only knows "in orbit",
 * "re-entered" or "future". What it does keep up to date is whether each
 * transmitter is alive. A satellite whose transmitters are **all** dead is
 * silent (AO-85, AO-92); one with a single live beacon is not.
 *
 * **Unknown is not dead.** A satellite missing from the list is not judged,
 * so a new launch not yet in SatNOGS is never hidden.
 */
object Inactifs {

    /** Catalogue numbers whose every listed transmitter is dead. */
    fun calcule(emetteurs: Sequence<Pair<Int, Boolean>>): Set<Int> {
        val vivant = HashMap<Int, Boolean>()
        for ((norad, alive) in emetteurs) vivant[norad] = (vivant[norad] ?: false) || alive
        return vivant.filterValues { !it }.keys
    }

    /** A week: a satellite does not die or come back every day. */
    const val VALIDITE_MS = 7L * 24 * 3600 * 1000

    /** Cache text: first line the fetch time, then one catalogue number per line. */
    fun versTexte(quandMs: Long, ids: Set<Int>): String =
        buildString {
            append(quandMs).append('\n')
            ids.sorted().forEach { append(it).append('\n') }
        }

    /** Reads [versTexte]'s output; null when it is not one. */
    fun depuisTexte(txt: String): Pair<Long, Set<Int>>? {
        val lignes = txt.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val quand = lignes.firstOrNull()?.toLongOrNull() ?: return null
        val ids = lignes.drop(1).map { it.toIntOrNull() ?: return null }.toSet()
        return quand to ids
    }
}
