/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorChemin
import fr.f4ioz.satcombo.rotor.RotorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The mast's path over a pass crossing the end stop: no useless turn, the smallest miss on the sky. */
class RotorCheminTest {

    /** A G-5400 with its stop at south: 450° from 180 (180..630). */
    private val g5400 = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 180.0)

    /**
     * A pass along a great circle of the sky, culminating at [azCulm] / [elCulm],
     * going towards azimuth [vers], [dureeS] long from horizon to horizon.
     */
    private fun passage(azCulm: Double, elCulm: Double, vers: Double, dureeS: Int = 600, pasS: Int = 5): List<Triple<Long, Double, Double>> {
        fun vec(az: Double, el: Double): DoubleArray { val a = Math.toRadians(az); val e = Math.toRadians(el)
            return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e)) }
        val p = vec(azCulm, elCulm)
        val h = vec(vers, 0.0)
        val ph = p[0] * h[0] + p[1] * h[1] + p[2] * h[2]
        val d0 = DoubleArray(3) { h[it] - ph * p[it] }
        val nd = sqrt(d0.sumOf { it * it }); val d = DoubleArray(3) { d0[it] / nd }
        // From horizon to horizon: s where the elevation is 0.
        val sMax = atan2(p[2], -d[2]).let { a -> Math.PI - abs(a) }.coerceAtMost(Math.PI / 2)
        val out = ArrayList<Triple<Long, Double, Double>>()
        val n = dureeS / pasS
        for (i in 0..n) {
            val s = -sMax + 2 * sMax * i / n
            val v = DoubleArray(3) { cos(s) * p[it] + sin(s) * d[it] }
            val el = Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0)))
            if (el < 0) continue
            val az = (Math.toDegrees(atan2(v[0], v[1])) + 360.0) % 360.0
            out += Triple(i * pasS * 1000L, az, el)
        }
        return out
    }

    @Test
    fun la_separation_et_l_elevation_au_zenith() {
        // 79° of elevation, 180° of azimuth away: raised to the zenith, missed by 11°.
        val e = RotorChemin.meilleureElevation(200.0, 79.0, 20.0, 90.0)
        assertEquals(90.0, e, 1e-6)
        assertEquals(11.0, RotorChemin.separation(200.0, 79.0, 20.0, e), 1e-6)
        // A rotator that flips aims over the top: no miss at all.
        val f = RotorChemin.meilleureElevation(200.0, 79.0, 20.0, 180.0)
        assertEquals(101.0, f, 1e-6)
        assertEquals(0.0, RotorChemin.separation(200.0, 79.0, 20.0, f), 1e-6)
    }

    @Test
    fun l_iss_du_8_10_passe_de_l_autre_cote_sans_tour() {
        // Culmination at 79° on the south side, from west-north-west to east-south-east: the stop (south) is crossed.
        val p = passage(azCulm = 200.0, elCulm = 79.0, vers = 110.0)
        assertTrue(p.first().second in 280.0..300.0 && p.last().second in 100.0..120.0)
        val chemin = RotorChemin.plan(p, g5400, degParS = 6.0, toleranceDeg = 15.0)
        assertTrue("pas de tour", RotorChemin.tours(chemin).isEmpty())
        val pire = chemin.maxOf { it.erreurDeg }
        assertTrue("écart max $pire°", pire <= 25.0)
        // Low on the sky the antennas are right on it.
        chemin.zip(p).filter { it.second.third < 30.0 }.forEach { (c, _) -> assertTrue(c.erreurDeg < 5.0) }
        // The path is continuous: never faster than the rotator.
        chemin.zipWithNext().forEach { (a, b) -> assertTrue(abs(b.azDeg - a.azDeg) <= 6.0 * 5 + 2.0 + 1e-9) }
        // It goes round through north (the far side of the zenith): from 290 up to 470 (= 110).
        assertTrue(chemin.last().azDeg > 400.0)
    }

    @Test
    fun un_passage_qui_ne_touche_pas_la_butee_est_suivi_tel_quel() {
        val p = passage(azCulm = 20.0, elCulm = 50.0, vers = 100.0)
        val chemin = RotorChemin.plan(p, g5400, 6.0, 15.0)
        assertFalse(chemin.any { it.tour })
        assertTrue(chemin.maxOf { it.erreurDeg } < 2.0)
    }

    @Test
    fun un_passage_bas_longtemps_derriere_la_butee_fait_un_tour_hors_des_images() {
        // Low (15°), rising east, culminating south, setting west: on a 360° rotator with a south stop.
        val r360 = RotorMath.Limits(azMaxDeg = 360.0, elMaxDeg = 90.0, azStopDeg = 180.0)
        val p = passage(azCulm = 180.0, elCulm = 15.0, vers = 250.0)
        val chemin = RotorChemin.plan(p, r360, 6.0, 15.0)
        // It follows to the stop, waits within the dead zone, then turns back the other way
        // at full speed to find the satellite on the far side: one turn, at the best moment.
        val tours = RotorChemin.tours(chemin)
        assertEquals(1, tours.size)
        assertTrue(chemin.last().erreurDeg < 5.0)
        // A picture while the satellite is still within the dead zone behind the stop: the
        // antennas wait at the stop and receive it, the turn comes after it.
        val image = 305_000L..340_000L
        val autre = RotorChemin.plan(p, r360, 6.0, 15.0, images = listOf(image))
        assertTrue(autre.filter { it.tMs in image }.all { it.erreurDeg <= 15.0 + 1e-9 && !it.tour })
        assertTrue(RotorChemin.tours(autre).all { it.first > image.last })
    }

    @Test
    fun replanifie_en_cours_de_passage_depuis_la_position_du_mat() {
        val p = passage(azCulm = 200.0, elCulm = 79.0, vers = 110.0)
        val suite = p.drop(40)
        val chemin = RotorChemin.plan(suite, g5400, 6.0, 15.0, depuisAz = 330.0, imposeDepart = true)
        assertTrue(abs(chemin.first().azDeg - 330.0) <= 30.0 + 1e-9)
    }
}
