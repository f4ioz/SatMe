/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/** One SatNOGS transmitter/transceiver/transponder entry. */
data class Transmitter(
    val description: String,
    val mode: String?,
    val uplinkLowHz: Long?,
    val uplinkHighHz: Long?,
    val downlinkLowHz: Long?,
    val downlinkHighHz: Long?,
    val invert: Boolean,
    val alive: Boolean,
    val type: String,
    /** Symbol rate of a data transmitter (SatNOGS' « baud »), when known. */
    val baud: Double? = null,
) {
    /** A linear/inverting transponder spans a passband (high != low). */
    val isTransponder: Boolean
        get() = (downlinkHighHz != null && downlinkLowHz != null && downlinkHighHz != downlinkLowHz)

    /**
     * Is the mode worth showing for this transmitter?
     *
     * SatNOGS stores **one mode per entry**. Right for a beacon (BPSK, CW),
     * meaningless for a linear transponder, which relays whatever the operator
     * chooses: the field holds whatever the first contributor typed. On QO-100
     * the narrowband segments are labelled "FM" although reserved for SSB and
     * CW — a wrong instruction, not an imprecision.
     *
     * We do not correct SatNOGS data; we just do not show it where it means
     * nothing.
     */
    val modeSignifiant: Boolean get() = !isTransponder
}
