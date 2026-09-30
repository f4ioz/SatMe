/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.AmsatReport
import fr.f4ioz.satcombo.data.AmsatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Satellite names matched to AMSAT reports: same answers, fast enough for SatNOGS's 1700. */
class AmsatMatchTest {

    private fun r(nom: String) = AmsatReport(nom, "V/u", AmsatStatus.ACTIVE, 1)
    private val rapports = listOf("AO-7", "RS-44", "ISS", "SO-50", "IO-117", "AO-91")
        .associateBy { it.uppercase() }.mapValues { r(it.key) }

    @Test
    fun memes_correspondances() {
        assertEquals("RS-44", amsatMatch("RS-44", rapports)?.name)          // exact
        assertEquals("AO-7", amsatMatch("AO-07", rapports)?.name)           // leading zero
        assertEquals("AO-91", amsatMatch("AO-91 (FOX-1B)", rapports)?.name) // suffix
        assertEquals("ISS", amsatMatch("ISS (ZARYA)", rapports)?.name)
        assertNull(amsatMatch("STARLINK-1234", rapports))
        assertNull(amsatMatch("RS-44", emptyMap()))
    }

    @Test
    fun rapide_sur_1700_satellites() {
        val noms = (1..1700).map { "SAT-$it" } + listOf("AO-07", "ISS (ZARYA)")
        amsatMatch("X", rapports)  // index built once
        val debut = System.nanoTime()
        repeat(5) { noms.forEach { amsatMatch(it, rapports) } }
        val ms = (System.nanoTime() - debut) / 1_000_000
        assertTrue("5 × 1700 correspondances en $ms ms", ms < 1000)
    }
}
