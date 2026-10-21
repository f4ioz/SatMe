/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPlanPassage
import fr.f4ioz.satcombo.rotor.RotorPos
import fr.f4ioz.satcombo.station.EtatRotor
import fr.f4ioz.satcombo.station.ResumePlanRotor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The mast's path on the sky, for the page's polar plots: planned for the
 * pass to come (`/api/rotor/plan`), or as it really went during a logged
 * contact's pass (`/api/carnet/rotor`, from the pass journal).
 *
 * Points are `[t, az, el]` (sky directions: a flipped mast at el 120 is
 * shown where it looks, az + 180 / el 60), angles to a tenth of a degree.
 */
object ApiRotorPlan {

    private fun d1(x: Double) = (x * 10).roundToInt() / 10.0

    /** The mast's limits from its settings (as the tracking loop makes them). */
    fun limites(r: EtatRotor) = RotorMath.Limits(
        azMaxDeg = r.rotorMaxAz.toDouble(),
        elMaxDeg = if (r.rotorFlip || r.rotorMaxEl >= 180) 180.0 else r.rotorMaxEl.toDouble(),
        deadbandDeg = r.rotorDeadband.toDouble(),
        azStopDeg = if (r.rotorAzStop == "SOUTH") 180.0 else 0.0)

    fun plan(p: ResumePlanRotor?, r: EtatRotor): JSONObject {
        val o = JSONObject().put("zones", r.rotorZones).put("elMax", if (r.rotorFlip || r.rotorMaxEl >= 180) 180 else r.rotorMaxEl)
            .put("azMax", r.rotorMaxAz).put("butee", r.rotorAzStop)
        if (p == null) return o.put("ok", false)
        return o.put("ok", true).put("aos", p.aosMs).put("los", p.losMs)
            .put("sat", JSONArray(p.sat.map { JSONArray().put(it.first).put(d1(it.second)).put(d1(it.third)) }))
            // [t, az, el, over the top 0/1, changing 0/1]
            .put("mat", JSONArray(p.mat.map {
                JSONArray().put(it.tMs).put(d1(it.az)).put(d1(it.el)).put(if (it.retourne) 1 else 0).put(if (it.bascule) 1 else 0)
            }))
            .put("couverture", (p.couverture * 1000).roundToInt() / 1000.0)
            .put("course", p.courseAzDeg.roundToInt())
            .put("retourne", p.retourne)
            .put("changement", p.changement)
            .put("changementMs", p.changementMs ?: JSONObject.NULL)
            .put("changementS", p.changementS)
            .put("texte", p.texte)
    }

    /**
     * The pass of a contact made at [tQso] on [sat], from the journal: the
     * satellite's track and the mast as it went (when a rotor was there), with
     * its travel and coverage. `ok` false when no logged pass covers it.
     */
    fun journal(passages: List<JournalPassage.Entree>, tQso: Long, sat: String,
                limits: RotorMath.Limits, toleranceDeg: Double): JSONObject {
        val e = passages.filter { JournalPassage.memeSatellite(it.satName, sat) && tQso in JournalPassage.fenetre(it) }
            .minByOrNull { if (tQso in it.debutMs..it.finMs) 0L else minOf(Math.abs(tQso - it.debutMs), Math.abs(tQso - it.finMs)) }
            ?: return JSONObject().put("ok", false).put("erreur", "aucun passage au journal pour ce contact")
        val pts = e.points
        val o = JSONObject().put("ok", true).put("id", e.id).put("sat", e.satName).put("qso", tQso)
            .put("aos", e.debutMs).put("los", e.finMs)
            .put("trace", JSONArray(pts.map { JSONArray().put(it.tMs).put(d1(it.az)).put(d1(it.el)) }))
        val avec = pts.filter { it.rotorAz != null && it.rotorEl != null }
        if (avec.size < 2) return o.put("rotor", false)
        val b = RotorPlanPassage.bilan(avec.map { Triple(it.tMs, it.az, it.el) },
            avec.map { RotorPos(it.rotorAz!!, it.rotorEl!!) }, limits, toleranceDeg)
        return o.put("rotor", true)
            .put("mat", JSONArray(avec.map {
                val (a, el) = RotorPlanPassage.versCiel(it.rotorAz!!, it.rotorEl!!)
                JSONArray().put(it.tMs).put(d1(a)).put(d1(el)).put(if (it.rotorEl > 90.0) 1 else 0)
            }))
            .put("course", b.courseDeg.roundToInt())
            .put("couverture", (b.couverture * 1000).roundToInt() / 1000.0)
    }
}
