/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/**
 * The picture frozen while the stream still flows (locked, packets coming):
 * a decoder lost in a damaged stream, which a good receiver does not leave
 * as it is. Its players are started again, at most once every [pauseMs].
 */
class GardeImage(val figeMs: Long = 6_000L, val pauseMs: Long = 20_000L) {
    private var derniereRelanceMs = Long.MIN_VALUE / 2

    /** True when the players should be started again now. */
    fun tic(t: Long, verrouille: Boolean, kbps: Int, derniereImageMs: Long, lecteursDepuisMs: Long?): Boolean {
        if (!verrouille || kbps < KBPS_MIN || lecteursDepuisMs == null) return false
        if (t - maxOf(derniereImageMs, lecteursDepuisMs) < figeMs) return false
        if (t - derniereRelanceMs < pauseMs) return false
        derniereRelanceMs = t
        relances++
        return true
    }

    /** Times the players were started again. */
    var relances = 0
        private set

    companion object {
        /** Below this, no stream to speak of (a lock without packets yet). */
        const val KBPS_MIN = 50
    }
}

/**
 * LongMynd gone by itself in the middle of a reception (the USB link
 * hiccuped, the MiniTiouner reset): started again after a pause growing
 * with each try ([pausesMs]), as long as it had run [tenuMs] at least the
 * last time; not when it said it cannot reach the MiniTiouner (unplugged,
 * no access: starting again would not help).
 */
class RelanceLongMynd(private val pausesMs: List<Long> = listOf(3_000L, 10_000L, 30_000L), private val tenuMs: Long = 15_000L) {
    private var essais = 0

    /** The pause before starting again, or null to stay stopped, for a run of [dureeMs] ended with [pourquoi]. */
    fun apres(dureeMs: Long, pourquoi: String): Long? {
        if (definitif(pourquoi)) return null
        if (dureeMs >= tenuMs) essais = 0
        if (essais >= pausesMs.size) return null
        return pausesMs[essais++]
    }

    /** A start asked by the operator: the count starts again. */
    fun raz() { essais = 0 }

    companion object {
        /** Words of a stop that starting again would not cure (see DatvPc.pourquoi). */
        fun definitif(pourquoi: String): Boolean =
            pourquoi.contains("introuvable", true) || pourquoi.contains("pas d'accès", true) || pourquoi.contains("udev", true)
    }
}
