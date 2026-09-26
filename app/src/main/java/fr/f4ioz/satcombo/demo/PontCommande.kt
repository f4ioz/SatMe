/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

/**
 * What the control desk is allowed to do.
 *
 * **Why a bridge rather than a direct call.** The server does not know about
 * the ViewModel, and must not: it serves bytes over a socket, and has no
 * business with the lifecycle of an Android screen. The ViewModel places here
 * the few gestures it permits, and the server can call nothing else.
 *
 * This is also the plain list of what a PC can do: a few lines to re-read in
 * order to know exactly what is exposed to the local network. A surface you
 * can read at a glance is a surface you can defend.
 */
object PontCommande {

    /** Adds a contact to the log. True when the callsign was accepted. */
    @Volatile var ajouteQso: ((call: String, locator: String,
                               rstEnvoye: String, rstRecu: String) -> Boolean)? = null

    /** Starts or stops recording the pass. */
    @Volatile var enregistre: ((Boolean) -> Unit)? = null

    /** Selects the tracked satellite by name. False when the name is unknown. */
    @Volatile var choisitSatellite: ((String) -> Boolean)? = null

    /** The tracked satellites, in the order the operator arranged them. */
    @Volatile var satellites: (() -> List<String>)? = null

    /**
     * One suggestion from the callsign keypad: what the phone already shows
     * under the input field, handed to the PC unchanged.
     */
    class Proposition(
        val indicatif: String,
        val locator: String,
        val nom: String,
        val contacts: Int,
    )

    /**
     * Suggestions for the text being typed.
     *
     * The ranking is not rewritten here: it is `Indicatifs.suggestions()`, the
     * very one that feeds the phone keypad. Two rankings for the same question
     * would end up suggesting different things depending on which screen you
     * look at — and the operator would no longer know which to trust.
     */
    @Volatile var propose: ((String) -> List<Proposition>)? = null

    /** A QRZ record, cut down to what is useful during a pass. */
    class Fiche(
        val indicatif: String, val carre: String,
        val nom: String, val prenom: String,
        val qth: String, val pays: String, val erreur: String,
    )

    /**
     * Looks a callsign up on QRZ.
     *
     * **Called on demand, never on every keystroke.** A QRZ subscription caps
     * the number of queries, and asking on each letter would burn the quota in
     * a single pass. The operator decides when to look.
     */
    @Volatile var chercheQrz: ((String) -> Fiche)? = null

    /**
     * Is the bridge up?
     *
     * When the ViewModel has placed nothing — which happens while the app
     * starts — the server answers that the control desk is not ready, rather
     * than leaving a button with no effect.
     */
    val pret: Boolean
        get() = ajouteQso != null && enregistre != null && choisitSatellite != null
}
