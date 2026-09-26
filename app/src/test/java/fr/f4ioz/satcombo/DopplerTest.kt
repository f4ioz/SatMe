/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Doppler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DopplerTest {

    @Test fun `approaching satellite raises downlink, receding lowers it`() {
        val rest = 145_800_000L // ISS FM
        val approaching = Doppler.downlink(rest, -7.0)  // -7 km/s toward us
        val receding = Doppler.downlink(rest, +7.0)
        assertTrue(approaching > rest)
        assertTrue(receding < rest)
        // ~7 km/s on 2 m is about ±3.4 kHz.
        assertEquals(3404.0, (approaching - rest).toDouble(), 30.0)
    }

    @Test fun `uplink correction is inverse of downlink`() {
        val rest = 435_000_000L
        // When receding, you must transmit HIGHER so the sat hears the rest freq.
        assertTrue(Doppler.uplink(rest, +7.0) > rest)
        assertTrue(Doppler.uplink(rest, -7.0) < rest)
    }

    @Test fun `rest recovered from observed downlink`() {
        val rest = 145_825_000L
        val rate = -3.2
        val observed = Doppler.downlink(rest, rate)
        assertEquals(rest.toDouble(), Doppler.restFromDownlink(observed, rate).toDouble(), 1.0)
    }

    @Test fun `zero range-rate leaves frequency untouched`() {
        assertEquals(145_800_000L, Doppler.downlink(145_800_000L, 0.0))
        assertEquals(435_000_000L, Doppler.uplink(435_000_000L, 0.0))
    }

    @Test fun `inverting transponder maps low downlink to high uplink`() {
        // AO-73-style: DL 145.950-145.970, UL 435.130-435.150, inverting.
        val dlLow = 145_950_000L; val dlHigh = 145_970_000L
        val ulLow = 435_130_000L; val ulHigh = 435_150_000L
        assertEquals(ulHigh, Doppler.transponderUplinkRest(dlLow, dlLow, dlHigh, ulLow, ulHigh, true))
        assertEquals(ulLow, Doppler.transponderUplinkRest(dlHigh, dlLow, dlHigh, ulLow, ulHigh, true))
        // Mid maps to mid either way.
        val mid = Doppler.transponderUplinkRest((dlLow + dlHigh) / 2, dlLow, dlHigh, ulLow, ulHigh, true)
        assertEquals((ulLow + ulHigh) / 2, mid, 2)
    }

    private fun assertEquals(expected: Long, actual: Long, tol: Long) {
        assertTrue("expected $expected±$tol got $actual", Math.abs(expected - actual) <= tol)
    }
}
