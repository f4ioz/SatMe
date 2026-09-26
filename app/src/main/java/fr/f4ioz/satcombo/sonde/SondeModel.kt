/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

/**
 * What sets one radiosonde model apart, from the receiver's point of view.
 *
 * Running every decoder blind behind a single 22 kHz filter was convenient but
 * wrong: the filter width must follow the sonde's deviation. An RS41 fits in
 * 15 kHz; opening 22 lets in half as much noise again and loses the ~2 dB that
 * separate a sonde decoded at 100 km from a lost one. Values here are those
 * auto_rx measured on the bench, model by model.
 *
 * Auto mode stays the default: wide filter, all decoders. Once the operator
 * knows the model (the frequency usually names the station, the station the
 * model), selecting it wins those decibels back.
 */
object SondeModel {

    /**
     * A reception profile.
     *
     * [baud] is the true bit rate, [chipRate] the rate the demodulator runs
     * at. They differ for the M10, whose biphase coding means counting half-bits.
     */
    data class Profile(
        /** Id stored in settings. */
        val id: String,
        /** Display name, not translated: these are proper names. */
        val label: String,
        /** Recommended FM filter width, Hz. */
        val bandwidthHz: Int,
        /** Payload bit rate, bit/s. 0 for auto mode. */
        val baud: Double,
        /** Demodulator rate, symbols/s. */
        val chipRate: Double,
        /** Biphase frame, two half-bits per bit? */
        val biphase: Boolean
    ) {
        /**
         * Samples per symbol at this sample rate.
         *
         * Below two, clock recovery has nothing to work with and decoding
         * becomes luck.
         */
        fun samplesPerChip(sampleRate: Int): Double =
            if (chipRate <= 0.0) 0.0 else sampleRate / chipRate

        /**
         * True when the sound card can't keep up with this model's rate.
         *
         * Threshold is two, not three. The M10 is the edge case: 9 616
         * half-bits/s gives 4.59 samples per symbol at 44.1 kHz (an older
         * version wrongly computed 2.29, off by a factor of two). auto_rx
         * decodes in production at 2.5 samples per symbol, so a threshold of
         * three would scare the operator off a perfectly usable setup.
         */
        fun marginal(sampleRate: Int): Boolean {
            val s = samplesPerChip(sampleRate)
            return s > 0.0 && s < 2.0
        }
    }

    /** Auto mode: wide filter, all decoders in parallel. */
    const val AUTO = "AUTO"

    val RS41 = Profile(
        id = "RS41", label = "Vaisala RS41",
        bandwidthHz = Rs41.BANDWIDTH_HZ,
        baud = Rs41.BAUD, chipRate = Rs41.BAUD, biphase = false)

    val M20 = Profile(
        id = "M20", label = "Meteomodem M20",
        bandwidthHz = Meteomodem.BANDWIDTH_HZ,
        baud = Meteomodem.M20_BAUD, chipRate = Meteomodem.M20_BAUD, biphase = false)

    val M10 = Profile(
        id = "M10", label = "Meteomodem M10",
        bandwidthHz = Meteomodem.BANDWIDTH_HZ,
        baud = Meteomodem.M10_BAUD, chipRate = Meteomodem.M10_CHIP_RATE,
        biphase = true)

    /** Auto-mode profile: the widest of the three. */
    val ANY = Profile(
        id = AUTO, label = "Auto",
        bandwidthHz = maxOf(Rs41.BANDWIDTH_HZ, Meteomodem.BANDWIDTH_HZ),
        baud = 0.0, chipRate = 0.0, biphase = false)

    /** Offered models, auto first. */
    val ALL = listOf(ANY, RS41, M20, M10)

    /** Profile with this id, or auto. */
    fun byId(id: String?): Profile = ALL.firstOrNull { it.id == id } ?: ANY

    /** Filter width to request from the SDR chain for this choice. */
    fun bandwidthFor(id: String?): Int = byId(id).bandwidthHz

    fun wantsRs41(id: String?): Boolean = id == null || id == AUTO || id == "RS41"

    fun wantsM20(id: String?): Boolean = id == null || id == AUTO || id == "M20"

    fun wantsM10(id: String?): Boolean = id == null || id == AUTO || id == "M10"

    /**
     * Models not decoded yet, listed so that someone decoding nothing can check
     * in seconds that their sonde simply isn't supported. South-east France
     * only launches RS41 and Meteomodem, but Germany launches Graw DFM and
     * Russia MRZ.
     */
    val NOT_YET = listOf(
        "Graw DFM-09/17", "Vaisala RS92", "Meisei iMS-100", "iMet-4", "LMS6", "MRZ-N1")
}
