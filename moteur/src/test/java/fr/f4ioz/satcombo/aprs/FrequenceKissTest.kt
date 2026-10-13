/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrequenceKissTest {
    @Test fun `terre, ISS, automatique`() {
        assertEquals(144_800_000L, FrequenceKiss.voulue("TERRE", true, true, 30.0, -6.0))
        assertEquals(145_825_000L, FrequenceKiss.voulue("ISS", false, false, null, null))
        assertEquals(144_800_000L, FrequenceKiss.voulue("AUTO", false, false, null, null))
        assertEquals(145_825_000L, FrequenceKiss.voulue("AUTO", true, false, 20.0, -6.0))
        assertNull(FrequenceKiss.voulue("", true, true, 20.0, -6.0))
    }

    @Test fun `Doppler par pas de 5 kHz, nul l'ISS couchee`() {
        // Coming closer (−6 km/s): the frequency heard is higher.
        assertEquals(145_830_000L, FrequenceKiss.voulue("ISS", true, true, 20.0, -6.0))
        assertEquals(145_820_000L, FrequenceKiss.voulue("ISS", true, true, 20.0, 6.0))
        assertEquals(145_825_000L, FrequenceKiss.voulue("ISS", true, true, -3.0, -6.0))
    }
}
