/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NouveauPassageTest {

    private fun tx(desc: String, mode: String, dl: Long, dlHaut: Long?, ul: Long?, ulHaut: Long?, transpondeur: Boolean, inverse: Boolean) =
        Transmitter(description = desc, mode = mode, uplinkLowHz = ul, uplinkHighHz = ulHaut, downlinkLowHz = dl, downlinkHighHz = dlHaut,
            invert = inverse, alive = true, type = if (transpondeur) "Transponder" else "Transmitter")

    // RS-44 as SatNOGS gives it: a CW beacon and an inverting linear transponder.
    private val balise = tx("CW Beacon", "CW", 435_605_000L, null, null, null, transpondeur = false, inverse = false)
    private val lineaire = tx("Linear transponder", "SSB", 435_610_000L, 435_670_000L, 145_935_000L, 145_995_000L, transpondeur = true, inverse = true)

    @Test fun `le transpondeur du satellite quand rien d'autre ne le dit`() {
        val e = NouveauPassage.elementsDuSatellite(listOf(balise, lineaire), null, null)!!
        assertEquals("Linear transponder", e.emetteur)
        assertEquals("LSB", e.mode)
        assertEquals(435_640_000L, e.dlHz)
        assertEquals(145_965_000L, e.ulHz)
    }

    @Test fun `celui choisi la derniere fois, ou celui du poste`() {
        assertEquals("CW Beacon", NouveauPassage.elementsDuSatellite(listOf(balise, lineaire), "CW Beacon", null)!!.emetteur)
        assertEquals("Linear transponder", NouveauPassage.elementsDuSatellite(listOf(balise, lineaire), "CW Beacon", 435_650_000L)!!.emetteur)
        assertNull(NouveauPassage.elementsDuSatellite(emptyList(), null, null))
    }
}
