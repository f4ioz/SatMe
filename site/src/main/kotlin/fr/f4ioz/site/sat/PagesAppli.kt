/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.demo.OngletOrbites
import fr.f4ioz.satcombo.demo.OngletSked
import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page
import org.json.JSONObject

/** The data SatMe's tabs read on the site (`#site-donnees`): the site's station. */
private fun donnees(s: Site): String = "<script type=\"application/json\" id=\"site-donnees\">" +
    Html.jsonScript(JSONObject().put("locator", s.config.locator).put("indicatif", s.config.indicatif).toString()) + "</script>"

/**
 * Orbites, SatMe-PC's tab as it is: the Earth in 3D or on a map with the
 * satellites, their footprints and orbits, a pass played as an animation,
 * six looks, the dashboard (Tableau) and its themes up to the Vitrine for
 * a club's stand, the ISS's live video. Worked out in the browser
 * (satellite.js, three.js, bundled): the site only hands out the elements.
 */
class PageOrbites(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 30, "/sat/orbites", "Orbites", "orbites", "La Terre en 3D, les satellites, un passage animé"))
    override fun plan() = listOf("/sat/orbites" to 0.7)

    override fun demarre() { AppliSatme.installe(s.statique) }

    override fun installe(r: Routeur) { r.get("/sat/orbites") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val corps = "<h1 class=\"visually-hidden\">Orbites — les satellites autour de la Terre</h1>" + donnees(s) +
            "<div class=\"satme-onglet onglet-orbites\">" + AppliSatme.visible(OngletOrbites.HTML) + "</div>" +
            "<p class=\"appli-credits\">Les satellites sont calculés dans votre navigateur (SGP4, satellite.js) avec les éléments AMSAT du site ; " +
            "Terre : Natural Earth et ETOPO1 (domaine public), drapeaux Twemoji (CC BY 4.0), three.js (MIT). C'est l'onglet Orbites de <a href=\"/satme\">SatMe-PC</a>.</p>"
        return s.gabarit.reponse(q, Page("Orbites — satellites en 3D",
            "Les satellites radioamateurs autour de la Terre en 3D ou sur une carte, leurs empreintes, un passage animé depuis ${s.config.locator}, en plein écran : l'onglet Orbites de SatMe-PC.",
            corps, "/sat/orbites", styles = listOf("css/appli.css", "css/satme-onglets.css"), scripts = listOf("js/orbites.js"),
            appli = true, youtube = true, ogImage = s.statique.url("img/satme/pc-orbites-tableau.webp").substringBefore('?'))).cache("public, max-age=300")
    }
}

/**
 * Sked, SatMe-PC's tab: the windows in which two stations both see a
 * satellite in the next 48 hours, each replayed with both skies, on the map
 * or on the Orbites globe with its looks. « My » station is the visitor's.
 */
class PageSked(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 20, "/sat/sked", "Sked", "sked", "Les fenêtres communes de deux stations"))
    override fun plan() = listOf("/sat/sked" to 0.7)

    override fun installe(r: Routeur) { r.get("/sat/sked") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val corps = "<header class=\"page-head appli-tete\"><h1>Sked</h1><p>Les fenêtres des 48 prochaines heures où deux stations voient ensemble un satellite, " +
            "rejouées sur la carte ou sur le globe d'Orbites.</p><label class=\"site-moi\">Ma station <input id=\"site-moi\" value=\"" + h(s.config.locator) +
            "\" maxlength=\"6\" size=\"7\" autocapitalize=\"characters\" spellcheck=\"false\" aria-label=\"Mon locator\"></label></header>" + donnees(s) +
            "<div class=\"satme-onglet onglet-sked\">" + AppliSatme.visible(OngletSked.HTML) + "</div>" +
            "<p class=\"appli-credits\">Fond de carte © <a href=\"https://www.openstreetmap.org/copyright\" rel=\"noopener\">les contributeurs d'OpenStreetMap</a>. " +
            "Fenêtres calculées par le moteur de <a href=\"/satme\">SatMe</a> avec les éléments AMSAT.</p>"
        return s.gabarit.reponse(q, Page("Sked — fenêtres communes à deux stations",
            "Préparer un sked satellite : les fenêtres où deux stations (deux locators) voient ensemble un satellite radioamateur, rejouées sur la carte et sur le globe.",
            corps, "/sat/sked", styles = listOf("css/appli.css", "css/satme-onglets.css"), scripts = listOf("js/sked.js"), appli = true)).cache("public, max-age=300")
    }
}
