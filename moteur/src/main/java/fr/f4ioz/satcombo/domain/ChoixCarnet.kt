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
 * The satellite a new contact is logged on: what the operator works, not
 * what a receiver listens to in the background.
 *
 * 1. The satellite chosen in the form wins.
 * 2. Else QO-100 when the rig is on it (« Appliquer au poste ») or when the
 *    contact is typed in the QO-100 cockpit's own form.
 * 3. Else the satellite followed (the rig driven on it when CAT is on).
 * 4. A receiver on QO-100 counts only when nothing else is followed.
 *
 * Pure logic: SatMe-PC and the phone give their state, the answers are the same.
 */
object ChoixCarnet {

    /** What the form asks: nothing (auto), QO-100, or the satellite followed. */
    enum class Demande {
        AUTO, QO100, SUIVI;
        companion object {
            fun de(s: String?): Demande = when (s?.trim()?.lowercase()) { "qo100" -> QO100; "suivi" -> SUIVI; else -> AUTO }
        }
    }

    /** Why that satellite, in the form's words. */
    enum class Pourquoi(val mot: String) {
        CHOISI("choisi"),
        POSTE_QO100("poste sur QO-100"),
        COCKPIT("cockpit"),
        POSTE_CAT("poste en CAT"),
        SUIVI("satellite suivi"),
        RECEPTEUR("récepteur, rien d'autre suivi"),
        AUCUN(""),
    }

    data class Choix(val qo100: Boolean, val satellite: String, val catnum: Int, val pourquoi: Pourquoi) {
        /** « RS-44 · poste en CAT », « QO-100 · cockpit ». */
        val libelle: String get() = if (satellite.isEmpty()) "" else satellite + if (pourquoi.mot.isEmpty()) "" else " · " + pourquoi.mot
    }

    private val QO = { p: Pourquoi -> Choix(true, TraficQo100.NOM, Qo100.NORAD, p) }

    /**
     * [depuisCockpit]: typed in the QO-100 cockpit's form; [posteSurQo100]:
     * the rig set on QO-100 by CAT; [recepteurSurQo100]: a receiver hears it
     * (background, receive only); [catConnecte]: the rig on CAT, Doppler
     * driven on the satellite followed.
     */
    fun choix(demande: Demande, depuisCockpit: Boolean, posteSurQo100: Boolean, recepteurSurQo100: Boolean,
              catConnecte: Boolean, suiviNom: String?, suiviCatnum: Int): Choix {
        val suivi = suiviNom?.takeIf { it.isNotBlank() }
        if (demande == Demande.QO100) return QO(Pourquoi.CHOISI)
        // QO-100 followed as a satellite is QO-100 all the same.
        val suiviQo = suiviCatnum == Qo100.NORAD
        if (demande == Demande.SUIVI && suivi != null) return Choix(suiviQo, suivi, suiviCatnum, Pourquoi.CHOISI)
        if (posteSurQo100) return QO(Pourquoi.POSTE_QO100)
        if (depuisCockpit) return QO(Pourquoi.COCKPIT)
        if (suivi != null) return Choix(suiviQo, suivi, suiviCatnum, if (catConnecte) Pourquoi.POSTE_CAT else Pourquoi.SUIVI)
        if (recepteurSurQo100) return QO(Pourquoi.RECEPTEUR)
        return Choix(false, "", 0, Pourquoi.AUCUN)
    }
}
