/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * A rig behind a guard: a frequency it may not be sent ([permis] false) is
 * dropped, the other leg still written.
 *
 * The Doppler loop writes many places; the guard sits where every write
 * passes. Without it, a QO-100 downlink with no converter line in front of
 * the rig went out as 10 489 MHz, ten times a second, into a silent NAK.
 */
class PosteCatFiltre(private val p: PosteCat, private val permis: (Long) -> Boolean) : PosteCat by p {

    override suspend fun ecrisDescente(hz: Long) { if (permis(hz)) p.ecrisDescente(hz) }

    override suspend fun ecrisMontee(montee: Long, memeBande: Boolean) { if (permis(montee)) p.ecrisMontee(montee, memeBande) }

    override suspend fun ecrisCouple(descente: Long, montee: Long, memeBande: Boolean) {
        val d = permis(descente); val m = permis(montee)
        when {
            d && m -> p.ecrisCouple(descente, montee, memeBande)
            d -> p.ecrisDescente(descente)
            m -> p.ecrisMontee(montee, memeBande)
        }
    }
}
