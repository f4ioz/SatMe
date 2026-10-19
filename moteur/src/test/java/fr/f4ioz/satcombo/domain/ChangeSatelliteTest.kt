/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSatelliteTest {

    private val rs44 = LogEntry(timeMs = 1_760_000_000_000L, satName = "RS-44", catnum = 44909, azimuthDeg = 120.0, elevationDeg = 30.0,
        callsign = "F5ABC", mode = "USB", downlinkMhz = 435.640, uplinkMhz = 145.965)

    @Test
    fun rs44_vers_qo100_et_retour() {
        val q = ChangeSatellite.applique(rs44, "QO-100", 0, 160.0 to 32.0, null)
        assertEquals("QO-100", q.satName); assertEquals(Qo100.NORAD, q.catnum)
        assertEquals(10_489.75, q.downlinkMhz, 1e-6); assertEquals(2_400.25, q.uplinkMhz, 1e-6)
        assertEquals(160.0, q.azimuthDeg, 0.0)
        val a = Adif.export(listOf(q))
        assertTrue(a.contains("<SAT_NAME:6>QO-100")); assertTrue(a.contains("<PROP_MODE:3>SAT")); assertTrue(a.contains("<SAT_MODE:"))
        assertFalse(a.contains("RS-44"))

        val r = ChangeSatellite.applique(q, "RS-44", 44909, 100.0 to 20.0, 435_640_000L to 145_965_000L)
        assertEquals("RS-44", r.satName); assertEquals(44909, r.catnum)
        assertEquals(435.64, r.downlinkMhz, 1e-6); assertEquals(145.965, r.uplinkMhz, 1e-6)
        val b = Adif.export(listOf(r))
        assertTrue(b.contains("<SAT_NAME:5>RS-44")); assertFalse(b.contains("QO-100"))
        assertTrue(b.contains("<BAND_RX:4>70cm"))
    }

    @Test
    fun qo100_vers_un_satellite_sans_frequences_connues_les_oublie() {
        val q = ChangeSatellite.applique(rs44, "QO-100", 0, null, null)
        val x = ChangeSatellite.applique(q, "SO-50", 27607, null, null)
        assertEquals(0.0, x.downlinkMhz, 0.0); assertEquals(0.0, x.uplinkMhz, 0.0)
        // Between two LEO satellites, unknown frequencies stay (the phone's way).
        assertEquals(435.64, ChangeSatellite.applique(rs44, "SO-50", 27607, null, null).downlinkMhz, 1e-6)
    }

    @Test
    fun terrestre_et_nom_libre() {
        val t = ChangeSatellite.applique(rs44, "terrestre", 0, 1.0 to 2.0, null)
        assertEquals(Adif.DATV_TERRESTRE, t.satName); assertEquals(0, t.catnum)
        assertFalse(Adif.export(listOf(t)).contains("PROP_MODE"))
        val l = ChangeSatellite.applique(rs44, "XW-3", 0, null, null)
        assertEquals("XW-3", l.satName); assertEquals(0, l.catnum)
        assertTrue(Adif.export(listOf(l)).contains("<SAT_NAME:4>XW-3"))
    }

    @Test
    fun le_meme_satellite_ne_change_rien() {
        assertSame(rs44, ChangeSatellite.applique(rs44, "RS-44", 44909, 1.0 to 1.0, null))
    }
}
