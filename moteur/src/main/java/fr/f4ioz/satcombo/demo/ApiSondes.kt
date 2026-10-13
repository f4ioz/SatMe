/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.sonde.SondeFlight
import fr.f4ioz.satcombo.sonde.SondeFrame
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's radiosondes tab, written once: SatMe-PC's decoder and
 * the phone's give where they listen, what they decoded and the flight; the
 * answers are the same, in the same words.
 */
object ApiSondes {

    /** What the receiver is and does. */
    class Recepteur(val actif: Boolean, val sdrActif: Boolean, val erreur: String, val freqHz: Long, val decalageHz: Int,
                    val niveauDb: Double, val modele: String, val auto: Boolean,
                    val trames: Int, val rejetees: Int, val excursion: Int, val serie: String, val type: String)

    fun json(r: Recepteur, derniere: SondeFrame?, vol: SondeFlight?, obs: Observer?, journaux: List<java.io.File>): JSONObject {
        val o = JSONObject().put("actif", r.actif).put("sdrActif", r.sdrActif)
            .put("erreur", r.erreur).put("freq", r.freqHz).put("decalage", r.decalageHz)
            .put("niveau", Math.round(r.niveauDb * 10) / 10.0).put("modele", r.modele).put("auto", r.auto)
            .put("modeles", JSONArray(fr.f4ioz.satcombo.sonde.SondeModel.ALL.map { JSONObject().put("id", it.id).put("nom", it.label) }))
            .put("trames", r.trames).put("rejetees", r.rejetees).put("excursion", r.excursion).put("serie", r.serie).put("type", r.type)
        if (obs != null) o.put("moi", JSONObject().put("lat", obs.latDeg).put("lon", obs.lonDeg))
            .put("sites", JSONArray(fr.f4ioz.satcombo.sonde.SondeSites.nearest(obs.latDeg, obs.lonDeg, max = 6).map { (s, km) ->
                JSONObject().put("nom", s.name).put("km", Math.round(km)).put("hz", s.mainHz).put("sonde", s.sonde)
                    .put("lachers", JSONArray(s.launchesUtc.map { h -> String.format(java.util.Locale.US, "%02d:%02d", h.toInt(), Math.round((h - h.toInt()) * 60).toInt()) }))
            }))
        derniere?.let { f ->
            val p = JSONObject().put("lat", f.lat).put("lon", f.lon).put("alt", Math.round(f.altM)).put("vitesse", Math.round(f.speedMps * 3.6))
                .put("cap", Math.round(f.headingDeg)).put("montee", Math.round(f.climbMps * 10) / 10.0).put("sats", f.sats)
                .put("heure", f.timeUtcMs).put("freq", f.freqHz).put("sure", f.trusted)
            if (obs != null) {
                val km = fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, f.lat, f.lon)
                val az = fr.f4ioz.satcombo.sonde.Geo.bearingDeg(obs.latDeg, obs.lonDeg, f.lat, f.lon)
                p.put("km", Math.round(km * 10) / 10.0).put("azimut", Math.round(az)).put("rose", fr.f4ioz.satcombo.sonde.Geo.compass(az))
            }
            fr.f4ioz.satcombo.sonde.SondeSites.likelyOrigin(f.lat, f.lon, f.type)?.let { p.put("origine", it.name) }
            o.put("derniere", p)
        }
        vol?.let { v ->
            val pts = v.track
            val pas = maxOf(1, pts.size / 600)
            o.put("trace", JSONArray(pts.filterIndexed { i, _ -> i % pas == 0 || i == pts.size - 1 }.map { JSONArray(listOf(Math.round(it.lat * 1e5) / 1e5, Math.round(it.lon * 1e5) / 1e5)) }))
            if (v.hasBurst) o.put("eclatement", JSONObject().put("alt", Math.round(v.burstAltM)).put("lat", v.burst!!.lat).put("lon", v.burst!!.lon))
            v.estimatedLanding()?.let { (la, lo) -> o.put("atterrissage", JSONObject().put("lat", la).put("lon", lo)) }
        }
        o.put("journaux", JSONArray(journaux.take(30).map { JSONObject().put("nom", it.name).put("taille", it.length()).put("date", it.lastModified()) }))
        return o
    }
}
