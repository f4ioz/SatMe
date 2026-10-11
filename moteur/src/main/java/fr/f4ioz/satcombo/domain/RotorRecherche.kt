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
 * Which serial adapters the rotor's automatic search may open.
 *
 * **Opening a port raises RTS and DTR** (a GS-232 Arduino needs it). On a rig
 * interface those lines key the transmitter: on 07/10 the search opened a
 * RigExpert Tiny's second port and put a TS-2000 on the air, no antenna on.
 * So the search leaves alone every adapter known to be a rig's, and every
 * adapter whose name says it is a rig interface — unless it is the very one
 * the rotor answered on before.
 */
object RotorRecherche {

    /** Names of USB rig interfaces and rigs (product strings, any case). */
    val INTERFACES_POSTE = listOf("RigExpert", "Tiny", "Digirig", "SignaLink", "Signalink", "USB Interface",
        "IC-9700", "IC-705", "IC-7300", "IC-9100", "TS-2000", "TS-590", "FT-991", "FT-710")

    /** A product name that says "rig interface". */
    fun interfacePoste(produit: String?): Boolean =
        produit != null && INTERFACES_POSTE.any { produit.contains(it, ignoreCase = true) }

    /**
     * May the search open an adapter with key [cle] and name [produit]?
     * [clesPostes]: the adapters the rig(s) use; [cleRotor]: the one the rotor
     * answered on last time ("" when none yet).
     */
    fun permis(cle: String?, produit: String?, clesPostes: Collection<String>, cleRotor: String): Boolean {
        if (cle != null && cleRotor.isNotBlank() && cle == cleRotor) return true
        if (cle != null && cle in clesPostes) return false
        return !interfacePoste(produit)
    }

    /**
     * Should plugging in this adapter start the rotor by itself? Only the
     * adapter it answered on before: any other may be a rig's.
     */
    fun auBranchement(cle: String?, cleRotor: String): Boolean =
        cle != null && cleRotor.isNotBlank() && cle == cleRotor
}
