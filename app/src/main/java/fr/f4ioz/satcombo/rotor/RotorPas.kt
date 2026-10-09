/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import kotlin.math.abs

/**
 * When to move the mast, and where to — so that the controller's relays click
 * as seldom as the beam allows.
 *
 * Every command starts a motor and stops it: two clicks. Three things kept
 * them coming every few seconds:
 *
 * - **Small steps.** The mast went exactly onto the satellite and was behind
 *   again at once. Now it goes **ahead** of it by the margin along its path:
 *   the satellite catches up, passes, and only beyond the margin on the other
 *   side does the mast move again — twice as far apart.
 * - **Both axes each time.** A GS-232 command carries azimuth and elevation;
 *   the axis that did not need to move got a value a few tenths away and its
 *   relay could click for nothing. Now it is given its own position back.
 * - **No pause.** At least [INTERVALLE_MS] between two commands, unless the
 *   miss grows past twice the margin (a turn, a new pass).
 */
object RotorPas {

    /** Least time between two commands, ms. */
    const val INTERVALLE_MS = 6_000L

    /** How far ahead the mast is allowed to go, seconds. */
    const val AVANCE_MAX_S = 30.0

    /** The command for one axis: to [vise] plus a lead in its direction of travel, or null (stays). */
    fun axe(vise: Double, actuel: Double, vitesseDegS: Double, marge: Double): Double? {
        if (abs(vise - actuel) < marge) return null
        // Ahead by the margin when the target moves steadily; not when it stands still
        // or goes the other way than the mast must (it would run past and come back).
        val sens = Math.signum(vise - actuel)
        val avance = if (abs(vitesseDegS) > 0.01 && Math.signum(vitesseDegS) == sens)
            (marge * 0.9).coerceAtMost(abs(vitesseDegS) * AVANCE_MAX_S) * sens else 0.0
        return vise + avance
    }

    /**
     * The command (unwrapped azimuth, elevation), or null when nothing needs
     * to move. [vitesseAz]/[vitesseEl]: how fast the aim moves, °/s.
     * [dernierEnvoiMs]: when the last command left.
     */
    fun commande(
        vise: RotorPos, actuel: RotorPos, vitesseAz: Double, vitesseEl: Double, marge: Double,
        limits: RotorMath.Limits, maintenantMs: Long, dernierEnvoiMs: Long, azSeul: Boolean = false
    ): RotorPos? {
        val ecartAz = abs(vise.azDeg - actuel.azDeg)
        val ecartEl = if (azSeul) 0.0 else abs(vise.elDeg - actuel.elDeg)
        if (ecartAz < marge && ecartEl < marge) return null
        val urgent = ecartAz >= 2 * marge || ecartEl >= 2 * marge
        if (!urgent && maintenantMs - dernierEnvoiMs < INTERVALLE_MS) return null
        val az = axe(vise.azDeg, actuel.azDeg, vitesseAz, marge)
            ?.let { RotorMath.clampAz(it, limits) } ?: actuel.azDeg
        val el = if (azSeul) vise.elDeg
            else axe(vise.elDeg, actuel.elDeg, vitesseEl, marge)?.coerceIn(0.0, limits.elMaxDeg) ?: actuel.elDeg
        return RotorPos(az, el)
    }
}
