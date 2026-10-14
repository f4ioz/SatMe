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
import fr.f4ioz.satcombo.data.SkedAlert
import org.junit.Assert.assertEquals
import org.junit.Test

class SkedsPassageTest {

    private val m = 60_000L
    private val aos = 1_000 * m
    private val los = aos + 15 * m

    private fun sked(call: String, n: Int, de: Long, a: Long, wde: Long? = null, wa: Long? = null) =
        SkedAlert(call, n, "RS-44", "SSB", de, a, listOf("IN80"), null, null, wde, wa)

    @Test fun `les skeds du passage, et pas ceux du passage suivant ni d'un autre satellite`() {
        val l = listOf(sked("EA4A", 44909, aos + 2 * m, aos + 10 * m), sked("EA4B", 44909, aos + 100 * m, aos + 110 * m),
            sked("EA4C", 7530, aos, los), sked("EA4D", 44909, aos - 30 * m, aos + 40 * m, wde = aos + m, wa = aos + 5 * m))
        assertEquals(listOf("EA4D", "EA4A"), SkedsPassage.duPassage(l, 44909, aos, los).map { it.callsign })
    }

    private fun qso(call: String, t: Long, n: Int = 44909, sat: String = "RS-44") =
        LogEntry(timeMs = t, satName = sat, catnum = n, azimuthDeg = 0.0, elevationDeg = 0.0, callsign = call)

    @Test fun `les contacts du passage, notes un peu apres le LOS compris`() {
        val l = listOf(qso("F5RRO", aos + 3 * m), qso("G0ABC", los + 10 * m), qso("TROPTOT", aos - 20 * m),
            qso("AUTRE", aos + 3 * m, 7530, "AO-07"), qso("PARNOM", aos + 4 * m, 0, "RS 44"))
        assertEquals(listOf("F5RRO", "PARNOM", "G0ABC"), SkedsPassage.contacts(l, 44909, "RS-44", aos, los).map { it.callsign })
    }
}
