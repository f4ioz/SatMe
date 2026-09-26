/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Sonde-hunting geometry.
 *
 * This is where a mistake costs most: one sign error on an axis and the chaser
 * drives off to the next county. Every conversion is checked both ways against
 * independently computed values.
 */
class GeoTest {

    @Test
    fun `ECEF et retour rendent la position de depart`() {
        // Author's home, the equator, the southern hemisphere, and near the
        // date line.
        val cases = listOf(
            Triple(48.44425, -4.41238, 95.0),
            Triple(0.0, 0.0, 0.0),
            Triple(-33.9, 151.2, 12_000.0),
            Triple(60.1, 179.9, 31_500.0))
        for ((lat, lon, alt) in cases) {
            val e = SondeTestFrames.geodeticToEcef(lat, lon, alt)
            val f = Geo.ecefToGeodetic(e[0], e[1], e[2])
            assertEquals(lat, f.lat, 1e-7)
            assertEquals(lon, f.lon, 1e-7)
            assertEquals(alt, f.altM, 1e-3)
        }
    }

    @Test
    fun `le pole ne divise pas par zero`() {
        val f = Geo.ecefToGeodetic(0.0, 0.0, Geo.B + 100.0)
        assertEquals(90.0, f.lat, 1e-9)
        assertEquals(100.0, f.altM, 1e-6)
        val s = Geo.ecefToGeodetic(0.0, 0.0, -(Geo.B + 100.0))
        assertEquals(-90.0, s.lat, 1e-9)
    }

    @Test
    fun `la rotation des vitesses est celle du lieu`() {
        // At lat 0, lon 0 the rotation is trivial: local east is ECEF Y, north
        // is Z, up is X.
        val v = Geo.ecefVelToEnu(0.0, 0.0, 1.0, 2.0, 3.0)
        assertEquals(2.0, v.east, 1e-12)
        assertEquals(3.0, v.north, 1e-12)
        assertEquals(1.0, v.up, 1e-12)
    }

    @Test
    fun `vitesse ENU aller-retour`() {
        val lat = 48.5; val lon = -4.0
        val ecef = SondeTestFrames.enuToEcefVel(lat, lon, 12.0, -5.0, 4.5)
        val enu = Geo.ecefVelToEnu(lat, lon, ecef[0], ecef[1], ecef[2])
        assertEquals(12.0, enu.east, 1e-9)
        assertEquals(-5.0, enu.north, 1e-9)
        assertEquals(4.5, enu.up, 1e-9)
    }

    @Test
    fun `vitesse sol et cap`() {
        assertEquals(5.0, Geo.Enu(3.0, 4.0, 99.0).groundMps, 1e-12)
        assertEquals(90.0, Geo.Enu(10.0, 0.0, 0.0).headingDeg, 1e-9)
        assertEquals(0.0, Geo.Enu(0.0, 10.0, 0.0).headingDeg, 1e-9)
        assertEquals(180.0, Geo.Enu(0.0, -10.0, 0.0).headingDeg, 1e-9)
        assertEquals(270.0, Geo.Enu(-10.0, 0.0, 0.0).headingDeg, 1e-9)
        // Stationary sonde: no invented heading.
        assertEquals(0.0, Geo.Enu(0.0, 0.0, -5.0).headingDeg, 1e-12)
    }

    @Test
    fun `distance au sol`() {
        // One degree of longitude at the equator = mean radius × π / 180.
        val d = Geo.distanceKm(0.0, 0.0, 0.0, 1.0)
        assertEquals(Geo.EARTH_KM * Math.PI / 180.0, d, 1e-6)
        assertEquals(0.0, Geo.distanceKm(48.4, -4.4, 48.4, -4.4), 1e-12)
        // Brest to Paris, about 500 km.
        val bp = Geo.distanceKm(48.4, -4.5, 48.85, 2.35)
        assertTrue("Brest-Paris = $bp", bp > 495.0 && bp < 515.0)
    }

    @Test
    fun `azimut initial`() {
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(270.0, Geo.bearingDeg(0.0, 0.0, 0.0, -1.0), 1e-9)
        // Never a negative value to display.
        for (lon in -180..180 step 7) {
            val b = Geo.bearingDeg(48.0, 0.0, 48.0, lon.toDouble())
            assertTrue("azimut $b", b >= 0.0 && b < 360.0)
        }
    }

    @Test
    fun `rose des vents francaise`() {
        assertEquals("N", Geo.compass(0.0))
        assertEquals("N", Geo.compass(11.2))
        assertEquals("NNE", Geo.compass(11.3))
        assertEquals("E", Geo.compass(90.0))
        assertEquals("S", Geo.compass(180.0))
        assertEquals("O", Geo.compass(270.0))
        assertEquals("NNO", Geo.compass(340.0))
        assertEquals("N", Geo.compass(355.0))
        // Out-of-range values must not index outside the table.
        assertEquals("N", Geo.compass(720.0))
        assertEquals("O", Geo.compass(-90.0))
    }

    @Test
    fun `temps GPS vers temps Unix`() {
        // Week 2300, 12 h into the week: the date must fall in 2024.
        val ms = Geo.gpsToUnixMs(2300, 43_200_000L)
        assertEquals(Geo.GPS_EPOCH_MS + 2300L * 604_800_000L + 43_200_000L - 18_000L, ms)
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("2024-02-04", f.format(java.util.Date(ms)))
    }

    @Test
    fun `le rattrapage des mille vingt-quatre semaines`() {
        // Old firmware reports the week modulo 1024: without rollover handling
        // the frame would be dated 1999.
        assertEquals(Geo.gpsToUnixMs(2148, 1000L), Geo.gpsToUnixMs(100, 1000L))
        // A full week number is left alone.
        assertEquals(Geo.GPS_EPOCH_MS + 2300L * 604_800_000L - 18_000L,
            Geo.gpsToUnixMs(2300, 0L))
    }

    @Test
    fun `les secondes intercalaires sont un parametre`() {
        val a = Geo.gpsToUnixMs(2300, 0L, 18)
        val b = Geo.gpsToUnixMs(2300, 0L, 19)
        assertEquals(1000L, a - b)
    }

    @Test
    fun `constantes WGS84`() {
        assertEquals(6_378_137.0, Geo.A, 0.0)
        assertTrue(abs(Geo.B - 6_356_752.314) < 0.01)
        assertTrue(Geo.E2 > 0.0066 && Geo.E2 < 0.0068)
    }
}
