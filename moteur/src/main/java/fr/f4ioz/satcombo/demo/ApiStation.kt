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
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.station.EtatBoucleCat
import fr.f4ioz.satcombo.station.EtatRotor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station's page speaks the same JSON whoever serves it, SatMe-PC or
 * the phone: its pieces written once here, each server putting them
 * together from what it knows.
 */
object ApiStation {

    /**
     * The sky frequencies the rig is set to for transmitter [t]: the rest
     * frequency (the transponder's middle unless chosen) after the Doppler
     * and the RX calibration; the uplink landing on it (inverting or not),
     * with the TX shift, after the Doppler.
     */
    fun frequencesDuCiel(t: Transmitter?, vitesseKmS: Double, repos: Long?, calibHz: Long, txShiftHz: Long): Pair<Long?, Long?> {
        if (t == null) return null to null
        val dlLo = t.downlinkLowHz; val dlHi = t.downlinkHighHz ?: dlLo
        val r = repos ?: dlLo?.let { (it + (dlHi ?: it)) / 2 }
        val ul = t.uplinkLowHz?.let { ulLo ->
            val ulHi = t.uplinkHighHz ?: ulLo
            if (r != null && dlLo != null && dlHi != null && dlHi != dlLo && ulHi != ulLo) {
                val part = (r - dlLo).toDouble() / (dlHi - dlLo)
                if (t.invert) ulHi - Math.round(part * (ulHi - ulLo)) else ulLo + Math.round(part * (ulHi - ulLo))
            } else (ulLo + ulHi) / 2
        }
        return r?.let { fr.f4ioz.satcombo.domain.Doppler.downlink(it, vitesseKmS) + calibHz } to
            ul?.let { fr.f4ioz.satcombo.domain.Doppler.uplink(it + txShiftHz, vitesseKmS) }
    }

    /** A pass in the list, with its small sky arc (some twenty points). */
    fun passage(p: SatPass, fav: Boolean = false, agenda: Boolean = false, amsat: String = ""): JSONObject = JSONObject()
        .put("fav", fav).put("agenda", agenda).put("amsat", amsat)
        .put("nom", p.satName).put("catnum", p.catalogNumber).put("aos", p.aosEpochMs).put("los", p.losEpochMs)
        .put("elMax", p.maxElevationDeg).put("azAos", p.aosAzimuthDeg).put("azLos", p.losAzimuthDeg)
        .put("trace", JSONArray(p.track.let { t -> val pas = maxOf(1, t.size / 20); t.filterIndexed { i, _ -> i % pas == 0 || i == t.size - 1 } }
            .map { (az, el) -> JSONArray(listOf(Math.round(az), Math.round(el * 10) / 10.0)) }))

    fun position(p: SatPosition): JSONObject = JSONObject().put("az", p.azimuthDeg).put("el", p.elevationDeg)
        .put("distance", p.rangeKm).put("vitesse", p.rangeRateKmS).put("alt", p.altKm)
        .put("lat", p.latDeg).put("lon", p.lonDeg).put("eclaire", p.sunlit)

    fun emetteurs(l: List<Transmitter>): JSONArray = JSONArray(l.map { t ->
        JSONObject().put("desc", t.description).put("mode", t.mode ?: "")
            .put("dlBas", t.downlinkLowHz ?: JSONObject.NULL).put("dlHaut", t.downlinkHighHz ?: JSONObject.NULL)
            .put("ulBas", t.uplinkLowHz ?: JSONObject.NULL).put("ulHaut", t.uplinkHighHz ?: JSONObject.NULL)
            .put("inverse", t.invert).put("lineaire", t.isTransponder)
    })

    /** The rig, as the Doppler loop sees it ([modele], [port]… what the server knows of the link). */
    fun cat(c: EtatBoucleCat, modele: String, port: Int, connecte: Boolean, simule: Boolean, statut: String, maintien: Boolean): JSONObject =
        JSONObject().put("modele", modele).put("port", port).put("connecte", connecte).put("simule", simule)
            .put("statut", statut).put("maintien", maintien)
            .put("dl", c.descenteHz ?: JSONObject.NULL).put("ul", c.monteeHz ?: JSONObject.NULL)
            .put("pilote", c.rxPilote).put("mode", c.mode ?: "").put("modeEcart", c.modeEcart)

    fun rotor(r: EtatRotor): JSONObject = JSONObject().put("connecte", r.rotorConnected).put("statut", r.rotorStatus)
        .put("suit", r.rotorEnabled).put("simule", r.rotorSim).put("lien", r.rotorLink)
        .put("az", r.rotorActualAz ?: JSONObject.NULL).put("el", r.rotorActualEl ?: JSONObject.NULL)
        .put("azCible", r.rotorTargetAz ?: JSONObject.NULL).put("elCible", r.rotorTargetEl ?: JSONObject.NULL)
        .put("port", r.rotorUsbIndex).put("baud", r.rotorBaud).put("hote", r.rotorHost).put("tcp", r.rotorPort)
        .put("attente", r.rotorPrePositioning)
        .put("reglages", JSONObject().put("maxAz", r.rotorMaxAz).put("maxEl", r.rotorMaxEl).put("butee", r.rotorAzStop)
            .put("depuisButee", r.rotorAzFromStop).put("flip", r.rotorFlip).put("azSeul", r.rotorAzOnly)
            .put("parkAz", r.rotorParkAz).put("parkEl", r.rotorParkEl).put("minEl", r.rotorMinEl)
            .put("ecart", r.rotorDeadband).put("avantAos", r.rotorPreAos))
        .put("diag", JSONArray(r.rotorDiag))

    /**
     * A callsign's QRZ record as the page shows it: who, where, how far
     * from [moi]; the photo only when the operator wants the card ([fiche]).
     * [f] null: no credentials, or the network failed.
     */
    fun qrz(f: fr.f4ioz.satcombo.domain.QrzReponse.Fiche?, moi: fr.f4ioz.satcombo.data.Observer?, fiche: Boolean): JSONObject {
        if (f == null) return JSONObject().put("ok", false).put("erreur", "identifiants QRZ absents ou réseau")
        if (f.erreur.isNotBlank()) return JSONObject().put("ok", false).put("erreur", f.erreur)
        val o = JSONObject().put("ok", !f.vide).put("nom", f.nom).put("prenom", f.prenom).put("qth", f.qth)
            .put("pays", f.pays).put("carre", f.carre).put("indicatif", f.indicatif)
            .put("adresse", f.adresse).put("etat", f.etat).put("comte", f.comte).put("zoneCq", f.zoneCq).put("zoneItu", f.zoneItu)
            .put("classe", f.classe).put("lotw", f.lotw).put("eqsl", f.eqsl).put("qslPapier", f.qslPapier)
            .put("qslManager", f.qslManager).put("site", f.site).put("surnom", f.surnom).put("depuis", f.depuis)
        ouEstIl(o, moi, f.lat, f.lon, f.carre)
        return o.put("image", if (fiche) f.image else "").put("fiche", fiche)
    }

    /** Where a station is (its coordinates, else its square's centre), and its distance and bearing from [moi]. */
    fun ouEstIl(o: JSONObject, moi: fr.f4ioz.satcombo.data.Observer?, lat: Double?, lon: Double?, carre: String) {
        val la = lat ?: fr.f4ioz.satcombo.location.Maidenhead.centre(carre)?.first
        val lo = lon ?: fr.f4ioz.satcombo.location.Maidenhead.centre(carre)?.second
        if (la == null || lo == null) return
        o.put("lat", la).put("lon", lo)
        if (moi == null) return
        val az = fr.f4ioz.satcombo.sonde.Geo.bearingDeg(moi.latDeg, moi.lonDeg, la, lo)
        o.put("moi", JSONObject().put("lat", moi.latDeg).put("lon", moi.lonDeg))
            .put("km", Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(moi.latDeg, moi.lonDeg, la, lo)))
            .put("azimut", Math.round(az))
            .put("rose", fr.f4ioz.satcombo.sonde.Geo.compass(az))
    }

    /** A station's contacts in the log, newest first: those of the same base callsign; none under three characters. */
    fun historique(log: List<fr.f4ioz.satcombo.data.LogEntry>, indicatif: String): List<fr.f4ioz.satcombo.data.LogEntry> {
        val b = fr.f4ioz.satcombo.domain.Indicatifs.base(indicatif)
        if (b.length < 3) return emptyList()
        return log.filter { fr.f4ioz.satcombo.domain.Indicatifs.base(it.callsign) == b }.sortedByDescending { it.timeMs }
    }

    /**
     * What the log says of a station ([l], newest first): how often, when,
     * on which satellites, from which squares; and where it is, from its most
     * frequent square, when QRZ is not asked.
     */
    fun historiqueJson(l: List<fr.f4ioz.satcombo.data.LogEntry>, moi: fr.f4ioz.satcombo.data.Observer?, fiche: Boolean): JSONObject {
        val o = JSONObject().put("total", l.size).put("fiche", fiche)
            .put("premier", l.lastOrNull()?.timeMs ?: 0L).put("dernier", l.firstOrNull()?.timeMs ?: 0L)
            .put("sats", JSONArray(l.map { it.satName }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { "${it.key} ×${it.value}" }))
            .put("carres", JSONArray(l.map { it.theirLocator.take(4).uppercase() }.filter { it.length == 4 }.distinct()))
            .put("contacts", JSONArray(l.take(100).map { e -> JSONObject().put("t", e.timeMs).put("c", e.callsign).put("l", e.theirLocator)
                .put("sat", e.satName).put("md", e.mode).put("rs", e.rstSent).put("rr", e.rstRcvd).put("nm", e.nom).put("qth", e.qth).put("n", e.note) }))
        l.mapNotNull { it.theirLocator.takeIf { x -> x.length >= 4 } }.groupingBy { it.uppercase() }.eachCount().maxByOrNull { it.value }?.key
            ?.let { ouEstIl(o, moi, null, null, it) }
        return o
    }

    /**
     * The online log's station locations as the settings show them: the
     * profiles, the one for here, and which one each place of the log goes
     * to ([lieux]: place, contacts, profile); [couvre] says whether a
     * profile really matches a place (else it is only the default).
     */
    fun profilsWavelog(profils: List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil>, ici: Set<String>, pourIci: String?,
                       lieux: List<Triple<Set<String>, Int, String?>>, couvre: (String?, Set<String>) -> Boolean,
                       choisi: String, indicatif: String): JSONObject =
        JSONObject().put("ok", true)
            .put("profils", JSONArray(profils.map { JSONObject().put("id", it.id).put("indicatif", it.indicatif).put("carre", it.carre).put("nom", it.nom) }))
            .put("ici", fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(ici)).put("pourIci", pourIci ?: JSONObject.NULL)
            .put("iciCouvert", couvre(pourIci, ici))
            .put("choisi", choisi)
            .put("lieux", JSONArray(lieux.map { (lieu, n, p) ->
                JSONObject().put("lieu", fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(lieu)).put("n", n).put("profil", p ?: JSONObject.NULL)
                    .put("couvert", couvre(p, lieu))
            }))
            // Wavelog needs these to create a profile: France's when the callsign is French.
            .put("creation", if (indicatif.trim().uppercase().startsWith("F"))
                JSONObject().put("dxcc", "227").put("pays", "FRANCE").put("cq", "14").put("itu", "27") else JSONObject())

    /**
     * The orbital elements' choice as the settings show it: the SatMe GP
     * server, it alone or not, the sources ticked. [celestrakParServeur]:
     * CelesTrak reached only through the server (SatMe-PC's rule).
     */
    fun sources(serveur: String, seul: Boolean, actives: Set<String>, etat: String, nombre: Int, celestrakParServeur: Boolean): JSONObject =
        JSONObject().put("serveur", serveur).put("seul", seul).put("defaut", fr.f4ioz.satcombo.data.ServeurGp.DEFAUT)
            .put("etat", etat).put("nombre", nombre)
            .put("sources", JSONArray(fr.f4ioz.satcombo.data.Sources.ALL.map { s ->
                JSONObject().put("id", s.id).put("nom", s.label).put("actif", s.id in actives)
                    .put("celestrak", celestrakParServeur && s.url.contains("celestrak", true))
                    .put("detail", if (s.id == fr.f4ioz.satcombo.data.Sources.IMAGES) fr.f4ioz.satcombo.data.SatellitesImages.LISTE.joinToString(", ") { it.nom + " (" + it.images + ")" } else "")
            }))

    /** The SatMe GP server at [serveur]: its groups and their counts, or why it does not answer. */
    fun essaiServeurGp(serveur: String): JSONObject {
        val base = fr.f4ioz.satcombo.data.ServeurGp.normalise(serveur)
        if (base.isEmpty()) return JSONObject().put("ok", false).put("erreur", "aucune adresse")
        val r = runCatching {
            val c = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
            c.newCall(okhttp3.Request.Builder().url(fr.f4ioz.satcombo.data.ServeurGp.index(base)).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()).execute().use { rep ->
                if (!rep.isSuccessful) return JSONObject().put("ok", false).put("erreur", "HTTP " + rep.code)
                val g = JSONObject(rep.body?.string().orEmpty()).getJSONArray("groupes")
                (0 until g.length()).joinToString(" · ") { val x = g.getJSONObject(it); x.getString("id") + " " + x.optInt("nombre") }
            }
        }.getOrElse { return JSONObject().put("ok", false).put("erreur", "pas de réponse (" + it.javaClass.simpleName + ")") }
        return JSONObject().put("ok", true).put("groupes", r)
    }
}
