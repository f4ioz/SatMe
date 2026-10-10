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
 * Should the uplink be written while the operator turns the receive dial?
 *
 * **The answer depends on the rig.**
 *
 * On a pair of FT-817s the two rigs are independent: nobody retunes the
 * uplink for us, and leaving it behind would transmit off the station just
 * found. We must write.
 *
 * An IC-9700 **in satellite mode** (and an IC-910H or IC-9100, tracking on) does reverse tracking itself: moving SUB
 * moves MAIN the other way. Writing the uplink while the operator turns then
 * makes a loop — he tunes down, we write a higher uplink, the rig raises its
 * downlink, he tunes down again. Seen on RS-44 and FO-29: the sum of both
 * VFOs stayed exactly constant, proof that the rig, not SatMe, held the pair.
 * "The operator does not touch the transmit side" is false on a dual-VFO rig
 * driven by one knob: touching one moves the other.
 *
 * **No setting for this.** A switch would only move the trap onto the
 * operator, who cannot guess that the rig and the app fight over one VFO.
 */
object SuiviMontee {

    /** Rigs that hold the uplink/downlink pair themselves in satellite mode. */
    private val SUIVI_INTERNE = setOf("IC9700", "IC910", "IC9100")

    /** True when this rig retunes the uplink by itself as the downlink moves. */
    fun posteSuitSeul(rigModel: String): Boolean = rigModel in SUIVI_INTERNE

    /**
     * Minimum offset worth a write, in hertz.
     *
     * Finer when the uplink must follow fast: otherwise you would hear the
     * station without being able to answer on the same spot.
     */
    fun seuilHz(txSuitVite: Boolean): Long = if (txSuitVite) 20L else 50L

    /**
     * @param operateurTourne true while the arbiter sees the dial move, i.e.
     *   until receive is handed back to automatic tracking.
     */
    fun doitEcrire(
        rigModel: String,
        operateurTourne: Boolean,
        txSuitVite: Boolean,
        maintienDoppler: Boolean,
        ecartHz: Long
    ): Boolean {
        // Hold comes first: it is an explicit request to write nothing, and
        // nothing may bypass it.
        if (maintienDoppler) return false
        // The rig handles it: staying silent is the only way not to fight it.
        if (operateurTourne && posteSuitSeul(rigModel)) return false
        if (ecartHz < seuilHz(txSuitVite)) return false
        // Otherwise the uplink follows fast if asked to, and waits for the
        // resume delay if not.
        return txSuitVite || !operateurTourne
    }
}
