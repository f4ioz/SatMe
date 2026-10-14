/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cw

import fr.f4ioz.satcombo.cw.AnalyseCw.Genre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyseCwTest {

    private fun mot(l: List<AnalyseCw.Mot>, t: String) = l.first { it.texte == t }

    @Test fun `un echange ordinaire`() {
        val l = AnalyseCw.analyse("CQ CQ DE EA4ABC EA4ABC K F4IOZ DE EA4ABC UR 5NN IN80 BK TU 73")
        assertEquals(Genre.PROCEDURE, mot(l, "CQ").genre)
        assertEquals(Genre.CARRE, mot(l, "IN80").genre)
        assertEquals(Genre.REPORT, mot(l, "5NN").genre)
        val ea = mot(l, "EA4ABC")
        assertEquals(Genre.INDICATIF, ea.genre); assertTrue(ea.score >= 3); assertTrue(ea.coherent)
        // Once, but right before DE and a French prefix: coherent.
        assertTrue(mot(l, "F4IOZ").coherent)
    }

    @Test fun `une erreur de decodage ne passe pas pour un indicatif`() {
        val l = AnalyseCw.analyse("TTE5EA QQ9ZZZ DE")
        // QQ: no country; alone and not backed: doubtful.
        val q = mot(l, "QQ9ZZZ")
        assertEquals(Genre.INDICATIF, q.genre); assertFalse(q.coherent)
        assertFalse(mot(l, "TTE5EA").coherent)
    }

    @Test fun `un indicatif du carnet est sur, meme seul`() {
        val l = AnalyseCw.analyse("HW OH2XYZ", connus = setOf("OH2XYZ"))
        assertTrue(mot(l, "OH2XYZ").coherent)
        assertTrue(AnalyseCw.analyse("EA4ABC/P DE F4IOZ").first().coherent)
    }
}
