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
import org.junit.Assume
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Rotator stop bench: thousands of passes past an imaginary mast, to check the
 * strategy holds across the real variety of the sky. Slow; enable with
 * `-Dsatme.bench=1`. On a mast without full rotation the start decides the
 * whole pass, hence the focus on coverage and worst error.
 */
class RotorBenchTest {

    private fun benchEnabled(): Boolean =
        (System.getProperty("satme.bench") ?: "").isNotEmpty()

    /**
     * A deliberately crude pass, one position per second. What matters is that
     * azimuth sweep grows with max elevation (20° grazing, 180° overhead) —
     * what strains a mast.
     */
    private fun passage(
        azDepartDeg: Double,
        elMaxDeg: Double,
        sens: Int = 1,
        n: Int = 61
    ): List<Pair<Double, Double>> {
        val balayage = (20.0 + 160.0 * (elMaxDeg / 90.0)) * sens
        return (0 until n).map { i ->
            val u = i.toDouble() / (n - 1)
            (azDepartDeg + balayage * u) to (elMaxDeg * sin(PI * u))
        }
    }

    /** What a pass cost the mast, once the plan is chosen and followed. */
    private class Trace(
        val couverture: Double,
        val pireEcartDeg: Double,
        val courseDeg: Double,
        val plusGrandPasDeg: Double,
        val retournements: Int
    )

    /**
     * Plan once, then follow. The start azimuth is clamped (it may be past the
     * stop); [RotorMath.follow] then stays on the branch the plan opened.
     */
    private fun suivre(
        track: List<Pair<Double, Double>>,
        limits: RotorMath.Limits
    ): Trace? {
        val p = RotorMath.plan(track, limits) ?: return null
        var az = RotorMath.clampAz(p.startAzDeg, limits)
        var el = track[0].second.coerceIn(0.0, limits.elMaxDeg)
        var flip = false
        var course = 0.0
        var pas = 0.0
        var pire = 0.0
        var retournements = 0
        for ((a, e) in track) {
            val aim = RotorMath.follow(a, e, RotorPos(az, el), limits, flip)
            val d = abs(aim.azDeg - az)
            if (d > pas) pas = d
            course += d + abs(aim.elDeg - el)
            if (aim.flipped != flip) retournements++
            flip = aim.flipped
            az = aim.azDeg
            el = aim.elDeg
            if (aim.errorDeg > pire) pire = aim.errorDeg
        }
        return Trace(p.coverage, pire, course, pas, retournements)
    }

    /** The naive way: aim with no plan, starting from the stop. */
    private fun sansPlan(
        track: List<Pair<Double, Double>>,
        limits: RotorMath.Limits
    ): Pair<Double, Int> {
        var courant = RotorPos(limits.azMinReach, 0.0)
        var course = 0.0
        var refus = 0
        for ((a, e) in track) {
            val aim = RotorMath.aim(a, e, courant, limits)
            if (aim == null) { refus++; continue }
            course += abs(aim.azDeg - courant.azDeg) + abs(aim.elDeg - courant.elDeg)
            courant = RotorPos(aim.azDeg, aim.elDeg)
        }
        return course to refus
    }

    private fun pourcent(x: Double): String = String.format(Locale.US, "%5.1f %%", 100.0 * x)

    // ------------------------------------------------------------------

    @Test
    fun balayage_des_courses_et_des_butees() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== couverture moyenne, par course et par butée ===")
        for (course in listOf(360.0, 380.0, 450.0, 540.0)) {
            for (butee in listOf(0.0, 180.0)) {
                val limits = RotorMath.Limits(course, 90.0, 2.0, azStopDeg = butee)
                var somme = 0.0
                var pire = 0.0
                var n = 0
                for (depart in 0 until 360 step 5) {
                    for (elMax in listOf(5.0, 20.0, 45.0, 75.0)) {
                        for (sens in listOf(1, -1)) {
                            val t = suivre(passage(depart.toDouble(), elMax, sens), limits)!!
                            somme += t.couverture
                            if (t.pireEcartDeg > pire) pire = t.pireEcartDeg
                            n++
                        }
                    }
                }
                println(
                    "course ${course.toInt()}° butée ${if (butee == 0.0) "nord" else "sud "}" +
                        " : couverture ${pourcent(somme / n)}" +
                        String.format(Locale.US, "  pire écart %5.1f°  sur %d passages", pire, n)
                )
                // A 540° mast should never miss anything.
                if (course >= 540.0) {
                    assertEquals("un mât de $course° a manqué du ciel", 1.0, somme / n, 1e-9)
                }
            }
        }
    }

    @Test
    fun balayage_du_cout_mecanique() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== degrés parcourus : avec plan, et sans ===")
        val limits = RotorMath.Limits(450.0, 90.0, 2.0, azStopDeg = 0.0)
        var avec = 0.0
        var sans = 0.0
        var refus = 0
        var n = 0
        for (depart in 0 until 360 step 5) {
            for (elMax in listOf(10.0, 40.0, 80.0)) {
                val t = suivre(passage(depart.toDouble(), elMax), limits)!!
                val (c, r) = sansPlan(passage(depart.toDouble(), elMax), limits)
                avec += t.courseDeg
                sans += c
                refus += r
                n++
            }
        }
        println(
            String.format(
                Locale.US,
                "avec plan %.0f°  sans plan %.0f°  (%d passages, %d consignes refusées)",
                avec / n, sans / n, n, refus
            )
        )
        // The plan must never cost more than aiming on the fly: it picks the same
        // branch, just in advance.
        assertTrue("le plan fait tourner le mât davantage : $avec contre $sans", avec <= sans + 1e-6)
    }

    @Test
    fun balayage_des_passages_au_nord() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== la traversée du nord, sur un mât à butée nord ===")
        val limits = RotorMath.Limits(450.0, 90.0, 2.0, azStopDeg = 0.0)
        var pireSaut = 0.0
        var n = 0
        for (depart in 300..359) {
            for (elMax in listOf(15.0, 45.0, 85.0)) {
                val t = suivre(passage(depart.toDouble(), elMax), limits)!!
                if (t.plusGrandPasDeg > pireSaut) pireSaut = t.plusGrandPasDeg
                n++
            }
        }
        println(String.format(Locale.US, "plus grand pas d'une seconde : %.2f° sur %d passages", pireSaut, n))
        // A 30° jump in one second means the mast is unwinding: the overlap was
        // missed and the antenna swings the wrong way.
        assertTrue("un pas de $pireSaut° en une seconde", pireSaut <= 30.0)
    }

    @Test
    fun balayage_des_passages_au_zenith() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== retournements, sur un mât d'élévation qui monte à 180° ===")
        val limits = RotorMath.Limits(450.0, 180.0, 2.0, azStopDeg = 0.0)
        var total = 0
        var n = 0
        for (depart in 0 until 360 step 3) {
            for (elMax in listOf(70.0, 80.0, 85.0, 89.0)) {
                val t = suivre(passage(depart.toDouble(), elMax), limits)!!
                total += t.retournements
                n++
            }
        }
        println(String.format(Locale.US, "%.2f retournement par passage (%d passages)", total.toDouble() / n, n))
        // That is what hysteresis is for: near zenith the choice flips every
        // second if nothing holds it.
        assertTrue("le mât passe son temps à se retourner", total.toDouble() / n < 2.0)
    }

    @Test
    fun balayage_de_l_ecart_tolere() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== part des passages tenus sous l'écart toléré, mât 360° butée sud ===")
        val limits = RotorMath.Limits(360.0, 90.0, 2.0, azStopDeg = 180.0)
        val alea = Random(20_260_801L)
        val traces = ArrayList<Trace>()
        repeat(2_000) {
            val depart = alea.nextDouble() * 360.0
            val elMax = 2.0 + alea.nextDouble() * 86.0
            val sens = if (alea.nextBoolean()) 1 else -1
            traces += suivre(passage(depart, elMax, sens), limits)!!
        }
        for (seuil in listOf(5.0, 10.0, 15.0, 20.0, 30.0)) {
            val part = traces.count { it.pireEcartDeg <= seuil + 1e-9 }.toDouble() / traces.size
            println(String.format(Locale.US, "écart toléré %2.0f° : %s des passages", seuil, pourcent(part)))
        }
        // Default is 15°; if it let almost nothing through it would be a
        // permanent alarm, not a default.
        val defaut = traces.count { it.pireEcartDeg <= 15.0 + 1e-9 }.toDouble() / traces.size
        assertTrue("le réglage par défaut alarme tout le temps : $defaut", defaut > 0.5)
    }

    @Test
    fun temoin_sans_butee() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== témoin : un tour et demi, butée nord, rien ne doit manquer ===")
        val limits = RotorMath.Limits(540.0, 90.0, 2.0, azStopDeg = 0.0)
        var pire = 0.0
        var n = 0
        for (depart in 0 until 360) {
            for (sens in listOf(1, -1)) {
                val t = suivre(passage(depart.toDouble(), 88.0, sens), limits)!!
                assertEquals("couverture incomplète au départ $depart", 1.0, t.couverture, 1e-9)
                if (t.pireEcartDeg > pire) pire = t.pireEcartDeg
                n++
            }
        }
        println(String.format(Locale.US, "%d passages, pire écart %.3f°", n, pire))
        assertEquals("un mât d'un tour et demi a buté", 0.0, pire, 1e-9)
    }
}
