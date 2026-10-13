/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's METEOR tab, written once: SatMe-PC's LRPT receiver
 * and the phone's say what they lock and decode, the next pass, the
 * pictures; the answers are the same, in the same words.
 */
object ApiMeteor {

    class Recepteur(val actif: Boolean, val source: String, val satellite: String, val qualiteDb: Float, val verrou: Boolean,
                    val trames: Int, val ratees: Int, val lignes: Int, val canaux: List<Int>, val frequenceHz: Int, val perdus: Int,
                    val progression: Float, val fichier: String, val erreur: String, val apercus: Int)

    fun json(r: Recepteur, auto: Boolean, cotes: Boolean, sdrActif: Boolean, sdrErreur: String, suiviMeteor: Boolean, dernier: String,
             prochain: Pair<TleEntry, SatPass>?, images: List<Pair<java.io.File, SstvMeta.SstvShot>>): JSONObject {
        val o = JSONObject().put("actif", r.actif).put("source", r.source).put("satellite", r.satellite)
            .put("qualite", Math.round(r.qualiteDb * 10) / 10.0).put("verrou", r.verrou).put("trames", r.trames).put("ratees", r.ratees)
            .put("lignes", r.lignes).put("canaux", JSONArray(r.canaux)).put("frequence", r.frequenceHz).put("perdus", r.perdus)
            .put("progression", r.progression.toDouble()).put("fichier", r.fichier).put("erreur", r.erreur).put("apercus", r.apercus)
            .put("auto", auto).put("cotes", cotes).put("sdrActif", sdrActif)
            .put("sdrErreur", sdrErreur).put("suiviMeteor", suiviMeteor).put("dernier", dernier)
        prochain?.let { (sat, p) ->
            o.put("prochain", JSONObject().put("sat", sat.name).put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("el", Math.round(p.maxElevationDeg)))
        }
        o.put("images", JSONArray(images.take(60).map { (f, s) ->
            JSONObject().put("nom", f.name).put("sat", s.satName).put("t", s.timeMs).put("mode", s.mode).put("note", s.note)
                .put("complete", s.complete).put("source", s.source) }))
        return o
    }
}
