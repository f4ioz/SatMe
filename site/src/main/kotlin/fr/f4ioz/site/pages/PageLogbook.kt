/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.pages

import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page

/**
 * The logbook: every contact (Wavelog's, SatMe's), the newest first, by
 * satellite or by kind (satellites, HF, VHF and up), a callsign looked for;
 * fifty a page. Public: who, when (UTC), band or satellite, mode, square.
 */
class PageLogbook(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SITE, 40, "/logbook", "Logbook", "logbook", "Les QSO, par satellite"))
    override fun plan() = listOf("/logbook" to 0.6)

    override fun installe(r: Routeur) { r.get("/logbook") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val tous = s.logbook.tous()
        val sat = q.param("sat")?.trim()?.take(24).orEmpty()
        val genre = q.param("genre")?.takeIf { it in setOf("sat", "hf", "vhf") }.orEmpty()
        val call = q.param("call")?.trim()?.uppercase()?.filter { it.isLetterOrDigit() || it == '/' }?.take(15).orEmpty()
        val l = tous.filter { k ->
            (sat.isEmpty() || k.satellite.equals(sat, true)) &&
                (genre.isEmpty() || when (genre) { "sat" -> k.satellite.isNotEmpty(); "hf" -> k.satellite.isEmpty() && k.bande.endsWith("m") && (k.bande.removeSuffix("m").toIntOrNull() ?: 0) >= 10; else -> k.satellite.isEmpty() && !(k.bande.endsWith("m") && (k.bande.removeSuffix("m").toIntOrNull() ?: 0) >= 10) }) &&
                (call.isEmpty() || k.indicatif.contains(call))
        }
        val pages = maxOf(1, (l.size + PAR_PAGE - 1) / PAR_PAGE)
        val page = (q.param("page")?.toIntOrNull() ?: 1).coerceIn(1, pages)
        val sats = tous.map { it.satellite }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val sb = StringBuilder("<header class=\"page-head\"><h1>Logbook</h1><p>")
            .append(tous.size).append(" QSO, dont ").append(tous.count { it.satellite.isNotEmpty() }).append(" par satellite")
            .append(" · ").append(tous.map { it.indicatif }.distinct().size).append(" indicatifs · ").append(tous.map { it.carre }.filter { it.length == 4 }.distinct().size).append(" carrés")
        if (s.logbook.luLe > 0) sb.append(" · Wavelog lu le ").append(h(Html.dateHeure(s.logbook.luLe, s.config.fuseau)))
        sb.append("</p></header>")
        // The filters: a plain form (GET), no script needed.
        sb.append("<form class=\"filtres card\" method=\"get\" action=\"/logbook\"><div class=\"card-body\"><label>Satellite <select name=\"sat\"><option value=\"\">tous</option>")
        sats.forEach { (n, c) -> sb.append("<option value=\"").append(h(n)).append("\"").append(if (n.equals(sat, true)) " selected" else "").append(">").append(h(n)).append(" (").append(c).append(")</option>") }
        sb.append("</select></label><label>Trafic <select name=\"genre\">")
        listOf("" to "tout", "sat" to "satellites", "hf" to "HF", "vhf" to "VHF et au-dessus").forEach { (v, t) ->
            sb.append("<option value=\"").append(v).append("\"").append(if (v == genre) " selected" else "").append(">").append(t).append("</option>") }
        sb.append("</select></label><label>Indicatif <input name=\"call\" value=\"").append(h(call)).append("\" placeholder=\"F4…\" maxlength=\"15\" autocapitalize=\"characters\"></label>")
            .append("<button class=\"btn solid\" type=\"submit\">Filtrer</button>")
        if (sat.isNotEmpty() || genre.isNotEmpty() || call.isNotEmpty()) sb.append(" <a class=\"btn\" href=\"/logbook\">Tout voir</a>")
        sb.append("</div></form>")
        if (l.isEmpty()) sb.append("<p class=\"card-empty\">").append(if (tous.isEmpty()) "Le logbook n'est pas disponible pour le moment." else "Aucun QSO ne correspond.").append("</p>")
        else {
            sb.append("<div class=\"table-wrap card\"><table class=\"log\"><thead><tr><th>Date (UTC)</th><th>Indicatif</th><th>Satellite / bande</th><th>Mode</th><th>Carré</th><th class=\"pays\">Pays</th></tr></thead><tbody>")
            for (k in l.drop((page - 1) * PAR_PAGE).take(PAR_PAGE)) {
                sb.append("<tr><td class=\"num\">").append(h(Html.utc(k.quandMs).removeSuffix(" UTC"))).append("</td><td class=\"call\">").append(h(k.indicatif))
                    .append(if (k.source == "satme") " <span class=\"pill mini\" title=\"Publié par SatMe-PC\">SatMe</span>" else "")
                    .append("</td><td>").append(if (k.satellite.isNotEmpty()) "<a class=\"sat\" href=\"/logbook?sat=" + Html.u(k.satellite) + "\">" + h(k.satellite) + "</a>" else h(k.bande))
                    .append("</td><td>").append(h(k.mode)).append("</td><td class=\"num\">").append(h(k.carre)).append("</td><td class=\"pays\">")
                    .append(h(k.pays.lowercase().replaceFirstChar { it.titlecase() })).append("</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }
        if (pages > 1) {
            val base = "/logbook?" + listOfNotNull(sat.takeIf { it.isNotEmpty() }?.let { "sat=" + Html.u(it) }, genre.takeIf { it.isNotEmpty() }?.let { "genre=$it" },
                call.takeIf { it.isNotEmpty() }?.let { "call=" + Html.u(it) }).joinToString("&").let { if (it.isEmpty()) "" else "$it&" }
            sb.append("<nav class=\"pager\" aria-label=\"Pagination\">")
                .append(if (page > 1) "<a href=\"${h(base)}page=${page - 1}\">← plus récents</a>" else "<span></span>")
                .append("<span>page $page / $pages</span>")
                .append(if (page < pages) "<a href=\"${h(base)}page=${page + 1}\">plus anciens →</a>" else "<span></span>").append("</nav>")
        }
        val filtre = sat.isNotEmpty() || genre.isNotEmpty() || call.isNotEmpty() || page > 1
        return s.gabarit.reponse(q, Page(if (sat.isNotEmpty()) "Logbook · $sat" else "Logbook",
            "Les QSO de ${s.config.indicatif} : satellites, HF et VHF, par satellite et par indicatif.", sb.toString(),
            "/logbook", actif = "/logbook",
            robots = if (filtre) "noindex, follow" else "index, follow")).cache("public, max-age=60")
    }

    companion object { const val PAR_PAGE = 50 }
}
