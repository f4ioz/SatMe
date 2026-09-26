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
 * QO-100 converter chains and how to calibrate them. These settings belong to
 * QO-100 only (no other satellite needs converters), so they are not in a
 * general menu.
 *
 * **Calibration uses two frequencies read, not an oscillator typed.** Nobody
 * knows their chain's LO; everyone can read a WebSDR and their own radio. The
 * subtraction is the machine's job.
 *
 * **A station is a whole, and there are several.** Home and portable setups
 * have different LNBs, hence different LOs. Mixing them up puts every contact
 * off-frequency after a setup change — and looks like drift.
 */
object StationsQo100 {

    /**
     * A complete, named chain.
     *
     * [descenteOlHz] and [monteeOlHz] are zero when the stage does not exist
     * (SDR dongle behind the LNB: no upconverter; direct 10 GHz: no
     * downconverter).
     */
    data class Station(
        val nom: String,
        val descenteOlHz: Long = 0L,
        val monteeOlHz: Long = 0L,
        /** The measurement (sky and radio), kept so it can be reviewed. */
        val mesureCielHz: Long = 0L,
        val mesurePosteHz: Long = 0L,
    ) {
        val descenteReglee: Boolean get() = descenteOlHz > 0L
        val monteeReglee: Boolean get() = monteeOlHz > 0L

        /** Downlink IF for a given sky frequency. */
        fun posteRx(cielHz: Long): Long =
            if (descenteReglee) cielHz - descenteOlHz else cielHz

        /** Uplink IF for a given sky frequency. */
        fun posteTx(cielHz: Long): Long =
            if (monteeReglee) cielHz - monteeOlHz else cielHz
    }

    /** Result of a calibration: the oscillator, or why it was refused. */
    sealed class Etalonnage {
        data class Trouve(val olHz: Long) : Etalonnage()
        /** [motif] is a translation key, not a sentence. */
        data class Refuse(val motif: String) : Etalonnage()
    }

    /** Credible bounds for a downlink oscillator, in Hz. */
    private const val OL_MIN = 100_000_000L
    private const val OL_MAX = 12_000_000_000L

    /**
     * Local oscillator from two frequencies read: `LO = sky − radio`.
     *
     * The guards matter. **Swapping the two fields is the natural mistake**
     * (you read your radio first, it is in front of you). A negative difference
     * gives it away at once; saying so beats storing an absurd LO that would
     * only show on the first failed listen.
     */
    fun etalonne(cielHz: Long, posteHz: Long): Etalonnage {
        if (cielHz <= 0L || posteHz <= 0L) return Etalonnage.Refuse("qo100_cal_vide")
        val ol = cielHz - posteHz
        if (ol <= 0L) return Etalonnage.Refuse("qo100_cal_inverse")
        if (ol < OL_MIN || ol > OL_MAX) return Etalonnage.Refuse("qo100_cal_absurde")
        return Etalonnage.Trouve(ol)
    }

    /**
     * Measured LO minus nominal, in Hz: this tells whether the chain is sane.
     * A few tens of kHz on a TV LNB is normal (its TCXO; a GPSDO does not help,
     * it does not drive the LNB). A few MHz means something else: wrong LNB,
     * wrong band, or swapped fields.
     */
    fun ecartAuNominal(olHz: Long, nominalHz: Long): Long = olHz - nominalHz

    /** The same offset in ppm of the oscillator. */
    fun ecartPpm(olHz: Long, nominalHz: Long): Double =
        if (nominalHz <= 0L) 0.0
        else (olHz - nominalHz) * 1_000_000.0 / nominalHz

    /**
     * Default setups, with no oscillator: an uncalibrated station must say so,
     * not offer a nominal value that looks right and is not.
     */
    fun parDefaut(): List<Station> = listOf(
        Station(nom = "fixe"),
        Station(nom = "portable"),
    )

    /**
     * Replaces a station in the list without reordering: reshuffling under the
     * operator's eyes after a calibration would be confusing.
     */
    fun remplace(liste: List<Station>, index: Int, station: Station): List<Station> =
        if (index !in liste.indices) liste
        else liste.toMutableList().also { it[index] = station }
}
