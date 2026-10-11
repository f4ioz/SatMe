/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotator aiming maths. The only code that physically moves an antenna: a
 * sign error means three metres of aluminium turning the wrong way for a
 * minute. Telling case: the northern pass, 40° or 400° of travel depending
 * only on how azimuth is written.
 */
class RotorMathTest {

    private val limits90 = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 2.0)
    private val limits180 = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 180.0, deadbandDeg = 2.0)

    @Test
    fun norm360_ramene_tout_dans_le_tour() {
        assertEquals(350.0, RotorMath.norm360(-10.0), 1e-9)
        assertEquals(10.0, RotorMath.norm360(370.0), 1e-9)
        assertEquals(0.0, RotorMath.norm360(360.0), 1e-9)
        assertEquals(0.0, RotorMath.norm360(0.0), 1e-9)
        assertEquals(180.0, RotorMath.norm360(-180.0), 1e-9)
    }

    @Test
    fun le_recouvrement_choisit_la_representation_la_plus_proche() {
        // On a 450° mast, 10° can be 10 or 370: same point in the sky, reached
        // from different places.
        assertEquals(370.0, RotorMath.unwrapNear(10.0, 350.0, 450.0)!!, 1e-9)
        assertEquals(10.0, RotorMath.unwrapNear(10.0, 20.0, 450.0)!!, 1e-9)
        // On a single-turn mast there is no choice.
        assertEquals(10.0, RotorMath.unwrapNear(10.0, 350.0, 360.0)!!, 1e-9)
        assertEquals(350.0, RotorMath.unwrapNear(350.0, 400.0, 450.0)!!, 1e-9)
    }

    @Test
    fun un_azimut_hors_course_ne_rend_rien() {
        // 270° does not exist on a mast limited to 180°: say so rather than
        // return the nearest.
        assertNull(RotorMath.unwrapNear(270.0, 0.0, 180.0))
        assertNotNull(RotorMath.unwrapNear(170.0, 0.0, 180.0))
    }

    @Test
    fun un_passage_au_nord_coute_dix_fois_moins_cher_avec_le_recouvrement() {
        // A pass crossing north: azimuth 340° to 380°, i.e. 40° of mast if it
        // keeps going straight. 601 positions, finely spaced like the real
        // tracking loop.
        val n = 601
        var avecRecouvrement = 0.0
        var sansRecouvrement = 0.0
        var courantLarge = RotorPos(340.0, 30.0)
        var courantEtroit = RotorPos(340.0, 30.0)
        val large = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 0.0)
        val etroit = RotorMath.Limits(azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 0.0)

        for (i in 0 until n) {
            val az = 340.0 + i * (40.0 / (n - 1))
            val a = RotorMath.aim(az, 30.0, courantLarge, large)!!
            avecRecouvrement += RotorMath.travel(courantLarge.azDeg, a.azDeg)
            courantLarge = RotorPos(a.azDeg, a.elDeg)

            val b = RotorMath.aim(az, 30.0, courantEtroit, etroit)!!
            sansRecouvrement += RotorMath.travel(courantEtroit.azDeg, b.azDeg)
            courantEtroit = RotorPos(b.azDeg, b.elDeg)
        }

        // Straight on: exactly the track's 40°.
        assertEquals(40.0, avecRecouvrement, 1e-6)
        // Without overlap, a full half-turn mid-pass, right when the satellite
        // is highest.
        assertTrue("le mât bridé n'a pas fait son demi-tour : $sansRecouvrement",
            sansRecouvrement > 350.0)
        assertTrue("rapport insuffisant : $sansRecouvrement contre $avecRecouvrement",
            sansRecouvrement / avecRecouvrement > 9.0)
        assertEquals(380.0, courantLarge.azDeg, 1e-6)
    }

    @Test
    fun un_rotor_a_quatre_vingt_dix_degres_ne_se_retourne_pas() {
        // The flipped branch needs an elevation rotator reaching 180°. On others
        // it does not exist and must never be offered "just in case".
        val a = RotorMath.aim(30.0, 85.0, RotorPos(200.0, 45.0), limits90)!!
        assertTrue(!a.flipped)
        // Even coming from a flipped state, there is nothing to hold.
        val b = RotorMath.aim(30.0, 85.0, RotorPos(200.0, 45.0), limits90, wasFlipped = true)!!
        assertTrue(!b.flipped)
    }

    @Test
    fun le_retournement_vise_le_meme_point_du_ciel() {
        // Az 180 / el 80 is exactly az 0 / el 100. The mast is already at the
        // latter: nothing to do.
        val a = RotorMath.aim(180.0, 80.0, RotorPos(0.0, 100.0), limits180, wasFlipped = true)!!
        assertTrue(a.flipped)
        assertEquals(0.0, a.azDeg, 1e-9)
        assertEquals(100.0, a.elDeg, 1e-9)
        assertEquals(0.0, RotorMath.cost(a, RotorPos(0.0, 100.0)), 1e-9)
    }

    @Test
    fun on_ne_se_retourne_pas_pour_economiser_vingt_degres() {
        // Without hysteresis a near-zenith satellite flips the choice every
        // second and the mast spends the best part of the pass turning around.
        // Saving 20° is not worth that.
        val courant = RotorPos(130.0, 90.0)
        val a = RotorMath.aim(30.0, 85.0, courant, limits180, wasFlipped = false)!!
        assertTrue("il s'est retourné pour vingt degrés", !a.flipped)
        assertEquals(30.0, a.azDeg, 1e-9)
    }

    @Test
    fun on_se_retourne_quand_l_economie_depasse_l_hysteresis() {
        // 170° of azimuth saved, half a minute of mast time: worth it.
        val courant = RotorPos(200.0, 95.0)
        val a = RotorMath.aim(30.0, 85.0, courant, limits180, wasFlipped = false)!!
        assertTrue("il aurait dû se retourner", a.flipped)
        assertEquals(210.0, a.azDeg, 1e-9)
        assertEquals(95.0, a.elDeg, 1e-9)
        assertTrue(RotorMath.cost(a, courant) < RotorMath.FLIP_HYSTERESIS_DEG)
    }

    @Test
    fun la_zone_morte_laisse_le_mat_tranquille() {
        // A satellite antenna beam spans tens of degrees: one degree off is
        // invisible on any receiver, but every start wears a relay.
        val courant = RotorPos(100.0, 45.0)
        assertTrue(!RotorMath.needsMove(RotorMath.Aim(101.0, 45.0, false), courant, 2.0))
        assertTrue(!RotorMath.needsMove(RotorMath.Aim(100.0, 46.5, false), courant, 2.0))
        assertTrue(RotorMath.needsMove(RotorMath.Aim(103.0, 45.0, false), courant, 2.0))
        assertTrue(RotorMath.needsMove(RotorMath.Aim(100.0, 48.0, false), courant, 2.0))
    }

    @Test
    fun une_consigne_hors_course_rend_null_plutot_que_d_etre_bornee() {
        // The most important point in this file. Silently clamping an
        // impossible target would turn the mast to where the satellite is not —
        // worse than doing nothing, because nothing would say so.
        assertNull(RotorMath.aim(30.0, 95.0, RotorPos(0.0, 0.0), limits90))
        val bride = RotorMath.Limits(azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0)
        assertNull(RotorMath.aim(270.0, 20.0, RotorPos(0.0, 0.0), bride))
        // What fits, fits.
        assertNotNull(RotorMath.aim(170.0, 20.0, RotorPos(0.0, 0.0), bride))
    }

    @Test
    fun le_mat_part_attendre_le_satellite_juste_avant_le_lever() {
        // Requirement: be in position a configurable number of minutes before
        // AOS. Only inside that window: before it the mast stays parked; after
        // AOS normal tracking takes over.
        val maintenant = 1_700_000_000_000L
        val lever = maintenant + 3 * 60_000L

        // Inside the three-minute window: yes, to the second.
        assertTrue(RotorMath.prePositionDue(maintenant, lever, 3))
        assertTrue(RotorMath.prePositionDue(maintenant, maintenant + 1_000L, 3))
        assertTrue(RotorMath.prePositionDue(maintenant, maintenant + 179_000L, 3))

        // Four minutes before: too early, stay parked.
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant + 4 * 60_000L, 3))

        // AOS has passed: no longer pre-positioning.
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant - 1_000L, 3))
        assertFalse(RotorMath.prePositionDue(maintenant, maintenant, 3))

        // Zero minutes disables; no known pass triggers nothing.
        assertFalse(RotorMath.prePositionDue(maintenant, lever, 0))
        assertFalse(RotorMath.prePositionDue(maintenant, null, 3))
    }

    @Test
    fun le_garage_refuse_une_position_impossible() {
        assertNotNull(RotorMath.park(0.0, 0.0, limits90))
        assertNotNull(RotorMath.park(450.0, 90.0, limits90))
        assertNull(RotorMath.park(500.0, 0.0, limits90))
        assertNull(RotorMath.park(0.0, -5.0, limits90))
        assertEquals(180.0, RotorMath.park(180.0, 0.0, limits90)!!.azDeg, 1e-9)
    }
}
