/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs

/**
 * Who absorbs the Doppler: the PLL or the software.
 *
 * **Retuning the R820T PLL is not silent.** The tuner relocks and for a few
 * tens of milliseconds the output is a transient, not signal. Retuning once a
 * second over a ten-minute pass means six hundred glitches: visible on SSTV
 * images, lost radiosonde frames, and a regular click by ear that gets blamed
 * on the satellite.
 *
 * Instead, the baseband IQ is multiplied by a complex exponential before the
 * channel filter, which shifts the listening frequency without touching the
 * hardware. This object only holds the decision (no Android, bench-testable).
 *
 * Three numbers, with different meanings: [FINE_LIMIT_HZ] is where we give in,
 * move the PLL and recentre; [FINE_MAX_HZ] is the physical limit of the
 * digitised channel; [DEADBAND_HZ] is below which we do nothing, since
 * correcting 3 Hz costs more than ignoring it.
 */
object DopplerTuner {

    /** Beyond this, retune the PLL and restart from a zero offset. */
    const val FINE_LIMIT_HZ = 30_000L

    /** Physical limit: beyond this we would leave the digitised channel. */
    const val FINE_MAX_HZ = 80_000L

    /** Below this, change nothing. */
    const val DEADBAND_HZ = 10L

    /** Where to put the PLL, which fine offset to apply, and whether to retune. */
    data class Plan(
        /** Frequency for the tuner (unchanged when [retune] is false). */
        val pllHz: Long,
        /** Software offset applied to the IQ, in Hz. */
        val fineHz: Long,
        /** True only when the PLL must actually be reprogrammed. */
        val retune: Boolean
    )

    /**
     * @param wantHz frequency to listen to, Doppler included.
     * @param pllHz frequency currently set on the tuner; zero or less means
     *   "never tuned" and forces a first programming.
     * @param fineHz current software offset.
     */
    fun plan(wantHz: Long, pllHz: Long, fineHz: Long): Plan {
        if (wantHz <= 0L) return Plan(pllHz, fineHz, false)
        if (pllHz <= 0L) return Plan(wantHz, 0L, true)

        val delta = wantHz - pllHz
        // Recentre on the wanted frequency itself, not the rest frequency, so
        // the rest of the pass has the full margin ahead of it.
        if (abs(delta) > FINE_LIMIT_HZ) return Plan(wantHz, 0L, true)

        // Inside the deadband, return the current offset unchanged: the caller
        // compares and writes nothing.
        if (abs(delta - fineHz) < DEADBAND_HZ) return Plan(pllHz, fineHz, false)
        return Plan(pllHz, clampFine(delta), false)
    }

    /** The offset never leaves the digitised channel. */
    fun clampFine(hz: Long): Long = hz.coerceIn(-FINE_MAX_HZ, FINE_MAX_HZ)

    /** Does an offset of this size fit in the channel? */
    fun fits(hz: Long): Boolean = abs(hz) <= FINE_MAX_HZ
}
