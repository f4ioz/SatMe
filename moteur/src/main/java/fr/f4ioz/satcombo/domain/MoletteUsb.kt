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
 * A USB volume knob repurposed as a VFO control.
 *
 * These knobs (AIMOS AM-U001 and similar) are HID keyboards sending only three
 * keys: volume up, volume down, mute. Android treats them like the phone's
 * volume buttons. A foreground app can intercept those keys; the whole problem
 * is doing so only when appropriate.
 */
object MoletteUsb {

    /**
     * What the knob controls. With a small three-key pad, each key picks the
     * target and the knob stays the same — press a key then turn, like on a
     * radio, rather than hunting for a field on screen.
     */
    enum class Cible { VFO, SHIFT_RX, SHIFT_TX }

    /**
     * What pressing the knob does. The push button is the only control usable
     * without letting go of the knob:
     *
     * - **PAS**: cycle 10, 100, 1000 Hz steps. Default, like a radio.
     * - **CIBLE**: cycle RX, TX, VFO — useful when the three keys do other things.
     * - **ZERO**: reset the current offset. What you want after manual Doppler
     *   tracking; digging through a menu mid-pass is not an option.
     */
    enum class Action { PAS, CIBLE, ZERO }

    /**
     * The three pad keys and what each selects.
     *
     * Codes are learned, not typed: nobody knows Android key codes by heart,
     * and a programmable pad can send almost anything. Zero means "not learned
     * yet".
     */
    data class Touches(
        val codeA: Int = 0, val cibleA: Cible = Cible.SHIFT_RX,
        val codeB: Int = 0, val cibleB: Cible = Cible.SHIFT_TX,
        val codeC: Int = 0, val cibleC: Cible = Cible.VFO,
        /**
         * The knob push button. Defaults to Mute, which most of these devices
         * send and which was the previous behaviour. Learning another code only
         * changes this value: there is still a single rule for the press.
         */
        val codeD: Int = VOLUME_MUTE, val actionD: Action = Action.PAS
    ) {
        /** Target for this code, or `null` if it is not one of the three. */
        fun cibleDe(code: Int): Cible? = when {
            code == 0 -> null                 // never: an unlearned key
            code == codeA -> cibleA           //        must trigger nothing
            code == codeB -> cibleB
            code == codeC -> cibleC
            else -> null
        }
    }

    /** What one knob event does. */
    sealed interface Geste {
        /** Move the VFO by this many Hz, signed. */
        data class Bouge(val deltaHz: Long) : Geste
        /** Change step. */
        data object ChangePas : Geste
        /** Go to the next target without letting go of the knob. */
        data object CibleSuivante : Geste
        /** Reset the current offset. */
        data object RemetZero : Geste
        /** Choose what the knob controls from now on. */
        data class ChoisitCible(val cible: Cible) : Geste
        /** Not ours: the system handles the key. */
        data object Ignore : Geste
    }

    /** Available steps, in the order the press cycles them. */
    val PAS = listOf(10L, 100L, 1_000L)

    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val VOLUME_MUTE = 164

    /**
     * What to do with this key.
     *
     * [externe] says the event comes from a plugged device rather than the
     * phone (Android gives the USB knob its own device id). **This guard is
     * what makes the feature acceptable**: without it, enabling the knob would
     * confiscate the phone's volume buttons and the operator could no longer
     * set the audio level.
     *
     * [actif] is the setting. When off, everything goes to the system: someone
     * without a knob must lose nothing.
     */
    fun geste(
        codeTouche: Int,
        actif: Boolean,
        externe: Boolean,
        pasHz: Long,
        // Default is a real Touches, not `null`, so the knob press keeps its
        // meaning even without settings, under the same single rule.
        touches: Touches? = Touches()
    ): Geste {
        if (!actif || !externe) return Geste.Ignore
        // Pad keys first: if the operator learned "volume up" on a key, they
        // want it to pick a target, not turn the VFO.
        touches?.cibleDe(codeTouche)?.let { return Geste.ChoisitCible(it) }
        // Then the knob press, before detents, for the same reason.
        if (touches != null && touches.codeD != 0 && codeTouche == touches.codeD) {
            return when (touches.actionD) {
                Action.PAS -> Geste.ChangePas
                Action.CIBLE -> Geste.CibleSuivante
                Action.ZERO -> Geste.RemetZero
            }
        }
        return when (codeTouche) {
            VOLUME_UP -> Geste.Bouge(pasHz)
            VOLUME_DOWN -> Geste.Bouge(-pasHz)
            else -> Geste.Ignore
        }
    }

    /**
     * Can this key be learned?
     *
     * Back, Home and App switch are refused: confiscating them would lock the
     * operator inside the app. Everything else is allowed, volume keys included
     * — the common case, since a macro pad often has nothing else to offer.
     */
    fun apprenable(codeTouche: Int): Boolean =
        codeTouche != 0 && codeTouche != 4 && codeTouche != 3 && codeTouche != 187

    /** Next target, cycling: RX, TX, VFO. */
    fun cibleSuivante(nom: String): String {
        val ordre = listOf(Cible.SHIFT_RX, Cible.SHIFT_TX, Cible.VFO)
        val i = ordre.indexOfFirst { it.name == nom }
        return if (i < 0) ordre.first().name else ordre[(i + 1) % ordre.size].name
    }

    /**
     * Next step, cycling. An unknown value gives the first step: a damaged
     * setting must not freeze the knob on a step it cannot leave.
     */
    fun pasSuivant(pasHz: Long): Long {
        val i = PAS.indexOf(pasHz)
        return if (i < 0) PAS.first() else PAS[(i + 1) % PAS.size]
    }
}
