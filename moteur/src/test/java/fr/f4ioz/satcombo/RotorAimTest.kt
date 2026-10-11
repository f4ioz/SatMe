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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the mast shows on the compass, and what the tolerance lets it accept.
 *
 * First: when a rotator holds the antenna, it knows where it points, and the
 * compass must show that instead of the phone. But its reading must be
 * translated — an overlap mast says 380° where the sky has only 20°, and a
 * flipped elevation rotator says 100° while aiming 80° the opposite way. The
 * raw reading would put the needle exactly opposite the antenna.
 *
 * Second: the tolerated error is not just for colouring a banner, it feeds
 * the plan choice. A pass starting a few degrees behind the stop is not worth
 * a full mast turn: start after the stop and leave those degrees unpointed.
 */
class RotorAimTest {

    /** A common setup: one turn, stop at south. */
    private val sud360 = RotorMath.Limits(
        azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 180.0)

    @Test
    fun la_boussole_montre_ou_l_antenne_pointe_et_non_ce_que_le_mat_affiche() {
        // Overlap first: 380° on the ring is 20° in the sky.
        assertEquals(RotorPos(20.0, 30.0), RotorMath.antennaAim(RotorPos(380.0, 30.0)))
        assertEquals(RotorPos(0.0, 0.0), RotorMath.antennaAim(RotorPos(720.0, 0.0)))
        // An azimuth already within one turn does not move.
        assertEquals(RotorPos(215.0, 45.0), RotorMath.antennaAim(RotorPos(215.0, 45.0)))

        // Then the flip, which is the trap: at 100° elevation the mast looks
        // over its head, i.e. the other way.
        assertEquals(RotorPos(180.0, 80.0), RotorMath.antennaAim(RotorPos(0.0, 100.0)))
        assertEquals(RotorPos(30.0, 10.0), RotorMath.antennaAim(RotorPos(210.0, 170.0)))
        // The boundary belongs to the upright case: 90° is zenith, and flipping
        // it would swing the needle half a turn for nothing.
        assertEquals(RotorPos(100.0, 90.0), RotorMath.antennaAim(RotorPos(100.0, 90.0)))

        // The antenna always points somewhere nameable: azimuth within one turn,
        // elevation above the horizon.
        var el = 0.0
        while (el <= 180.0 + 1e-9) {
            var az = -720.0
            while (az <= 720.0 + 1e-9) {
                val v = RotorMath.antennaAim(RotorPos(az, el))
                assertTrue("azimut hors du tour : ${v.azDeg}", v.azDeg >= -1e-9 && v.azDeg < 360.0)
                assertTrue("élévation impossible : ${v.elDeg}", v.elDeg >= -1e-9 && v.elDeg <= 90.0 + 1e-9)
                az += 37.5
            }
            el += 7.5
        }
    }

    /**
     * A pass starting 30° behind the south stop, then moving into range.
     *
     * Without tolerance the only way to cover it all is an extra turn — the
     * mast heads for 510° instead of 180° and spends much of the pass getting
     * there.
     */
    private fun passageDerriereLaButee(): List<Pair<Double, Double>> =
        (0..35).map { i -> (150.0 + i.toDouble()) to 45.0 }

    @Test
    fun l_ecart_tolere_evite_le_tour_complet() {
        val track = passageDerriereLaButee()

        // With zero tolerance the plan takes the extra turn: the only shift that
        // covers the start of the pass exactly.
        val strict = RotorMath.plan(track, sud360, toleranceDeg = 0.0)!!
        assertEquals(360.0, strict.shiftDeg, 1e-9)

        // With 35° tolerated, the missed start costs nothing: start at the stop,
        // let those degrees go, no unwinding.
        val souple = RotorMath.plan(track, sud360, toleranceDeg = 35.0)!!
        assertEquals(0.0, souple.shiftDeg, 1e-9)
        assertEquals(1.0, souple.coverage, 1e-9)

        // But no lying about what was missed: the worst error stays the real
        // one, shown to the operator in the banner.
        assertEquals(30.0, souple.worstErrorDeg, 1e-9)

        // Tolerance never moves the mast further than without it: it allows
        // giving up, not doing more.
        for (tol in listOf(0.0, 5.0, 10.0, 15.0, 20.0, 30.0, 35.0, 60.0)) {
            val p = RotorMath.plan(track, sud360, toleranceDeg = tol)!!
            assertTrue("décalage inattendu à $tol° : ${p.shiftDeg}",
                p.shiftDeg == 0.0 || p.shiftDeg == 360.0)
        }
    }

    @Test
    fun un_passage_bien_dans_la_course_ne_change_pas_avec_la_tolerance() {
        // Where no stop is crossed, tolerance has nothing to decide and the plan
        // must be exactly as before — same shift, coverage, zero error.
        val track = (0..40).map { i -> (200.0 + i.toDouble() * 3.0) to (10.0 + i.toDouble()) }
        val sans = RotorMath.plan(track, sud360, toleranceDeg = 0.0)!!
        for (tol in listOf(5.0, 15.0, 30.0)) {
            val avec = RotorMath.plan(track, sud360, toleranceDeg = tol)!!
            assertEquals(sans.shiftDeg, avec.shiftDeg, 1e-9)
            assertEquals(sans.coverage, avec.coverage, 1e-9)
            assertEquals(sans.worstErrorDeg, avec.worstErrorDeg, 1e-9)
        }
        assertEquals(1.0, sans.coverage, 1e-9)
        assertEquals(0.0, sans.worstErrorDeg, 1e-9)
    }
}
