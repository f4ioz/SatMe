/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.pages

import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html.h

/**
 * What every site needs beside its pages: its own files (`/static/…`), the
 * icon, `robots.txt`, `sitemap.xml` (every module's paths and the
 * articles), and the old site's addresses — moved ones redirected for good,
 * the pages taken away (TM60LOUV, CW, the activation) answered « gone ».
 */
class Base(private val s: Site) : ModuleSite {

    override fun installe(r: Routeur) {
        r.prefixe("GET", "/static/") { q -> s.statique.sert(q) ?: s.pageErreur(q, 404) }
        r.get("/favicon.ico") { _ -> Reponse.redirige(s.statique.url("img/f4ioz-mark.svg"), 301) }
        r.get("/robots.txt") { _ -> Reponse.texte(robots()).cache("public, max-age=3600") }
        r.get("/sitemap.xml") { _ -> Reponse.texte(planXml(), type = "application/xml; charset=utf-8").cache("public, max-age=3600") }
        // The old site's addresses.
        for ((de, vers) in DEPLACEES) r.get(de) { _ -> Reponse.redirige(vers, 301) }
        for (p in RETIREES) {
            r.get(p) { q -> s.pageErreur(q, 410) }
            r.prefixe("GET", "$p/") { q -> s.pageErreur(q, 410) }
        }
    }

    private fun robots(): String = listOf("User-agent: *", "Allow: /", "Disallow: /api/", "Disallow: /admin", "Disallow: /sat/api/", "",
        "Sitemap: ${s.config.urlBaseNette}/sitemap.xml", "").joinToString("\n")

    private fun planXml(): String {
        val base = s.config.urlBaseNette
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
        val vus = HashSet<String>()
        fun url(chemin: String, priorite: Double, lastmod: String? = null) {
            if (!vus.add(chemin)) return
            sb.append("  <url><loc>").append(h(base + chemin)).append("</loc>")
            if (lastmod != null) sb.append("<lastmod>").append(lastmod).append("</lastmod>")
            sb.append("<priority>").append(String.format(java.util.Locale.ROOT, "%.1f", priorite)).append("</priority></url>\n")
        }
        s.modules.forEach { m -> m.plan().forEach { (c, p) -> url(c, p) } }
        s.articles.tous().forEach { a -> url(a.url, 0.7, a.date.toString()) }
        sb.append("</urlset>\n")
        return sb.toString()
    }

    companion object {
        /** The old site's paths and where they are now. */
        val DEPLACEES = mapOf("/sat/passes" to "/sat/sked", "/sstv" to "/gallery", "/about" to "/satme", "/index.html" to "/")
        /** Taken away with the new site. */
        val RETIREES = listOf("/tm60louv", "/cw", "/activation", "/login", "/logout", "/admin/visites")
    }
}

/**
 * The menu's slots for the pages of phase 2 (HF propagation, the station):
 * greyed, « bientôt ». A real module takes its slot's place.
 */
class Bientot : ModuleSite {
    override val menu = SLOTS
    override fun installe(r: Routeur) {}

    companion object {
        val SLOTS = listOf(
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.TRAFIC, 10, "/dx", "DX cluster", "dx", bientot = true),
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.TRAFIC, 20, "/bands", "Bandes", "bands", bientot = true),
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.TRAFIC, 30, "/beacons", "Balises", "beacons", bientot = true),
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.TRAFIC, 40, "/wspr", "WSPR", "wspr", bientot = true),
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.TRAFIC, 50, "/openings", "Ouvertures", "openings", bientot = true),
            fr.f4ioz.site.EntreeMenu(fr.f4ioz.site.Groupe.STATION, 10, "/station", "Station", "station", bientot = true),
        )
    }
}
