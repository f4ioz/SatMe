/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.securite.Limiteur
import org.json.JSONObject
import java.io.File

/**
 * The HF pages' data: PSKReporter's reports, HamQSL's conditions, KC2G's
 * model, wspr.live, the RBN live and its archives — each source asked by the
 * site alone, politely, its answers kept and shared by every visitor. Every
 * route has its own brake per visitor's address (on top of the server's),
 * every answer is JSON the pages put into the page as text (never as HTML).
 */
class ApiHf(private val s: Site, horloge: () -> Long = System::currentTimeMillis) : ModuleSite {

    private val c = s.config
    private val maintenant = horloge
    val amont = Amont(c.horsLigne, Site.agentDe(c) + (if (c.hfContact.isNotEmpty()) "; " + c.hfContact else ""))
    val solaire = Solaire(amont, horloge)
    val psk = PskReporter(amont, c.hfContact.ifEmpty { c.urlBaseNette }, horloge)
    val kc2g = Kc2g(amont, horloge)
    val wspr = WsprLive(amont, c.wsprServeur, horloge)
    val rbn = Rbn(c.rbnIndicatif.ifEmpty { c.indicatif }, c.rbnActif && !c.horsLigne, horloge = horloge)
    val archives = RbnArchives(File(c.donnees, "rbn"), c.locatorsOuvertures, c.ouverturesJours, amont, horloge)

    private val freins = mapOf(
        "dx" to Limiteur(40, 60_000L, horloge), "solaire" to Limiteur(30, 60_000L, horloge), "carte" to Limiteur(20, 60_000L, horloge),
        "iono" to Limiteur(20, 60_000L, horloge), "wspr" to Limiteur(20, 60_000L, horloge), "empreinte" to Limiteur(10, 60_000L, horloge),
        "rbn" to Limiteur(30, 60_000L, horloge), "ouvertures" to Limiteur(40, 60_000L, horloge), "indicatif" to Limiteur(30, 60_000L, horloge),
    )

    private fun frein(q: Requete, nom: String, t: () -> Reponse): Reponse =
        if (freins.getValue(nom).accepte(q.ip)) t() else Reponse.erreur(429, "trop de requêtes, patienter une minute").entete("Retry-After", "30")

    private fun entier(q: Requete, k: String, def: Int, min: Int, max: Int) = (q.param(k)?.trim()?.toIntOrNull() ?: def).coerceIn(min, max)

    override fun installe(r: Routeur) {
        r.get("/api/dx/spots") { q -> frein(q, "dx") { spots(q) } }
        r.get("/api/dx/solar") { q -> frein(q, "solaire") { Reponse.json(solaire.conditions()).cache("public, max-age=300") } }
        r.get("/api/prop/map") { q -> frein(q, "carte") { carte(q) } }
        r.get("/api/prop/iono") { q -> frein(q, "iono") { Reponse.json(kc2g.couche(q.param("layer").orEmpty())).cache("public, max-age=900") } }
        r.get("/api/hf/indicatif") { q -> frein(q, "indicatif") { indicatif(q) } }
        r.get("/api/wspr/bands") { q -> frein(q, "wspr") { Reponse.json(wspr.bandes(entier(q, "minutes", 30, 5, 180))).cache("public, max-age=120") } }
        r.get("/api/wspr/footprint") { q -> frein(q, "empreinte") {
            Reponse.json(wspr.empreinte(q.param("call") ?: c.indicatif, entier(q, "hours", 6, 1, 48), q.param("direction").orEmpty())).cache("public, max-age=120") } }
        r.get("/api/rbn/bands") { q -> frein(q, "rbn") { rbnBandes(q) } }
        r.get("/api/rbn/footprint") { q -> frein(q, "rbn") { rbnEmpreinte(q) } }
        r.get("/api/rbn/openings") { q -> frein(q, "ouvertures") {
            val loc = (q.param("loc") ?: archives.locators.first()).trim().uppercase().take(8)
            Reponse.json(archives.ouvertures(loc, entier(q, "days", c.ouverturesJours, 1, 30))) } }
        r.get("/api/rbn/heat") { q -> frein(q, "ouvertures") {
            val loc = (q.param("loc") ?: archives.locators.first()).trim().uppercase().take(8)
            Reponse.json(archives.chaleur(loc, Bandes.nette(q.param("band")), entier(q, "hour", -1, -1, 23), entier(q, "days", c.ouverturesJours, 1, 30))) } }
    }

    override fun demarre() {
        // The station's own archives are read once a day, whoever looks (the first one at startup, a minute later).
        s.taches.scheduleWithFixedDelay({ runCatching { archives.locators.forEach { archives.assure(it) } } }, 1L, 6 * 60L, java.util.concurrent.TimeUnit.MINUTES)
    }

    private fun spots(q: Requete): Reponse {
        val depuis = entier(q, "since", 300, 60, PskReporter.FENETRE_S)
        val mode = q.param("mode")?.trim()?.uppercase()?.take(10).orEmpty().takeIf { it in PskReporter.MODES }.orEmpty()
        val bande = Bandes.nette(q.param("band"))
        val limite = entier(q, "limit", 200, 10, 1000)
        val l = psk.spots(mode)
        val sel = PskReporter.filtre(l.valeur.orEmpty(), maintenant() / 1000, depuis, bande, limite)
        val o = JSONObject().put("count", sel.size).put("spots", PskReporter.json(sel)).put("updated", if (l.quand > 0) l.quand / 1000 else JSONObject.NULL)
            .put("error", l.erreur ?: JSONObject.NULL).put("refresh_s", PskReporter.INTERVALLE_MS / 1000)
        return Reponse.json(o)
    }

    private fun carte(q: Requete): Reponse {
        val bande = Bandes.nette(q.param("band"))
        val depuis = entier(q, "since", 900, 300, 3600)
        val qth = Geo.locatorNet(q.param("qth")) ?: c.locator
        val minutes = (depuis / 60).coerceIn(5, 120)
        val o = when (q.param("source")) {
            "wspr" -> JSONObject(wspr.carte(minutes, bande).toString()).put("since", minutes.coerceIn(5, 120) * 60)
            "rbn" -> {
                rbn.assure()
                val m = minutes.coerceAtMost(30)
                Rbn.carte(rbn.recents(m * 60_000L, bande)).put("since", m * 60).put("connected", rbn.connecte).put("error", rbn.erreur ?: JSONObject.NULL)
                    .put("enabled", c.rbnActif && !c.horsLigne)
            }
            else -> {
                val l = psk.spots("")
                val d = depuis.coerceAtMost(PskReporter.FENETRE_S)
                CartePropagation.psk(PskReporter.filtre(l.valeur.orEmpty(), maintenant() / 1000, d, bande, PskReporter.MAX_SPOTS), qth)
                    .put("source", "psk").put("since", d).put("updated", if (l.quand > 0) l.quand / 1000 else JSONObject.NULL).put("error", l.erreur ?: JSONObject.NULL)
            }
        }
        val sol = solaire.conditions(0L)
        o.put("band", bande).put("solar", JSONObject().put("solarflux", sol.opt("solarflux") ?: "").put("kindex", sol.opt("kindex") ?: "")
            .put("aindex", sol.opt("aindex") ?: "").put("updated", sol.opt("updated") ?: "").put("source", sol.opt("source") ?: "")
            .put("hf_conditions", sol.optJSONObject("hf_conditions") ?: JSONObject()))
        return Reponse.json(o).cache("public, max-age=120")
    }

    /** A callsign's rough place for the point-to-point path: its DXCC entity's centre (no lookup elsewhere, no owner's account). */
    private fun indicatif(q: Requete): Reponse {
        val call = WsprLive.indicatifNet(q.param("call"))
        val p = if (call.length >= 2) IndicatifGeo.position(call) else null
        if (p == null) return Reponse.json(JSONObject().put("call", call).put("error", "indicatif inconnu"))
        val e = Dxcc.entite(call)
        return Reponse.json(JSONObject().put("call", call).put("lat", p.first).put("lon", p.second).put("approx", true).put("entity", e?.nom ?: ""))
            .cache("public, max-age=86400")
    }

    private fun rbnBandes(q: Requete): Reponse {
        rbn.assure()
        val m = entier(q, "minutes", 10, 2, 30)
        val l = rbn.recents(m * 60_000L)
        return Reponse.json(JSONObject().put("bands", Rbn.parBande(l)).put("minutes", m).put("connected", rbn.connecte).put("total", l.size)
            .put("enabled", c.rbnActif && !c.horsLigne).put("error", rbn.erreur ?: JSONObject.NULL))
    }

    private fun rbnEmpreinte(q: Requete): Reponse {
        rbn.assure()
        val call = WsprLive.indicatifNet(q.param("call") ?: c.indicatif)
        val m = entier(q, "minutes", 15, 2, 30)
        // The site's own call: from its locator; any other: its country's centre (approximate, said so).
        val propre = call == c.indicatif
        val home = if (propre) Geo.locator(c.locator) else IndicatifGeo.position(call)
        val o = Rbn.empreinte(call, rbn.recents(m * 60_000L), home).put("minutes", m).put("connected", rbn.connecte)
            .put("home_approx", !propre).put("enabled", c.rbnActif && !c.horsLigne)
        return Reponse.json(o)
    }
}
