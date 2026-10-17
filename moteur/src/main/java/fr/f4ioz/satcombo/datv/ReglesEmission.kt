/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.Modcod
import fr.f4ioz.satcombo.datv.s2.Rolloff
import java.util.Locale

/**
 * QO-100's wide band transponder as a transmitter sees it: uplink
 * 2.4 GHz, downlink 8089.5 MHz higher, the beacon at the bottom
 * (10491.5 MHz, uplink 2402.0 MHz, 1.5 MS/s).
 *
 * What may be sent: the whole occupied band (Rs·(1+α)) between [BAS_KHZ]
 * and [HAUT_KHZ]. The bottom keeps clear of the beacon: its band ends near
 * 2402.9 MHz (1.5 MS/s, roll-off 0.20), 100 kHz of margin above. The top
 * is the band plan's last channel (2409.75 MHz, ≤ 333 kS/s) with its
 * occupied band, 2410.0 MHz.
 */
object PlanQo100 {
    const val DECALAGE_KHZ = 8_089_500L
    const val BALISE_MONTEE_KHZ = Datv.BALISE_KHZ - DECALAGE_KHZ
    const val BAS_KHZ = 2_403_000L
    const val HAUT_KHZ = 2_410_000L

    /** A channel of the band plan (downlink), and the highest symbol rate it takes. */
    data class Canal(val descenteKhz: Long, val srMaxKs: Int) {
        val monteeKhz: Long get() = descenteKhz - DECALAGE_KHZ
    }

    /**
     * The wide channels (the lowest one 1000 kS/s only: at 1500 its band would
     * touch the beacon's), then the narrow ones every 500 kHz (≤ 333 kS/s).
     */
    val CANAUX: List<Canal> = listOf(Canal(10_493_250L, 1000), Canal(10_494_750L, 1500), Canal(10_496_250L, 1500)) +
        (0..13).map { k -> Canal(10_492_750L + 500L * k, 333) }

    fun occupeKhz(srKs: Int, alpha: Double): Double = srKs * (1 + alpha)

    /** Why [monteeKhz] at [srKs] and [alpha] may not go up, or null. */
    fun refus(monteeKhz: Long, srKs: Int, alpha: Double): String? {
        val demi = occupeKhz(srKs, alpha) / 2
        return when {
            monteeKhz - demi < BAS_KHZ -> "trop près de la balise : le signal (%s MHz ± %s kHz) doit rester au-dessus de %s MHz"
                .format(Locale.FRANCE, mhz(monteeKhz), "%.0f".format(demi), mhz(BAS_KHZ))
            monteeKhz + demi > HAUT_KHZ -> "hors du transpondeur large bande : le haut du signal dépasse %s MHz".format(Locale.FRANCE, mhz(HAUT_KHZ))
            else -> null
        }
    }

    fun mhz(khz: Long) = "%.3f".format(Locale.FRANCE, khz / 1000.0)
}

/**
 * The indoor test: a few metres between two small antennas, the MiniTiouner
 * receiving on its own input. Only in amateur bands its tuner covers (the
 * whole occupied band inside), at the transmitter's lowest power, for a few
 * minutes.
 */
object PlanInterieur {
    data class Bande(val nom: String, val basKhz: Long, val hautKhz: Long)

    val BANDES = listOf(Bande("23 cm", 1_240_000L, 1_300_000L), Bande("13 cm", 2_300_000L, 2_450_000L))
    /** Inside 23 cm, below the part shared with radionavigation satellites. */
    const val DEFAUT_KHZ = 1_255_000L
    const val DUREE_DEFAUT_S = 300
    const val DUREE_MAX_S = 600
    const val DUREE_MIN_S = 60
    /** The Pluto's TX gain indoors: never above. */
    const val GAIN_PLUTO_DB = -40.0

    /** Why [khz] at [srKs] and [alpha] may not be used indoors, or null. */
    fun refus(khz: Long, srKs: Int, alpha: Double): String? {
        val demi = PlanQo100.occupeKhz(srKs, alpha) / 2
        if (BANDES.any { khz - demi >= it.basKhz && khz + demi <= it.hautKhz }) return null
        return "essai en intérieur : le signal entier (%s MHz ± %s kHz) doit tenir dans 1240–1300 MHz (23 cm) ou 2300–2450 MHz (13 cm)"
            .format(Locale.FRANCE, PlanQo100.mhz(khz), "%.0f".format(demi))
    }

    /** What to know about this part of the band, or null. */
    fun avertissement(khz: Long, srKs: Int, alpha: Double): String? {
        val demi = PlanQo100.occupeKhz(srKs, alpha) / 2
        val bas = khz - demi
        val haut = khz + demi
        return when {
            haut > 2_400_000 && bas < 2_450_000 -> "2400–2450 MHz est partagé avec le Wi-Fi : préférer 2300–2400 MHz, ou 23 cm"
            haut > 1_260_000 && bas < 1_300_000 -> "1260–1300 MHz est partagé avec les satellites de radionavigation : préférer 1240–1260 MHz (1255 MHz)"
            else -> null
        }
    }

    fun dureeS(demandee: Int): Int = demandee.coerceIn(DUREE_MIN_S, DUREE_MAX_S)
}

/** Which transmitter sends. */
enum class Emetteur(val cle: String, val libelle: String) {
    PLUTO("pluto", "ADALM-PLUTO"),
    HACKRF("hackrf", "HackRF One"),
    /** An AD9361 as the Pluto (12-bit DAC, the same attenuation): the Pluto's rules, QO-100 included. */
    BLADERF("bladerf", "bladeRF 2.0");

    companion object {
        fun de(cle: String?): Emetteur = entries.firstOrNull { it.cle == cle } ?: PLUTO
    }
}

/** What the operator asks for one transmission. */
data class DemandeEmission(
    val frequenceKhz: Long,
    val srKs: Int,
    val modcod: Modcod,
    val rolloff: Rolloff,
    /** TX gain: the Pluto's and the bladeRF's (dB, ≤ 0: an attenuation), the HackRF's TX VGA (dB, ≥ 0). */
    val gainDb: Double,
    val dureeS: Int = ReglesEmission.DUREE_DEFAUT_S,
    /** Into a dummy load: any frequency, the gain held at or below [ReglesEmission.GAIN_CHARGE_DB]. */
    val chargeFictive: Boolean = false,
    val indicatif: String = "",
    /** The indoor test ([PlanInterieur]). */
    val interieur: Boolean = false,
    val emetteur: Emetteur = Emetteur.PLUTO,
)

/**
 * The rules every transmission obeys, whoever asks: a callsign, the band
 * (or the dummy load at low power), a known symbol rate and MODCOD, a
 * bounded duration, a bounded gain.
 */
object ReglesEmission {
    const val GAIN_DEFAUT_DB = -30.0
    /** The test into a dummy load: never above this. */
    const val GAIN_CHARGE_DB = -40.0
    /** The AD936x's TX attenuation range. */
    const val GAIN_MIN_DB = -89.75
    const val DUREE_DEFAUT_S = 600
    const val DUREE_MAX_S = 1800
    const val DUREE_MIN_S = 10
    /** The Pluto's lowest rate without its FIR. */
    const val DEBIT_MIN = 2_083_334L
    /**
     * The HackRF's TX VGA, the only power it ever gets: its RF amplifier
     * always off, the gain never above this (dB).
     */
    const val HACKRF_VGA_MAX = 10
    /** Why the HackRF does not go to QO-100. */
    const val HACKRF_PAS_QO100 = "le HackRF n'émet qu'en « essai en intérieur » ou sur charge : son convertisseur 8 bits et ses " +
        "harmoniques demandent un filtre et un amplificateur avant QO-100 ; QO-100 reste réservé à l'ADALM-PLUTO"

    /** Why this may not be sent, or null. */
    fun refus(d: DemandeEmission, gainMaxDb: Double): String? {
        val ind = d.indicatif.trim()
        val hackrf = d.emetteur == Emetteur.HACKRF
        return when {
            ind.length < 3 || !ind.any { it.isDigit() } || !ind.all { it.isLetterOrDigit() || it == '/' } ->
                "indicatif manquant : le donner dans Réglages › Station avant toute émission"
            d.chargeFictive && d.interieur -> "essai en intérieur ou test sur charge : l'un ou l'autre"
            hackrf && !d.chargeFictive && !d.interieur -> HACKRF_PAS_QO100
            d.srKs !in Datv.DEBITS -> "débit de symboles non prévu : ${d.srKs} kS/s"
            d.modcod.refus() != null -> d.modcod.refus()
            d.interieur && d.dureeS !in PlanInterieur.DUREE_MIN_S..PlanInterieur.DUREE_MAX_S ->
                "essai en intérieur : de ${PlanInterieur.DUREE_MIN_S / 60} à ${PlanInterieur.DUREE_MAX_S / 60} min"
            d.dureeS !in DUREE_MIN_S..DUREE_MAX_S -> "durée de ${DUREE_MIN_S} s à ${DUREE_MAX_S / 60} min"
            d.gainDb.isNaN() -> "gain d'émission invalide"
            d.interieur -> PlanInterieur.refus(d.frequenceKhz, d.srKs, d.rolloff.alpha)
            !d.chargeFictive -> PlanQo100.refus(d.frequenceKhz, d.srKs, d.rolloff.alpha)
            hackrf && d.frequenceKhz !in 1_000L..6_000_000L -> "fréquence hors de la plage du HackRF"
            d.emetteur == Emetteur.BLADERF -> if (d.frequenceKhz !in 47_000L..6_000_000L) "fréquence hors de la plage du bladeRF" else null
            !hackrf && d.frequenceKhz !in 70_000L..6_000_000L -> "fréquence hors de la plage de l'ADALM-PLUTO"
            else -> null
        }
    }

    /** The gain actually applied: the asked one, never above the configured maximum, 0 dB, or −40 dB into a load. */
    fun gain(d: DemandeEmission, gainMaxDb: Double): Double = gain(d.gainDb, gainMaxDb, d.chargeFictive, d.interieur)

    fun gain(demande: Double, gainMaxDb: Double, charge: Boolean, interieur: Boolean = false): Double {
        var g = minOf(demande, gainMaxDb, 0.0)
        if (charge) g = minOf(g, GAIN_CHARGE_DB)
        if (interieur) g = minOf(g, PlanInterieur.GAIN_PLUTO_DB)
        // The AD936x's steps of 0.25 dB, rounded down (more attenuation).
        g = Math.floor(g * 4) / 4
        return g.coerceAtLeast(GAIN_MIN_DB)
    }

    /** Samples per symbol: the fewest (2 at least) that bring the rate to the Pluto's minimum. */
    fun sps(srKs: Int): Int {
        var k = 2
        while (srKs * 1000L * k < DEBIT_MIN) k++
        return k
    }

    fun dureeS(demandee: Int): Int = demandee.coerceIn(DUREE_MIN_S, DUREE_MAX_S)

    /** The HackRF's TX VGA actually applied (dB): whole, 0 at least, [HACKRF_VGA_MAX] at most. */
    fun vgaHackrf(demande: Double): Int =
        if (demande.isNaN()) 0 else Math.floor(demande).coerceIn(0.0, HACKRF_VGA_MAX.toDouble()).toInt()
}

/**
 * The closed loop on QO-100: our signal seen by the MiniTiouner (its MER)
 * against the beacon's (measured on the same receiver). On QO-100 a QPSK
 * signal stays at least [margeDb] below the beacon's power density; the
 * MER compares them, the noise being the same. Too strong: a warning, and
 * the TX gain stepped down by [pasDb], at most once every [intervalleMs]
 * (the measurement settles). Never stepped up by itself.
 */
class BoucleNiveau(
    val margeDb: Double = 1.0,
    val pasDb: Double = 1.0,
    val intervalleMs: Long = 5_000L,
    /** A measure older than this is not used. */
    val fraicheurMs: Long = 3_000L,
) {
    data class Etat(
        /** Our level against the beacon's (dB), null without both measures. */
        val ecartDb: Double? = null,
        val tropFort: Boolean = false,
        val message: String = "",
        /** Steps made since the start. */
        val baisses: Int = 0,
    )

    @Volatile var etat = Etat(); private set
    private var derniereBaisse = Long.MIN_VALUE / 2

    /**
     * A new measure: our MER (null when the receiver does not hold our
     * signal) and the beacon's; returns the gain to apply (lower when too
     * strong), or null to leave it.
     */
    fun mesure(merNousDb: Double?, merBaliseDb: Double?, ageMesureMs: Long, gainDb: Double, maintenant: Long): Double? {
        if (merNousDb == null || ageMesureMs > fraicheurMs) {
            etat = etat.copy(ecartDb = null, tropFort = false, message = "le MiniTiouner ne reçoit pas notre signal")
            return null
        }
        if (merBaliseDb == null) {
            etat = etat.copy(ecartDb = null, tropFort = false, message = "niveau de la balise inconnu : la recevoir d'abord (bouton Balise)")
            return null
        }
        val e = merNousDb - merBaliseDb
        val trop = e > -margeDb
        if (!trop) {
            etat = etat.copy(ecartDb = e, tropFort = false, message = "%.1f dB sous la balise".format(Locale.FRANCE, -e))
            return null
        }
        val peut = maintenant - derniereBaisse >= intervalleMs && gainDb - pasDb >= ReglesEmission.GAIN_MIN_DB
        etat = etat.copy(ecartDb = e, tropFort = true, baisses = etat.baisses + (if (peut) 1 else 0),
            message = "TROP FORT : %s%.1f dB par rapport à la balise (rester %.0f dB dessous)".format(Locale.FRANCE, if (e >= 0) "+" else "", e, margeDb) +
                (if (peut) " — gain baissé de %.0f dB".format(Locale.FRANCE, pasDb) else ""))
        if (!peut) return null
        derniereBaisse = maintenant
        return gainDb - pasDb
    }
}
