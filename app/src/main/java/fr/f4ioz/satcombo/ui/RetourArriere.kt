/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import fr.f4ioz.satcombo.Screen

/**
 * What the phone's back button closes, and in which order.
 *
 * **Close what is visible.** The branch order must match exactly the `when`
 * that picks the screen to draw, otherwise back acts on a hidden layer.
 *
 * Real failure: the Rotor screen is drawn over the satellite sheet
 * (`screen == ROTOR` beats `selected != null` when drawing), but back tested
 * `selected != null` first and called `backToList()` — clearing the selection
 * and stopping tracking while the Rotor screen stayed up showing "no satellite
 * tracked". Every sheet/rotor round trip silently lost tracking.
 *
 * Kept outside the composable so a test can pin the order down.
 */
object RetourArriere {

    /** What back closes. [RIEN] lets the system leave the app. */
    enum class Geste {
        RIEN,
        SECTION_REGLAGES,
        FERMER_REGLAGES,
        FERMER_LOCATOR,
        FERMER_GLOBE,
        FERMER_SKED,
        FERMER_TIMELINE,
        FERMER_PHOTO,
        FERMER_ACTIVATION,
        FERMER_SSTV,
        FERMER_APRS,
        FERMER_SDR,
        FERMER_APT,
        FERMER_SONDE,
        FERMER_ROTOR,
        FERMER_QO100,
        FERMER_NOMMAGE,
        FERMER_AGENDA,
        FERMER_FT8,
        RETOUR_LISTE,
        QUITTER_SELECTION,
    }

    /**
     * @param sectionReglages true when a settings sub-section is open: back
     *                        closes it before the settings themselves.
     * @param selection       true when a satellite sheet is open.
     * @param modeSelection   true in list multi-selection mode.
     */
    fun geste(
        ecran: Screen,
        sectionReglages: Boolean,
        selection: Boolean,
        modeSelection: Boolean
    ): Geste = when {
        // Full-screen pages first: they are the ones drawn on top.
        ecran == Screen.SETTINGS && sectionReglages -> Geste.SECTION_REGLAGES
        ecran == Screen.SETTINGS -> Geste.FERMER_REGLAGES
        ecran == Screen.FT8 -> Geste.FERMER_FT8
        ecran == Screen.GLOBE -> Geste.FERMER_GLOBE
        ecran == Screen.LOCATOR -> Geste.FERMER_LOCATOR
        ecran == Screen.SKED -> Geste.FERMER_SKED
        ecran == Screen.TIMELINE -> Geste.FERMER_TIMELINE
        ecran == Screen.PHOTO -> Geste.FERMER_PHOTO
        ecran == Screen.ACTIVATION -> Geste.FERMER_ACTIVATION
        ecran == Screen.SSTV -> Geste.FERMER_SSTV
        ecran == Screen.APRS -> Geste.FERMER_APRS
        ecran == Screen.SDR -> Geste.FERMER_SDR
        ecran == Screen.APT -> Geste.FERMER_APT
        ecran == Screen.SONDE -> Geste.FERMER_SONDE
        ecran == Screen.ROTOR -> Geste.FERMER_ROTOR
        ecran == Screen.QO100 -> Geste.FERMER_QO100
        ecran == Screen.NOMMAGE -> Geste.FERMER_NOMMAGE
        ecran == Screen.AGENDA -> Geste.FERMER_AGENDA
        // Only then the sheet underneath.
        selection -> Geste.RETOUR_LISTE
        modeSelection -> Geste.QUITTER_SELECTION
        else -> Geste.RIEN
    }

    /** True when back has something to close inside the app. */
    fun intercepte(
        ecran: Screen,
        sectionReglages: Boolean,
        selection: Boolean,
        modeSelection: Boolean
    ): Boolean = geste(ecran, sectionReglages, selection, modeSelection) != Geste.RIEN
}
