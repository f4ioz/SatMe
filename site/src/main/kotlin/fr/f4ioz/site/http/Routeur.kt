/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.http

/** What a route does with a request. */
typealias Traitement = (Requete) -> Reponse

/**
 * The site's routes: a method and a path, exact (`/logbook`), with variables
 * (`/articles/{annee}/{slug}`, one segment each) or a prefix (`/static/…`).
 * Each page module adds its own ([fr.f4ioz.site.ModuleSite.installe]); the
 * first route declared wins when two match.
 */
class Routeur {

    private class Route(val methode: String, val segments: List<String>, val prefixe: Boolean, val t: Traitement)

    private val routes = ArrayList<Route>()

    /**
     * Asked before each route added while it is set (a module's, see [fr.f4ioz.site.Site.routeur]):
     * an answer stops the request there (a hidden page's 404), null lets the route answer.
     */
    var garde: ((Requete) -> Reponse?)? = null

    fun get(chemin: String, t: Traitement) = ajoute("GET", chemin, false, t)
    fun post(chemin: String, t: Traitement) = ajoute("POST", chemin, false, t)
    fun delete(chemin: String, t: Traitement) = ajoute("DELETE", chemin, false, t)
    /** Every path under [chemin] (`/static/` → `/static/css/site.css`), the rest in the variable `reste`. */
    fun prefixe(methode: String, chemin: String, t: Traitement) = ajoute(methode, chemin.trimEnd('/'), true, t)

    private fun ajoute(methode: String, chemin: String, prefixe: Boolean, t: Traitement) {
        val g = garde
        routes += Route(methode, decoupe(chemin), prefixe, if (g == null) t else { q -> g(q) ?: t(q) })
    }

    private fun decoupe(c: String) = c.trim('/').split('/').filter { it.isNotEmpty() }

    /** The route for [r] (its variables filled in), null when none; [autres] said true when the path exists for another method. */
    fun trouve(r: Requete): Traitement? {
        val s = decoupe(r.chemin)
        val methode = if (r.methode == "HEAD") "GET" else r.methode
        for (route in routes) {
            if (route.methode != methode) continue
            val v = correspond(route, s) ?: continue
            r.variables.putAll(v)
            return route.t
        }
        return null
    }

    /** True when some route takes [chemin] with another method (405 rather than 404). */
    fun existeAutrement(chemin: String): Boolean {
        val s = decoupe(chemin)
        return routes.any { correspond(it, s) != null }
    }

    private fun correspond(route: Route, s: List<String>): Map<String, String>? {
        val p = route.segments
        if (route.prefixe) {
            if (s.size <= p.size) return null
        } else if (s.size != p.size) return null
        val v = HashMap<String, String>()
        for (i in p.indices) {
            val a = p[i]
            if (a.startsWith("{") && a.endsWith("}")) v[a.substring(1, a.length - 1)] = s[i]
            else if (a != s[i]) return null
        }
        if (route.prefixe) v["reste"] = s.drop(p.size).joinToString("/")
        return v
    }
}
