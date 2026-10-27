/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.EtatDonnees
import fr.f4ioz.satcombo.cat.PosteCat

/**
 * The rig's data mode (USB-D…) while FT8/FT4 wants it, and the flags it had
 * put back after — whoever drives the frequencies (the Doppler loop, QO-100,
 * the operator's own dial).
 *
 * Only the data flag moves, never the mode: USB stays USB, LSB stays LSB,
 * with -D added. The Doppler loop carries the same wish in its own mode
 * writes ([MondeCat.donnees]), so neither undoes the other.
 *
 * Called from the CAT loop's coroutine, between two Doppler rounds: the band
 * selections (07 D0/D1) never interleave with the loop's. **Nothing while
 * keyed**: the switch waits for the PTT to be released, both ways.
 */
class BasculeDonnees(private val horloge: () -> Long = System::currentTimeMillis) {

    /** The data flags read before switching, null when not switched. */
    @Volatile var avant: EtatDonnees? = null; private set
    /** The data modes are on, set by SatMe. */
    @Volatile var actif: Boolean = false; private set
    /** When the rig last failed to answer (ms): tried again 2 s later, not every round. */
    private var echecMs = Long.MIN_VALUE / 2

    /**
     * One look: switches on when [voulu] and not yet done, puts back when no
     * longer [voulu]. Never while [emission].
     */
    suspend fun tour(poste: PosteCat, voulu: Boolean, emission: Boolean) {
        if (emission) return
        if (voulu && !actif) {
            if (!poste.donneesPossibles) return
            val t = horloge()
            if (t - echecMs < 2_000L) return
            val lu = runCatching { poste.posDonnees(true) }.getOrNull()
            if (lu == null) { echecMs = t; return }
            avant = lu; actif = true
        } else if (!voulu && actif) {
            val a = avant
            actif = false; avant = null
            if (a != null) runCatching { poste.remetDonnees(a) }
        }
    }

    /** The rig is gone: nothing to put back any more. */
    fun oublie() { actif = false; avant = null; echecMs = Long.MIN_VALUE / 2 }

    /** May FT8/FT4 key now? Yes unless the data modes are wanted, possible, and not yet on. */
    fun pret(poste: PosteCat, voulu: Boolean): Boolean = !voulu || actif || !poste.donneesPossibles
}
