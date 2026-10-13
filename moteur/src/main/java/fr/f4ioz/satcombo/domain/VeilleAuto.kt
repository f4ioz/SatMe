/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import kotlin.math.abs

/**
 * The automatic mode, so no pass is missed: the satellites chosen, their
 * passes (some set aside by hand), each followed in turn — the rig and the
 * mast with it — from a few minutes before AOS, and recorded from a little
 * before AOS to a little after LOS. Two passes at once: the earlier goes on,
 * the later is lost (set the earlier aside to keep the later).
 */
object VeilleAuto {

    /** Followed from this long before AOS: the mast's time to go and wait. */
    const val AVANT_SUIVI_MS = 5 * 60_000L
    const val AVANT_SON_MS = EnregistrementAuto.AVANT_MS
    const val APRES_MS = 30_000L
    /** A pass set aside is the same pass again when its AOS moved less than this (new elements). */
    const val TOLERANCE_MS = 5 * 60_000L

    class Ligne(val passage: SatPass, val garde: Boolean, val ecarte: Boolean, val perdu: String? = null)

    /** A pass's key, to set it aside: "catnum:AOS ms". */
    fun cle(p: SatPass): String = "${p.catalogNumber}:${p.aosEpochMs}"

    fun ecarte(p: SatPass, ecartes: Set<String>): Boolean = ecartes.any { k ->
        val n = k.substringBefore(':').toIntOrNull(); val t = k.substringAfter(':').toLongOrNull()
        n == p.catalogNumber && t != null && abs(t - p.aosEpochMs) <= TOLERANCE_MS
    }

    /** The keys still worth keeping (passes not over). */
    fun purge(ecartes: Set<String>, maintenant: Long): Set<String> =
        ecartes.filter { (it.substringAfter(':').toLongOrNull() ?: 0L) > maintenant - 24 * 3_600_000L }.toSet()

    /**
     * The plan: the chosen satellites' passes not over, from [elMin] up,
     * soonest first; those set aside, and those a kept pass overlaps (lost,
     * with the name of the one that goes on).
     */
    fun plan(passages: List<SatPass>, sats: Set<Int>, elMin: Double, ecartes: Set<String>, maintenant: Long): List<Ligne> {
        var fin = Long.MIN_VALUE
        var nom = ""
        return passages.filter { it.catalogNumber in sats && it.maxElevationDeg >= elMin && it.losEpochMs + APRES_MS > maintenant }
            .sortedBy { it.aosEpochMs }
            .map { p ->
                when {
                    ecarte(p, ecartes) -> Ligne(p, garde = false, ecarte = true)
                    p.aosEpochMs < fin -> Ligne(p, garde = false, ecarte = false, perdu = nom)
                    else -> { fin = p.losEpochMs + APRES_MS; nom = p.satName; Ligne(p, garde = true, ecarte = false) }
                }
            }
    }

    /** The kept pass to follow now: from [AVANT_SUIVI_MS] before AOS to [APRES_MS] after LOS. */
    fun aSuivre(plan: List<Ligne>, maintenant: Long): SatPass? = plan.firstOrNull {
        it.garde && maintenant >= it.passage.aosEpochMs - AVANT_SUIVI_MS && maintenant <= it.passage.losEpochMs + APRES_MS
    }?.passage

    /** The kept pass to record now: from [AVANT_SON_MS] before AOS to [APRES_MS] after LOS. */
    fun aEnregistrer(plan: List<Ligne>, maintenant: Long): SatPass? = plan.firstOrNull {
        it.garde && maintenant >= it.passage.aosEpochMs - AVANT_SON_MS && maintenant <= it.passage.losEpochMs + APRES_MS
    }?.passage
}
