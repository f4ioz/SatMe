/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.RotorRecherche
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rotor's search never opens a rig interface: its RTS/DTR may key the transmitter. */
class RotorRechercheTest {

    @Test
    fun jamais_une_interface_de_poste() {
        // The 07/10 incident: the Tiny's second port opened, the TS-2000 on the air.
        assertFalse(RotorRecherche.permis("0403:6010@1-2", "RigExpert Tiny", emptyList(), ""))
        assertFalse(RotorRecherche.permis("x", "Digirig Mobile", emptyList(), ""))
        assertFalse(RotorRecherche.permis("x", "IC-9700", emptyList(), ""))
        // A rig's adapter, whatever its name.
        assertFalse(RotorRecherche.permis("FT817RX", "FT232R USB UART", listOf("FT817RX"), ""))
        // A plain bridge may be the rotor.
        assertTrue(RotorRecherche.permis("1a86:7523@1-3", "USB Serial", listOf("FT817RX"), ""))
        // The adapter it answered on before stays allowed, whatever its name.
        assertTrue(RotorRecherche.permis("rot", "USB Interface", emptyList(), "rot"))
    }

    @Test
    fun au_branchement_seulement_l_adaptateur_du_rotor() {
        assertFalse(RotorRecherche.auBranchement("0403:6010@1-2", ""))
        assertFalse(RotorRecherche.auBranchement("0403:6010@1-2", "rot"))
        assertTrue(RotorRecherche.auBranchement("rot", "rot"))
    }
}
