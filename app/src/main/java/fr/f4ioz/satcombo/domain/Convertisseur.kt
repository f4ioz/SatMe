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
 * The link between the satellite frequency and the radio frequency.
 *
 * Satellite frequency equals radio dial frequency only from 2 m to 23 cm. On
 * QO-100 the downlink is at 10 489 MHz and the uplink at 2400 MHz: between
 * antenna and radio sits a box that shifts everything — an LNB on receive, a
 * transverter on transmit. That box is just a local oscillator and a
 * direction.
 *
 * **Low-side injection** (by far the usual case): LO below the band,
 * subtract. A 9750 MHz LNB brings the QO-100 middle beacon, 10 489.750, down
 * to 739.750 MHz. A 2400 transverter driven at 432 has a 1968 LO: feed it
 * 432.050, it transmits on 2400.050. Same subtraction both ways, as long as
 * it is always written satellite → radio; hence the method names, which leave
 * no choice.
 *
 * **High-side injection**: LO above the band, spectrum inverted. Conversion
 * is `LO − f`, its own inverse. Rare in amateur gear, but forgetting it would
 * give an upside-down band that is impossible to diagnose.
 *
 * ### Why a range, not just an LO
 *
 * A converter only exists on its band. The dish LNB does not see 145 MHz, and
 * applying its subtraction would give a negative frequency. Without bounds, a
 * converter left enabled would break every other satellite — tick QO-100 on
 * Sunday, and the ISS fails on Tuesday with no message. With bounds the
 * converter drops out of the chain outside its band, which also lets an LNB
 * and a transverter stay configured permanently: their ranges do not overlap.
 *
 * ### What it does not do
 *
 * No Android, radio or dongle knowledge: testable on the bench. It does not
 * correct LNB drift (that is the per-satellite calibration offset, set on the
 * beacon). And Doppler applies to the satellite frequency, before this, never
 * after.
 */
data class Convertisseur(
    /** Enabled or not. Disabled, it is transparent. */
    val actif: Boolean = false,
    /** Local oscillator, Hz. Zero means "no converter". */
    val olHz: Long = 0L,
    /** High-side injection: spectrum inverted, `radio = LO − satellite`. */
    val inverseur: Boolean = false,
    /** Lower bound of the covered band, satellite side. 0 = no bound. */
    val basHz: Long = 0L,
    /** Upper bound of the covered band, satellite side. 0 = no bound. */
    val hautHz: Long = 0L,
) {

    /** Usable: enabled, with a plausible LO. */
    val configure: Boolean get() = actif && olHz > 0L

    /** Does this converter apply to this satellite frequency? Outside its range it is not in the chain. */
    fun couvre(satHz: Long): Boolean {
        if (!configure) return false
        if (basHz > 0L && satHz < basHz) return false
        if (hautHz > 0L && satHz > hautHz) return false
        return versPosteBrut(satHz) > 0L
    }

    /**
     * Satellite → radio: what to set on the radio, or write to the dongle PLL,
     * to be on [satHz] in the sky.
     *
     * Out of range, the frequency passes through unchanged, on purpose: a
     * converter left enabled must not stop you working the ISS.
     */
    fun versPoste(satHz: Long): Long =
        if (couvre(satHz)) versPosteBrut(satHz) else satHz

    /**
     * Radio → satellite (see [versSatellite]): what the radio display means in
     * the sky. Used on read-back; without it a turn of the knob would read as
     * a 9750 MHz jump.
     */
    /**
     * Can this frequency be an IF **of this converter**?
     *
     * Checking only the output is not enough: a LEO downlink at 145.95 MHz,
     * converted back through a 10 344.973 LO, gives 10 490.9 MHz — right inside
     * the QO-100 bounds, so a LEO screen showed gigahertz. Checking the input
     * as well closes this class of bug for every caller: a frequency is an IF
     * only if it falls in the window this converter actually produces.
     */
    fun accepteEnEntree(posteHz: Long): Boolean {
        if (!configure) return false
        if (basHz <= 0L || hautHz <= 0L) return true
        val fiBasse = versPosteBrut(if (inverseur) hautHz else basHz)
        val fiHaute = versPosteBrut(if (inverseur) basHz else hautHz)
        return posteHz in minOf(fiBasse, fiHaute)..maxOf(fiBasse, fiHaute)
    }

    fun versSatellite(posteHz: Long): Long {
        if (!configure) return posteHz
        // Input first: a frequency that is not one of this converter's IFs
        // does not belong to it, whatever the arithmetic says.
        if (!accepteEnEntree(posteHz)) return posteHz
        val sat = versSatelliteBrut(posteHz)
        return if (couvre(sat)) sat else posteHz
    }

    private fun versPosteBrut(satHz: Long): Long =
        if (inverseur) olHz - satHz else satHz - olHz

    private fun versSatelliteBrut(posteHz: Long): Long =
        if (inverseur) olHz - posteHz else posteHz + olHz

    companion object {
        /** None: the direct chain, used by every other satellite. */
        val AUCUN = Convertisseur()

        /** Upper amateur Ku band, home of the QO-100 downlink. */
        private const val KU_BAS = 10_400_000_000L
        private const val KU_HAUT = 10_800_000_000L

        /** 13 cm, home of the QO-100 uplink. */
        private const val S_BAS = 2_390_000_000L
        private const val S_HAUT = 2_450_000_000L

        /**
         * The common setups.
         *
         * Starting points only: the LO stays editable, since everyone builds
         * their own.
         *
         * Two presets put the downlink in a band an amateur radio really
         * receives: `lnb10057` outputs 432.250, `down145` outputs 144.777 and
         * puts the whole narrowband transponder between 144.532 and 145.024 —
         * inside 2 m with margin. The latter pairs with an upconverter driven
         * at 432: receive on 145, transmit on 432, a cross-band pair any
         * satellite rig handles.
         */
        val PRESETS: List<Preset> = listOf(
            Preset("lnb9750", 9_750_000_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("lnb10000", 10_000_000_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("lnb10057", 10_057_500_000L, KU_BAS, KU_HAUT, descente = true),
            // **Measured, not theoretical.** 10 344.972 94 MHz measured on
            // F4IOZ's Bullseye + DX Patrol chain on 3 September 2026, against
            // the IS0GRB WebSDR (GPSDO-locked, so an absolute reference). Two
            // separate readings, 10 Hz apart.
            //
            // The offset from nominal is not an error to correct: it is the
            // LNB's TCXO, about 2.6 ppm, within spec, and **constant from one
            // power-up to the next** (0.85 kHz spread over two hours with full
            // power-off between tests).
            //
            // A GPSDO will not fix it: it does not touch the LNB's internal
            // oscillator. Do not expect the calibration to return to zero
            // because the rest of the chain is locked.
            Preset("down145", 10_344_973_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("tvtr432", 1_968_000_000L, S_BAS, S_HAUT, descente = false),
            Preset("tvtr144", 2_256_000_000L, S_BAS, S_HAUT, descente = false),
        )

        /** A ready-made setup. [cle] looks up the translated label. */
        data class Preset(
            val cle: String,
            val olHz: Long,
            val basHz: Long,
            val hautHz: Long,
            /** True for an LNB (receive), false for a transverter (transmit). */
            val descente: Boolean,
        ) {
            fun vers(): Convertisseur = Convertisseur(
                actif = true, olHz = olHz, inverseur = false,
                basHz = basHz, hautHz = hautHz)
        }

        /**
         * Reference used to show a preset's IF while choosing ("LNB 9750 →
         * 739.750 MHz"). Seeing a familiar number is the only check possible
         * before anything is connected.
         */
        const val BALISE_MEDIANE_HZ = 10_489_750_000L

        /**
         * Upper CW beacon, also generated on the ground.
         *
         * A **second calibration point, 250 kHz from the first**. If both give
         * the same LO, the error is a constant offset one number fixes. If they
         * differ, it is a slope — the oscillator is not just offset, it is
         * wrong — and no single offset will fit both ends of the transponder.
         */

        const val BALISE_HAUTE_HZ = 10_490_000_000L
    }
}
