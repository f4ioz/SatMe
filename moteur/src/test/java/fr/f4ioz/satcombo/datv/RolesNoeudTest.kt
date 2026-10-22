/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesNoeudTest {
    @Test
    fun une_carte_un_role() {
        assertTrue(RolesNoeud.conflits("minitiouner", "bladerf", "pluto").isEmpty())
        assertTrue(RolesNoeud.conflits("minitiouner", "aucun", "aucun").isEmpty())
        // « aucun » twice is no sharing.
        assertTrue(RolesNoeud.conflits("minitiouner", "aucun", "").isEmpty())
    }

    @Test
    fun une_carte_partagee_se_dit() {
        val sdrTx = RolesNoeud.conflits("minitiouner", "bladerf", "bladerf")
        assertEquals(1, sdrTx.size); assertTrue(sdrTx[0], sdrTx[0].contains("bladeRF") && sdrTx[0].contains("émission"))
        val tout = RolesNoeud.conflits("pluto", "pluto", "pluto")
        assertEquals(3, tout.size)
        assertEquals(1, RolesNoeud.conflits("bladerf", "bladerf", "aucun").size)
    }

    @Test
    fun versions_comparees() {
        assertEquals(0, RolesNoeud.compareVersions("2026.10.24", "2026.10.24"))
        assertTrue(RolesNoeud.compareVersions("2026.10.9", "2026.10.24")!! < 0)
        assertTrue(RolesNoeud.compareVersions("2026.11.1", "2026.10.24")!! > 0)
        assertTrue(RolesNoeud.compareVersions("v2026.10.24", "2026.10.24.1")!! < 0)
        assertNull(RolesNoeud.compareVersions("dev", "2026.10.24"))
        assertNull(RolesNoeud.compareVersions("2026.10.24", ""))
    }
}
