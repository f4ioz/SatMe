/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter

/**
 * ISS SSTV on its own: each pass recorded (and so decoded) from 10 s before
 * AOS to 5 s after LOS, pass after pass, up to the one the operator chose.
 */
object SstvIss {

    const val AVANCE_MS = 10_000L
    const val APRES_MS = 5_000L

    /**
     * The recording windows for [passes] (AOS, LOS) not over yet at
     * [maintenant], up to the pass starting at [dernierAos] included.
     */
    fun fenetres(passes: List<Pair<Long, Long>>, maintenant: Long, dernierAos: Long): List<Pair<Long, Long>> =
        passes.filter { it.first <= dernierAos && it.second + APRES_MS > maintenant }
            .sortedBy { it.first }
            .map { (it.first - AVANCE_MS) to (it.second + APRES_MS) }

    /** The SSTV transmitter among [liste]: named SSTV, else the 145.800 MHz downlink. −1 if none. */
    fun indexSstv(liste: List<Transmitter>): Int {
        val i = liste.indexOfFirst {
            it.mode.orEmpty().uppercase().contains("SSTV") || it.description.uppercase().contains("SSTV")
        }
        if (i >= 0) return i
        return liste.indexOfFirst { it.downlinkLowHz?.let { hz -> hz in 145_795_000L..145_805_000L } == true }
    }
}
