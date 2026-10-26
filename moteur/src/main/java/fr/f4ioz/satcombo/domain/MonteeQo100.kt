/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Transmitting on QO-100's narrow band: the modes, where each may go in the
 * AMSAT-DL band plan, and the frequencies of the two uplink chains:
 *
 * - **the rig and an up-converter** (an IC-9700 on 432 MHz into a DX
 *   Patrol Up MK4…): the rig's TX dial is the uplink minus the chain's
 *   measured oscillator ([ChaineQo100.Chaine.posteTx]);
 * - **the ADALM-PLUTO straight on 2.4 GHz**: its LO parked *outside* the
 *   transponder ([PlanPluto]), the signal moved there digitally, the board's
 *   crystal error taken out ([CorrectionPluto]).
 *
 * Everything here is in sky frequencies and pure: no radio, no settings.
 * The dial frequency is always the **suppressed carrier**, as on a rig in
 * USB: a CW carrier sits [TON_CW_HZ] above it, so the same dial heard in USB
 * on the downlink gives the same tone.
 */
object MonteeQo100 {

    /** What is sent. */
    enum class Mode(val cle: String, val libelle: String, val usages: Set<Qo100.Usage>) {
        /** Voice, upper sideband. */
        BLU("blu", "BLU (USB)", setOf(Qo100.Usage.PHONIE, Qo100.Usage.MIXTE)),
        /** A keyed tone in USB: a clean carrier [TON_CW_HZ] above the dial. */
        CW("cw", "CW", setOf(Qo100.Usage.CW, Qo100.Usage.MIXTE)),
        /** FT8, PSK… a sound card's audio in USB. */
        NUM("num", "Numérique (FT8…) en USB", setOf(Qo100.Usage.NUMERIQUE, Qo100.Usage.MIXTE));

        companion object {
            fun de(cle: String?): Mode? = entries.firstOrNull { it.cle == cle?.trim()?.lowercase() }
        }
    }

    /** Which chain carries the uplink. */
    enum class Chaine(val cle: String, val libelle: String) {
        POSTE("poste", "IC-9700 + convertisseur montant"),
        PLUTO("pluto", "ADALM-PLUTO en direct (2,4 GHz)");

        companion object {
            fun de(cle: String?): Chaine? = entries.firstOrNull { it.cle == cle?.trim()?.lowercase() }
        }
    }

    /** The CW tone above the dial (Hz): the carrier's place, and the tone heard in USB on the same dial. */
    const val TON_CW_HZ = 700.0

    /** The SSB passband (Hz above the dial). */
    const val BLU_BAS_HZ = 300L
    const val BLU_HAUT_HZ = 2_800L

    /** Half the room a keyed CW carrier is given (its clicks, the drift). */
    const val CW_DEMI_HZ = 100L

    /** The band occupied for a dial (suppressed carrier) frequency, uplink Hz, low and high edges. */
    fun occupe(mode: Mode, cadranHz: Long): LongRange = when (mode) {
        Mode.CW -> (cadranHz + TON_CW_HZ.toLong() - CW_DEMI_HZ)..(cadranHz + TON_CW_HZ.toLong() + CW_DEMI_HZ)
        else -> (cadranHz + BLU_BAS_HZ)..(cadranHz + BLU_HAUT_HZ)
    }

    /** The uplink dial that goes with a downlink dial: the two tied, no inversion (one kHz up is one kHz up). */
    fun cadranLie(descenteHz: Long): Long = Qo100.monteeDepuisDescente(descenteHz)

    /**
     * Why [mode] may not be sent on the uplink dial [cadranHz], or null.
     *
     * The whole occupied band ([occupe]) must lie in the narrow-band
     * transponder's uplink, and every band-plan segment it touches must
     * allow the mode ([Mode.usages]): never a beacon, the broadcast or the
     * emergency frequency, never voice in the digital segments nor FT8 in
     * the voice ones.
     */
    fun refus(mode: Mode, cadranHz: Long): String? {
        val o = occupe(mode, cadranHz)
        if (o.first < Qo100.NB.monteeBasHz || o.last > Qo100.NB.monteeHautHz)
            return "hors du transpondeur à bande étroite de QO-100 (montée %s–%s MHz) : le signal entier (%s) doit y tenir"
                .format(Locale.FRANCE, mhz(Qo100.NB.monteeBasHz), mhz(Qo100.NB.monteeHautHz), plage(o.first, o.last))
        val d1 = Qo100.descenteDepuisMontee(o.first)
        val d2 = Qo100.descenteDepuisMontee(o.last)
        if (d2 > Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ) return "au-dessus de 10 489,990 MHz : la balise multimédia, jamais d'émission"
        val touches = Qo100.SEGMENTS.filter { s -> d2 >= s.basHz && d1 < s.hautHz }
        for (s in touches) {
            when {
                s.usage == Qo100.Usage.BALISE -> return "sur une balise (" + nomSegment(s) + ") : jamais d'émission"
                s.usage == Qo100.Usage.URGENCE -> return "fréquence d'urgence (descente 10 489,858–865) : à laisser libre"
                s.usage == Qo100.Usage.DIFFUSION -> return "fréquence de diffusion (descente 10 489,850–858) : réservée aux bulletins"
                s.usage !in mode.usages -> return mode.libelle + " : pas dans le segment « " + nomSegment(s) + " » (descente " + plage(s.basHz, s.hautHz) + ")"
            }
        }
        return null
    }

    /** What to know before sending there, or null. */
    fun avertissement(mode: Mode, cadranHz: Long): String? {
        val d = Qo100.descenteDepuisMontee(cadranHz)
        return when {
            mode == Mode.BLU && d in Qo100.CONCOURS -> "segment mixte : les concours y sont admis"
            abs(Qo100.descenteDepuisMontee(occupe(mode, cadranHz).last) - Qo100.BALISE_MEDIANE_HZ) < 10_000L ||
                abs(Qo100.descenteDepuisMontee(occupe(mode, cadranHz).first) - Qo100.BALISE_MEDIANE_HZ) < 10_000L ->
                "à moins de 10 kHz de la balise médiane : vérifier que rien ne déborde dessus"
            else -> null
        }
    }

    /** A starting dial for [mode] (uplink Hz), inside its segments, away from the beacons. */
    fun cadranParDefaut(mode: Mode): Long = when (mode) {
        // Downlink 10 489.800 (upper SSB segment).
        Mode.BLU -> 2_400_300_000L
        // Downlink 10 489.525: carrier at .5257.
        Mode.CW -> 2_400_025_000L
        // Downlink 10 489.540: the FT8 dial used on QO-100.
        Mode.NUM -> 2_400_040_000L
    }

    // ------------------------------------------------------------ the rig and its up-converter

    /**
     * The rig's TX dial for [cadranHz] through [chaine]: the uplink minus the
     * measured oscillator (no converter: the uplink itself, which no amateur
     * rig reaches). Null when the chain has no uplink oscillator.
     */
    fun cadranPoste(chaine: ChaineQo100.Chaine, cadranHz: Long): Long? =
        if (chaine.monteeActive) chaine.posteTx(cadranHz) else null

    /**
     * The up-converter's real oscillator from our own signal heard on the
     * downlink: the rig sent on [posteHz] (its dial, a CW carrier: dial +
     * tone), it was heard on [entenduDescenteHz] (sky, the beacon calibration
     * already applied). The uplink really reached is the downlink minus the
     * 8 089.5 MHz; minus the rig's frequency, the oscillator.
     */
    fun olMonteeMesure(entenduDescenteHz: Long, posteHz: Long): Long = Qo100.monteeDepuisDescente(entenduDescenteHz) - posteHz

    // ------------------------------------------------------------ the Pluto

    /**
     * The Pluto's frequency error as an offset at 2 400 MHz: **how many Hz
     * too high it transmits** there (its crystal: tens of ppm, 1 ppm being
     * 2.4 kHz on QO-100). Scaled to any frequency by [hzA].
     */
    object CorrectionPluto {
        const val REFERENCE_HZ = 2_400_000_000L
        /** ±50 ppm: beyond, a mistake (fields swapped, a kHz typed for Hz). */
        const val MAX_HZ = 120_000L

        /** The relative error (+: high). */
        fun relative(decalageHz: Long): Double = decalageHz.toDouble() / REFERENCE_HZ

        /** The error at [frequenceHz] (Hz). */
        fun hzA(decalageHz: Long, frequenceHz: Long): Double = relative(decalageHz) * frequenceHz

        fun ppm(decalageHz: Long): Double = relative(decalageHz) * 1e6

        fun credible(decalageHz: Long): Boolean = abs(decalageHz) <= MAX_HZ

        /**
         * A new correction from our signal: expected on the downlink at
         * [attenduDescenteHz], heard at [entenduDescenteHz] (sky, the beacon
         * calibration applied), sent with [avantHz] in force. The residual
         * error adds to it, scaled to 2 400 MHz.
         */
        fun mesure(attenduDescenteHz: Long, entenduDescenteHz: Long, monteeHz: Long, avantHz: Long): Long {
            val residu = (entenduDescenteHz - attenduDescenteHz).toDouble() * REFERENCE_HZ / monteeHz
            return (avantHz + residu).roundToLong()
        }
    }

    /**
     * Where the Pluto is tuned for an uplink, and the digital shift that puts
     * the signal there.
     *
     * The AD936x leaks its LO (a carrier at the LO) and leaves an image
     * (mirror of the signal about the LO). Tuned on the dial, the leak would
     * be a carrier on the transponder. So the LO is parked at [LO_HZ], below
     * the transponder's uplink (2 400.000–2 400.500), and the signal moved up
     * by a numerical oscillator: the leak at 2 399.600, the image near
     * 2 399.1 — both outside what the satellite repeats.
     *
     * The crystal error moves the LO and the DAC clock alike: both are asked
     * lower by the same ratio ([CorrectionPluto]).
     */
    data class PlanPluto(
        /** The LO asked of the Pluto (Hz, whole). */
        val loHz: Long,
        /** The shift applied to the baseband (Hz, signed), at the Pluto's own clock. */
        val ncoHz: Double,
    ) {
        companion object {
            const val LO_HZ = 2_399_600_000L
            /** The sample rate: 48 kHz audio × 64 (3.072 MS/s), room for a shift up to ±1.2 MHz. */
            const val DEBIT = 3_072_000L

            /** The plan for the dial [cadranHz] with [decalageHz] of correction. */
            fun pour(cadranHz: Long, decalageHz: Long): PlanPluto {
                val r = 1.0 + CorrectionPluto.relative(decalageHz)
                val lo = (LO_HZ / r).roundToLong()
                return PlanPluto(lo, cadranHz / r - lo)
            }

            /** The shift for a new dial while transmitting, the LO kept. */
            fun ncoPour(loHz: Long, cadranHz: Long, decalageHz: Long): Double =
                cadranHz / (1.0 + CorrectionPluto.relative(decalageHz)) - loHz
        }
    }

    // ------------------------------------------------------------ helpers

    fun nomSegment(s: Qo100.Segment): String = NOMS[s.cle] ?: s.cle

    private val NOMS = mapOf("balise_basse" to "balise basse", "cw" to "CW", "num_etroit" to "numérique étroit", "num_large" to "numérique 2,7 kHz",
        "ssb_bas" to "BLU bas", "balise_mediane" to "balise médiane", "ssb_haut" to "BLU haut", "diffusion" to "diffusion", "urgence" to "urgence",
        "mixte" to "mixte", "balise_multimedia" to "balise multimédia", "balise_haute" to "balise haute")

    fun mhz(hz: Long): String = "%.4f".format(Locale.FRANCE, hz / 1e6).trimEnd('0').let { if (it.endsWith(',')) it + "0" else it }

    fun plage(bas: Long, haut: Long): String = mhz(bas) + "–" + mhz(haut) + " MHz"
}
