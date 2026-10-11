/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

/**
 * What the screen shows when the controller skips a reply.
 *
 * On an Arduino emulator driving two motors, the `C2` reply is sometimes late
 * or missing. A single missed reply used to wipe everything — mast position,
 * `AZ MÂT` / `ÉL MÂT` labels, the "aim: rotor" line — and the compass flipped
 * to the satellite and back a second later. So the last reading is held for
 * [DEFAUT_MS]. Two rules:
 *
 * **The hold is short.** A G-5500 turns 6°/s: after four seconds a held
 * position can be 24° off. Fine for a display, not for pointing. The hold
 * serves the screen, never the command: [rotorTick] keeps driving the mast
 * from the computed satellite position, not from a stale reading.
 *
 * **The hold ends.** After the delay, control goes back to the satellite,
 * labels included. An unplugged controller must be visible: holding forever
 * would show a number long after it stopped being true.
 */
object RotorTenue {

    /** How long an unconfirmed position is kept. */
    const val DEFAUT_MS = 4000L

    /**
     * @param lue        the position just read, or null if the controller was
     *                   silent this round.
     * @param derniere   the last real reading, or null if there never was one.
     * @param dateMs     time of that last reading.
     * @param maintenant current time.
     * @param tenueMs    hold duration.
     * @return the fresh reading, the previous one while still young, or null
     *         to hand control back.
     */
    fun montrer(
        lue: RotorPos?,
        derniere: RotorPos?,
        dateMs: Long,
        maintenant: Long,
        tenueMs: Long = DEFAUT_MS
    ): RotorPos? {
        if (lue != null) return lue
        if (derniere == null) return null
        val age = maintenant - dateMs
        // A clock going backwards (time change, reboot) must not extend the
        // hold forever.
        if (age < 0L || age > tenueMs) return null
        return derniere
    }
}
