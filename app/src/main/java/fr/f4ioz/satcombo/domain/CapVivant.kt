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
 * The effective antenna orientation, whatever its source.
 *
 * **Why this holder.** The choice between the Bluetooth module and the phone
 * compass is made inside a composable, out of the ViewModel's sight. The demo
 * page used to read the module directly, and showed nothing whenever the
 * operator used the phone — most of the time. The composable publishes its
 * choice here; anyone who needs the heading reads it here.
 *
 * Volatile rather than a flow: written every frame, read once a second. A
 * reactive flow would only wake collectors for nothing.
 */
object CapVivant {
    @Volatile var azimutDeg: Float? = null
    @Volatile var elevationDeg: Float? = null
    /** True when the remote module supplies the heading, false for the phone. */
    @Volatile var depuisModule: Boolean = false

    fun pose(az: Float?, el: Float?, module: Boolean) {
        azimutDeg = az; elevationDeg = el; depuisModule = module
    }

    fun oublie() { azimutDeg = null; elevationDeg = null; depuisModule = false }
}
