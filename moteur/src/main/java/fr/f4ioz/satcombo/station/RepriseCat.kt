/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.GardeLiaisonCat

/**
 * The CAT link kept alive around the Doppler loop: before each round,
 * [GardeLiaisonCat] says whether the round runs, a read checks the rig, the
 * port is opened again — and once the rig answers again, [repris] sets it
 * up again.
 *
 * **Receive side only.** The read is a frequency read; reopening is the
 * connection's own opening (same port, same lines); [repris] writes
 * frequencies and modes. Nothing here keys the transmitter, nor replays a
 * transmit state: SatMe's PTT ([EmissionPoste]) keys only when the operator
 * holds the button, never after a reconnection.
 */
class RepriseCat(
    val garde: GardeLiaisonCat,
    /** When the rig last answered anything (refusals included), 0: never. */
    private val reponduMs: () -> Long,
    /** One read of the rig; true when it answered (whatever it said). */
    private val sonde: suspend () -> Boolean,
    /** False when the rig's port is gone, null when that cannot be told (a network link). */
    private val portPresent: () -> Boolean? = { null },
    /** Closes and opens the same port again; true when opened. Null: not reopened here (the network session reconnects by itself). */
    private val rouvre: (suspend () -> Boolean)? = null,
    /** The rig is back: set it up again (receive side and modes only). */
    private val repris: suspend () -> Unit = {},
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** How many reads and reopenings were tried while silent (the tests count them). */
    @Volatile var sondes = 0; private set
    @Volatile var reouvertures = 0; private set

    /** Before a round: true when the round may run (the rig answers), false to skip it. */
    suspend fun avantTour(): Boolean {
        val t = horloge()
        val present = runCatching { portPresent() }.getOrNull()
        return when (garde.decide(t, reponduMs(), present, rouvre != null)) {
            GardeLiaisonCat.Geste.TOUR -> true
            GardeLiaisonCat.Geste.ATTENDS -> false
            GardeLiaisonCat.Geste.SONDE -> essaie(rouvrir = false)
            GardeLiaisonCat.Geste.ROUVRE -> essaie(rouvrir = true)
        }
    }

    private suspend fun essaie(rouvrir: Boolean): Boolean {
        val r = rouvre
        if (rouvrir && r != null) {
            reouvertures++
            if (!runCatching { r() }.getOrDefault(false)) { garde.resultat(horloge(), false); return false }
        }
        sondes++
        val ok = runCatching { sonde() }.getOrDefault(false)
        val revient = garde.resultat(horloge(), ok)
        if (revient) runCatching { repris() }
        return ok
    }
}
