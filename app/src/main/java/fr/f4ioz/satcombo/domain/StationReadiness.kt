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
 * Is the station ready for the pass? One light per domain (satellite,
 * position, CAT, Doppler, pointing, audio, recording, log, sync), each OK,
 * WARNING, ERROR or NOT_REQUIRED, with the reason and the action that fixes
 * it.
 *
 * Pure: it reads a snapshot of values the app already holds ([Instantane])
 * and the operator's station profile ([Profil]); it calls nothing. A check
 * can therefore never key a transmitter, move a mast or write to a rig.
 * Severity follows the profile: a station that points by hand never sees a
 * red rotor.
 */
object StationReadiness {

    enum class Niveau { OK, WARNING, ERROR, NOT_REQUIRED }

    enum class Domaine { SAT, POSITION, CAT, DOPPLER, POINTAGE, AUDIO, ENREGISTREMENT, CARNET, SYNCHRO }

    /** What a tap on the light should do (resolved to a real function by the screen). */
    enum class Action {
        AUCUNE,
        RAFRAICHIR_ELEMENTS, CHOISIR_TRANSPONDEUR,
        REGLAGES_QTH, REGLAGES_GPS,
        CONNECTER_CAT, REGLAGES_CAT, REPRENDRE_DOPPLER,
        OUVRIR_ROTOR, REGLAGES_POINTAGE,
        PERMISSION_MICRO, REGLAGES_ENREGISTREMENT,
        REGLAGES_CARNET, DEPOSER_CARNET,
    }

    /**
     * One light. [raison] is a string key (translated by the screen), with
     * [args] for its placeholders.
     */
    data class Voyant(
        val domaine: Domaine,
        val niveau: Niveau,
        val raison: String,
        val action: Action = Action.AUCUNE,
        val args: List<String> = emptyList(),
    )

    enum class Pointage { ROTOR, MANUEL }

    /** How this station works: what is expected, so what may be missing. */
    data class Profil(
        val catRequis: Boolean = true,
        val pointage: Pointage = Pointage.MANUEL,
        val enregistrementRequis: Boolean = true,
        val synchroRequise: Boolean = false,
    )

    /** CAT link health, read from the PTT poll that runs while connected. */
    enum class SanteCat { INCONNUE, OK, MUETTE }

    /** BLE compass module, when it is the heading source. */
    enum class Boussole { TELEPHONE, CONNECTEE, EN_COURS, ECHEC }

    /** The values a check needs, taken from the app's own state. Nothing else. */
    data class Instantane(
        // Satellite
        val satNom: String? = null,
        val epochMs: Long? = null,
        val prochainAosMs: Long? = null,
        val prochainLosMs: Long? = null,
        val transpondeurs: Int = 0,
        val transpondeursEnCours: Boolean = false,
        val satMuet: Boolean = false,
        val amsatPasEntendu: Boolean = false,
        val cacheHorsLigne: Boolean = false,
        // Position
        val observateur: Boolean = false,
        val manuel: Boolean = true,
        val locatorInvalide: Boolean = false,
        val gpsPermission: Boolean = false,
        val gpsPoints: Int = 0,
        val positionParDefaut: Boolean = false,
        val indicatif: String = "",
        val locator: String = "",
        // CAT and Doppler
        val catActive: Boolean = false,
        val catConnecte: Boolean = false,
        val catSimule: Boolean = false,
        val catSante: SanteCat = SanteCat.INCONNUE,
        val catPaireIncomplete: Boolean = false,
        val catModeDifferent: Boolean = false,
        val dopplerEnPause: Boolean = false,
        val dopplerRx: Boolean = true,
        val relectureRx: Boolean = false,
        val satLeve: Boolean = false,
        // Pointing
        val rotorDisponible: Boolean = false,
        val rotorConnecte: Boolean = false,
        val rotorSimule: Boolean = false,
        val rotorSuivi: Boolean = false,
        val rotorRelu: Boolean = false,
        val rotorHorsCourse: Boolean = false,
        val boussole: Boussole = Boussole.TELEPHONE,
        // Audio and recording
        val enregistreurActif: Boolean = true,
        val source: String = "MIC",
        val permissionMicro: Boolean = false,
        val permissionBluetooth: Boolean = true,
        val carteUsbPresente: Boolean = false,
        val enregistrementEnCours: Boolean = false,
        val enregistrementArme: Boolean = false,
        // Log and sync
        val carnetConfigure: Boolean = false,
        val carnetAuto: Boolean = false,
        val carnetProfil: Boolean = false,
        val contactsEnAttente: Int = 0,
        val contactsRefuses: Int = 0,
    )

    /** Elements older than this at the pass are worth a warning (the "⚠ n d" chip's threshold). */
    const val ELEMENTS_VIEUX_JOURS = 3.0

    fun evalue(i: Instantane, p: Profil, maintenant: Long = System.currentTimeMillis()): List<Voyant> = listOf(
        satellite(i, maintenant), position(i), cat(i, p), doppler(i, p), pointage(i, p),
        audio(i, p), enregistrement(i, p), carnet(i), synchro(i, p),
    )

    /** The worst light that counts (NOT_REQUIRED does not). */
    fun global(voyants: List<Voyant>): Niveau = when {
        voyants.any { it.niveau == Niveau.ERROR } -> Niveau.ERROR
        voyants.any { it.niveau == Niveau.WARNING } -> Niveau.WARNING
        else -> Niveau.OK
    }

    private fun v(d: Domaine, n: Niveau, raison: String, a: Action = Action.AUCUNE, vararg args: String) =
        Voyant(d, n, raison, a, args.toList())

    private fun satellite(i: Instantane, maintenant: Long): Voyant {
        val d = Domaine.SAT
        if (i.satNom == null) return v(d, Niveau.ERROR, "rd_sat_aucun")
        if (i.epochMs == null) return v(d, Niveau.ERROR, "rd_sat_sans_elements", Action.RAFRAICHIR_ELEMENTS)
        if (i.prochainAosMs == null) return v(d, Niveau.ERROR, "rd_sat_sans_passage")
        val jours = (maxOf(i.prochainAosMs, maintenant) - i.epochMs) / 86_400_000.0
        return when {
            jours > ELEMENTS_VIEUX_JOURS ->
                v(d, Niveau.WARNING, "rd_sat_elements_vieux", Action.RAFRAICHIR_ELEMENTS, jours.toInt().toString())
            i.cacheHorsLigne -> v(d, Niveau.WARNING, "rd_sat_hors_ligne", Action.RAFRAICHIR_ELEMENTS)
            i.transpondeursEnCours -> v(d, Niveau.OK, "rd_sat_transpondeurs_chargement")
            i.transpondeurs == 0 -> v(d, Niveau.WARNING, "rd_sat_sans_transpondeur", Action.CHOISIR_TRANSPONDEUR)
            i.satMuet -> v(d, Niveau.WARNING, "rd_sat_muet")
            i.amsatPasEntendu -> v(d, Niveau.WARNING, "rd_sat_pas_entendu")
            else -> v(d, Niveau.OK, "rd_sat_ok", Action.CHOISIR_TRANSPONDEUR)
        }
    }

    private fun position(i: Instantane): Voyant {
        val d = Domaine.POSITION
        return when {
            !i.observateur -> v(d, Niveau.ERROR, "rd_pos_aucune", Action.REGLAGES_QTH)
            i.manuel && i.locatorInvalide -> v(d, Niveau.ERROR, "rd_pos_locator_invalide", Action.REGLAGES_QTH)
            !i.manuel && !i.gpsPermission -> v(d, Niveau.ERROR, "rd_pos_gps_permission", Action.REGLAGES_GPS)
            !i.manuel && i.positionParDefaut -> v(d, Niveau.WARNING, "rd_pos_par_defaut", Action.REGLAGES_GPS)
            !i.manuel && i.gpsPoints == 0 -> v(d, Niveau.WARNING, "rd_pos_gps_sans_point", Action.REGLAGES_GPS)
            else -> v(d, Niveau.OK, if (i.manuel) "rd_pos_manuelle" else "rd_pos_gps", Action.REGLAGES_QTH, i.locator)
        }
    }

    private fun cat(i: Instantane, p: Profil): Voyant {
        val d = Domaine.CAT
        if (!p.catRequis && !i.catConnecte) return v(d, Niveau.NOT_REQUIRED, "rd_cat_non_requis", Action.REGLAGES_CAT)
        return when {
            // Configured but not connected: the operator may still work by hand.
            !i.catConnecte -> v(d, Niveau.WARNING,
                if (i.catActive) "rd_cat_non_connecte" else "rd_cat_coupe", Action.CONNECTER_CAT)
            i.catSante == SanteCat.MUETTE -> v(d, Niveau.ERROR, "rd_cat_muet", Action.REGLAGES_CAT)
            i.catSimule -> v(d, Niveau.WARNING, "rd_cat_simule", Action.REGLAGES_CAT)
            i.catPaireIncomplete -> v(d, Niveau.WARNING, "rd_cat_paire_incomplete", Action.REGLAGES_CAT)
            i.catModeDifferent -> v(d, Niveau.WARNING, "rd_cat_mode", Action.REGLAGES_CAT)
            else -> v(d, Niveau.OK, "rd_cat_ok", Action.REGLAGES_CAT)
        }
    }

    private fun doppler(i: Instantane, p: Profil): Voyant {
        val d = Domaine.DOPPLER
        if (!p.catRequis && !i.catConnecte) return v(d, Niveau.NOT_REQUIRED, "rd_dop_non_requis")
        return when {
            !i.catConnecte -> v(d, Niveau.WARNING, "rd_dop_sans_cat", Action.CONNECTER_CAT)
            i.dopplerEnPause -> v(d, Niveau.WARNING, "rd_dop_pause", Action.REPRENDRE_DOPPLER)
            i.satLeve && !i.relectureRx && i.catSante != SanteCat.OK -> v(d, Niveau.WARNING, "rd_dop_sans_relecture", Action.REGLAGES_CAT)
            !i.dopplerRx -> v(d, Niveau.OK, "rd_dop_tx_seul", Action.REGLAGES_CAT)
            else -> v(d, Niveau.OK, "rd_dop_auto")
        }
    }

    private fun pointage(i: Instantane, p: Profil): Voyant {
        val d = Domaine.POINTAGE
        if (p.pointage == Pointage.MANUEL) return when (i.boussole) {
            Boussole.ECHEC -> v(d, Niveau.WARNING, "rd_pt_boussole_echec", Action.REGLAGES_POINTAGE)
            Boussole.EN_COURS -> v(d, Niveau.WARNING, "rd_pt_boussole_attente", Action.REGLAGES_POINTAGE)
            Boussole.CONNECTEE -> v(d, Niveau.OK, "rd_pt_manuel_ble", Action.REGLAGES_POINTAGE)
            Boussole.TELEPHONE -> v(d, Niveau.OK, "rd_pt_manuel", Action.REGLAGES_POINTAGE)
        }
        return when {
            !i.rotorDisponible -> v(d, Niveau.WARNING, "rd_pt_rotor_indisponible", Action.REGLAGES_POINTAGE)
            !i.rotorConnecte -> v(d, Niveau.ERROR, "rd_pt_rotor_non_connecte", Action.OUVRIR_ROTOR)
            !i.rotorSimule && !i.rotorRelu -> v(d, Niveau.ERROR, "rd_pt_rotor_muet", Action.OUVRIR_ROTOR)
            i.rotorSimule -> v(d, Niveau.WARNING, "rd_pt_rotor_simule", Action.OUVRIR_ROTOR)
            i.rotorHorsCourse -> v(d, Niveau.WARNING, "rd_pt_rotor_hors_course", Action.OUVRIR_ROTOR)
            !i.rotorSuivi -> v(d, Niveau.WARNING, "rd_pt_rotor_sans_suivi", Action.OUVRIR_ROTOR)
            else -> v(d, Niveau.OK, "rd_pt_rotor_ok", Action.OUVRIR_ROTOR)
        }
    }

    private fun audio(i: Instantane, p: Profil): Voyant {
        val d = Domaine.AUDIO
        if (!p.enregistrementRequis && !i.enregistrementArme && !i.enregistrementEnCours)
            return v(d, Niveau.NOT_REQUIRED, "rd_audio_non_requis", Action.REGLAGES_ENREGISTREMENT)
        return when {
            !i.permissionMicro -> v(d, Niveau.ERROR, "rd_audio_permission", Action.PERMISSION_MICRO)
            i.source == "BT" && !i.permissionBluetooth -> v(d, Niveau.ERROR, "rd_audio_permission_bt", Action.PERMISSION_MICRO)
            // Not an error: the recording goes ahead on the phone microphone.
            i.source == "USB" && !i.carteUsbPresente -> v(d, Niveau.WARNING, "rd_audio_usb_absente", Action.REGLAGES_ENREGISTREMENT)
            else -> v(d, Niveau.OK, when (i.source) { "USB" -> "rd_audio_usb"; "BT" -> "rd_audio_bt"; else -> "rd_audio_micro" },
                Action.REGLAGES_ENREGISTREMENT)
        }
    }

    private fun enregistrement(i: Instantane, p: Profil): Voyant {
        val d = Domaine.ENREGISTREMENT
        return when {
            i.enregistrementEnCours -> v(d, Niveau.OK, "rd_rec_en_cours")
            i.enregistrementArme -> v(d, Niveau.OK, "rd_rec_arme")
            !p.enregistrementRequis -> v(d, Niveau.NOT_REQUIRED, "rd_rec_non_requis", Action.REGLAGES_ENREGISTREMENT)
            !i.enregistreurActif -> v(d, Niveau.WARNING, "rd_rec_coupe", Action.REGLAGES_ENREGISTREMENT)
            else -> v(d, Niveau.OK, "rd_rec_pret", Action.REGLAGES_ENREGISTREMENT)
        }
    }

    private fun carnet(i: Instantane): Voyant {
        val d = Domaine.CARNET
        return when {
            i.indicatif.isBlank() -> v(d, Niveau.ERROR, "rd_log_sans_indicatif", Action.REGLAGES_QTH)
            i.locator.isBlank() -> v(d, Niveau.WARNING, "rd_log_sans_locator", Action.REGLAGES_QTH)
            else -> v(d, Niveau.OK, "rd_log_ok", Action.REGLAGES_CARNET, i.indicatif)
        }
    }

    private fun synchro(i: Instantane, p: Profil): Voyant {
        val d = Domaine.SYNCHRO
        // Online logs are a bonus: never a prerequisite unless the profile says so.
        if (!i.carnetConfigure) return if (p.synchroRequise)
            v(d, Niveau.WARNING, "rd_sync_non_configure", Action.REGLAGES_CARNET)
            else v(d, Niveau.NOT_REQUIRED, "rd_sync_non_requis", Action.REGLAGES_CARNET)
        return when {
            i.carnetAuto && !i.carnetProfil -> v(d, Niveau.ERROR, "rd_sync_sans_profil", Action.REGLAGES_CARNET)
            i.contactsRefuses > 0 -> v(d, Niveau.ERROR, "rd_sync_refus", Action.REGLAGES_CARNET, i.contactsRefuses.toString())
            i.contactsEnAttente > 0 -> v(d, Niveau.WARNING, "rd_sync_en_attente", Action.DEPOSER_CARNET, i.contactsEnAttente.toString())
            else -> v(d, Niveau.OK, if (i.carnetAuto) "rd_sync_auto" else "rd_sync_manuel", Action.REGLAGES_CARNET)
        }
    }
}
