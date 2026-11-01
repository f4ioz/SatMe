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

/** A POTA activation's ADIF: the park on each of its contacts. Fictitious contacts only. */
class ActivationAdifTest {

    private val t0 = 1_790_000_000_000L

    private fun qso(c: String, t: Long) = LogEntry(t, "SO-50", 27607, 100.0, 20.0, myLocator = "JN18FS",
        callsign = c, mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795)

    private val log = listOf(qso("F5ZZZ", t0 + 60_000), qso("F6AAA", t0 + 120_000), qso("F6BBB", t0 + 7_200_000))

    @Test
    fun `une activation POTA ecrit son parc sur ses contacts`() {
        val a = Activation(startMs = t0, endMs = t0 + 3_600_000, name = "Parc", locator = "JN18FS", potaRef = "FR-0012")
        val adif = ActivationStore.adifOf(a, log, "F4XYZ")
        assertEquals(2, Regex("<EOR>").findAll(adif).count())
        assertEquals(2, Regex("<MY_POTA_REF:7>FR-0012").findAll(adif).count())
        assertEquals(2, Regex("<MY_SIG:4>POTA").findAll(adif).count())
        assertEquals(2, Regex("<MY_SIG_INFO:7>FR-0012").findAll(adif).count())
        assertFalse(adif.contains("F6BBB"))
    }

    @Test
    fun `une activation sans parc ne change rien`() {
        val a = Activation(startMs = t0, endMs = t0 + 3_600_000, locator = "JN18FS")
        val adif = ActivationStore.adifOf(a, log, "F4XYZ")
        assertFalse(adif.contains("POTA"))
        assertTrue(adif.contains("F5ZZZ"))
        assertEquals(LogStore.toAdif(ActivationStore.qsosOf(a, log), "F4XYZ"), adif)
    }
}
