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
import kotlin.math.roundToInt

/**
 * Calibrating a receiver on a signal whose frequency is known.
 *
 * A dongle's crystal is off by a few to tens of ppm; an LNB's by a few ppm of
 * 9.75 GHz, i.e. tens of kHz. Tune to a reference, find its peak, and the
 * distance to where it should be is the error: in ppm of the frequency the
 * device was tuned to (that is where its crystal works), or in Hz of the LNB's
 * oscillator.
 *
 * **One reference cannot split two errors.** Behind an LNB, the offset seen is
 * the LNB's error plus the dongle's (at the IF). Calibrate the dongle first on
 * a terrestrial reference (a GSM channel, a local beacon), then the QO-100
 * beacon gives the LNB's alone.
 *
 * Pure: a spectrum in, numbers out; no device.
 */
object CalibrationFrequence {

    /** A known signal to calibrate on. */
    data class Reference(val cle: String, val nom: String, val hz: Long, val explication: String)

    /** QO-100's narrowband beacons, generated on the ground from a GPS-locked reference. */
    const val QO100_CW_BASSE_HZ = 10_489_500_000L
    const val QO100_PSK_HZ = 10_489_750_000L
    const val QO100_CW_HAUTE_HZ = 10_490_000_000L

    val REFERENCES: List<Reference> = listOf(
        Reference("qo100-cw-basse", "QO-100 · balise CW basse", QO100_CW_BASSE_HZ,
            "Porteuse et CW, calée sur un GPS au sol : la plus facile à pointer."),
        Reference("qo100-psk", "QO-100 · balise PSK (milieu)", QO100_PSK_HZ,
            "La balise du milieu, 400 bauds : deux lobes, SatMe pointe leur centre."),
        Reference("qo100-cw-haute", "QO-100 · balise CW haute", QO100_CW_HAUTE_HZ,
            "Une deuxième mesure 500 kHz plus haut : la même erreur confirme un simple décalage."),
    )

    /**
     * The FCCH of GSM: a pure tone 1625/24 kHz (67.708 kHz) above the
     * channel centre, sent by every base station. Its frequency is as good as
     * the operator's reference — a few hundredths of a ppm.
     */
    const val FCCH_DECALAGE_HZ = 67_708L

    /** GSM-900 / E-GSM downlink channel centre for [arfcn], Hz; null outside the band. */
    fun gsm900Hz(arfcn: Int): Long? = when (arfcn) {
        in 0..124 -> 935_000_000L + 200_000L * arfcn
        in 975..1023 -> 935_000_000L + 200_000L * (arfcn - 1024)
        else -> null
    }

    /** DCS-1800 downlink channel centre for [arfcn], Hz; null outside the band. */
    fun dcs1800Hz(arfcn: Int): Long? = if (arfcn in 512..885) 1_805_200_000L + 200_000L * (arfcn - 512) else null

    /** The FCCH tone of [arfcn] (GSM-900 first, then DCS-1800), Hz; null when no such channel. */
    fun fcchHz(arfcn: Int): Long? = (gsm900Hz(arfcn) ?: dcs1800Hz(arfcn))?.plus(FCCH_DECALAGE_HZ)

    // ------------------------------------------------------------ the peak

    /** A peak: where (Hz from the spectrum's centre), how high, how far above the noise. */
    data class Pic(val decalageHz: Double, val niveauDb: Double, val snrDb: Double)

    /**
     * The strongest bin within [fenetreHz] of [attenduHz] (both from the
     * spectrum's centre), refined by a parabola through it and its two
     * neighbours — a tenth of a bin, where a plain maximum is half a bin.
     *
     * [spectreDb]: one level per bin, bin `nb/2` at the centre, [parBinHz]
     * apart. Null when the window is outside the spectrum or flat.
     */
    fun pic(spectreDb: FloatArray, parBinHz: Double, attenduHz: Double, fenetreHz: Double): Pic? {
        val nb = spectreDb.size
        if (nb < 3 || parBinHz <= 0.0) return null
        val j0 = (Math.floor((attenduHz - fenetreHz) / parBinHz).toInt() + nb / 2).coerceAtLeast(1)
        val j1 = (Math.ceil((attenduHz + fenetreHz) / parBinHz).toInt() + nb / 2).coerceAtMost(nb - 2)
        if (j1 < j0) return null
        var m = j0
        for (j in j0..j1) if (spectreDb[j] > spectreDb[m]) m = j
        val a = spectreDb[m - 1].toDouble(); val b = spectreDb[m].toDouble(); val c = spectreDb[m + 1].toDouble()
        val den = a - 2 * b + c
        val d = if (den < 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        val fenetre = (j0..j1).map { spectreDb[it] }.sorted()
        val bruit = fenetre[fenetre.size / 2].toDouble()
        val snr = b - bruit
        if (snr <= 0.0) return null
        return Pic((m - nb / 2 + d) * parBinHz, b - 0.25 * (a - c) * d, snr)
    }

    /**
     * A two-lobed signal (the PSK beacon, 400 Bd): the middle of its two
     * strongest lobes, when a second one within [ecartMaxHz] of the first is
     * no more than [toleranceDb] below it; else the single peak.
     */
    fun picDouble(spectreDb: FloatArray, parBinHz: Double, attenduHz: Double, fenetreHz: Double,
                  ecartMinHz: Double = 150.0, ecartMaxHz: Double = 700.0, toleranceDb: Double = 6.0): Pic? {
        val p1 = pic(spectreDb, parBinHz, attenduHz, fenetreHz) ?: return null
        val masque = spectreDb.copyOf()
        val nb = spectreDb.size
        val c = p1.decalageHz / parBinHz + nb / 2
        val r = Math.ceil(ecartMinHz / parBinHz).toInt()
        for (j in (c.toInt() - r)..(c.toInt() + r + 1)) if (j in 0 until nb) masque[j] = -300f
        val cote = (ecartMaxHz + ecartMinHz) / 2
        val p2 = listOf(-1.0, 1.0).mapNotNull { sg -> pic(masque, parBinHz, p1.decalageHz + sg * cote, (ecartMaxHz - ecartMinHz) / 2) }
            .maxByOrNull { it.niveauDb } ?: return p1
        if (p2.niveauDb < p1.niveauDb - toleranceDb) return p1
        return Pic((p1.decalageHz + p2.decalageHz) / 2, maxOf(p1.niveauDb, p2.niveauDb), p1.snrDb)
    }

    /** Below this much above the median, a peak is noise: SatMe says so rather than guess. */
    const val SNR_MIN_DB = 6.0

    // ------------------------------------------------------------ the numbers

    /** [ecartHz] as parts per million of [frequenceHz]. */
    fun ppm(ecartHz: Double, frequenceHz: Double): Double = if (frequenceHz == 0.0) 0.0 else ecartHz / frequenceHz * 1e6

    /**
     * The dongle's correction after a measurement.
     *
     * A crystal fast by e ppm tunes e ppm high, so a signal shows e ppm *below*
     * where it is; the driver's correction is e. The offset is the one seen at
     * the dongle ([ecartFiHz], measured − expected in IF: the sky offset with
     * its sign flipped when the converter turns the spectrum over), and the
     * crystal works at [fiHz], the frequency it was tuned to. [actuel] is
     * already in what was measured, hence added.
     */
    fun ppmCle(actuel: Int, ecartFiHz: Double, fiHz: Double): Double = actuel - ppm(ecartFiHz, fiHz)

    /** The same, as the dongle takes it: whole ppm, within what the driver accepts. */
    fun ppmCleEntier(actuel: Int, ecartFiHz: Double, fiHz: Double): Int =
        ppmCle(actuel, ecartFiHz, fiHz).roundToInt().coerceIn(-PPM_CLE_MAX, PPM_CLE_MAX)

    /** The dongle's correction is taken within ±200 ppm (SatMe's setting). */
    const val PPM_CLE_MAX = 200

    /**
     * The converter's real oscillator, from a sky offset ([ecartCielHz],
     * shown − true) seen through its nominal [olNominalHz].
     *
     * Low side: shown = true + nominal − real; high side: the same once both
     * conversions are written out. So real = nominal − offset either way.
     */
    fun olReel(olNominalHz: Long, ecartCielHz: Double): Long = Math.round(olNominalHz - ecartCielHz)

    /** Beyond this, the "LO error" is a wrong preset or a wrong beacon, not drift: 1 MHz. */
    const val OL_ECART_MAX_HZ = 1_000_000L

    /** Credible LO correction (not a wrong reference or a wrong preset). */
    fun olCredible(olNominalHz: Long, olReelHz: Long): Boolean = abs(olReelHz - olNominalHz) <= OL_ECART_MAX_HZ
}
