/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.location.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadTest {

    @Test fun `known locator JN18fs resolves near Paris south-east`() {
        val (lat, lon) = Maidenhead.toLatLon("JN18FS")!!
        assertEquals(48.77, lat, 0.05)
        assertEquals(2.46, lon, 0.05)
    }

    @Test fun `round-trip latlon to locator and back stays in the same subsquare`() {
        val cases = listOf(
            48.8049 to 2.4836,     // F6KMX
            -33.8688 to 151.2093,  // Sydney
            40.7128 to -74.0060,   // New York
            -0.5 to 0.5, 0.0 to 0.0
        )
        for ((lat, lon) in cases) {
            val loc = Maidenhead.fromLatLon(lat, lon)
            assertEquals(6, loc.length)
            val (lat2, lon2) = Maidenhead.toLatLon(loc)!!
            // Subsquare is 2.5' lat x 5' lon: centre must be within half a cell.
            assertEquals(lat, lat2, 1.0 / 24 / 2 + 1e-9)
            assertEquals(lon, lon2, 2.0 / 24 / 2 + 1e-9)
        }
    }

    @Test fun `four-char locator resolves to square centre`() {
        val (lat, lon) = Maidenhead.toLatLon("JN18")!!
        assertEquals(48.5, lat, 1e-9)
        assertEquals(3.0, lon, 1e-9)
    }

    @Test fun `invalid locators are rejected`() {
        assertNull(Maidenhead.toLatLon(""))
        assertNull(Maidenhead.toLatLon("J"))
        assertNull(Maidenhead.toLatLon("JN1"))
        assertNull(Maidenhead.toLatLon("ZZ99"))    // fields beyond R
        assertNull(Maidenhead.toLatLon("JN18F"))   // 5 chars
        assertNull(Maidenhead.toLatLon("JNAA"))    // letters where digits expected
        assertNotNull(Maidenhead.toLatLon("jn18fs")) // lowercase ok
    }

    @Test fun `bounds nest correctly`() {
        val sq = Maidenhead.bounds("JN18")!!
        val sub = Maidenhead.bounds("JN18FS")!!
        assertTrue(sub[0] >= sq[0] && sub[0] + sub[2] <= sq[0] + sq[2] + 1e-9)
        assertTrue(sub[1] >= sq[1] && sub[1] + sub[3] <= sq[1] + sq[3] + 1e-9)
        assertEquals(1.0 / 24, sub[2], 1e-9)
        assertEquals(2.0 / 24, sub[3], 1e-9)
    }
}
