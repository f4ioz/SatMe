/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.location.Maidenhead

/**
 * What a DATV start needs before [ReglesEmission] is even asked, the page's
 * « Démarrer » greyed out the same way:
 *
 * - **the station identified**: a callsign (not a placeholder such as
 *   NOCALL) and a locator (4 or 6 characters, not the AA00 left by default),
 *   both from Réglages › Station — they go into the service's name, the
 *   test cards and the station card;
 * - **a level chosen**: the level starts at 0 when SatMe-PC starts and goes
 *   back to 0 after every stop (unless the operator keeps it), and nothing
 *   starts at 0. A level is a gesture of the operator's, never a leftover.
 */
object DepartDatv {
    /** Callsigns left in by default or as an example: never an identification. */
    val INDICATIFS_FICTIFS = setOf("NOCALL", "N0CALL", "MYCALL", "M0CALL", "CALLSIGN", "TEST", "N0NE", "XX0XX", "AA0AA")

    /** Locators left in by default: valid squares, but nobody's. */
    private val LOCATORS_FICTIFS = setOf("AA00", "AA00AA")

    const val NIVEAU_ZERO = "niveau à 0 : monter le niveau d’émission avant de démarrer (il revient à 0 après chaque arrêt)"

    /** Why [indicatif] and [locator] do not identify the station, or null. */
    fun refusIdentification(indicatif: String, locator: String): String? {
        val ind = indicatif.trim().uppercase()
        val loc = locator.trim().uppercase()
        return when {
            ind.isEmpty() -> "indicatif manquant : le donner dans Réglages › Station avant toute émission"
            ind in INDICATIFS_FICTIFS || ind.split('/').any { it in INDICATIFS_FICTIFS } ->
                "indicatif « $ind » : un exemple, pas le vôtre — le donner dans Réglages › Station"
            ind.length < 3 || !ind.any { it.isDigit() } || !ind.any { it.isLetter() } || !ind.all { it.isLetterOrDigit() || it == '/' } ->
                "indicatif « $ind » illisible : le corriger dans Réglages › Station"
            loc.isEmpty() -> "locator manquant : le donner dans Réglages › Station avant toute émission"
            Maidenhead.toLatLon(loc) == null -> "locator « $loc » invalide (4 ou 6 caractères, JN18 ou JN18du) : Réglages › Station"
            loc in LOCATORS_FICTIFS -> "locator « $loc » : la valeur d’origine, pas le vôtre — Réglages › Station"
            else -> null
        }
    }

    /** Why a start is refused before the transmission's own rules, or null. */
    fun refus(indicatif: String, locator: String, niveauAZero: Boolean): String? =
        refusIdentification(indicatif, locator) ?: if (niveauAZero) NIVEAU_ZERO else null
}
