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
}
