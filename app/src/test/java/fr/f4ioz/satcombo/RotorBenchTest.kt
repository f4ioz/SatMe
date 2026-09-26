/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le banc des butées : six balayages qui font passer des milliers de passages
 * devant un mât imaginaire.
 *
 * Aucun de ces essais ne tourne à la compilation ordinaire — ils prennent du
 * temps et impriment des tableaux, ce qui n'a d'intérêt qu'au moment où l'on
 * règle quelque chose. Ils s'ouvrent par `-Dsatme.bench=1`, comme le banc des
 * sondes, et pour la même raison : un chiffre imprimé sur mille passages dit ce
 * qu'aucun essai à cinq points ne peut dire, à savoir si la stratégie tient sur
 * la variété réelle du ciel plutôt que sur le cas qu'on avait en tête en
 * l'écrivant.
 *
 * Ce qu'ils mesurent tient en une phrase : sur un mât qui ne fait pas le tour
 * complet, l'endroit où l'on démarre décide de tout le passage. Le plan est
 * choisi une fois, à l'acquisition, et ne se rattrape plus ensuite — d'où
 * l'insistance de ces balayages sur la couverture et sur le pire écart, qui
 * sont les deux seules choses que l'opérateur verra.
 */
class RotorBenchTest {

    private fun benchEnabled(): Boolean =
        (System.getProperty("satme.bench") ?: "").isNotEmpty()

    /**
     * Un passage vu du sol, à la cadence d'une position par seconde.
     *
     * Le modèle est volontairement grossier — l'azimut défile régulièrement,
     * l'élévation monte en cloche — parce que ce qui est mesuré ici n'est pas
     * la mécanique céleste mais la stratégie de pointage. Ce qui compte est que
     * le balayage d'azimut grandisse avec la culmination : un passage rasant
     * traverse vingt degrés d'horizon, un passage au zénith en traverse cent
     * quatre-vingts, et c'est exactement la différence qui fait souffrir un mât.
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

    /** Ce qu'un passage a coûté au mât, une fois le plan choisi et suivi. */
    private class Trace(
        val couverture: Double,
        val pireEcartDeg: Double,
        val courseDeg: Double,
        val plusGrandPasDeg: Double,
        val retournements: Int
    )

    /**
     * Le plan est choisi une fois, puis suivi seconde par seconde.
     *
     * Le mât part à l'azimut que le plan a désigné — borné, car ce départ peut
     * être derrière la butée : c'est le cas d'Olivier, où le plan vise 560° sur
     * un mât qui s'arrête à 540. La suite se déroule toute seule, le
     * déroulement libre de [RotorMath.follow] restant naturellement sur la
     * branche que le plan a ouverte.
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

    /** La façon naïve : viser, sans plan, en partant de la butée. */
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
                // Un mât d'un tour et demi ne devrait jamais rien manquer.
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
        // Le plan ne doit jamais coûter plus cher que la visée au fil de l'eau :
        // il choisit la même branche, simplement il la choisit à l'avance.
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
        // Un mât qui saute de trente degrés en une seconde, c'est un mât qui
        // déroule : le recouvrement n'a pas été vu, et l'antenne part à l'envers.
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
        // L'hystérésis existe pour cela : au voisinage du zénith, le choix
        // bascule d'une seconde à l'autre si rien ne le retient.
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
        // Le réglage par défaut est quinze degrés ; s'il ne laissait passer
        // presque rien, ce ne serait pas un défaut mais une alarme permanente.
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
