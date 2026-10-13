/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

/**
 * When the automatic METEOR reception may take the station (the dongle and
 * the satellite followed) for a pass — the same on the phone and SatMe-PC:
 * once a pass at most (a dongle that does not start is not asked again
 * every few seconds), and never from under the operator working another
 * satellite now (FO-29 taken away mid-pass, 13/10/2026).
 */
object MeteorAuto {

    /**
     * The METEOR pass starting at [aosMs] may be taken: not tried yet
     * ([essayes]), and the satellite followed is a METEOR, or none, or
     * below the horizon ([suiviElDeg]).
     */
    fun peutPrendre(aosMs: Long, essayes: Set<Long>, suiviEstMeteor: Boolean, suiviElDeg: Double?): Boolean =
        aosMs !in essayes && (suiviEstMeteor || suiviElDeg == null || suiviElDeg < 0.0)
}
