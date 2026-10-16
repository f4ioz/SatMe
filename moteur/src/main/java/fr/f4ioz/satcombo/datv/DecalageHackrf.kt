/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import java.util.Locale
import kotlin.math.roundToLong

/**
 * The HackRF's oscillator tuned beside the signal: its leakage (a carrier
 * at the LO, the DC offset of its DAC) falls outside the occupied band
 * instead of at its centre. The baseband is shifted by the same amount
 * ([fr.f4ioz.satcombo.sdr.Melangeur]), so the signal stays on the wanted
 * frequency; the band rules still apply to that signal.
 *
 * The shift: 0.6 × the occupied band, [MIN_HZ] at least. The sample rate
 * raised (more samples per symbol) until the shifted signal, plus a guard,
 * fits under Nyquist. Indoors, the LO and the I/Q image (mirrored around
 * the LO) stay inside the same amateur band as the signal: below the
 * signal when it fits, else above.
 */
object DecalageHackrf {
    const val MIN_HZ = 500_000L
    const val FACTEUR = 0.6
    /** Room between the shifted signal's edge and Nyquist, as a share of the occupied band. */
    const val GARDE = 0.25
    /** The HackRF's highest sample rate. */
    const val DEBIT_MAX = 20_000_000L

    data class Plan(
        /** Where the HackRF is tuned (Hz). */
        val loHz: Long,
        /** The signal's centre less the LO (Hz): the baseband shift. */
        val decalageHz: Long,
        val sps: Int,
        /** Samples per second. */
        val debit: Long,
        /** What the baseband filter must pass (Hz): the LO to the far edge, both sides. */
        val bandeHz: Long,
    )

    fun occupeHz(srKs: Int, alpha: Double): Double = PlanQo100.occupeKhz(srKs, alpha) * 1000.0

    /** The shift's size (Hz, whole kHz). */
    fun decalageHz(srKs: Int, alpha: Double): Long = maxOf(MIN_HZ, (FACTEUR * occupeHz(srKs, alpha) / 1000.0).roundToLong() * 1000L)

    /** Samples per symbol: the transmitter's usual ones, more until the shifted signal fits. */
    fun sps(srKs: Int, alpha: Double, decalageHz: Long): Int {
        val occ = occupeHz(srKs, alpha)
        var k = ReglesEmission.sps(srKs)
        while (srKs * 1000.0 * k / 2 < decalageHz + occ / 2 + GARDE * occ) k++
        return k
    }

    /** The plan for a signal centred on [khz], or null with [refus] saying why. */
    fun plan(khz: Long, srKs: Int, alpha: Double, interieur: Boolean): Plan? = calcule(khz, srKs, alpha, interieur).first

    fun refus(khz: Long, srKs: Int, alpha: Double, interieur: Boolean): String? = calcule(khz, srKs, alpha, interieur).second

    private fun calcule(khz: Long, srKs: Int, alpha: Double, interieur: Boolean): Pair<Plan?, String?> {
        val occ = occupeHz(srKs, alpha)
        val d = decalageHz(srKs, alpha)
        val k = sps(srKs, alpha, d)
        val debit = srKs * 1000L * k
        if (debit > DEBIT_MAX) return null to "décalage de l'oscillateur : %d kS/s demanderait %.1f Méch/s, au-delà du HackRF".format(Locale.FRANCE, srKs, debit / 1e6)
        val f = khz * 1000L
        val bande = (2 * (d + occ / 2)).roundToLong()
        for (signe in listOf(-1L, 1L)) {
            val lo = f + signe * d
            if (lo !in 1_000_000L..6_000_000_000L) continue
            if (interieur) {
                // The signal, the LO and the image (mirrored around the LO) all inside one band.
                val image = lo + signe * d
                val bas = minOf(f, image) - occ / 2
                val haut = maxOf(f, image) + occ / 2
                if (PlanInterieur.BANDES.none { bas >= it.basKhz * 1000.0 && haut <= it.hautKhz * 1000.0 }) continue
            }
            return Plan(lo, f - lo, k, debit, bande) to null
        }
        return null to "décalage de l'oscillateur : l'oscillateur ou l'image du signal sortirait de la bande amateur ; " +
            "s'éloigner du bord de la bande ou décocher « Oscillateur décalé »"
    }
}
