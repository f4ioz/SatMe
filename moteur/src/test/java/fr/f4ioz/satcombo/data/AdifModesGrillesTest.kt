/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADIF modes as the 3.1.4 enumeration has them, and grid lines kept out of the GRIDSQUARE fields. Fictitious data. */
class AdifModesGrillesTest {

    @Test
    fun `FT8 est un mode a part entiere, FT4 un sous-mode de MFSK`() {
        assertEquals("FT8" to "", Adif.modeOf("FT8"))
        assertEquals("MFSK" to "FT4", Adif.modeOf("FT4"))
        assertEquals("MFSK" to "JS8", Adif.modeOf("JS8"))
        assertEquals("PSK" to "PSK31", Adif.modeOf("PSK31"))
        assertEquals("DIGITALVOICE" to "FREEDV", Adif.modeOf("FreeDV"))
        // "C4FM" holds "FM": it must not come out as plain FM.
        assertEquals("DIGITALVOICE" to "C4FM", Adif.modeOf("C4FM"))
        assertEquals("ATV" to "", Adif.modeOf("DATV"))
        assertEquals("SSTV" to "", Adif.modeOf("SSTV"))
        assertEquals("FM" to "", Adif.modeOf("FM"))
        assertEquals("SSB" to "USB", Adif.modeOf("USB"))
        assertEquals("PKT" to "", Adif.modeOf("APRS"))
    }

    @Test
    fun `un contact FT8 sort sans SUBMODE`() {
        val e = LogEntry(1_790_000_000_000L, "RS-44", 44909, 0.0, 10.0, callsign = "F5ZZZ", mode = "FT8",
            uplinkMhz = 145.966, downlinkMhz = 435.641)
        val out = Adif.export(listOf(e), "F4XXX")
        assertTrue(out, out.contains("<MODE:3>FT8"))
        assertFalse(out, out.contains("SUBMODE"))
    }

    @Test
    fun `les locators valides et les autres`() {
        listOf("JN", "JN18", "jn18fs", "JN18FS42").forEach { assertTrue(it, Adif.locatorValide(it)) }
        listOf("", "J", "JN1", "JN18F", "ZZ18", "JN18FS4", "JN06,JN16", "JN18YY").forEach { assertFalse(it, Adif.locatorValide(it)) }
        assertEquals(listOf("JN06", "JN16"), Adif.grilles("jn06, JN16fs, JN06"))
    }

    @Test
    fun `une ligne de carres dans mon locator part en MY_VUCC_GRIDS, jamais en MY_GRIDSQUARE`() {
        val e = LogEntry(1_790_000_000_000L, "SO-50", 27607, 0.0, 10.0, myLocator = "JN06,JN16", callsign = "ea4zzz",
            theirLocator = "IN80", mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795)
        val out = Adif.export(listOf(e), "F4XXX")
        assertFalse(out, out.contains("MY_GRIDSQUARE"))
        assertTrue(out, out.contains("<MY_VUCC_GRIDS:9>JN06,JN16"))
        // The callsign goes out in capitals.
        assertTrue(out, out.contains("<CALL:6>EA4ZZZ"))
        assertTrue(out, out.contains("<GRIDSQUARE:4>IN80"))
    }

    @Test
    fun `mes carres separes gardent mon locator, la ligne du correspondant part en VUCC_GRIDS`() {
        val e = LogEntry(1_790_000_000_000L, "SO-50", 27607, 0.0, 10.0, myLocator = "JN06XX", myGrids = "JN06,JN16",
            callsign = "F5ZZZ", theirLocator = "JN12,JN13,JN22,JN23", mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795)
        val out = Adif.export(listOf(e))
        assertTrue(out, out.contains("<MY_GRIDSQUARE:6>JN06XX"))
        assertTrue(out, out.contains("<MY_VUCC_GRIDS:9>JN06,JN16"))
        assertTrue(out, out.contains("<VUCC_GRIDS:19>JN12,JN13,JN22,JN23"))
        assertFalse(out, out.contains("<GRIDSQUARE:"))
    }

    @Test
    fun `un locator mal forme est omis plutot qu-exporte`() {
        val e = LogEntry(1_790_000_000_000L, "AO-91", 43017, 0.0, 10.0, myLocator = "JN18F", callsign = "F5ZZZ",
            theirLocator = "XX99", mode = "FM", uplinkMhz = 435.250, downlinkMhz = 145.960)
        val out = Adif.export(listOf(e))
        assertFalse(out, out.contains("GRIDSQUARE"))
    }

    @Test
    fun `a l-import, une ligne de carres en MY_GRIDSQUARE va aux grilles`() {
        val adif = "x<EOH>\n" +
            "<CALL:5>F5ZZZ<QSO_DATE:8>20261020<TIME_ON:6>101500<SAT_NAME:5>SO-50<PROP_MODE:3>SAT" +
            "<MY_GRIDSQUARE:9>JN06,JN16<GRIDSQUARE:9>IN80,IN90<MODE:2>FM<EOR>\n" +
            "<CALL:5>F6ZZZ<QSO_DATE:8>20261020<TIME_ON:6>102000<SAT_NAME:5>SO-50<PROP_MODE:3>SAT" +
            "<MY_GRIDSQUARE:6>JN06XX<MY_VUCC_GRIDS:9>JN06,JN16<GRIDSQUARE:4>JN03<MODE:2>FM<EOR>\n"
        val l = AdifImport.contactsEnLigne(adif)
        assertEquals("", l[0].monLocator)
        assertEquals("JN06,JN16", l[0].monGrilles)
        assertEquals("IN80,IN90", l[0].locator)
        assertEquals("JN06XX", l[1].monLocator)
        assertEquals("JN06,JN16", l[1].monGrilles)
        assertEquals("JN03", l[1].locator)
    }

    @Test
    fun `un profil de station a cheval sur deux carres remplit les grilles, pas le locator`() {
        val q = AdifImport.ContactEnLigne("F5ZZZ", 1L, "SO-50")
        val r = AdifImport.avecLieu(q, "jn06,jn16")
        assertEquals("", r.monLocator)
        assertEquals("JN06,JN16", r.monGrilles)
        assertEquals("JN18FS", AdifImport.avecLieu(q, "jn18fs").monLocator)
        // Its own square wins.
        assertEquals("JN03AA", AdifImport.avecLieu(q.copy(monLocator = "JN03AA"), "JN06,JN16").monLocator)
    }
}
