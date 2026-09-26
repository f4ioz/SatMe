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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight, and what it tells us for recovering the sonde.
 *
 * Two things matter. The continuity filter: a misread byte gives a well-formed
 * but wrong position that nothing in the frame can flag; only comparison with
 * the previous point exposes it. And landing extrapolation, which must refuse
 * to answer more often than it answers: an invented landing point sends
 * someone walking for nothing.
 */
class SondeFlightTest {

    private val T0 = 1_700_000_000_000L

    /** A flight frame at a given index, drifting slowly north-east. */
    private fun pt(alt: Double, climb: Double, i: Int, sats: Int = 8,
                   speed: Double = 12.0, heading: Double = 90.0) = SondeFrame(
        type = "M20", serial = "4242", frameNo = i,
        timeUtcMs = T0 + i * 1000L,
        lat = 48.4 + i * 0.001, lon = -4.4 + i * 0.001,
        altM = alt, speedMps = speed, headingDeg = heading, climbMps = climb,
        sats = sats, batteryV = 2.7, freqHz = 404_000_000L,
        heardAtMs = T0 + i * 1000L)

    /** A full flight: climb to 30 km, burst, descent. */
    private fun vol(): SondeFlight {
        val f = SondeFlight("4242", "M20")
        var i = 0
        var alt = 1_000.0
        while (alt < 30_000.0) { f.add(pt(alt, 5.0, i)); alt += 1_000.0; i++ }
        f.add(pt(30_000.0, 0.5, i)); i++
        alt = 29_000.0
        while (alt > 800.0) { f.add(pt(alt, -6.0, i)); alt -= 1_000.0; i++ }
        f.add(pt(800.0, -6.0, i))
        return f
    }

    @Test
    fun `une trame invraisemblable n est pas retenue`() {
        val f = SondeFlight("4242", "M20")
        assertTrue(!f.add(pt(50_000.0, 3.0, 0)))          // above any balloon
        assertTrue(!f.add(pt(5_000.0, 3.0, 1, speed = 400.0)))
        assertEquals(0, f.count)
        assertNull(f.last)
    }

    @Test
    fun `un saut impossible est refuse`() {
        val f = SondeFlight("4242", "M20")
        assertTrue(f.add(pt(5_000.0, 4.0, 0)))
        // 200 km in one second is a misread byte, not wind.
        val faux = pt(5_100.0, 4.0, 1).copy(lat = 50.2, lon = -4.4)
        assertTrue(!f.add(faux))
        assertEquals(1, f.count)
        // The next point is consistent and must pass.
        assertTrue(f.add(pt(5_100.0, 4.0, 2)))
        assertEquals(2, f.count)
    }

    @Test
    fun `un trou de reception elargit la tolerance`() {
        // Half an hour of silence: the sonde may have moved more than 20 km.
        val f = SondeFlight("4242", "M20")
        f.add(pt(5_000.0, 4.0, 0))
        val plusTard = pt(20_000.0, 4.0, 0)
            .copy(lat = 49.0, lon = -4.4, heardAtMs = T0 + 1_800_000L)
        assertTrue(f.add(plusTard))
        assertEquals(2, f.count)
    }

    @Test
    fun `une trame douteuse est gardee sans devenir la reference`() {
        val f = SondeFlight("4242", "M20")
        f.add(pt(5_000.0, 4.0, 0))
        val douteuse = pt(6_000.0, 4.0, 1, sats = 2)
        assertTrue(f.add(douteuse))
        assertEquals(2, f.count)
        assertEquals(6_000.0, f.last!!.altM, 0.0)
        // The point we walk towards stays the last trusted one.
        assertEquals(5_000.0, f.lastTrusted!!.altM, 0.0)
    }

    @Test
    fun `le premier point sur donne le lacher`() {
        val f = SondeFlight("4242", "M20")
        f.add(pt(300.0, 5.0, 0, sats = 1))
        f.add(pt(400.0, 5.0, 1))
        assertEquals(400.0, f.first!!.altM, 0.0)
    }

    @Test
    fun `l eclatement est vu quand la sonde repasse sous son sommet`() {
        val f = SondeFlight("4242", "M20")
        var alt = 1_000.0
        var i = 0
        while (alt <= 25_000.0) { f.add(pt(alt, 5.0, i)); alt += 1_000.0; i++ }
        // Still climbing: nothing to report.
        assertTrue(!f.hasBurst)
        assertEquals(25_000.0, f.burstAltM, 0.0)
        f.add(pt(24_000.0, -8.0, i))
        assertTrue(f.hasBurst)
        assertEquals(25_000.0, f.burstAltM, 0.0)
    }

    @Test
    fun `une sonde basse ne compte pas comme eclatee`() {
        // Flight lost at 8 km: signal loss, not burst.
        val f = SondeFlight("4242", "M20")
        f.add(pt(8_000.0, 5.0, 0))
        f.add(pt(7_000.0, -5.0, 1))
        assertTrue(!f.hasBurst)
    }

    @Test
    fun `vitesse de descente moyenne`() {
        val f = vol()
        assertEquals(6.0, f.descentRate(), 1e-9)
        // A climbing sonde has no descent rate.
        val m = SondeFlight("4242", "M20")
        m.add(pt(1_000.0, 5.0, 0))
        m.add(pt(2_000.0, 5.0, 1))
        assertEquals(0.0, m.descentRate(), 0.0)
        // One point is not enough for an average.
        val u = SondeFlight("4242", "M20")
        u.add(pt(1_000.0, -5.0, 0))
        assertEquals(0.0, u.descentRate(), 0.0)
    }

    @Test
    fun `l impact n est estime que quand il a un sens`() {
        // Empty flight: nothing to extrapolate.
        assertNull(SondeFlight("4242", "M20").estimatedLanding())
        // Climbing: above all, announce nothing.
        val m = SondeFlight("4242", "M20")
        m.add(pt(5_000.0, 5.0, 0))
        m.add(pt(6_000.0, 5.0, 1))
        assertNull(m.estimatedLanding())
        // 20 km at 5 m/s is over an hour of fall: wind drift would make the
        // landing point fiction.
        val h = SondeFlight("4242", "M20")
        h.add(pt(21_000.0, -5.0, 0))
        h.add(pt(20_000.0, -5.0, 1))
        assertNull(h.estimatedLanding())
    }

    @Test
    fun `l impact estime prolonge le dernier vecteur`() {
        val f = vol()
        val l = f.lastTrusted!!
        val p = f.estimatedLanding()
        assertNotNull(p)
        p!!
        // Heading due east: latitude barely moves, longitude increases.
        assertEquals(l.lat, p.first, 1e-3)
        assertTrue("longitude ${p.second} contre ${l.lon}", p.second > l.lon)
        // 800 m at 6 m/s with 12 m/s wind: about 1.6 km of drift.
        val d = Geo.distanceKm(l.lat, l.lon, p.first, p.second)
        assertTrue("dérive $d km", d > 1.0 && d < 2.5)
    }

    @Test
    fun `distance et azimut depuis le QTH`() {
        val f = vol()
        val l = f.lastTrusted!!
        assertEquals(Geo.distanceKm(48.0, -4.0, l.lat, l.lon),
            f.distanceFromKm(48.0, -4.0), 1e-9)
        assertEquals(Geo.bearingDeg(48.0, -4.0, l.lat, l.lon),
            f.bearingFrom(48.0, -4.0), 1e-9)
        // No trusted point: return zero rather than an invented bearing.
        val vide = SondeFlight("4242", "M20")
        assertEquals(0.0, vide.distanceFromKm(48.0, -4.0), 0.0)
        assertEquals(0.0, vide.bearingFrom(48.0, -4.0), 0.0)
    }

    @Test
    fun `duree ecoutee et remise a zero`() {
        val f = vol()
        assertEquals((f.count - 1).toLong(), f.durationSec)
        assertEquals(404_000_000L, f.freqHz)
        f.clear()
        assertEquals(0, f.count)
        assertEquals(0L, f.durationSec)
        assertNull(f.last)
        assertNull(f.lastTrusted)
        assertNull(f.burst)
        assertTrue(!f.hasBurst)
        assertEquals("4242", f.serial)
    }

    @Test
    fun `l allegement garde le debut et la fin`() {
        val f = vol()
        val tous = f.thinned(1)
        assertEquals(f.count, tous.size)
        val allege = f.thinned(5)
        assertTrue(allege.size < tous.size)
        assertEquals(tous.first(), allege.first())
        assertEquals(tous.last(), allege.last())
        // A doubtful point is left out of the exported track.
        val g = SondeFlight("4242", "M20")
        g.add(pt(1_000.0, 5.0, 0))
        g.add(pt(1_100.0, 5.0, 1, sats = 2))
        assertEquals(1, g.thinned().size)
    }

    @Test
    fun `le GPX porte les trois reperes de la chasse`() {
        val x = SondeExport.gpx(vol())
        assertTrue(x.startsWith("<?xml"))
        assertTrue(x.contains("<gpx"))
        assertTrue(x.contains("Dernier point sûr"))
        assertTrue(x.contains("Éclatement"))
        assertTrue(x.contains("Impact estimé"))
        assertTrue(x.contains("</gpx>"))
        // One track point per trusted point.
        val n = Regex("<trkpt").findAll(x).count()
        assertEquals(vol().count, n)
        // Coordinates use a decimal point whatever the phone locale, or no GPS
        // can read the file.
        assertTrue(!x.contains("lat=\"48,"))
    }

    @Test
    fun `le KML porte les memes reperes`() {
        val k = SondeExport.kml(vol())
        assertTrue(k.contains("<kml"))
        assertTrue(k.contains("Dernier point sûr"))
        assertTrue(k.contains("Éclatement"))
        assertTrue(k.contains("Impact estimé"))
        assertTrue(k.contains("<LineString>"))
        assertTrue(k.contains("</kml>"))
    }

    @Test
    fun `un vol sans eclatement n exporte pas d eclatement`() {
        val f = SondeFlight("4242", "M20")
        f.add(pt(5_000.0, 5.0, 0))
        f.add(pt(6_000.0, 5.0, 1))
        val x = SondeExport.gpx(f)
        assertTrue(x.contains("Dernier point sûr"))
        assertTrue(!x.contains("Éclatement"))
        assertTrue(!x.contains("Impact estimé"))
    }

    @Test
    fun `le journal a autant de colonnes que d en-tetes`() {
        val h = SondeExport.csvHeader()
        assertTrue(h.endsWith("\n"))
        val l = SondeExport.csvLine(pt(12_345.0, -3.5, 7))
        assertTrue(l.endsWith("\n"))
        assertEquals(h.trim().split(';').size, l.trim().split(';').size)
        assertTrue(l.contains("4242"))
        assertTrue(l.contains("M20"))
        assertTrue(l.contains("404000000"))
        // Decimal point and semicolon separator: opens everywhere.
        assertTrue(l.contains("12345.0"))
    }
}
