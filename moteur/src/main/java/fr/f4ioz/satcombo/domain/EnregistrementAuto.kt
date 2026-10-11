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
 * Automatic recording while the rig is under CAT (option): every pass of
 * the satellite followed, from a little before AOS to a little after LOS —
 * the rig already tuned before AOS, its sound card giving the pass audio.
 */
object EnregistrementAuto {

    const val AVANT_MS = 5_000L
    const val APRES_MS = 5_000L
    /** Passes armed at once: about a day for a low satellite. */
    const val MAX = 16

    /** Recording windows for passes (AOS, LOS), those not over yet, soonest first. */
    fun fenetres(passages: List<Pair<Long, Long>>, maintenant: Long): List<Pair<Long, Long>> =
        passages.map { (aos, los) -> (aos - AVANT_MS) to (los + APRES_MS) }
            .filter { it.second > maintenant }
            .sortedBy { it.first }
            // Two overlapping windows (a pass cut in two by the search): one.
            .fold(ArrayList<Pair<Long, Long>>()) { l, w ->
                val d = l.lastOrNull()
                if (d != null && w.first <= d.second) l[l.size - 1] = d.first to maxOf(d.second, w.second) else l += w
                l
            }
            .take(MAX)

    /** What the 2-second listen of the input says, for arming. */
    enum class Audio { OK, SILENCE, SATURE, ABSENTE }

    fun juge(rmsDbfs: Double?, creteDbfs: Double?): Audio = when {
        rmsDbfs == null || creteDbfs == null -> Audio.ABSENTE
        rmsDbfs < StationCheck.SILENCE_DBFS -> Audio.SILENCE
        creteDbfs > StationCheck.SATURE_DBFS -> Audio.SATURE
        else -> Audio.OK
    }
}
