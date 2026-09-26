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
 * Finding a contact in the recording.
 *
 * The only recovery that does not rely on the operator's memory: ten unnamed
 * entries after a busy pass cannot be recalled (you would get four right and
 * invent two, worse than nothing). With a recording, each timestamp has a
 * position and the station gives its own callsign there.
 *
 *  - **SatMe records.** Timestamp and file share the same clock: nothing to
 *    adjust.
 *  - **External recorder.** The anchor is missing: which UTC instant is
 *    second zero of the file? Recorder clocks drift and the file date is the
 *    stop time, not the start. But **a pass lasts twelve minutes**, and drift
 *    over that is well under a second, so one alignment point is enough.
 */
object Rattrapage {

    /** Margin before the timestamp, in seconds: you speak before tapping. */
    const val AVANT_S: Long = 12

    /** Margin after. */
    const val APRES_S: Long = 8

    /**
     * Position of a contact in a recording, in ms. Negative when the contact
     * precedes the start (recording started late): report it, do not clamp to
     * zero silently.
     */
    fun position(contactMs: Long, debutBandeMs: Long): Long = contactMs - debutBandeMs

    /** Does the contact fall inside the recording? */
    fun dansLaBande(contactMs: Long, debutBandeMs: Long, dureeMs: Long): Boolean {
        val p = position(contactMs, debutBandeMs)
        return p >= 0 && p <= dureeMs
    }

    /**
     * Recording start deduced from one alignment point: the operator scrubs to
     * a moment they recognise and picks the matching contact. Over such a short
     * span both time bases run at the same rate.
     */
    fun debutDeduit(contactMs: Long, positionDansFichierMs: Long): Long =
        contactMs - positionDansFichierMs

    /** Listening window around a contact, clamped to the file. */
    fun fenetre(positionMs: Long, dureeMs: Long): LongRange {
        val debut = (positionMs - AVANT_S * 1000).coerceAtLeast(0L)
        val fin = (positionMs + APRES_S * 1000).coerceAtMost(dureeMs.coerceAtLeast(0L))
        return debut..fin.coerceAtLeast(debut)
    }

    /**
     * Sanity check of a proposed alignment. An offset over a day means the
     * file is not from this pass; say so rather than let the operator listen
     * to silence thinking they missed their mark.
     */
    fun alignementVraisemblable(debutDeduitMs: Long, contactsMs: List<Long>): Boolean {
        if (contactsMs.isEmpty()) return false
        val premier = contactsMs.min()
        val dernier = contactsMs.max()
        return debutDeduitMs <= premier + 60_000L &&
            debutDeduitMs >= dernier - 86_400_000L
    }
}
