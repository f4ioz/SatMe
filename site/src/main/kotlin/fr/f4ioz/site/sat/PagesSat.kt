/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.TleEntry
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
import java.util.Locale

/**
 * Passages: the passes over any locator (the site's by default) of the
 * satellites chosen — a favourites' list kept in the visitor's browser,
 * sent in the address (`?sats=ISS,AO-91`) so the page works without
 * JavaScript too. Worked out here by SatMe's engine; each pass with its
 * track in the sky, and a timeline of the coming hours.
 */
class PagePassages(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 10, "/sat/pass", "Passages", "pass", "Les passages d'une station, ses favoris"))
    override fun plan() = listOf("/sat/pass" to 0.8)

    override fun installe(r: Routeur) { r.get("/sat/pass") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val c = s.config
        val z = c.fuseau
        val locSaisi = q.param("loc")?.trim()?.uppercase()?.take(6).orEmpty()
        val loc = locSaisi.ifEmpty { c.locator }
        val obs = s.catalogue.observateur(loc)
        val heures = (q.param("h")?.toIntOrNull() ?: 24).let { h -> listOf(12, 24, 48, 72).minByOrNull { kotlin.math.abs(it - h) }!! }
        val minEl = (q.param("el")?.toIntOrNull() ?: 0).let { e -> listOf(0, 5, 10, 20, 30).minByOrNull { kotlin.math.abs(it - e) }!! }
        val demandes = q.param("sats")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(30)
        val sats: List<TleEntry> = (demandes ?: c.satellitesAccueil).mapNotNull { s.catalogue.cherche(it) }.distinctBy { it.catalogNumber }
        val maintenant = System.currentTimeMillis()
        val passes = if (obs != null && sats.isNotEmpty()) s.catalogue.passages(sats, obs, heures, minEl.toDouble(), maintenant) else emptyList()
        val sb = StringBuilder("<header class=\"page-head\"><h1>Passages</h1><p>Les passages des satellites choisis au-dessus d'une station, calculés par le moteur de SatMe " +
            "avec les éléments AMSAT (âge : voir <a href=\"/sat/tle\">TLE</a>). Heures de Paris ; survoler une heure donne l'heure UTC.</p></header>")
        // The form (GET): the locator, the satellites, the span, the lowest elevation.
        sb.append("<form class=\"card passes-form\" method=\"get\" action=\"/sat/pass\" id=\"pass-form\"><div class=\"card-body\">")
            .append("<label>Locator <input name=\"loc\" value=\"").append(h(loc)).append("\" maxlength=\"6\" size=\"7\" required pattern=\"[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?\" autocapitalize=\"characters\"></label>")
            .append("<label>Durée <select name=\"h\">").append(listOf(12, 24, 48, 72).joinToString("") { "<option value=\"$it\"" + (if (it == heures) " selected" else "") + ">$it h</option>" }).append("</select></label>")
            .append("<label>Élévation min. <select name=\"el\">").append(listOf(0, 5, 10, 20, 30).joinToString("") { "<option value=\"$it\"" + (if (it == minEl) " selected" else "") + ">$it°</option>" }).append("</select></label>")
            .append("<label class=\"sats-l\">Satellites <input name=\"sats\" id=\"pass-sats\" list=\"pass-liste\" value=\"").append(h(sats.joinToString(", ") { it.name }))
            .append("\" placeholder=\"ISS, AO-91, RS-44…\"></label><datalist id=\"pass-liste\">")
        s.catalogue.satellites.forEach { sb.append("<option value=\"").append(h(it.name)).append("\">") }
        sb.append("</datalist><button class=\"btn solid\" type=\"submit\">Calculer</button>")
            .append("<p class=\"dim\" id=\"pass-fav\">Les satellites et le locator choisis restent dans ce navigateur (favoris).</p></div></form>")
        when {
            obs == null -> sb.append("<p class=\"card-empty\">« ").append(h(loc)).append(" » n'est pas un locator (4 ou 6 caractères : JN18, JN18FS).</p>")
            s.catalogue.satellites.isEmpty() -> sb.append("<p class=\"card-empty\">Les éléments orbitaux ne sont pas encore arrivés ; réessayez dans un instant.</p>")
            sats.isEmpty() -> sb.append("<p class=\"card-empty\">Aucun de ces satellites n'est connu. Choisissez-les dans la liste.</p>")
            else -> {
                sb.append(frise(sats, passes, maintenant, minOf(heures, 24)))
                if (passes.isEmpty()) sb.append("<p class=\"card-empty\">Aucun passage au-dessus de ").append(minEl).append("° dans les ").append(heures).append(" heures.</p>")
                else {
                    sb.append("<div class=\"table-wrap card\"><table class=\"passes\"><thead><tr><th>Satellite</th><th>AOS</th><th>LOS</th><th>Durée</th><th>Él. max</th><th>Azimuts</th><th class=\"ciel-c\">Ciel</th><th></th></tr></thead><tbody>")
                    for (p in passes.take(150)) {
                        val enCours = p.aosEpochMs <= maintenant
                        val el = p.maxElevationDeg.toInt()
                        sb.append("<tr").append(if (enCours) " class=\"encours\"" else "").append("><td class=\"name\">").append(h(p.satName))
                            .append(if (p.sunlit && p.nightAtObserver) " <span class=\"pill mini amber\" title=\"Éclairé par le Soleil dans un ciel noir : visible à l'œil nu s'il est assez gros\">visible</span>" else "")
                            .append("</td><td class=\"num\" title=\"").append(h(Html.utc(p.aosEpochMs))).append("\">").append(if (enCours) "<b class=\"now\">en cours</b>" else h(Html.jourHeure(p.aosEpochMs, z)))
                            .append("</td><td class=\"num\" title=\"").append(h(Html.utc(p.losEpochMs))).append("\">").append(h(Html.heure(p.losEpochMs, z)))
                            .append("</td><td class=\"num\">").append(h(Html.duree(p.losEpochMs - p.aosEpochMs))).append("</td><td class=\"num el ")
                            .append(if (el >= 45) "high" else if (el < 15) "low" else "").append("\">").append(el).append("°</td><td class=\"num\">")
                            .append(String.format(Locale.FRANCE, "%.0f° → %.0f°", p.aosAzimuthDeg, p.losAzimuthDeg)).append("</td><td class=\"ciel-c\">").append(cielSvg(p))
                            .append("</td><td class=\"act\"><a href=\"/sat/sked?sat=").append(p.catalogNumber).append("\" title=\"Préparer un sked sur ce satellite\">Sked</a></td></tr>")
                    }
                    sb.append("</tbody></table></div>")
                }
            }
        }
        val js = """(function(){var f=document.getElementById('pass-form');if(!f)return;var K='site-pass';
var u=new URLSearchParams(location.search);var d={};try{d=JSON.parse(localStorage.getItem(K))||{}}catch(e){}
if(!u.has('sats')&&!u.has('loc')&&(d.sats||d.loc)){if(d.sats)f.sats.value=d.sats;if(d.loc)f.loc.value=d.loc;f.submit();return;}
f.addEventListener('submit',function(){try{localStorage.setItem(K,JSON.stringify({sats:f.sats.value,loc:f.loc.value.toUpperCase()}))}catch(e){}});})();"""
        return s.gabarit.reponse(q, Page("Passages des satellites radioamateurs",
            "Les prochains passages des satellites radioamateurs au-dessus de n'importe quel locator : AOS, LOS, élévation, trace dans le ciel. Calculés par SatMe.",
            sb.toString(), "/sat/pass", robots = if (q.requete.isEmpty()) "index, follow" else "noindex, follow", scriptEnLigne = js)).cache("public, max-age=60")
    }

    /** A pass's track in the sky, small: the horizon, 30° and 60°, north up. */
    private fun cielSvg(p: SatPass): String {
        if (p.track.size < 2) return ""
        val pts = p.track.filterIndexed { i, _ -> i % 2 == 0 || i == p.track.size - 1 }.joinToString(" ") { (az, el) ->
            val r = 20 * (1 - el.coerceIn(0.0, 90.0) / 90); val a = Math.toRadians(az)
            String.format(Locale.ROOT, "%.1f,%.1f", r * kotlin.math.sin(a), -r * kotlin.math.cos(a))
        }
        val (az0, el0) = p.track.first()
        val r0 = 20 * (1 - el0.coerceIn(0.0, 90.0) / 90); val a0 = Math.toRadians(az0)
        return "<svg class=\"mini-ciel\" viewBox=\"-22 -22 44 44\" width=\"44\" height=\"44\" role=\"img\" aria-label=\"Trace du passage dans le ciel\">" +
            "<circle r=\"20\"/><circle r=\"13.3\"/><circle r=\"6.7\"/><path d=\"M0-20V20M-20 0H20\"/>" +
            "<polyline points=\"$pts\"/>" + String.format(Locale.ROOT, "<circle class=\"aos\" cx=\"%.1f\" cy=\"%.1f\" r=\"1.8\"/>", r0 * kotlin.math.sin(a0), -r0 * kotlin.math.cos(a0)) + "</svg>"
    }

    /** The coming hours: one line per satellite, its passes as bars (taller, the higher). */
    private fun frise(sats: List<TleEntry>, passes: List<SatPass>, debut: Long, heures: Int): String {
        val fin = debut + heures * 3_600_000L
        val w = 1000.0; val ligne = 22; val gauche = 120.0; val hTotal = sats.size * ligne + 26
        fun x(t: Long) = gauche + (t - debut).coerceIn(0, fin - debut).toDouble() / (fin - debut) * (w - gauche - 8)
        val sb = StringBuilder("<figure class=\"card frise\"><svg viewBox=\"0 0 $w $hTotal\" role=\"img\" aria-label=\"Frise des passages des $heures prochaines heures\">")
        val z = s.config.fuseau
        // Hours.
        val premiere = (debut / 3_600_000L + 1) * 3_600_000L
        var t = premiere
        while (t < fin) {
            val xx = x(t)
            val hh = Html.heure(t, z)
            sb.append(String.format(Locale.ROOT, "<line class=\"h\" x1=\"%.1f\" x2=\"%.1f\" y1=\"0\" y2=\"%d\"/>", xx, xx, hTotal - 18))
            if (hh.endsWith(":00") && (hh.substring(0, 2).toInt() % (if (heures > 12) 3 else 1) == 0))
                sb.append(String.format(Locale.ROOT, "<text class=\"ht\" x=\"%.1f\" y=\"%d\">%s</text>", xx, hTotal - 4, hh.substring(0, 2) + " h"))
            t += 3_600_000L
        }
        sats.forEachIndexed { i, sat ->
            val y = i * ligne + 4
            sb.append("<text class=\"nom\" x=\"4\" y=\"").append(y + 14).append("\">").append(h(sat.name.take(16))).append("</text>")
            passes.filter { it.catalogNumber == sat.catalogNumber && it.aosEpochMs < fin }.forEach { p ->
                val x0 = x(p.aosEpochMs); val x1 = maxOf(x(p.losEpochMs), x0 + 2)
                val op = 0.35 + 0.65 * (p.maxElevationDeg.coerceIn(0.0, 60.0) / 60)
                sb.append(String.format(Locale.ROOT, "<rect class=\"p%s\" x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"16\" rx=\"3\" fill-opacity=\"%.2f\"><title>%s %s · %d°</title></rect>",
                    if (p.maxElevationDeg >= 45) " haut" else "", x0, y + 2, x1 - x0, op, h(p.satName), h(Html.heure(p.aosEpochMs, z)), p.maxElevationDeg.toInt()))
            }
        }
        sb.append(String.format(Locale.ROOT, "<line class=\"maintenant\" x1=\"%.1f\" x2=\"%.1f\" y1=\"0\" y2=\"%d\"/>", gauche, gauche, hTotal - 18))
        sb.append("</svg><figcaption>Les $heures prochaines heures (heure de Paris) : chaque barre est un passage, plus vive quand il monte plus haut (vert : au-dessus de 45°).</figcaption></figure>")
        return sb.toString()
    }
}

/** Ciel: the station's sky now — every satellite above the horizon, where, how far — worked out in the browser each second. */
class PageCiel(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 40, "/sat/radar", "Ciel", "radar", "Le ciel en direct, les satellites visibles"))
    override fun plan() = listOf("/sat/radar" to 0.6)

    override fun installe(r: Routeur) { r.get("/sat/radar") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val corps = """<header class="page-head"><h1>Ciel</h1><p>Le ciel d'une station en ce moment : les satellites au-dessus de l'horizon, leur azimut et leur élévation,
calculés chaque seconde dans votre navigateur avec les éléments AMSAT du site.</p></header>
<script type="application/json" id="ciel-donnees">${Html.jsonScript(org.json.JSONObject().put("locator", s.config.locator).toString())}</script>
<div class="ciel-grille">
<section class="card"><header class="card-head"><h2 class="card-title">Le ciel</h2><span class="card-meta" id="ciel-heure">--:--:--</span></header>
<div class="card-body"><form class="ciel-form" id="ciel-form"><label>Locator <input id="ciel-loc" maxlength="6" size="7" autocapitalize="characters" value="${h(s.config.locator)}"></label>
<label><input type="checkbox" id="ciel-tous"> tous les satellites (sinon : les favoris de la page Passages)</label></form>
<svg id="ciel-svg" viewBox="-115 -115 230 230" role="img" aria-label="Le ciel de la station : les satellites visibles"></svg>
<noscript><p class="card-empty">Le ciel en direct demande JavaScript ; les <a href="/sat/pass">Passages</a> s'en passent.</p></noscript></div></section>
<section class="card"><header class="card-head"><h2 class="card-title">Au-dessus de l'horizon</h2><span class="card-meta" id="ciel-n"></span></header>
<div class="card-body"><table class="ciel-liste"><thead><tr><th>Satellite</th><th>Az.</th><th>Él.</th><th>Distance</th><th></th></tr></thead><tbody id="ciel-l"><tr><td colspan="5" class="dim">Chargement…</td></tr></tbody></table>
<h3 class="sous">Bientôt</h3><ul class="rows" id="ciel-bientot"></ul></div></section>
</div>"""
        return s.gabarit.reponse(q, Page("Ciel — les satellites visibles en direct", "Le ciel radioamateur en direct : les satellites au-dessus de l'horizon d'une station, azimut, élévation et distance.",
            corps, "/sat/radar", scripts = listOf("/api/lib/satellite.js", "js/ciel.js"))).cache("public, max-age=300")
    }
}

/** TLE: the elements the site uses, their age and their source. */
class PageTle(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SATELLITES, 50, "/sat/tle", "TLE", "tle", "Les éléments orbitaux, leur âge"))
    override fun plan() = listOf("/sat/tle" to 0.4)

    override fun installe(r: Routeur) {
        r.get("/sat/tle") { q -> page(q) }
        r.get("/sat/tle.txt") { _ -> Reponse.texte(s.catalogue.satellites.joinToString("") { it.name + "\n" + it.line1 + "\n" + it.line2 + "\n" }).cache("public, max-age=600") }
    }

    private fun page(q: Requete): Reponse {
        val maintenant = System.currentTimeMillis()
        val z = s.config.fuseau
        val filtre = q.param("q")?.trim()?.take(30).orEmpty()
        val l = s.catalogue.satellites.filter { filtre.isEmpty() || it.name.contains(filtre, true) || it.catalogNumber.toString() == filtre }
        val gp = s.catalogue.serveurNom
        val sb = StringBuilder("<header class=\"page-head\"><h1>TLE</h1><p>Les éléments orbitaux qu'utilisent les pages du site, tous pris au <b>serveur GP SatMe (")
            .append(h(gp)).append(")</b> — jamais directement à CelesTrak ou AMSAT : le serveur les relaie pour tous — relus toutes les six heures ; " +
            "le plus frais par satellite, son âge ci-dessous. <a href=\"/sat/tle.txt\">Le fichier TLE</a>.</p></header>")
        sb.append("<section class=\"card\"><header class=\"card-head\"><h2 class=\"card-title\">Source : serveur GP SatMe (").append(h(gp)).append(")</h2>")
        if (s.catalogue.pauseJusqua > maintenant) sb.append("<span class=\"card-meta\">en pause jusqu'au ").append(h(Html.utc(s.catalogue.pauseJusqua))).append("</span>")
        sb.append("</header><div class=\"card-body\"><ul class=\"rows sources\">")
        s.catalogue.etats.forEach { e ->
            sb.append("<li><span><b>").append(h(e.nom)).append("</b>").append(if (e.adresse.isNotEmpty()) " <span class=\"dim\">" + h(e.adresse.removePrefix("https://")) + "</span>" else "")
                .append("</span><span class=\"num\">").append(if (e.lu > 0) h(Html.dateHeure(e.lu, z)) else "jamais lu").append("</span><span class=\"num\">")
                .append(e.nombre).append(" sat.</span>").append(if (e.erreur.isNotEmpty()) "<span class=\"num low\">" + h(e.erreur) + "</span>" else "").append("</li>")
        }
        sb.append("</ul></div></section>")
        sb.append("<form class=\"card filtres\" method=\"get\" action=\"/sat/tle\"><div class=\"card-body\"><label>Satellite <input name=\"q\" value=\"").append(h(filtre))
            .append("\" placeholder=\"ISS, 25544…\"></label><button class=\"btn solid\">Chercher</button><span class=\"dim\">").append(l.size).append(" satellites</span></div></form>")
        sb.append("<div class=\"table-wrap card\"><table class=\"tle\"><thead><tr><th>Satellite</th><th>N° NORAD</th><th>Époque (UTC)</th><th>Âge</th><th>Période</th><th class=\"lignes\">Éléments</th></tr></thead><tbody>")
        for (e in l.take(400)) {
            val ep = e.epochMs
            val age = ep?.let { (maintenant - it) / 86_400_000.0 }
            val mm = e.line2.substring(52, minOf(63, e.line2.length)).trim().toDoubleOrNull()
            sb.append("<tr><td class=\"name\">").append(h(e.name)).append("</td><td class=\"num\">").append(e.catalogNumber).append("</td><td class=\"num\">")
                .append(ep?.let { h(Html.utc(it).removeSuffix(" UTC")) } ?: "—").append("</td><td class=\"num ")
                .append(when { age == null -> ""; age > 14 -> "low"; age > 4 -> "mid"; else -> "high" }).append("\">")
                .append(age?.let { String.format(Locale.FRANCE, "%.1f j", it) } ?: "—").append("</td><td class=\"num\">")
                .append(mm?.takeIf { it > 0 }?.let { String.format(Locale.FRANCE, "%.1f min", 1440 / it) } ?: "—")
                .append("</td><td class=\"lignes\"><code>").append(h(e.line1)).append("<br>").append(h(e.line2)).append("</code></td></tr>")
        }
        sb.append("</tbody></table></div>")
        return s.gabarit.reponse(q, Page("TLE — éléments orbitaux", "Les éléments orbitaux (TLE) des satellites radioamateurs utilisés par le site : époque, âge, pris au serveur GP SatMe.",
            sb.toString(), "/sat/tle", robots = if (filtre.isEmpty()) "index, follow" else "noindex, follow")).cache("public, max-age=300")
    }
}
