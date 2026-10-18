/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BruitSolaireTest {

    @Test fun `bruit solaire attendu d'une parabole de 1,2 m à 10 GHz`() {
        val a = BruitSolaire.attendu(1.2, 10.489e9, 100.0)
        // About four times the 10.7 cm index at 10 GHz, a beam of 1.7°, several hundred kelvins: ten dB or so.
        assertTrue(a.fluxSfu in 330.0..450.0)
        assertEquals(1.67, a.lobeDeg, 0.05)
        assertTrue(a.deltaTK in 600.0..1_200.0)
        assertTrue("${a.yDb}", a.yDb in 6.0..13.0)
        // A 60 cm dish: a quarter of the area, some 5 to 6 dB less.
        val p = BruitSolaire.attendu(0.6, 10.489e9, 100.0)
        assertTrue(a.yDb - p.yDb in 3.5..6.5)
        // A more active Sun, a noisier receiver.
        assertTrue(BruitSolaire.attendu(1.2, 10.489e9, 200.0).yDb > a.yDb)
        assertTrue(BruitSolaire.attendu(1.2, 10.489e9, 100.0, tsysK = 300.0).yDb < a.yDb)
        // At the index's own wavelength, the flux is the index.
        assertEquals(100.0, BruitSolaire.fluxSfu(2.8e9, 100.0), 1e-9)
        assertTrue(BruitSolaire.fluxSfu(1.3e9, 100.0) < 100.0)
    }

    @Test fun `facteur Y et température du système`() {
        assertEquals(9.5, BruitSolaire.facteurY(-60.5, -70.0), 1e-9)
        // 900 K seen as a 10 dB rise: the system is at 100 K.
        assertEquals(100.0, BruitSolaire.tsysDepuisY(10.0, 900.0)!!, 1e-6)
        assertNull(BruitSolaire.tsysDepuisY(0.0, 900.0))
        assertTrue(BruitSolaire.conforme(9.0, 10.0)); assertFalse(BruitSolaire.conforme(5.0, 10.0))
    }

    @Test fun `le bruit large bande laisse les signaux de côté`() {
        val db = FloatArray(1000) { -80f }
        for (k in 400 until 420) db[k] = -40f   // a strong carrier
        db[500] = -30f                           // the centre spike
        val n = BruitSolaire.bruitLargeBande(db)!!
        assertEquals(-80.0, n, 0.01)
        // Noise 6 dB up everywhere: the measure follows.
        val sol = FloatArray(1000) { -74f }
        assertEquals(6.0, BruitSolaire.bruitLargeBande(sol)!! - n, 0.01)
        assertNull(BruitSolaire.bruitLargeBande(FloatArray(4)))
        // Two measures: their mean power, not the mean of the dB.
        assertEquals(10 * Math.log10((1.0 + 0.1) / 2), BruitSolaire.moyennePuissance(listOf(0.0, -10.0))!!, 1e-9)
    }

    @Test fun `le balayage va ligne par ligne, en serpentin`() {
        val p = BruitSolaire.plan(1.0, 0.5)
        assertEquals(25, p.size)
        // From the lowest row up; each row the other way from the last.
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5, 1.0), p.take(5).map { it.dXDeg })
        assertTrue(p.take(5).all { it.dElDeg == -1.0 })
        assertEquals(listOf(1.0, 0.5, 0.0, -0.5, -1.0), p.subList(5, 10).map { it.dXDeg })
        // Never a jump of more than a step between cells.
        assertTrue(p.zipWithNext().all { (a, b) -> abs(a.dXDeg - b.dXDeg) <= 0.5 + 1e-9 && abs(a.dElDeg - b.dElDeg) <= 0.5 + 1e-9 })
        // Bounded: 11 × 11 at most, whatever is asked.
        assertEquals(121, BruitSolaire.plan(10.0, 0.1).size)
    }

    @Test fun `le balayage respecte les bornes du rotor`() {
        // The Sun low: the cells under the rotor's lowest elevation are left out.
        val b = BruitSolaire.Bornes(azMaxDeg = 360.0, elMinDeg = 5.0, elMaxDeg = 90.0)
        val l = BruitSolaire.planDansLesBornes(180.0, 5.2, 1.0, 0.5, b)
        assertTrue(l.all { BruitSolaire.vise(180.0, 5.2, it).second >= 5.0 })
        assertEquals(15, l.size) // the rows at 5.2, 5.7, 6.2 only
        // A rotor of 0 to 360° from the north stop: around north, the cells across the stop stay reachable (wrapped).
        assertEquals(25, BruitSolaire.planDansLesBornes(359.8, 30.0, 1.0, 0.5, BruitSolaire.Bornes(azMaxDeg = 360.0)).size)
        // A rotor of 270° of travel: the azimuths beyond are refused.
        val c = BruitSolaire.planDansLesBornes(269.5, 30.0, 1.0, 0.5, BruitSolaire.Bornes(azMaxDeg = 270.0))
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { BruitSolaire.vise(269.5, 30.0, it).first <= 270.0 })
        assertTrue(c.size < 25)
        // Nor above the rotor's highest elevation.
        val h = BruitSolaire.planDansLesBornes(180.0, 89.5, 1.0, 0.5, BruitSolaire.Bornes(elMaxDeg = 90.0))
        assertTrue(h.isNotEmpty())
        assertTrue(h.all { BruitSolaire.vise(180.0, 89.5, it).second <= 90.0 })
    }

    @Test fun `le pic du balayage, plus fin que le pas`() {
        val plan = BruitSolaire.plan(1.0, 0.5)
        // A beam of 1.7° centred 0.3° right and 0.2° low of the computed Sun.
        val m = plan.map { c ->
            val d2 = (c.dXDeg - 0.3) * (c.dXDeg - 0.3) + (c.dElDeg + 0.2) * (c.dElDeg + 0.2)
            c to -70.0 + 10.0 * Math.exp(-4 * Math.log(2.0) * d2 / (1.7 * 1.7))
        }
        val p = BruitSolaire.pic(m)
        assertNotNull(p)
        assertEquals(0.3, p!!.dXDeg, 0.15); assertEquals(-0.2, p.dElDeg, 0.15)
        assertTrue(p.monteeDb > 2.0)
        assertNull(BruitSolaire.pic(m.take(2)))
        // An offset across the sky is wider in azimuth up high.
        assertEquals(2.0, BruitSolaire.enAzimut(1.0, 60.0), 1e-9)
    }

    @Test fun `le Soleil vu d'ici, réfraction comprise`() {
        // 21 June 2026 at local solar noon near Paris: south, about 64.7° up.
        val (az, el) = BruitSolaire.soleil(48.85, 2.35, java.time.Instant.parse("2026-06-21T11:51:00Z").toEpochMilli())
        assertEquals(180.0, az, 2.0); assertEquals(64.7, el, 0.5)
        assertEquals(-20.0, BruitSolaire.ecartAz(350.0, 10.0), 1e-9)
    }
}
