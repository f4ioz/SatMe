/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsCarnetTest {

    private fun qso(call: String, loc: String, sat: String, mode: String, t: Long = 0L, moi: String = "") =
        LogEntry(timeMs = t, satName = sat, catnum = 0, azimuthDeg = 0.0, elevationDeg = 0.0, myLocator = moi, callsign = call, theirLocator = loc, mode = mode)

    @Test fun `records au total, par satellite, par mode, par continent`() {
        val l = listOf(qso("EA4ABC", "IN80", "RS-44", "LSB", 1), qso("4X1AB", "KM72", "RS-44", "USB", 2),
            qso("G0ABC", "IO91", "SO-50", "FM", 3), qso("CT1ABC", "IM58", "SO-50", "FM", 4), qso("F5XYZ", "", "AO-07", "CW", 5))
        val b = RecordsCarnet.calcule(l, "JN18FS")
        assertEquals("4X1AB", b.total!!.contact.callsign)
        assertTrue(b.total!!.km in 3000.0..3400.0)
        assertEquals("4X1AB", b.parSatellite["RS-44"]!!.contact.callsign)
        assertEquals("CT1ABC", b.parSatellite["SO-50"]!!.contact.callsign)
        assertNull(b.parSatellite["AO-07"])
        assertEquals("4X1AB", b.parMode["SSB"]!!.contact.callsign)
        assertEquals("AS", Dxcc.entite("4X1AB")!!.continent)
        assertEquals(setOf("EU", "AS"), b.continents)
        assertEquals("CT1ABC", b.parContinent["EU"]!!.contact.callsign)
        assertEquals(5, b.contacts); assertEquals(4, b.avecDistance)
        // Crossed 1 000 km first with EA4ABC (t = 1), 3 000 km with 4X1AB.
        assertEquals("EA4ABC", b.paliers.first { it.first == 1000 }.second.callsign)
        assertEquals("4X1AB", b.paliers.first { it.first == 3000 }.second.callsign)
    }

    @Test fun `continents de la table`() {
        assertEquals("SA", Dxcc.entite("PY2ABC")!!.continent)
        assertEquals("AF", Dxcc.entite("EA8XYZ")!!.continent)
        assertEquals("AS", Dxcc.entite("UA9ABC")!!.continent)
        assertEquals("EU", Dxcc.entite("UA3ABC")!!.continent)
        assertEquals("NA", Dxcc.entite("W1AW")!!.continent)
        assertEquals("OC", Dxcc.entite("VK2ABC")!!.continent)
    }
}
