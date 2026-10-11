/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

/**
 * What the two numeric corners of the compass show.
 *
 * With a rotator connected, the corners used to show only the satellite: dashes
 * whenever it was below the horizon — exactly when you are adjusting the setup —
 * while the controller's position arrived every second and was shown nowhere.
 *
 * **The read-back position wins.** As soon as the mast reports where it points,
 * show that, visible pass or not, tracking or not. It is the only real number.
 *
 * **The label follows the source.** "155°" under `AZ` and under `AZ MÂT` mean
 * different things and look identical. When the controller goes quiet the label
 * reverts to `AZ`, so the switch back to the satellite is visible.
 *
 * Kept out of the composable so it can be unit-tested: a lying corner does not crash.
 */
object CoinsAim {

    /** A corner's label and the value under it. */
    data class Coin(val libelle: String, val valeur: String)

    /** Shown when there is nothing honest to display. */
    const val RIEN = "—"

    /**
     * @param mat        position read from the controller, or null if silent
     *                   (or if the mast lacks this axis: an azimuth-only rotator).
     * @param sat        computed satellite position.
     * @param satVisible false below the horizon — the value exists but is meaningless.
     */
    fun coin(mat: Double?, sat: Double?, satVisible: Boolean,
             libelleMat: String, libelleSat: String): Coin {
        if (mat != null && !mat.isNaN()) {
            return Coin(libelleMat, Math.round(mat).toString() + "°")
        }
        if (satVisible && sat != null && !sat.isNaN()) {
            return Coin(libelleSat, sat.toInt().toString() + "°")
        }
        return Coin(libelleSat, RIEN)
    }
}
