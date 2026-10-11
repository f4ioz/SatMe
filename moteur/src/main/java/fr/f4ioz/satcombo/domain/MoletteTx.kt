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
 * The transmit dial treated as an offset knob.
 *
 * This calculation was wrong twice, shipped twice, and only caught by the
 * operator at the radio. It used to live inside the CAT loop, untestable; it
 * is isolated here so tests can break it.
 *
 * **The trap, and it is not obvious.** The uplink is recomputed every tick with
 * the current Doppler, while the radio stays on the last value actually sent
 * (the 20 Hz threshold deliberately spaces writes). Comparing the radio reading
 * with the fresh uplink therefore measures **the Doppler drift since the last
 * write**, not the operator's gesture. Absorbing it changes the uplink, which
 * triggers a write, which zeroes the gap, which lets Doppler build up again —
 * and the offset oscillates forever between two values.
 *
 * The only honest comparison is with **the last setpoint written**. Doppler is
 * already in it; what remains is what the hand did.
 */
object MoletteTx {

    /**
     * Below this, a gap is the radio's rounding, not a gesture.
     *
     * Same value as the CAT loop write threshold, on purpose: a gap not worth
     * writing cannot be worth absorbing.
     */
    const val SEUIL_HZ: Long = 20L

    /** What to do after reading the transmit radio. */
    class Decision(
        /** Offset to keep. */
        val shiftHz: Long,
        /** Reference setpoint for the next comparison. */
        val referenceHz: Long,
        /** Was the gesture consumed? */
        val gesteConsomme: Boolean,
        /** Did the offset change? */
        val absorbe: Boolean,
    )

    /**
     * @param shiftHz current offset.
     * @param referenceHz last uplink actually sent to the radio. Zero means
     *   nothing written yet, so nothing to compare.
     * @param lueHz uplink read back from the radio, in the satellite frame.
     * @param gesteVu has the arbiter seen movement since the last absorption?
     * @param moletteTranquille is the arbiter handing control back?
     */
    fun decide(
        shiftHz: Long,
        referenceHz: Long,
        lueHz: Long,
        gesteVu: Boolean,
        moletteTranquille: Boolean,
        seuilHz: Long = SEUIL_HZ,
    ): Decision {
        // While the dial turns, the reading is a passing position, not an intent.
        if (!moletteTranquille) return Decision(shiftHz, referenceHz, false, false)

        // Without a gesture, a gap can only be rounding or our own write.
        // Absorbing it would make the offset drift by itself every tick.
        if (!gesteVu) return Decision(shiftHz, referenceHz, false, false)

        // Nothing written yet: no reference, no measurement possible.
        if (referenceHz == 0L) return Decision(shiftHz, referenceHz, true, false)

        val ecart = lueHz - referenceHz
        if (abs(ecart) < seuilHz) {
            // Too small to count, but still consumed: leaving it pending would
            // absorb it later, when the gap may have changed sign.
            return Decision(shiftHz, referenceHz, true, false)
        }

        // The reference follows the radio. Otherwise the same gap would be
        // absorbed again next tick and the offset would double each time.
        return Decision(shiftHz + ecart, lueHz, true, true)
    }
}
