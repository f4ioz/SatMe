/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.StationReadiness.Action
import fr.f4ioz.satcombo.domain.StationReadiness.Domaine
import fr.f4ioz.satcombo.domain.StationReadiness.Niveau
import fr.f4ioz.satcombo.domain.StationReadiness.Voyant
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The station check: the lights say what the app believes, the check asks
 * the equipment itself — read the rig's frequency, read the mast's
 * position, listen two seconds to the audio input, ask the online log, look
 * at the last GPS fix. Read only: nothing transmits, nothing moves.
 *
 * This object judges the answers (pure, testable); the view model fetches
 * them. A part that cannot be tested now (not connected, a recording
 * running) is NOT_REQUIRED with its reason, never an error.
 */
object StationCheck {

    /** Audio measured over the test: peak and RMS in dBFS. */
    data class Niveaux(val creteDbfs: Double, val rmsDbfs: Double)

    /** Below this the input hears nothing (cable, wrong source, rig muted). */
    const val SILENCE_DBFS = -60.0
    /** Above this the input clips: the decoders suffer. */
    const val SATURE_DBFS = -1.0
    /** A GPS fix older than this is stale (the tracking watchdog's own limit). */
    const val GPS_VIEUX_MS = 90_000L

    /** Peak and RMS of 16-bit samples, in dBFS (−120 for pure silence). */
    fun niveaux(pcm: ShortArray, n: Int = pcm.size): Niveaux {
        if (n <= 0) return Niveaux(-120.0, -120.0)
        var crete = 0; var somme = 0.0
        for (i in 0 until n) {
            val v = kotlin.math.abs(pcm[i].toInt())
            if (v > crete) crete = v
            somme += pcm[i].toDouble() * pcm[i]
        }
        fun db(x: Double) = if (x <= 0.5) -120.0 else 20 * log10(x / 32768.0)
        return Niveaux(db(crete.toDouble()), db(sqrt(somme / n)))
    }

    private fun v(d: Domaine, n: Niveau, raison: String, a: Action = Action.AUCUNE, vararg args: String) =
        Voyant(d, n, raison, a, args.toList())

    private fun mhz(hz: Long) = "%.4f".format(java.util.Locale.US, hz / 1e6)

    /** Frequencies read back from the rig: [rx] and [tx] for a pair, [rx] alone for one rig (null = no answer). */
    fun cat(connecte: Boolean, paire: Boolean, rx: Long?, tx: Long?): Voyant = when {
        !connecte -> v(Domaine.CAT, Niveau.NOT_REQUIRED, "rd_test_cat_non_connecte", Action.CONNECTER_CAT)
        !paire && rx == null -> v(Domaine.CAT, Niveau.ERROR, "rd_test_cat_muet", Action.REGLAGES_CAT)
        !paire -> v(Domaine.CAT, Niveau.OK, "rd_test_cat_ok", Action.REGLAGES_CAT, mhz(rx!!))
        rx == null && tx == null -> v(Domaine.CAT, Niveau.ERROR, "rd_test_cat_muet", Action.REGLAGES_CAT)
        rx == null -> v(Domaine.CAT, Niveau.ERROR, "rd_test_cat_rx_muet", Action.REGLAGES_CAT, mhz(tx!!))
        tx == null -> v(Domaine.CAT, Niveau.ERROR, "rd_test_cat_tx_muet", Action.REGLAGES_CAT, mhz(rx))
        else -> v(Domaine.CAT, Niveau.OK, "rd_test_cat_paire_ok", Action.REGLAGES_CAT, mhz(rx), mhz(tx))
    }

    /** The mast's position read now (null = no answer). */
    fun rotor(connecte: Boolean, az: Double?, el: Double?): Voyant = when {
        !connecte -> v(Domaine.POINTAGE, Niveau.NOT_REQUIRED, "rd_test_rotor_non_connecte", Action.OUVRIR_ROTOR)
        az == null -> v(Domaine.POINTAGE, Niveau.ERROR, "rd_test_rotor_muet", Action.OUVRIR_ROTOR)
        else -> v(Domaine.POINTAGE, Niveau.OK, "rd_test_rotor_ok", Action.OUVRIR_ROTOR,
            "%.0f".format(java.util.Locale.US, az), el?.let { "%.0f".format(java.util.Locale.US, it) } ?: "—")
    }

    /** Two seconds of the audio input, or why it could not be listened to. */
    fun audio(n: Niveaux?, raisonSiAbsent: String?): Voyant = when {
        n == null -> v(Domaine.AUDIO, when (raisonSiAbsent) {
                "rd_test_audio_permission" -> Niveau.ERROR
                // The USB card chosen is not there: the recording would fall back to the phone mic.
                "rd_test_audio_usb_absente" -> Niveau.WARNING
                else -> Niveau.NOT_REQUIRED
            },
            raisonSiAbsent ?: "rd_test_audio_impossible",
            if (raisonSiAbsent == "rd_test_audio_permission") Action.PERMISSION_MICRO else Action.REGLAGES_ENREGISTREMENT)
        n.rmsDbfs < SILENCE_DBFS -> v(Domaine.AUDIO, Niveau.WARNING, "rd_test_audio_silence", Action.REGLAGES_ENREGISTREMENT,
            "%.0f".format(java.util.Locale.US, n.rmsDbfs))
        n.creteDbfs > SATURE_DBFS -> v(Domaine.AUDIO, Niveau.WARNING, "rd_test_audio_sature", Action.REGLAGES_ENREGISTREMENT)
        else -> v(Domaine.AUDIO, Niveau.OK, "rd_test_audio_ok", Action.REGLAGES_ENREGISTREMENT,
            "%.0f".format(java.util.Locale.US, n.rmsDbfs), "%.0f".format(java.util.Locale.US, n.creteDbfs))
    }

    /** The last GPS fix, in automatic mode. */
    fun gps(manuel: Boolean, ageMs: Long?): Voyant = when {
        manuel -> v(Domaine.POSITION, Niveau.NOT_REQUIRED, "rd_test_gps_manuel", Action.REGLAGES_QTH)
        ageMs == null -> v(Domaine.POSITION, Niveau.WARNING, "rd_test_gps_aucun", Action.REGLAGES_GPS)
        ageMs > GPS_VIEUX_MS -> v(Domaine.POSITION, Niveau.WARNING, "rd_test_gps_vieux", Action.REGLAGES_GPS, (ageMs / 1000).toString())
        else -> v(Domaine.POSITION, Niveau.OK, "rd_test_gps_ok", Action.REGLAGES_GPS, (ageMs / 1000).toString())
    }

    /** The online log's answer to a harmless query ("OK", or what went wrong). */
    fun carnet(configure: Boolean, reponse: String?): Voyant = when {
        !configure -> v(Domaine.SYNCHRO, Niveau.NOT_REQUIRED, "rd_test_sync_non_configure", Action.REGLAGES_CARNET)
        reponse == "OK" -> v(Domaine.SYNCHRO, Niveau.OK, "rd_test_sync_ok", Action.REGLAGES_CARNET)
        else -> v(Domaine.SYNCHRO, Niveau.ERROR, "rd_test_sync_erreur", Action.REGLAGES_CARNET, reponse.orEmpty())
    }
}
