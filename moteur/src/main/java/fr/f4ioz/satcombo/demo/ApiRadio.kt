/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.aprs.Paquet
import fr.f4ioz.satcombo.data.Observer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's APRS and FT8 tabs, written once: SatMe-PC and the
 * phone give what they heard; the answers are the same, in the same words.
 */
object ApiRadio {

    /** APRS heard ([paquets], newest first) and what they add up to: stations, how far, via the ISS. */
    fun aprs(paquets: List<Paquet>, actif: Boolean, recues: Int, obs: Observer?): JSONObject {
        val stations = fr.f4ioz.satcombo.aprs.AprsJeu.stations(paquets, obs?.latDeg, obs?.lonDeg)
        val km = stations.associateBy { it.indicatif }
        return JSONObject().put("present", true).put("actif", actif).put("recues", recues)
            .put("nbStations", stations.size).put("viaIss", paquets.count { it.viaIss })
            .put("loin", stations.mapNotNull { it.km }.maxOrNull() ?: JSONObject.NULL)
            .put("paquets", JSONArray(paquets.take(400).map { p ->
                val stn = km[p.nom ?: p.source]
                JSONObject().put("t", p.quand).put("src", p.source).put("type", p.type.name).put("iss", p.viaIss)
                    .put("dest", p.destinataire ?: "").put("txt", (p.message ?: p.commentaire).take(120))
                    .put("chemin", p.trame.relais.joinToString(",") { it.toString() + if (it.repete) "*" else "" })
                    .put("carre", stn?.carre ?: "").put("km", stn?.km?.let { Math.round(it) } ?: JSONObject.NULL)
            }))
    }

    /** One FT8/FT4 decode: its slot, its text, who calls whom, the square given. */
    class Ligne(val tranche: Long, val texte: String, val appelant: String?, val appele: String?, val locator: String?,
                val frequenceHz: Int, val rapportDb: Int, val decalageS: Double, val mode: String)

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}$")

    /**
     * Who calls whom in a standard message, from its text alone ("CQ DX
     * F4IOZ JN18", "F4ABC F4IOZ -10"): caller, called (null for a CQ), the
     * square when one ends it.
     */
    fun decoupe(texte: String): Triple<String?, String?, String?> {
        val t = texte.trim().split(Regex("\\s+")).map { it.removePrefix("<").removeSuffix(">") }.filter { it.isNotEmpty() }
        if (t.isEmpty()) return Triple(null, null, null)
        val carre = t.last().takeIf { t.size >= 3 && CARRE.matches(it) && it != "RR73" }
        return if (t[0] == "CQ") {
            // "CQ DX F4IOZ JN18": the word after CQ is a direction when a callsign follows it.
            val reste = t.drop(1).let { r -> if (r.size >= 2 && (r[0].length <= 4 && r[0].all { it.isLetter() } || r[0].all { it.isDigit() })) r.drop(1) else r }
            Triple(reste.firstOrNull(), null, carre)
        } else Triple(t.getOrNull(1), t[0], carre)
    }

    /** FT8 heard: the decodes with what they mean here (CQ, for me, how far, already worked), the waterfall. */
    fun ft8(enMarche: Boolean, mode: String, avancement: Float, niveau: Float, tranches: Int, carte: Boolean, basseHz: Int, hauteHz: Int,
            cascade: List<ByteArray>, spectre: FloatArray, entendus: List<Ligne>, moi: String, obs: Observer?, faits: Set<String>): JSONObject =
        JSONObject().put("present", true).put("enMarche", enMarche).put("mode", mode).put("avancement", avancement.toDouble())
            .put("niveau", Math.round(niveau * 1000.0) / 1000.0).put("tranches", tranches).put("carte", carte)
            .put("basse", basseHz).put("haute", hauteHz)
            .put("cascade", JSONArray(cascade.map { java.util.Base64.getEncoder().encodeToString(it) }))
            .put("spectre", JSONArray(spectre.map { Math.round(it * 100) / 100.0 }))
            .put("entendus", JSONArray(entendus.take(400).map { e ->
                val ll = e.locator?.let { fr.f4ioz.satcombo.location.Maidenhead.centre(it) }
                JSONObject().put("t", e.tranche).put("texte", e.texte).put("de", e.appelant ?: "").put("a", e.appele ?: "")
                    .put("loc", e.locator ?: "").put("hz", e.frequenceHz).put("db", e.rapportDb).put("dt", Math.round(e.decalageS * 10) / 10.0)
                    .put("mode", e.mode).put("cq", e.texte.startsWith("CQ"))
                    .put("moi", moi.isNotBlank() && e.appele?.uppercase() == moi)
                    .put("fait", e.appelant?.uppercase()?.substringBefore('/') in faits)
                    .put("km", if (ll != null && obs != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, ll.first, ll.second)) else JSONObject.NULL)
            }))

    /**
     * The KISS radio as the APRS tab shows it: connected, in KISS, its
     * frequency, what it received; [ports] the serial ports to choose from,
     * [port] the one kept; the working mode and the Doppler.
     */
    fun kiss(e: fr.f4ioz.satcombo.aprs.LienKiss.Etat, ports: List<Pair<Int, String>>, port: Int?, travail: String, doppler: Boolean,
             lignesLevees: Boolean, lignesAuChoix: Boolean, voulue: Long?): JSONObject =
        JSONObject().put("connecte", e.connecte).put("nom", e.nom).put("vitesse", e.vitesse).put("initialise", e.initialise)
            .put("recues", e.recues).put("envoyees", e.envoyees).put("erreur", e.erreur ?: "")
            .put("frequence", e.frequenceHz ?: JSONObject.NULL).put("bande", e.bande ?: JSONObject.NULL)
            .put("ports", JSONArray(ports.map { (i, l) -> JSONObject().put("index", i).put("libelle", l) }))
            .put("port", port ?: JSONObject.NULL).put("travail", travail).put("doppler", doppler)
            .put("lignes", lignesLevees).put("lignesAuChoix", lignesAuChoix).put("voulue", voulue ?: JSONObject.NULL)
}
