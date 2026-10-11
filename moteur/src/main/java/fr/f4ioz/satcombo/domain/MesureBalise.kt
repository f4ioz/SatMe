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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a beacon in a spectrum and says how far it has moved. The most useful
 * measurement of the QO-100 station, in two numbers.
 *
 * **The offset** is the downconverter LO drift and nothing else: the middle
 * beacon is clock-disciplined on the ground, its sky frequency is right. So
 * any error comes from the LNB, which typically starts tens of kHz off at
 * power-up and settles in half an hour. Without this you look for contacts in
 * the wrong place and think the transponder is empty.
 *
 * **The ratio to the noise floor** is pointing quality. The beacon is
 * constant-level: if the figure rises when you move the dish half a degree,
 * that half degree was good. The only way to fine-tune pointing alone.
 *
 * This object knows nothing about where the spectrum comes from, converters or
 * dongles: it takes dB values and a scale in sky frequencies, so it can be
 * tested on a hand-made spectrum — which is what you want from something whose
 * output corrects a calibration.
 */
object MesureBalise {

    /**
     * What was found where the beacon should be. All frequencies are sky
     * frequencies: the caller has already undone the conversions.
     */
    data class Mesure(
        /**
         * Measured minus expected, in Hz. Positive when the beacon is heard too
         * high, i.e. the converter LO is too low.
         */
        val ecartHz: Double,
        /** Peak of the line, dBFS. */
        val niveauDb: Float,
        /** Surrounding noise, dBFS: the window median. */
        val plancherDb: Float,
    ) {
        /**
         * Height above noise, in dB. The figure to watch while turning the
         * dish; the absolute level also moves with dongle gain and means
         * nothing alone.
         */
        val rapportDb: Float get() = niveauDb - plancherDb

        /** Offset rounded to the Hz, for writing into the calibration. */
        val ecartArrondiHz: Long get() = Math.round(ecartHz)
    }

    /**
     * The middle beacon, measured in [magDb].
     *
     * @param magDb power per bin, dBFS, lowest to highest frequency (spectrum
     *   already centred).
     * @param centreHz sky frequency at the middle of the array (bin
     *   `magDb.size / 2`).
     * @param etendueHz sky width covered by the whole array. **Signed**:
     *   negative behind a high-side injection converter, which flips the
     *   spectrum. That is how an inverting setup gets in without this object
     *   knowing about converters.
     * @param cibleHz where the beacon should be, in the sky.
     * @param fenetreHz search half-width. 20 kHz by default: cold drift of an
     *   ordinary TV LNB, no more — wider would catch the neighbouring SSB
     *   station instead of the beacon.
     * @param seuilDb how far the line must rise above the floor to count. Below
     *   it, return `null`: a noise measurement written into the calibration
     *   does more harm than no measurement.
     *
     * Returns `null` when the window falls outside the array, is too narrow to
     * interpolate, or nothing rises above the noise.
     */
    fun mesurer(
        magDb: FloatArray,
        centreHz: Double,
        etendueHz: Double,
        cibleHz: Double = Qo100.BALISE_MEDIANE_HZ.toDouble(),
        fenetreHz: Double = 20_000.0,
        seuilDb: Float = 6f,
    ): Mesure? {
        val n = magDb.size
        if (n < 8 || etendueHz == 0.0 || fenetreHz <= 0.0) return null

        val hzParRaie = etendueHz / n
        fun raie(f: Double): Double = n / 2.0 + (f - centreHz) / hzParRaie

        // Ordered in frequency, not necessarily in index: behind an inverting
        // converter the array runs backwards.
        val a = raie(cibleHz - fenetreHz)
        val b = raie(cibleHz + fenetreHz)

        // One bin of margin on each side: the parabolic fit reads both
        // neighbours of the peak.
        val bas = max(1.0, ceil(min(a, b))).toInt()
        val haut = min((n - 2).toDouble(), floor(max(a, b))).toInt()
        if (haut - bas < 4) return null

        var sommet = bas
        var valeur = magDb[bas]
        for (i in bas..haut) {
            if (magDb[i] > valeur) { valeur = magDb[i]; sommet = i }
        }

        // Median, not mean: a mean gets pulled up by the beacon itself and by
        // any passing station; the median does not, as long as the signal
        // fills less than half the window (a beacon of a few hundred Hz in
        // 20 kHz never does).
        val copie = magDb.copyOfRange(bas, haut + 1)
        copie.sort()
        val plancher = copie[copie.size / 2]
        if (valeur - plancher < seuilDb) return null

        // Parabolic interpolation on the three peak bins. Without it the
        // measurement is quantised to the FFT step (65 Hz on the panorama),
        // the same order as the warm-LNB drift we want to follow.
        val gauche = magDb[sommet - 1]
        val milieu = magDb[sommet]
        val droite = magDb[sommet + 1]
        val den = gauche - 2f * milieu + droite
        val d = if (den >= -1e-6f) 0.0 else (0.5 * (gauche - droite) / den)
        val fin = sommet + d.coerceIn(-0.5, 0.5)

        val mesuree = centreHz + (fin - n / 2.0) * hzParRaie
        return Mesure(
            ecartHz = mesuree - cibleHz,
            niveauDb = milieu,
            plancherDb = plancher,
        )
    }

    /**
     * Is the alignment clean enough to leave alone? 100 Hz at 10 GHz is
     * 0.01 ppm — better than an ordinary crystal LNB holds, and about the
     * width of a CW note. Below it nobody hears a difference.
     */
    fun calageSuffisant(m: Mesure?, toleranceHz: Long = 100L): Boolean =
        m != null && abs(m.ecartHz) < toleranceHz
}
