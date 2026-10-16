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

    // ------------------------------------------------------------------ look-alike signs

    private val f4ioz = IndicatifsImage.Connus(service = "F4IOZ")

    @Test fun `FAIOZ avec le service F4IOZ devient F4IOZ tout de suite`() {
        val l = IndicatifsImage.lis("SM A FAIOZ JN18FS DATV 1280X720", f4ioz).single()
        assertEquals("F4IOZ", l.indicatif); assertEquals("FAIOZ", l.mot)
        assertEquals(IndicatifsImage.Garant.SERVICE, l.garant); assertEquals(1, l.corrections)
        // Shown from the first read.
        val v = VoteIndicatifs()
        val lus = IndicatifsImage.lis("FAIOZ JN18FS", f4ioz)
        assertEquals(listOf("F4IOZ"), v.lecture(lus.map { it.indicatif }, 1000, lus.filter { it.garant != null }.map { it.indicatif }))
        assertEquals("F4IOZ JN18FS", IndicatifsImage.corrige("FAIOZ JN18FS", lus))
        // Two signs swapped, a /P kept, any voucher: the strongest is named.
        assertEquals("F4IOZ/P", IndicatifsImage.lis("FAI0Z/P", f4ioz).single().indicatif)
        val tous = IndicatifsImage.Connus(carte = "EA4ABC", carnet = listOf("G4XYZ", "F4IOZ"), moi = "F4IOZ")
        assertEquals(IndicatifsImage.Garant.CARTE, IndicatifsImage.lis("EAAABC", tous).single().garant)
        assertEquals(IndicatifsImage.Garant.CARNET, IndicatifsImage.lis("G4XY2", tous).single().garant)
        assertEquals(IndicatifsImage.Garant.CARNET, IndicatifsImage.lis("FAIOZ", tous).single().garant)
        assertEquals(IndicatifsImage.Garant.MOI, IndicatifsImage.lis("FAIOZ", IndicatifsImage.Connus(moi = "F4IOZ")).single().garant)
        // Read right, vouched for too.
        assertEquals(IndicatifsImage.Garant.SERVICE, IndicatifsImage.lis("F4IOZ", f4ioz).single().garant)
        // Not look-alikes, or too many: nothing vouched.
        assertTrue(IndicatifsImage.lis("FXIOZ", f4ioz).isEmpty())
        assertNull(IndicatifsImage.lis("FA1O2", f4ioz).firstOrNull { it.garant != null })
    }

    @Test fun `FAIOZ seul devient F4IOZ par le vote`() {
        val l = IndicatifsImage.lis("FAIOZ JN18FS").single()
        assertEquals("F4IOZ", l.indicatif); assertNull(l.garant)
        val v = VoteIndicatifs()
        assertTrue(v.lecture(listOf(l.indicatif), 1000).isEmpty())
        assertEquals(listOf("F4IOZ"), v.lecture(IndicatifsImage.candidats("FAIOZ"), 4000))
        // The other look-alikes the same way.
        assertEquals(listOf("EA4ABC"), IndicatifsImage.candidats("EAAABC"))
        assertEquals(listOf("SP5XYZ"), IndicatifsImage.candidats("SPSXYZ"))
        assertEquals(listOf("G4XYZ"), IndicatifsImage.candidats("GAXYZ"))
        assertEquals(listOf("F4IOZ"), IndicatifsImage.candidats("F4I0Z"))
        assertEquals(listOf("G4XYZ/P"), IndicatifsImage.candidats("GAXYZ/P"))
        // Not in a sentence, nor written in small letters.
        assertTrue(IndicatifsImage.lis("CQ CQ FROM THE GAXYZ STATION").isEmpty())
        assertTrue(IndicatifsImage.lis("Kayak Gaxyz").isEmpty())
        assertEquals(listOf("G4XYZ"), IndicatifsImage.candidats("Gaxyz", IndicatifsImage.Connus(carnet = listOf("G4XYZ"))))
    }

    @Test fun `un signe lu deux fois et le meme mot lu deux facons`() {
        // A sign read twice (both look-alikes come out), the 9 read Q.
        assertEquals(listOf("SP5XYZ"), IndicatifsImage.candidats("SPS5XYZ"))
        assertEquals(listOf("F4IOZ"), IndicatifsImage.candidats("F4AIOZ"))
        assertEquals(listOf("G4XYZ"), IndicatifsImage.candidats("GA4XYZ"))
        assertEquals(listOf("9A1AA"), IndicatifsImage.candidats("9QA1AA"))
        assertEquals(listOf("9A1AA"), IndicatifsImage.candidats("QA1AA"))
        assertEquals(IndicatifsImage.Garant.SERVICE, IndicatifsImage.lis("F4AIOZ", f4ioz).single().garant)
        // One read, several views of one line: the better reading only.
        assertEquals(listOf("F/G4ABC"), IndicatifsImage.candidats("F/G4ABC JN18FS\nFIG4ABC JN18FS\nF/GA4ABC JN18FS"))
        assertEquals(listOf("SP5XYZ"), IndicatifsImage.candidats("SP5XYZ JN18FS\nSPS5XYZ JN1I8FS"))
        // Real callsigns of the usual shape stay as read.
        for (c in listOf("KB1ABC", "SM0ABCD", "EA4ABC", "OH2XYZ", "VK2ABC", "9A1AA", "DL1ABC/P", "F/G4ABC", "G0ABC", "2E0ABC", "M0XYZ")) {
            assertEquals(c, listOf(c), IndicatifsImage.candidats(c))
        }
    }

    @Test fun `jamais un mot sans chiffre ni un mot courant`() {
        for (m in listOf("FAIOZ", "SATME", "DATV", "HELLO", "CAMERA", "VIDEO", "TEST", "STATION", "BONJOUR", "LIVE", "QO100", "DVBS2",
                "1500KS", "10GHZ", "2400MHZ", "H265", "AUDIO", "SIGNAL", "IMAGE", "STEREO", "MPEG", "PLUTO", "LONGMYND")) {
            assertNull(m, IndicatifsImage.plausible(m))
            if (m != "FAIOZ") assertTrue(m, IndicatifsImage.lis(m).isEmpty())
            assertTrue(m, IndicatifsImage.lis(m, f4ioz).all { it.indicatif == "F4IOZ" })
        }
        // FAIOZ itself is never shown.
        assertFalse(IndicatifsImage.candidats("FAIOZ", f4ioz).contains("FAIOZ"))
        assertFalse(IndicatifsImage.candidats("FAIOZ").contains("FAIOZ"))
    }

    @Test fun `les carres ne sont pas des indicatifs`() {
        for (m in listOf("JN18FS", "JN18", "IN97AB", "JNI8FS", "JNL8FS", "IO91WM", "JO20")) {
            assertTrue(m, IndicatifsImage.lis(m).isEmpty())
            assertTrue(m, IndicatifsImage.lis(m, f4ioz).isEmpty())
        }
        assertEquals("F4IOZ JN18FS", IndicatifsImage.corrige("FAIOZ JN18FS", IndicatifsImage.lis("FAIOZ JN18FS")))
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
