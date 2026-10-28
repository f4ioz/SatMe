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
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.carnet.StatsCarnet

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
        val filtre = sat.isNotEmpty() || genre.isNotEmpty() || call.isNotEmpty()
        if (l.isNotEmpty()) chiffresEtCarte(sb, l, filtre)
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
        sb.append(carteQsl())
        val indexe = !filtre && page == 1
        return s.gabarit.reponse(q, Page(if (sat.isNotEmpty()) "Logbook · $sat" else "Logbook",
            "Les QSO de ${s.config.indicatif} : satellites, HF et VHF, par satellite et par indicatif.", sb.toString(),
            "/logbook", actif = "/logbook", scripts = if (l.isNotEmpty()) listOf("js/carte-qso.js") else emptyList(),
            robots = if (indexe) "index, follow" else "noindex, follow")).cache("public, max-age=60")
    }

    /**
     * The log in figures (counted up when they come into view) and on a map: an arc from the station to each square
     * worked, as the filter chose them; the satellites by contacts beside it.
     */
    private fun chiffresEtCarte(sb: StringBuilder, l: List<fr.f4ioz.site.carnet.Qso>, filtre: Boolean) {
        val c = s.config
        val ici = s.catalogue.observateur(c.locator)
        val st = StatsCarnet.stats(l, ici?.latDeg, ici?.lonDeg)
        val carres = StatsCarnet.carres(l, ici?.latDeg, ici?.lonDeg)
        fun chiffre(n: Int, libelle: String, detail: String = "") = "<div class=\"chiffre\"><b data-compte=\"$n\">${Html.nombre(n)}</b><span>${h(libelle)}</span>" +
            (if (detail.isNotEmpty()) "<small>${h(detail)}</small>" else "") + "</div>"
        sb.append("<section class=\"carnet-chiffres\" aria-label=\"").append(if (filtre) "Ces QSO en chiffres" else "Le carnet en chiffres").append("\">")
            .append(chiffre(st.qso, "QSO")).append(chiffre(st.indicatifs, "indicatifs")).append(chiffre(st.pays, "pays (DXCC)"))
            .append(chiffre(st.carres, "carrés")).append(chiffre(st.satellites, "satellites"))
        if (st.odx != null) sb.append(chiffre(st.odxKm, "km, le plus loin", st.odx.indicatif + " · " + st.odx.carre.take(4)))
        sb.append("</section>")
        if (carres.isEmpty() || ici == null) return
        sb.append("<section class=\"card carte-qso\" aria-labelledby=\"h-carte-qso\"><header class=\"card-head\"><h2 class=\"card-title\" id=\"h-carte-qso\">")
            .append(Icones.svg("dx")).append("Carte des contacts</h2><span class=\"card-meta\">depuis ").append(h(c.locator)).append(" · ")
            .append(carres.size).append(if (carres.size > 1) " carrés" else " carré").append("</span></header><div class=\"card-body carte-qso-corps\">")
            .append("<div class=\"carte-qso-cadre\"><canvas data-carte-qso tabindex=\"0\" role=\"img\" aria-label=\"Carte : un arc de grand cercle depuis ")
            .append(h(c.locator)).append(" vers chacun des ").append(carres.size).append(" carrés contactés\"></canvas><div class=\"carte-bulle\" data-carte-bulle hidden></div></div>")
        if (st.parSatellite.isNotEmpty()) {
            val max = st.parSatellite.first().second.toDouble()
            sb.append("<div class=\"par-sat\"><h3 class=\"sous\">Par satellite</h3><ul>")
            st.parSatellite.take(8).forEach { (n, k) ->
                sb.append("<li><a href=\"/logbook?sat=").append(Html.u(n)).append("\"><span class=\"ps-n\">").append(h(n)).append("</span><span class=\"ps-b\"><i style=\"--p:")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", k / max)).append("\"></i></span><span class=\"num\">").append(k).append("</span></a></li>")
            }
            sb.append("</ul></div>")
        }
        sb.append("</div><script type=\"application/json\" id=\"carte-qso-donnees\">")
            .append(Html.jsonScript(org.json.JSONObject().put("locator", c.locator).put("lat", ici.latDeg).put("lon", ici.lonDeg).put("carres", StatsCarnet.json(carres)).toString()))
            .append("</script><footer class=\"card-foot carte-legende\"><span><i class=\"pt sat\"></i>par satellite</span><span><i class=\"pt ter\"></i>HF et VHF</span>")
            .append("<span class=\"dim\">Arcs de grand cercle depuis ").append(h(c.locator)).append(" ; carrés de quatre caractères.</span></footer></section>")
    }

    /** The station's card, in the spirit of a QSL card: who, where (the square's centre), how to confirm. */
    private fun carteQsl(): String {
        val c = s.config
        val ici = s.catalogue.observateur(c.locator)
        val coord = ici?.let { String.format(java.util.Locale.FRANCE, "%.2f° %s · %.2f° %s", kotlin.math.abs(it.latDeg), if (it.latDeg >= 0) "N" else "S",
            kotlin.math.abs(it.lonDeg), if (it.lonDeg >= 0) "E" else "O") }.orEmpty()
        return "<aside class=\"carte-qsl\" aria-label=\"Carte de la station\"><div class=\"qsl-gauche\">" + Icones.marque(64, "q") +
            "<p class=\"qsl-call\">" + h(c.indicatif) + "</p><p class=\"qsl-nom\">" + h(c.nom) + (if (c.club.isNotEmpty()) " · " + h(c.club) else "") + "</p></div>" +
            "<dl class=\"qsl-champs\"><dt>Locator</dt><dd class=\"mono\">" + h(c.locator) + "</dd>" +
            (if (coord.isNotEmpty()) "<dt>Centre du carré</dt><dd class=\"mono\">" + h(coord) + "</dd>" else "") +
            "<dt>Trafic</dt><dd>Satellites, QO-100, SSTV, DATV, HF</dd><dt>Confirmer</dt><dd><a href=\"" + h(c.qrz) + "\" rel=\"noopener\">ma page QRZ</a></dd></dl>" +
            "<p class=\"qsl-merci\">Merci pour le QSO — 73 !</p></aside>"
    }

    companion object { const val PAR_PAGE = 50 }
}
