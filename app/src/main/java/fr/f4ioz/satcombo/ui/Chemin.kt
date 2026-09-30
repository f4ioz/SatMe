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
 * The way back: closing a page returns to the page it was opened from.
 *
 * Every close used to land on the pass list. Settings › Rotor › back left the
 * settings behind, and the Rotor gear opened the settings, whose back went to
 * the list: the Rotor itself was lost on the way.
 *
 * **Back retraces the steps.** The pass list is the root: leaving it starts a
 * new path. Kept out of `UiState` (255-register limit) and out of Compose, so
 * a test can walk it.
 */
class Chemin(private val max: Int = 8) {

    /** A page to come back to; [section] only for the settings. */
    data class Etape(val ecran: Screen, val section: String? = null, val directe: String? = null)

    private val pile = ArrayDeque<Etape>()

    /**
     * Section the settings were opened on, from a screen's gear. Back from it
     * leaves the settings at once: the menu behind was never seen.
     */
    var directe: String? = null
        private set

    /** Leaving [ecran] (showing [section] if settings) for [vers]. */
    fun va(ecran: Screen, section: String?, vers: Screen, versSection: String? = null) {
        if (vers == Screen.SETTINGS) {
            if (ecran == Screen.SETTINGS) return
        } else if (ecran == vers) return
        if (ecran == Screen.PASSES) pile.clear()
        else {
            val e = Etape(ecran, section.takeIf { ecran == Screen.SETTINGS }, directe)
            if (pile.lastOrNull() != e) pile.addLast(e)
            // A round trip repeated through gears must not pile up forever.
            while (pile.size > max) pile.removeFirst()
        }
        directe = if (vers == Screen.SETTINGS) versSection else null
    }

    /** Where closing the current page goes. */
    fun retour(): Etape {
        val e = pile.removeLastOrNull() ?: Etape(Screen.PASSES)
        directe = e.directe
        return e
    }

    /**
     * Back with [section] open in the settings: true when it closes the
     * settings (section reached from a gear), false when it returns to the menu.
     */
    fun fermeReglages(section: String?): Boolean = section != null && section == directe

    /** The operator picked a section by hand: the menu is now behind it. */
    fun choixSection() { directe = null }

    companion object {
        /**
         * Settings section a screen's gear opens, or null for the menu. Rotor
         * and QO-100 keep their settings on their own screen.
         */
        fun sectionDe(ecran: Screen): String? = when (ecran) {
            Screen.SSTV, Screen.APT, Screen.APRS -> "recordings"
            Screen.SDR -> "accord"
            Screen.SONDE -> "sondemire"
            Screen.SKED -> "skeds"
            else -> null
        }
    }
}
