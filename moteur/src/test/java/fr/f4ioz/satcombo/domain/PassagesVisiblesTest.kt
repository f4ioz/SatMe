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
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.TleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** « L'ISS visible à l'œil nu »: the brightness, the phase, the visible part of a pass, real passes checked against their conditions. */
class PassagesVisiblesTest {

    private val iss = TleEntry(
        name = "ISS (ZARYA)",
        line1 = "1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9990",
        line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49512407430303"
    )
    private val epochMs = 1704110400000L
    private val paris = Observer(48.80, 2.48, 50.0, "JN18FS")

    @Test
    fun la_magnitude_suit_la_distance_et_la_phase() {
        assertEquals(-1.8, PassagesVisibles.magnitude(1000.0, 90.0), 1e-9)
        // Twice as close: 1.5 magnitude brighter.
        assertEquals(-1.8 + 5 * Math.log10(0.5), PassagesVisibles.magnitude(500.0, 90.0), 1e-9)
        // Fully lit (the Sun behind us): log10(π) × 2.5 brighter.
        assertEquals(-1.8 - 2.5 * Math.log10(Math.PI), PassagesVisibles.magnitude(1000.0, 0.0), 1e-9)
        assertTrue(PassagesVisibles.magnitude(1000.0, 150.0) > PassagesVisibles.magnitude(1000.0, 90.0))
        assertEquals("éblouissante", PassagesVisibles.eclat(-3.5))
        assertEquals("brillante", PassagesVisibles.eclat(-0.5))
    }

    @Test
    fun l_angle_de_phase() {
        // Overhead, the Sun on the horizon: lit from the side.
        assertEquals(90.0, PassagesVisibles.anglePhaseDeg(0.0, 90.0, 270.0, 0.0), 1e-9)
        // Opposite the Sun (the Sun below the horizon behind us): fully lit as seen from here.
        assertEquals(0.0, PassagesVisibles.anglePhaseDeg(180.0, 10.0, 0.0, -10.0), 1e-6)
        // Towards the Sun: its dark side.
        assertEquals(180.0, PassagesVisibles.anglePhaseDeg(90.0, 5.0, 90.0, 5.0), 1e-6)
        assertEquals("NE", PassagesVisibles.rose(44.0)); assertEquals("N", PassagesVisibles.rose(359.0)); assertEquals("OSO", PassagesVisibles.rose(247.5))
    }

    private fun e(i: Int, el: Double, lit: Boolean, sol: Double = -12.0) =
        PassagesVisibles.Echantillon(i * 10_000L, SatPosition(i * 10.0, el, 800.0, 0.0, 420.0, 0.0, 0.0, lit), 300.0, sol)

    @Test
    fun la_partie_visible_d_un_passage() {
        val l = listOf(e(0, 2.0, true), e(1, 8.0, true), e(2, 15.0, true), e(3, 30.0, true), e(4, 45.0, true), e(5, 35.0, true),
            e(6, 25.0, false), e(7, 12.0, false), e(8, 4.0, false))
        val v = PassagesVisibles.partieVisible(l, 0L, 80_000L)!!
        assertEquals(20_000L, v.debut.t)
        assertEquals(45.0, v.max.elDeg, 0.0)
        assertEquals(50_000L, v.fin.t)
        assertTrue("elle entre dans l'ombre", v.ombre)
        assertEquals(30L, v.dureeS)
        // In daylight here: nothing.
        assertNull(PassagesVisibles.partieVisible(l.map { it.copy(soleilEl = -3.0) }, 0L, 80_000L))
        // All in shadow: nothing.
        assertNull(PassagesVisibles.partieVisible(l.map { it.copy(pos = it.pos.copy(sunlit = false)) }, 0L, 80_000L))
        // Sets lit: no shadow mark.
        assertFalse(PassagesVisibles.partieVisible(l.map { it.copy(pos = it.pos.copy(sunlit = true)) }, 0L, 80_000L)!!.ombre)
    }

    @Test
    fun les_passages_visibles_reels_tiennent_leurs_conditions() {
        val pp = PassPredictor()
        val l = PassagesVisibles.visibles(pp, iss, paris, epochMs, 24 * 10)
        assertTrue("aucun passage visible en dix jours", l.isNotEmpty())
        l.forEach { v ->
            for (p in listOf(v.debut, v.max, v.fin)) {
                val pos = pp.positionAt(iss, paris, p.t)
                assertTrue("lit at ${Date(p.t)}", pos.sunlit)
                assertTrue(pos.elevationDeg >= 9.99)
                assertTrue(SunCalc.elevationDeg(paris.latDeg, paris.lonDeg, Date(p.t)) < -6.0)
                assertEquals(pos.azimuthDeg, p.azDeg, 1e-6)
            }
            assertTrue(v.max.elDeg >= v.debut.elDeg && v.max.elDeg >= v.fin.elDeg)
            assertTrue(v.debut.t in v.aosMs..v.losMs && v.fin.t in v.aosMs..v.losMs)
            assertTrue("magnitude ${v.magnitude}", v.magnitude in -5.0..3.0)
        }
        val o = PassagesVisibles.json(l, 10, "ISS", 25544, epochMs)
        assertEquals(l.size, o.getJSONArray("passages").length())
        val p0 = o.getJSONArray("passages").getJSONObject(0)
        assertTrue(p0.getJSONObject("debut").getString("utc").endsWith("Z"))
        assertTrue(PassagesVisibles.json(emptyList(), 3, "ISS", 25544, 0L, observateurConnu = false).getBoolean("inconnu"))
    }
}
