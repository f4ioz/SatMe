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
 * Each contact sent on to the online log by itself — an option.
 *
 * **Not at once: after [DELAI_MS].** Wavelog's API creates contacts but cannot
 * change them; a typo that left is fixed by hand on the server. The minute is
 * the time to see it and hold the contact back (paused), fix it, release it.
 * Changing a waiting contact starts its minute again.
 *
 * Only contacts logged **after the option was turned on** leave: an older
 * log may already be in Wavelog through an ADIF import, and Wavelog does not
 * deduplicate.
 */
object EnvoiAuto {

    const val DELAI_MS = 60_000L

    enum class Etat {
        /** Not concerned: option off, logged before it, or no callsign. */
        HORS,
        /** Waiting for its minute. */
        ATTENTE,
        /** Its minute is over: to send. */
        PRET,
        /** Held back by the operator (or refused by the server). */
        PAUSE,
        /** In the online log. */
        ENVOYE,
    }

    /**
     * State of one contact. [actifDepuisMs] is when the option was turned
     * on, null while it is off; [modifieMs] the last change, if any.
     */
    fun etat(
        timeMs: Long, callsign: String, envoyeMs: Long, retenu: Boolean,
        actifDepuisMs: Long?, modifieMs: Long?, maintenantMs: Long,
    ): Etat = when {
        envoyeMs > 0 -> Etat.ENVOYE
        callsign.isBlank() || actifDepuisMs == null || timeMs < actifDepuisMs -> Etat.HORS
        retenu -> Etat.PAUSE
        maintenantMs - depart(timeMs, modifieMs) < DELAI_MS -> Etat.ATTENTE
        else -> Etat.PRET
    }

    /** Seconds left before a waiting contact leaves. */
    fun resteS(timeMs: Long, modifieMs: Long?, maintenantMs: Long): Long =
        ((DELAI_MS - (maintenantMs - depart(timeMs, modifieMs))) / 1000).coerceAtLeast(0)

    private fun depart(timeMs: Long, modifieMs: Long?) = maxOf(timeMs, modifieMs ?: 0L)
}
