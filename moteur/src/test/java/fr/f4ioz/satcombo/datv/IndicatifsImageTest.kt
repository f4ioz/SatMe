/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.phonie.SourcePhonie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Callsigns read in a DATV picture: what passes, the vote, the band; the phonie's source. */
class IndicatifsImageTest {

    @Test fun `les indicatifs plausibles seulement`() {
        val t = "EA4ABC  QO-100 10491.500 1500KS DVB-S2 H265\nJN18AB 2400MHZ 10GHZ MER 9.5 F4IOZ/P CQ DE SP5XYZ"
        assertEquals(listOf("EA4ABC", "F4IOZ/P", "SP5XYZ"), IndicatifsImage.candidats(t))
    }

    @Test fun `ni carre ni frequence ni bruit`() {
        assertNull(IndicatifsImage.plausible("JN18"))
        assertNull(IndicatifsImage.plausible("IN97AB"))
        assertNull(IndicatifsImage.plausible("2400MHZ"))
        assertNull(IndicatifsImage.plausible("10GHZ"))
        assertNull(IndicatifsImage.plausible("H264"))
        assertNull(IndicatifsImage.plausible("ABCDEF"))
        assertEquals("G4ABC", IndicatifsImage.plausible("g4abc"))
        assertTrue(IndicatifsImage.candidats("").isEmpty())
    }

    @Test fun `deux lectures de suite pour montrer`() {
        val v = VoteIndicatifs()
        assertTrue(v.lecture(listOf("EA4ABC", "G4XYZ"), 1000).isEmpty())
        assertTrue(v.confirmes().isEmpty())
        assertEquals(listOf("EA4ABC"), v.lecture(listOf("EA4ABC"), 4000))
        assertEquals("EA4ABC", v.confirmes().single().indicatif)
        // G4XYZ missed a read: its count starts again.
        assertTrue(v.lecture(listOf("G4XYZ"), 7000).isEmpty())
        assertEquals(listOf("G4XYZ"), v.lecture(listOf("G4XYZ", "EA4ABC"), 10000))
        assertEquals(listOf("EA4ABC"), v.lecture(listOf("EA4ABC"), 16000))
        // An empty read (another picture) starts every count again.
        assertTrue(v.lecture(emptyList(), 17000).isEmpty())
        assertTrue(v.lecture(listOf("EA4ABC"), 18000).isEmpty())
        val c = v.confirmes()
        assertEquals(listOf("EA4ABC", "G4XYZ"), c.map { it.indicatif })
        assertEquals(4000L, c[0].premierMs); assertEquals(16000L, c[0].dernierMs)
        v.oublie(); assertTrue(v.confirmes().isEmpty())
    }

    @Test fun `accord avec le nom du service`() {
        assertTrue(IndicatifsImage.accord("EA4ABC", "EA4ABC"))
        assertTrue(IndicatifsImage.accord("EA4ABC/P", "ea4abc-tv"))
        assertFalse(IndicatifsImage.accord("EA4ABC", "SatMe"))
        assertFalse(IndicatifsImage.accord("EA4ABC", ""))
    }

    @Test fun `qo100 ou terrestre`() {
        assertTrue(IndicatifsImage.qo100(10_491_500))
        assertTrue(IndicatifsImage.qo100(10_497_750))
        assertFalse(IndicatifsImage.qo100(1_255_000))
        assertFalse(IndicatifsImage.qo100(437_000))
        assertEquals(2_402_000L, IndicatifsImage.monteeKhz(10_491_500))
    }

    @Test fun `une seule source pour la phonie`() {
        // DATV subtitles on: only them, whatever the choice.
        for (choix in listOf("auto", "carte", "sdr")) {
            assertTrue(SourcePhonie.admise("datv", choix, datv = true, sdrRecent = false))
            assertFalse(SourcePhonie.admise("carte", choix, datv = true, sdrRecent = false))
            assertFalse(SourcePhonie.admise("sdr", choix, datv = true, sdrRecent = true))
        }
        // Off: the choice as before, never the DATV sound.
        assertFalse(SourcePhonie.admise("datv", "auto", datv = false, sdrRecent = false))
        assertTrue(SourcePhonie.admise("carte", "auto", datv = false, sdrRecent = false))
        assertFalse(SourcePhonie.admise("carte", "auto", datv = false, sdrRecent = true))
        assertTrue(SourcePhonie.admise("sdr", "auto", datv = false, sdrRecent = true))
        assertFalse(SourcePhonie.admise("sdr", "carte", datv = false, sdrRecent = true))
        assertFalse(SourcePhonie.admise("carte", "sdr", datv = false, sdrRecent = false))
    }
}
