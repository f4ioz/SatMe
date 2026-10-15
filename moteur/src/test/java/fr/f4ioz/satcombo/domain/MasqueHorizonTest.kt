/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasqueHorizonTest {

    private val iss = TleEntry(
        name = "ISS (ZARYA)",
        line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
        line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303"
    )
    private val epochMs = 1704110400000L
    private val paris = Observer(48.80, 2.48, 50.0, "JN18FS")

    @Test fun `un masque nul ne change aucun passage`() {
        val p = PassPredictor()
        val sans = p.upcomingPasses(iss, paris, epochMs, 48, 0.0)
        val zero8 = p.upcomingPasses(iss, paris, epochMs, 48, 0.0, MasqueHorizon(List(8) { 0.0 }))
        val zero12 = p.upcomingPasses(iss, paris, epochMs, 48, 0.0, MasqueHorizon.lis("0,0,0,0,0,0,0,0,0,0,0,0"))
        assertTrue(sans.isNotEmpty())
        assertEquals(sans, zero8)
        assertEquals(sans, zero12)
        assertTrue(sans.none { it.masque })
    }

    @Test fun `un masque raccourcit le passage, le geometrique reste`() {
        val p = PassPredictor()
        val sans = p.upcomingPasses(iss, paris, epochMs, 48, 20.0)
        val m = MasqueHorizon(List(8) { 10.0 })
        val avec = p.upcomingPasses(iss, paris, epochMs, 48, 20.0, m)
        assertEquals(sans.size, avec.size)
        for ((a, b) in sans.zip(avec)) {
            assertTrue(b.masque)
            assertEquals(a.aosEpochMs, b.geoAosEpochMs)
            assertEquals(a.losEpochMs, b.geoLosEpochMs)
            assertTrue(b.aosEpochMs > a.aosEpochMs && b.losEpochMs < a.losEpochMs)
            // At the new AOS and LOS the satellite stands at the mask, to about a second.
            val debut = p.positionAt(iss, paris, b.aosEpochMs).elevationDeg
            val fin = p.positionAt(iss, paris, b.losEpochMs).elevationDeg
            assertEquals(10.0, debut, 0.3)
            assertEquals(10.0, fin, 0.3)
            assertEquals(p.positionAt(iss, paris, b.aosEpochMs).azimuthDeg, b.aosAzimuthDeg, 0.01)
        }
    }

    @Test fun `un passage cache tout du long disparait`() {
        val p = PassPredictor()
        val bas = p.upcomingPasses(iss, paris, epochMs, 48, 0.0).filter { it.maxElevationDeg < 15.0 }
        assertTrue(bas.isNotEmpty())
        val avec = p.upcomingPasses(iss, paris, epochMs, 48, 0.0, MasqueHorizon(List(12) { 20.0 }))
        assertTrue(avec.none { a -> bas.any { it.geoAosEpochMs == a.geoAosEpochMs } })
    }

    @Test fun `un seul secteur ne touche que son cote`() {
        // A satellite rising in the east, going west at constant 30°/min, 30° high.
        val m = MasqueHorizon(listOf(0.0, 0.0, 40.0, 0.0, 0.0, 0.0, 0.0, 0.0)) // 90–135° blocked
        val pos = { t: Long -> (150.0 - t / 60_000.0 * 30.0 + 360.0) % 360.0 to 30.0 }
        // From az 150 down to az 0 over 5 min: hidden while between 135 and 90.
        val f = m.fenetre(0L, 300_000L, position = pos)!!
        assertEquals(0L, f.first)
        assertEquals(300_000L, f.second)
        // Starting inside the blocked sector: AOS once out of it (az < 90, after 2 min).
        val g = m.fenetre(60_000L, 300_000L, position = pos)!!
        assertEquals(120_000.0, g.first.toDouble(), 1_000.0)
    }

    @Test fun `lecture et ecriture`() {
        val m = MasqueHorizon.lis("0, 5,12.5,0,0,0,3,0")
        assertEquals(8, m.elevationsDeg.size)
        assertEquals(12.5, m.elevationA(100.0), 0.0)
        assertEquals(5.0, m.elevationA(45.0), 0.0)
        assertEquals(0.0, m.elevationA(359.9), 0.0)
        assertEquals(3.0, m.elevationA(-80.0), 0.0)
        assertEquals("0,5,12.5,0,0,0,3,0", m.texte())
        assertTrue(MasqueHorizon.lis("1,2,3").vide)
        assertTrue(MasqueHorizon.lis("a,b,c,d,e,f,g,h").vide)
        assertTrue(MasqueHorizon.lis("").vide)
        assertNull(MasqueHorizon(List(8) { 90.0 }).fenetre(0, 60_000) { 0.0 to 45.0 })
    }
}
