/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPlanPassage
import fr.f4ioz.satcombo.rotor.RotorPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole-pass plan, replayed on Olivier's own passes (south stop, 360°
 * ring, rotator measured at about 6.5°/s on the unwind of 21/10), against
 * what his mast really did that day (the pass journal).
 */
class RotorPlanPassageTest {

    private val vitesse = 6.5
    private val tol = 15.0
    private val sud90 = RotorMath.Limits(azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 3.0, azStopDeg = 180.0)
    private val sud180 = sud90.copy(elMaxDeg = 180.0)

    private fun passage(nom: String): JournalPassage.Entree =
        JournalPassage.lit(javaClass.getResource("/rotor/$nom.passage")!!.readText())!!
            .let { e -> e.copy(points = e.points.filter { it.rotorAz != null && it.rotorEl != null }) }

    private fun trace(e: JournalPassage.Entree) = e.points.map { Triple(it.tMs, it.az, it.el) }
    private fun mat(e: JournalPassage.Entree) = e.points.map { RotorPos(it.rotorAz ?: 0.0, it.rotorEl ?: 0.0) }

    /** The mast where the journal starts: pre-positioned (within the beam), or joined mid-pass. */
    private fun depart(e: JournalPassage.Entree, l: RotorMath.Limits): Pair<RotorPos, Boolean> {
        val p = e.points.first()
        val az = l.azStopDeg + RotorMath.norm360(p.rotorAz!! - l.azStopDeg)
        val (va, ve) = RotorPlanPassage.versCiel(p.rotorAz, p.rotorEl!!)
        val loin = fr.f4ioz.satcombo.rotor.RotorChemin.separation(p.az, p.el, va, ve) > 30.0
        return RotorPos(az, p.rotorEl) to loin
    }

    private data class Rejeu(val avant: RotorPlanPassage.Bilan, val plan: RotorPlanPassage.Plan, val courseAz: Double)

    private fun rejoue(nom: String, l: RotorMath.Limits): Rejeu {
        val e = passage(nom)
        val (d, impose) = depart(e, l)
        val p = RotorPlanPassage.plan(trace(e), l, vitesse, tol, depuis = d, imposeDepart = impose)!!
        // Travel from the first sample on, as the journal counts it: the waiting before AOS is not in it.
        val ce = RotorPlanPassage.bilan(trace(e), mat(e), l, tol)
        val approche = if (impose) abs(p.points.first().azDeg - d.azDeg) else 0.0
        return Rejeu(ce, p, approche + RotorPlanPassage.courseAz(p.points))
    }

    private val passages = listOf(
        "25544_1792574351174", "27607_1792592640991", "27607_1792575021672", "44909_1792592085538",
        "25544_1792473436898", "44909_1792510223358", "57172_1792528113737")

    @Test
    fun rejeu_imprime() {
        for (n in passages) {
            val a = rejoue(n, sud180); val b = rejoue(n, sud90)
            val ev = { p: RotorPlanPassage.Plan -> p.evenement?.let { (if (it.retournement) "flip " else "déroul. ") +
                ((it.debutMs - p.points.first().tMs) / 1000) + "s/" + it.dureeS + "s" } ?: (if (p.avant.retourne) "retourné" else "droit") }
            println(String.format(java.util.Locale.US, "%-22s journal %4.0f° %3.0f%% | 180: %4.0f° %3.0f%% %s | 90: %4.0f° %3.0f%% %s",
                n, a.avant.courseDeg, a.avant.couverture * 100, a.courseAz, a.plan.couverture * 100, ev(a.plan),
                b.courseAz, b.plan.couverture * 100, ev(b.plan)))
        }
    }

    @Test
    fun iss_par_le_sud_retourne_au_lieu_du_grand_tour() {
        val r = rejoue("25544_1792574351174", sud180)
        assertTrue("journal ${r.avant}", r.avant.courseDeg > 400)
        assertTrue("travel ${r.courseAz}", r.courseAz < 200)
        assertTrue("coverage ${r.plan.couverture}", r.plan.couverture >= r.avant.couverture - 1e-9)
        assertTrue(r.plan.retourne)
        // Never a full unwind once flipping is allowed.
        assertTrue(r.plan.evenement == null || r.plan.evenement!!.retournement)
    }

    @Test
    fun so50_haut_par_le_sud_sans_deroulement() {
        val r = rejoue("27607_1792592640991", sud180)
        assertTrue("journal ${r.avant}", r.avant.courseDeg > 450)
        assertTrue("travel ${r.courseAz}", r.courseAz < 300)
        assertTrue("coverage ${r.plan.couverture} vs ${r.avant.couverture}", r.plan.couverture >= r.avant.couverture)
    }

    @Test
    fun a_90_le_deroulement_va_quand_le_satellite_est_bas() {
        val e = passage("27607_1792592640991")
        val r = rejoue("27607_1792592640991", sud90)
        val ev = r.plan.evenement
        assertNotNull("a 360° south-stop mast must unwind for this pass", ev)
        assertFalse(ev!!.retournement)
        // During the unwind the satellite is low: never through the top of the pass.
        val pendant = e.points.filter { it.tMs in ev.debutMs..ev.finMs }
        val elMax = e.points.maxOf { it.el }
        assertTrue("unwind at el ${pendant.maxOfOrNull { it.el }}", pendant.all { it.el < elMax * 0.6 })
        assertTrue("coverage ${r.plan.couverture}", r.plan.couverture >= 0.9)
    }

    @Test
    fun passage_court_rejoint_en_cours_pas_de_course_inutile() {
        // RS-44 joined at el 15, mast 77° away (left by the pass before): the way there, then a few degrees.
        val r = rejoue("44909_1792592085538", sud180)
        assertTrue("travel ${r.courseAz}", r.courseAz < 110)
        assertNull(r.plan.evenement)
    }

    @Test
    fun a_couverture_egale_la_branche_la_plus_proche_du_mat() {
        // A short low pass in the north-east, well inside the ring: whatever the mast's side, no turn.
        val t0 = 1_000_000L
        val tr = (0..40).map { Triple(t0 + it * 5_000L, 20.0 + it * 0.5, 2.0 + 6.0 * Math.sin(Math.PI * it / 40)) }
        val p = RotorPlanPassage.plan(tr, sud90, vitesse, tol, depuis = RotorPos(400.0, 0.0))!!
        assertEquals(380.0, p.points.first().azDeg, 1.0)
        assertTrue(RotorPlanPassage.courseAz(p.points) < 25)
        // Over the top would be just as good on a 180° rotor, but it is not nearer: upright stays.
        val q = RotorPlanPassage.plan(tr, sud180, vitesse, tol, depuis = RotorPos(400.0, 0.0))!!
        assertFalse(q.retourne)
    }

    @Test
    fun les_zones_attirent_le_deroulement() {
        // North stop, a pass crossing north while climbing: the unwind is taken where a roof hides the sky.
        val nord = RotorMath.Limits(azMaxDeg = 360.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        val tr = (0..120).map { i ->
            Triple(i * 5_000L, RotorMath.norm360(320.0 + i * 1.2), 2.0 + 50.0 * Math.sin(Math.PI * i / 120))
        }
        val zone = RotorPlanPassage.Zone(330.0, 60.0, 45.0)
        val sansZone = RotorPlanPassage.plan(tr, nord, vitesse, tol)!!
        val avecZone = RotorPlanPassage.plan(tr, nord, vitesse, tol, zones = listOf(zone))!!
        assertNotNull(sansZone.evenement)
        val ev = avecZone.evenement!!
        val dans = tr.filter { it.first in ev.debutMs..ev.finMs }
        assertTrue("unwind inside the zone: $dans", dans.all { zone.contient(it.second, it.third) })
    }

    @Test
    fun le_suivi_ne_bascule_jamais_deux_fois() {
        // Olivier's ISS pass followed second by second on the plan: the flip state changes at most once.
        val e = passage("25544_1792574351174")
        val tr = trace(e)
        val p = RotorPlanPassage.plan(tr, sud180, vitesse, tol)!!
        var changes = 0
        var prec: Boolean? = null
        var t = tr.first().first
        while (t <= tr.last().first) {
            val i = tr.indexOfFirst { it.first >= t }
            val a = RotorPlanPassage.vise(p, t, tr[i].second, tr[i].third, sud180)
            if (prec != null && prec != a.flipped) changes++
            prec = a.flipped
            assertTrue(a.azDeg in sud180.azMinReach..sud180.azMaxReach)
            t += 1_000L
        }
        assertTrue("flip changes $changes", changes <= p.changements)
    }

    @Test
    fun zones_lues_et_ecrites() {
        val z = RotorPlanPassage.litZones("170-190<20; 350-10<5;bad;10-20<0")
        assertEquals(2, z.size)
        assertTrue(z[1].contient(0.0, 3.0))
        assertFalse(z[1].contient(0.0, 6.0))
        assertFalse(z[0].contient(200.0, 3.0))
        assertEquals("170-190<20;350-10<5", RotorPlanPassage.ecritZones(z))
    }
}
