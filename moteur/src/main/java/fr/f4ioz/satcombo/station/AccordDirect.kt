/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import java.util.concurrent.atomic.AtomicLong

/**
 * The knob on the rig's own dial when nothing else drives it (terrestrial
 * SSB, no satellite tracked, the Doppler on hold or below the horizon): the
 * steps gathered as they come, then one read and one write by the CAT
 * loop — a fast spin is one write, never a queue of stale ones. Works the
 * same while the rig transmits: tuning keys nothing.
 */
class AccordDirect {
    private val enAttente = AtomicLong()

    fun ajoute(dHz: Long) { if (dHz != 0L) enAttente.addAndGet(dHz) }

    val attente: Long get() = enAttente.get()

    /** What waits applied: the dial read ([lis]), moved, written ([ecris]); the new frequency, or null (nothing waiting, nothing read). */
    suspend fun applique(lis: suspend () -> Long?, ecris: suspend (Long) -> Unit): Long? {
        val d = enAttente.getAndSet(0L)
        if (d == 0L) return null
        val f = lis() ?: return null
        val n = f + d
        if (n <= 0L) return null
        ecris(n)
        return n
    }
}
