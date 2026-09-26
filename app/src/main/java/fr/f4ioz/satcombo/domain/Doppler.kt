/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/** Doppler-corrected frequencies (Look4Sat live-tuning style). */
object Doppler {
    private const val C = 299_792.458 // km/s

    /** La même constante, pour qui en a besoin ailleurs. */
    const val C_KM_S = C

    /**
     * Observed frequency given range-rate (km/s, + = receding).
     * Receiver sees a *lower* frequency when the satellite is moving away.
     */
    fun shifted(restHz: Long, rangeRateKmS: Double): Long {
        val factor = 1.0 - rangeRateKmS / C
        return Math.round(restHz * factor)
    }

    /** Downlink as heard at the ground station. */
    fun downlink(restHz: Long, rangeRateKmS: Double) = shifted(restHz, rangeRateKmS)

    /** Inverse of [downlink]: recover the REST freq from an observed downlink. */
    fun restFromDownlink(observedHz: Long, rangeRateKmS: Double): Long {
        val factor = 1.0 - rangeRateKmS / C
        return if (factor != 0.0) Math.round(observedHz / factor) else observedHz
    }

    /**
     * Uplink frequency you must transmit so the satellite receives [restHz].
     * Inverse correction relative to downlink.
     */
    fun uplink(restHz: Long, rangeRateKmS: Double): Long {
        val factor = 1.0 + rangeRateKmS / C
        return Math.round(restHz * factor)
    }

    /**
     * For a linear transponder, map a chosen DOWNLINK frequency (within the
     * downlink passband) to the matching UPLINK frequency to transmit,
     * honoring band inversion. Frequencies are REST (uncorrected).
     */
    fun transponderUplinkRest(
        rxRestHz: Long, dlLow: Long, dlHigh: Long, ulLow: Long, ulHigh: Long, invert: Boolean
    ): Long {
        val dlSpan = (dlHigh - dlLow).coerceAtLeast(1)
        val frac = ((rxRestHz - dlLow).toDouble() / dlSpan).coerceIn(0.0, 1.0)
        val ul = if (invert) ulHigh - (ulHigh - ulLow) * frac
                 else ulLow + (ulHigh - ulLow) * frac
        return Math.round(ul)
    }

    fun formatMHz(hz: Long): String = "%.6f MHz".format(hz / 1_000_000.0)

    /**
     * Same figure without the trailing zeros — "436,795 MHz" instead of
     * "436,795000 MHz". Channel frequencies are round numbers, and the six
     * decimals only matter while chasing Doppler: in lists they were long
     * enough to burst the card on a narrow screen.
     */
    fun formatMHzShort(hz: Long): String {
        val s = "%.6f".format(hz / 1_000_000.0)
        val sep = if (s.contains(',')) ',' else '.'
        return s.trimEnd('0').trimEnd(sep) + " MHz"
    }
}
