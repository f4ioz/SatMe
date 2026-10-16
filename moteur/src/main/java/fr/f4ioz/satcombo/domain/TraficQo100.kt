/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.roundToLong

/**
 * Operating QO-100's narrow transponder from one screen: what SatMe follows
 * for the log (QO-100 or the satellite followed), how a signal clicked on is
 * tuned, and whether a reception cut by a restart is offered back.
 *
 * Pure logic: SatMe-PC and the phone give their state, the answers are the same.
 */
object TraficQo100 {

    /** The satellite name logged for QO-100 (what LoTW knows). */
    const val NOM = "QO-100"

    /** Where QO-100 is set: the receiver (dongle, Pluto), the rig by CAT, or neither. */
    enum class Par { CLE, POSTE, SUIVI, AUCUN }

    /**
     * What a new contact is logged on. [qo100] true: satellite « QO-100 »,
     * [descenteHz]/[monteeHz] the sky frequencies (nominal, the LNB's
     * calibration left out), [modeCarnet] its log mode (SSB, CW, FT8…).
     */
    data class Contexte(
        val qo100: Boolean,
        val satellite: String,
        val catnum: Int,
        val par: Par,
        val descenteHz: Long? = null,
        val monteeHz: Long? = null,
        val demod: String = "",
        val modeCarnet: String = "",
    ) {
        /** For the header: « QO-100 · 10 489,744 USB », or the satellite followed. */
        val libelle: String get() = when {
            qo100 && descenteHz != null -> "$NOM · ${khz(descenteHz)} ${if (modeCarnet == "CW") "CW" else demod.ifEmpty { "USB" }}"
            satellite.isNotEmpty() -> satellite
            else -> ""
        }
    }

    /**
     * The context now. QO-100 when the receiver is on it and running
     * ([cleSurQo100] and [recepteurActif]) or when the rig follows it
     * ([auPoste]); else the satellite followed, if any.
     */
    fun contexte(cleSurQo100: Boolean, recepteurActif: Boolean, auPoste: Boolean, descenteHz: Long, modeCarnet: String,
                 suiviNom: String?, suiviCatnum: Int): Contexte {
        val parCle = cleSurQo100 && recepteurActif
        if (parCle || auPoste) {
            val m = modeCarnet.ifBlank { "SSB" }
            return Contexte(true, NOM, Qo100.NORAD, if (parCle) Par.CLE else Par.POSTE, descenteHz,
                Qo100.monteeDepuisDescente(descenteHz), demodDe(m), m)
        }
        if (!suiviNom.isNullOrBlank()) return Contexte(false, suiviNom, suiviCatnum, Par.SUIVI)
        return Contexte(false, "", 0, Par.AUCUN)
    }

    /** The receiver's demodulation on QO-100: always USB (CW heard as a note in USB). */
    @Suppress("UNUSED_PARAMETER")
    fun demodDe(modeCarnet: String): String = "USB"

    /**
     * Tuning a signal: [cadranHz] the dial (the suppressed carrier, sky Hz),
     * [demod] the receiver's mode, [modeCarnet] the log's, [decodeur] the
     * panel to show (phonie, cw, ft8, ft4, sstv, or "" for none).
     */
    data class Accord(val cadranHz: Long, val demod: String, val modeCarnet: String, val decodeur: String, val ft8Mode: String = "")

    /** The usual FT8 dial on the narrow transponder. */
    const val CADRAN_FT8_HZ = 10_489_540_000L

    /** Where a CW note is heard, Hz above the dial. */
    const val NOTE_CW_HZ = 700L

    /** The audio window the FT8/FT4 decoder reads, Hz above the dial. */
    private const val FT8_BAS_HZ = 200L
    private const val FT8_HAUT_HZ = 2_900L

    /** The tuning for a signal of class [classe] (the classifier's key) centred on [hz]. */
    fun accord(classe: String, hz: Long): Accord = when (classe) {
        "cw" -> Accord(arrondi(hz - NOTE_CW_HZ, 10), "USB", "CW", "cw")
        "ft8", "ft4" -> {
            val mode = classe.uppercase()
            // The usual dial when the signal is in its window; else a dial putting it at 1.5 kHz.
            val cadran = if (hz - CADRAN_FT8_HZ in FT8_BAS_HZ..FT8_HAUT_HZ) CADRAN_FT8_HZ else arrondi(hz - 1_500, 1_000)
            Accord(cadran, "USB", mode, "ft8", mode)
        }
        // SSTV's tones run 1 200 to 2 300 Hz: the middle at 1.9 kHz.
        "sstv" -> Accord(arrondi(hz - 1_900, 10), "USB", "SSTV", "sstv")
        "psk31" -> Accord(arrondi(hz - 1_000, 10), "USB", "PSK31", "")
        "rtty" -> Accord(arrondi(hz - 1_500, 10), "USB", "RTTY", "")
        // A voice's centre about 1.5 kHz above its carrier.
        "blu" -> Accord(arrondi(hz - 1_500, 10), "USB", "SSB", "phonie")
        // A beacon, a carrier, something unknown: heard as a note in USB.
        else -> Accord(arrondi(hz - 1_000, 10), "USB", "SSB", "")
    }

    /** The decoder panel for a class (« auto »). */
    fun decodeurDe(classe: String): String = accord(classe, Qo100.BALISE_MEDIANE_HZ).decodeur

    /**
     * At start: offer to take QO-100 back when it was being received when
     * SatMe stopped ([enCours] kept), and it is not received now.
     */
    fun aReprendre(enCours: Boolean, recoitMaintenant: Boolean): Boolean = enCours && !recoitMaintenant

    /** Taken back by itself at start: only when chosen (off by default), and only the reception. */
    fun repriseAuto(choisie: Boolean, enCours: Boolean): Boolean = choisie && enCours

    /**
     * The calibration offset worth a word: the beacon measured more than
     * [seuilHz] off and the automatic calibration not on.
     */
    fun conseilCalage(ecartHz: Long?, auto: Boolean, seuilHz: Long = 500L): Boolean =
        !auto && ecartHz != null && kotlin.math.abs(ecartHz) >= seuilHz

    /** 10 489 744 007 Hz → « 10 489,744 » (MHz, to the kHz). */
    fun khz(hz: Long): String {
        val k = (hz / 1000.0).roundToLong()
        val mhz = k / 1000
        val reste = (k % 1000).toString().padStart(3, '0')
        val m = mhz.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$m,$reste"
    }

    private fun arrondi(hz: Long, pas: Long): Long = Math.floorDiv(hz + pas / 2, pas) * pas
}
