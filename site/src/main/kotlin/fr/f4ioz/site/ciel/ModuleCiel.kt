/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.ciel

import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.demo.OrbitesJs
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.securite.Limiteur
import org.json.JSONArray

/**
 * The station's sky for the home page (« Au-dessus de JN18FS »): `/api/ciel`
 * (the satellites of the home page, their passes and where they are, worked
 * out here), `/api/carte/traits-legers` (the coastline made light for the
 * home page's globe and the contacts' map), and the page's script, put
 * together at startup from SatMe's own pure functions (`OrbitesJs.PUR`: the
 * Sun, the Moon, the night, the footprints) and the site's drawing
 * (`static/js/ciel-accueil.js`). Read-only, never hidden: an API.
 */
class ModuleCiel(private val s: Site) : ModuleSite {

    override val idReglage: String? get() = null

    private val parIp = Limiteur(30, 60_000L)

    private val legers: ByteArray by lazy {
        val t = ModuleCiel::class.java.getResourceAsStream("/fr/f4ioz/site/traits/land.json")?.bufferedReader()?.use { it.readText() }
        val a = if (t == null) JSONArray() else runCatching { JSONArray(fr.f4ioz.satcombo.demo.ApiCarte.traits(t)) }.getOrDefault(JSONArray())
        CielStation.traitsLegers(a).toString().toByteArray(Charsets.UTF_8)
    }

    override fun demarre() {
        val propre = ModuleCiel::class.java.getResourceAsStream("/fr/f4ioz/site/static/js/ciel-accueil.js")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        s.statique.ajoute("js/ciel-station.js", ("/* SatMe's pure functions (moteur: OrbitesJs.PUR) */\n" + OrbitesJs.PUR + "\n" + propre).toByteArray(Charsets.UTF_8))
    }

    override fun installe(r: Routeur) {
        r.get("/api/ciel") { q ->
            if (!parIp.accepte(q.ip)) return@get Reponse.erreur(429, "trop de requêtes, patienter une minute")
            val i = maintenant() ?: return@get Reponse.erreur(503, "éléments orbitaux absents")
            Reponse.json(CielStation.json(i, s.config.locator, satellites())).cache("public, max-age=60")
        }
        r.get("/api/carte/traits-legers") { _ -> Reponse.octets(legers, Reponse.JSON).cache("public, max-age=86400") }
    }

    /** The home page's satellites (« Admin › Paramètres », else site.conf), as the catalogue knows them. */
    fun satellites(): List<TleEntry> =
        (s.reglages.courant.satellitesAccueil ?: s.config.satellitesAccueil).mapNotNull { s.catalogue.cherche(it) }.distinctBy { it.catalogNumber }

    /** The sky now over the station; null without elements or a locator. */
    fun maintenant(t: Long = System.currentTimeMillis()): CielStation.Instantane? {
        val obs = s.catalogue.observateur(s.config.locator) ?: return null
        val sats = satellites().ifEmpty { return null }
        val passages = s.catalogue.passages(sats, obs, 24, 5.0, t)
        return CielStation.instantane(s.catalogue.predicteur, sats, obs, passages, t)
    }
}
