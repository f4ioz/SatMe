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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Azimuth stops. A rotator has a stop (end of cable travel) at north or south
 * depending on make. Sending the raw true azimuth made passes crossing the
 * stop unwind a full turn mid-reception, while the signal was best.
 *
 * **Decide before, do not suffer during**: [RotorMath.plan] picks the side of
 * the stop once at AOS; [RotorMath.follow] applies it. Starting slightly off
 * costs less than unwinding at zenith — the beam is wide, a pass is not
 * recoverable.
 */
class RotorStopTest {

    /** A single-turn mast, stop at south. */
    private val sud360 = RotorMath.Limits(
        azMaxDeg = 360.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 180.0)

    /** An overlap mast, stop at north: the common case. */
    private val nord450 = RotorMath.Limits(
        azMaxDeg = 450.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)

    @Test
    fun la_butee_retient_le_mat_et_dit_de_combien() {
        // `clampAz` does not look for another representation of the same sky
        // point: it just stops at the limit. That is the difference from
        // `unwrapNear`, and why the error is read by subtraction.
        assertEquals(180.0, sud360.azMinReach, 1e-9)
        assertEquals(540.0, sud360.azMaxReach, 1e-9)
        assertEquals(540.0, RotorMath.clampAz(560.0, sud360), 1e-9)
        assertEquals(180.0, RotorMath.clampAz(100.0, sud360), 1e-9)
        assertEquals(300.0, RotorMath.clampAz(300.0, sud360), 1e-9)
        assertEquals(0.0, RotorMath.clampAz(-20.0, nord450), 1e-9)

        // Bounded unwrapping respects the stop: on a south-stop mast, a
        // satellite due north is aimed at 390, not 30.
        assertEquals(390.0, RotorMath.unwrapNear(30.0, 400.0, 360.0, 180.0)!!, 1e-9)
        // Free unwrapping ignores travel on purpose: it can name an unreachable
        // point, hence measure the error.
        assertEquals(370.0, RotorMath.unwrapFree(10.0, 350.0), 1e-9)
        assertEquals(-20.0, RotorMath.unwrapFree(340.0, 0.0), 1e-9)
    }

    @Test
    fun le_cas_d_olivier_choisit_le_tour_de_plus() {
        // Pass 200°, 180°, 150°, 90°, 30° on a single-turn south-stop mast.
        // Without a shift it hits the stop and must unwind. With one extra turn
        // (560…390) it stays in range, at the cost of 20° error at the very
        // start, when the satellite is still in the trees.
        val passage = listOf(
            200.0 to 0.0, 180.0 to 15.0, 150.0 to 40.0, 90.0 to 15.0, 30.0 to 0.0)

        val p = RotorMath.plan(passage, sud360)!!
        assertEquals("le décalage retenu", 360.0, p.shiftDeg, 1e-9)
        assertEquals("l'azimut de départ", 560.0, p.startAzDeg, 1e-9)
        assertEquals("la couverture", 1.0, p.coverage, 1e-9)
        assertEquals("le pire écart", 20.0, p.worstErrorDeg, 1e-9)

        // Tracking keeps the plan's promise: the mast goes to the stop, waits
        // there, then follows without ever going back.
        var az = RotorMath.clampAz(p.startAzDeg, sud360)
        assertEquals(540.0, az, 1e-9)
        val consignes = ArrayList<Double>()
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 0.0), sud360)
            consignes += a.azDeg
            az = a.azDeg
        }
        assertEquals(listOf(540.0, 540.0, 510.0, 450.0, 390.0), consignes)
    }

    @Test
    fun un_passage_entier_ne_saute_jamais_de_plus_de_trente_degres() {
        // One sample per second, crossing the south stop — the worst case. A
        // command jumping more than 30° in a second means a mid-pass branch
        // change, i.e. the unwinding we want to eliminate.
        val passage = (0..60).map { i ->
            (200.0 - 3.0 * i) to 45.0 * sin(PI * i / 60.0)
        }

        val p = RotorMath.plan(passage, sud360)!!
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertTrue("couverture insuffisante : ${p.coverage}", p.coverage > 0.95)

        var az = RotorMath.clampAz(p.startAzDeg, sud360)
        var plusGrandSaut = 0.0
        var plusGrandEcart = 0.0
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 0.0), sud360)
            plusGrandSaut = max(plusGrandSaut, abs(a.azDeg - az))
            plusGrandEcart = max(plusGrandEcart, a.errorDeg)
            assertTrue("consigne hors course : ${a.azDeg}",
                a.azDeg >= sud360.azMinReach - 1e-9 && a.azDeg <= sud360.azMaxReach + 1e-9)
            az = a.azDeg
        }
        assertTrue("saut de $plusGrandSaut degrés en une seconde", plusGrandSaut <= 30.0)
        assertEquals("le pire écart annoncé n'est pas celui vécu",
            p.worstErrorDeg, plusGrandEcart, 1e-9)
        // And we end on the other side without unwinding.
        assertEquals(380.0, az, 1e-9)
    }

    @Test
    fun la_couverture_pese_le_zenith_plus_que_l_horizon() {
        // Two passes differing only in where the high elevations are. Unweighted,
        // both would score "four points of six"; weighted, missing the trees
        // differs from missing the top of the pass.
        val azimuts = listOf(60.0, 90.0, 120.0, 150.0, 200.0, 250.0)
        val bride = RotorMath.Limits(
            azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)

        val basseFin = azimuts.zip(listOf(30.0, 50.0, 50.0, 30.0, 3.0, 3.0))
        val hauteFin = azimuts.zip(listOf(3.0, 3.0, 30.0, 50.0, 50.0, 30.0))

        val a = RotorMath.plan(basseFin, bride)!!
        val b = RotorMath.plan(hauteFin, bride)!!

        // Exactly the same geometry: same shift, same worst error.
        assertEquals(0.0, a.shiftDeg, 1e-9)
        assertEquals(0.0, b.shiftDeg, 1e-9)
        assertEquals(70.0, a.worstErrorDeg, 1e-9)
        assertEquals(70.0, b.worstErrorDeg, 1e-9)

        assertTrue("perdre l'horizon devrait à peine compter : ${a.coverage}",
            a.coverage > 0.95)
        assertTrue("perdre le zénith devrait coûter cher : ${b.coverage}",
            b.coverage < 0.55)
    }

    @Test
    fun a_couverture_egale_le_plus_petit_ecart_l_emporte() {
        // A sky point no shift reaches: the mast covers half a turn and the
        // satellite is behind. Both plans cover nothing — but one waits 20°
        // from the satellite, the other 160°. That matters for the antenna beam,
        // and when the satellite comes back into range.
        val bride = RotorMath.Limits(
            azMaxDeg = 180.0, elMaxDeg = 90.0, deadbandDeg = 2.0, azStopDeg = 0.0)
        val p = RotorMath.plan(listOf(200.0 to 45.0), bride)!!
        assertEquals(0.0, p.coverage, 1e-9)
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertEquals(200.0, p.startAzDeg, 1e-9)
        assertEquals(20.0, p.worstErrorDeg, 1e-9)
    }

    @Test
    fun derriere_la_butee_le_mat_attend_au_lieu_de_derouler() {
        // Mast at the upper stop (540, due south), satellite at 200°: 20° past
        // the stop. The driver stays and **reports** the error. The key point
        // of this file: a reported error can be explained; an unwinding mast
        // cannot.
        var a = RotorMath.follow(200.0, 10.0, RotorPos(540.0, 10.0), sud360)
        assertEquals(540.0, a.azDeg, 1e-9)
        assertEquals(20.0, a.errorDeg, 1e-9)

        a = RotorMath.follow(190.0, 20.0, RotorPos(540.0, 10.0), sud360)
        assertEquals(540.0, a.azDeg, 1e-9)
        assertEquals(10.0, a.errorDeg, 1e-9)

        // As soon as the satellite is back within range, the mast moves again.
        a = RotorMath.follow(175.0, 30.0, RotorPos(540.0, 20.0), sud360)
        assertEquals(535.0, a.azDeg, 1e-9)
        assertEquals(0.0, a.errorDeg, 1e-9)
    }

    @Test
    fun le_retournement_garde_son_hysteresis_meme_avec_une_butee() {
        // The flipped branch keeps its 90° hysteresis — otherwise a near-zenith
        // satellite would flip the choice every second and the mast would spend
        // the best part of the pass turning over.
        val gros = RotorMath.Limits(
            azMaxDeg = 450.0, elMaxDeg = 180.0, deadbandDeg = 2.0, azStopDeg = 0.0)

        // 170° of azimuth saved: worth it.
        val a = RotorMath.follow(30.0, 85.0, RotorPos(200.0, 95.0), gros)
        assertTrue("il aurait dû se retourner", a.flipped)
        assertEquals(210.0, a.azDeg, 1e-9)
        assertEquals(95.0, a.elDeg, 1e-9)

        // Only 20°: not worth it.
        val b = RotorMath.follow(30.0, 85.0, RotorPos(130.0, 90.0), gros)
        assertTrue("il s'est retourné pour vingt degrés", !b.flipped)
        assertEquals(30.0, b.azDeg, 1e-9)

        // On a rotator limited to 90°, the branch does not exist.
        val c = RotorMath.follow(30.0, 85.0, RotorPos(200.0, 45.0), nord450)
        assertTrue(!c.flipped)
    }

    @Test
    fun un_mat_a_recouvrement_ne_deroule_pas_sur_un_passage_au_nord() {
        // The northern pass with a stop: 41 points from 340° to 380°. The plan
        // must track at 340 rather than −20 — same sky, but one is in range and
        // the other behind the stop.
        val passage = (0..40).map { (340.0 + it) to 30.0 }
        val p = RotorMath.plan(passage, nord450)!!
        assertEquals(360.0, p.shiftDeg, 1e-9)
        assertEquals(340.0, p.startAzDeg, 1e-9)
        assertEquals(1.0, p.coverage, 1e-9)
        assertEquals(0.0, p.worstErrorDeg, 1e-9)

        var az = RotorMath.clampAz(p.startAzDeg, nord450)
        var parcouru = 0.0
        for ((azVrai, elVrai) in passage) {
            val a = RotorMath.follow(azVrai, elVrai, RotorPos(az, 30.0), nord450)
            parcouru += RotorMath.travel(az, a.azDeg)
            az = a.azDeg
        }
        // Exactly the track's 40°, nothing more.
        assertEquals(40.0, parcouru, 1e-6)
        assertEquals(380.0, az, 1e-9)
    }

    @Test
    fun les_azimuts_ne_changent_d_origine_qu_au_dernier_moment() {
        // Everywhere in the app azimuth is from true north: predictor, compass,
        // what the operator reads. Some controllers count from their stop. So
        // translation happens when writing the frame and is undone when the
        // position comes back — one setting, nothing else to change.
        assertEquals(20.0, RotorMath.commandAz(200.0, sud360, fromStop = true), 1e-9)
        assertEquals(200.0, RotorMath.commandAz(200.0, sud360, fromStop = false), 1e-9)
        assertEquals(200.0, RotorMath.trueAz(20.0, sud360, fromStop = true), 1e-9)
        assertEquals(20.0, RotorMath.trueAz(20.0, sud360, fromStop = false), 1e-9)

        // The round trip loses nothing, over the full travel and in both
        // conventions: the only thing that really matters here.
        for (fromStop in listOf(false, true)) {
            var az = sud360.azMinReach
            while (az <= sud360.azMaxReach + 1e-9) {
                val cmd = RotorMath.commandAz(az, sud360, fromStop)
                assertEquals(az, RotorMath.trueAz(cmd, sud360, fromStop), 1e-9)
                az += 7.5
            }
        }
        // On a north-stop mast there is nothing to translate.
        assertEquals(123.0, RotorMath.commandAz(123.0, nord450, fromStop = true), 1e-9)
    }
}
