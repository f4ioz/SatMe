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

/** Before a DATV start: the station identified, a level chosen. */
class DepartDatvTest {

    @Test
    fun indicatif_et_locator_obligatoires() {
        assertNull(DepartDatv.refusIdentification("F4IOZ", "JN18du"))
        assertNull(DepartDatv.refusIdentification(" f4ioz/p ", "jn18"))
        assertTrue(DepartDatv.refusIdentification("", "JN18")!!.startsWith("indicatif manquant"))
        assertTrue(DepartDatv.refusIdentification("F4IOZ", "")!!.startsWith("locator manquant"))
        assertTrue(DepartDatv.refusIdentification("F4IOZ", "  ")!!.startsWith("locator manquant"))
    }

    @Test
    fun pas_de_nocall_ni_de_valeur_d_origine() {
        for (c in listOf("NOCALL", "nocall", "N0CALL", "MYCALL", "F4IOZ/NOCALL", "N0CALL/P"))
            assertTrue(c, DepartDatv.refusIdentification(c, "JN18du")!!.contains("un exemple"))
        // No digit, a stray character: not a callsign.
        assertTrue(DepartDatv.refusIdentification("FOOBAR", "JN18")!!.contains("illisible"))
        assertTrue(DepartDatv.refusIdentification("F4 IOZ", "JN18")!!.contains("illisible"))
        assertTrue(DepartDatv.refusIdentification("44", "JN18")!!.contains("illisible"))
        // The locator: 4 or 6 characters of the grid, not the AA00 left by default.
        assertTrue(DepartDatv.refusIdentification("F4IOZ", "JN1")!!.contains("invalide"))
        assertTrue(DepartDatv.refusIdentification("F4IOZ", "ZZ99")!!.contains("invalide"))
        assertTrue(DepartDatv.refusIdentification("F4IOZ", "AA00aa")!!.contains("valeur d’origine"))
    }

    @Test
    fun niveau_zero_refuse_apres_l_identification() {
        assertEquals(DepartDatv.NIVEAU_ZERO, DepartDatv.refus("F4IOZ", "JN18du", niveauAZero = true))
        assertNull(DepartDatv.refus("F4IOZ", "JN18du", niveauAZero = false))
        // Identification said first: it is the one to fix in Réglages.
        assertTrue(DepartDatv.refus("NOCALL", "JN18du", niveauAZero = true)!!.contains("un exemple"))
    }
}
