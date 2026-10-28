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
import fr.f4ioz.site.ciel.CielStation
import fr.f4ioz.site.direct.ApiDirect
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.vue.Page
import org.json.JSONArray
import org.json.JSONObject

/**
 * The home page: who, where, and cards that live — the next passes over the
 * station, the last contacts, the last article, the last SSTV picture or
 * planche SatMe published, SatMe itself, the tools.
 */
class Accueil(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SITE, 10, "/", "Accueil", "home", actifPour = { it == "/" }))
    override fun plan() = listOf("/" to 1.0)

    override fun installe(r: Routeur) { r.get("/") { q -> page(q) } }

    private fun page(q: Requete): Reponse {
        val c = s.config
        val z = c.fuseau
        val reg = s.reglages.courant
        val sec = reg.accueil
        val sb = StringBuilder()
        val ville = z.id.substringAfterLast('/').replace('_', ' ')
        sb.append("""<section class="home-hero" aria-labelledby="hero-title">${Icones.marque(88, "h")}<div class="hero-txt">
<h1 id="hero-title">${h(c.indicatif)}<small>${h(c.nom)} · radioamateur · ${h(c.locator)}${if (c.club.isNotEmpty()) " · " + h(c.club) else ""}</small></h1>
<p>Satellites, trafic HF et VHF depuis la région parisienne : articles, images SSTV reçues, logbook, outils de passages
et de poursuite — et SatMe, l'appli de poursuite de satellites que je développe.</p>
<div class="pills"><span class="pill mono">${h(c.locator)}</span>${if (s.visible("/sat/pass")) "<a class=\"pill\" href=\"/sat/pass\">Prochains passages →</a>" else ""}${if (s.visible("/satme")) "<a class=\"pill\" href=\"/satme\">SatMe →</a>" else ""}</div></div>
<div class="hero-horloges" aria-hidden="true"><span class="hh utc"><span class="hh-l">UTC</span><span class="hh-v" data-utc-clock="nu">--:--:--</span></span>
<span class="hh"><span class="hh-l">${h(ville)}</span><span class="hh-v" data-heure-locale="${h(z.id)}">--:--</span></span></div></section>
<div class="home-grid">
""")
        // Live from the station (« Admin › Paramètres »): QO-100's spectrum, the DATV picture, or BATC's pages.
        val avecDirect = reg.direct.actif && reg.direct.accueil
        if (avecDirect) sb.append(ApiDirect.bloc(s, place = "@direct@"))
        // « Au-dessus de JN18FS »: the globe, the sky dial, the next AOS (needs the elements).
        val ciel = if (sec.ciel) s.ciel.maintenant() else null
        if (ciel != null) carteCiel(sb, ciel)
        // Each card on or off; a card whose page is hidden goes with it.
        val avecPassages = sec.passages
        val avecQso = sec.qso && s.visible("/logbook")
        if (avecPassages) passages(sb, if (avecQso) "span-7" else "span-12")
        // Last contacts.
        if (avecQso) contacts(sb, if (avecPassages) "span-5" else "span-12")
        // Last picture published by SatMe.
        val image = if (sec.image && s.visible("/gallery")) s.publications.visibles().firstOrNull { it.type.image } else null
        val article = if (sec.article && s.visible("/articles")) s.articles.tous().firstOrNull() else null
        if (image != null) {
            sb.append("""<section class="card span-5" style="@image@" aria-labelledby="h-image"><header class="card-head"><h2 class="card-title" id="h-image">${Icones.svg("sstv")}Dernière ${if (image.type == TypePublication.PLANCHE) "planche" else "image SSTV"}</h2>
<span class="card-meta">${h(Html.utc(image.quandMs))}</span></header><div class="card-body derniere-image"><a href="/gallery/item/${image.id}">
<img src="/media/${image.id}/vignette.jpg" alt="${h(image.titre)}" loading="lazy" width="${if (image.largeur > 0) minOf(480, image.largeur) else 320}"></a>
<p><b>${h(image.titre)}</b>${if (image.groupe != image.satellite && image.groupe != "Divers") " · " + h(image.groupe) else ""}</p></div>
<footer class="card-foot">${if (image.type == TypePublication.SSTV) "<a href=\"/gallery/sstv\">Le mur SSTV</a>" else "<a href=\"/gallery\">Toutes les images</a>"}</footer></section>
""")
        }
        if (article != null) {
            sb.append("""<section class="card span-7" style="@article@" aria-labelledby="h-article"><header class="card-head"><h2 class="card-title" id="h-article">${Icones.svg("articles")}Dernier article</h2>
<time class="card-meta" datetime="${article.date}">${h(Html.date(article.ms(z), z))}</time></header><div class="card-body latest-article">
<h3><a href="${article.url}">${h(article.titre)}</a></h3>""")
            if (article.tags.isNotEmpty()) sb.append("<p class=\"meta\">").append(article.tags.joinToString(" ") { "<a href=\"/articles?tag=${Html.u(it)}\" class=\"tag\">#${h(it)}</a>" }).append("</p>")
            if (article.resume.isNotEmpty()) sb.append("<p>").append(h(article.resume)).append("</p>")
            sb.append("""</div><footer class="card-foot"><a href="${article.url}">Lire l'article</a><a href="/articles">Tous les articles</a></footer></section>
""")
        }
        val largeur = if (!sec.satme) "" else if ((image == null) == (article == null)) "span-12" else if (image == null) "span-5" else "span-7"
        val avecOutils = sec.outils || sec.satme
        if (sec.satme) sb.append("""<section class="card $largeur${if (avecOutils) " carte-satme" else ""}" aria-labelledby="h-satme"><header class="card-head"><h2 class="card-title" id="h-satme">${Icones.svg("satme")}SatMe</h2>
<span class="card-meta">Android · PC</span></header><div class="card-body satme-teaser">
<img src="${s.statique.url("img/satme/fr-pass.webp")}" width="96" height="208" loading="lazy" alt="SatMe sur Android : passage de RS-44, boussole et fréquences corrigées du Doppler">
<div><p><strong>Poursuite de satellites radioamateurs</strong> : passages, pointage, Doppler, carnet, SSTV, QO-100. Sur le téléphone, sur le PC et au pied du mât.</p>
<p>Gratuit, sans compte, logiciel libre. Les images SSTV et les planches de ce site arrivent directement de SatMe-PC.</p></div></div>
<footer class="card-foot">${liens("/satme" to "Découvrir SatMe")}<a href="https://github.com/f4ioz/SatMe" rel="noopener">GitHub</a></footer></section>
""")
        val tuiles = listOf(tuile("/sat/pass", "pass", "Passages", "Les passages d'une station, ses favoris"),
            tuile("/sat/sked", "sked", "Sked", "Les fenêtres communes de deux stations, rejouées"),
            tuile("/sat/orbites", "orbites", "Orbites", "La Terre en 3D, les satellites, un passage animé"),
            tuile("/sat/radar", "radar", "Ciel", "Le ciel en direct, les satellites visibles"),
            tuile("/sat/tle", "tle", "TLE", "Les éléments orbitaux, leur âge"),
            tuile("/grid", "grid", "Grid", "Locator ↔ coordonnées, distance et azimut"),
            tuile("/gallery", "gallery", "Galeries", "Images SSTV, planches et photos"),
            tuile("/logbook", "logbook", "Logbook", "Les QSO, par satellite")).filter { it.isNotEmpty() && sec.outils } +
            // On one screen the SatMe card folds into the tools' strip.
            (if (sec.satme && s.visible("/satme")) listOf(tuile("/satme", "satme", "SatMe", "L'appli de poursuite").replace("<a ", "<a class=\"tuile-satme\" ")) else emptyList())
        if (tuiles.isNotEmpty()) sb.append("""<section class="card span-12 outils-bande" style="@outils@" aria-labelledby="h-tools"><header class="card-head"><h2 class="card-title" id="h-tools">${Icones.svg("grid")}Outils</h2></header>
<div class="card-body"><nav class="tool-tiles" aria-label="Outils">
${tuiles.joinToString("\n")}
</nav></div></section>
""")
        sb.append("</div>\n")
        // The desktop's one-screen layout: each card's place on the grid.
        val presentes = listOfNotNull("direct".takeIf { avecDirect }, "ciel".takeIf { ciel != null }, "passages".takeIf { avecPassages }, "qso".takeIf { avecQso },
            "image".takeIf { image != null }, "article".takeIf { article != null }, "outils".takeIf { tuiles.isNotEmpty() })
        val places = places(presentes)
        var html = sb.toString()
        for (k in listOf("direct", "ciel", "passages", "qso", "image", "article", "outils")) html = html.replace("@$k@", places[k].orEmpty())
        val jsonLd = JSONObject().put("@context", "https://schema.org").put("@type", "Person").put("name", c.nom).put("alternateName", c.indicatif)
            .put("url", c.urlBaseNette + "/").put("sameAs", JSONArray(listOf(c.qrz, c.github)))
            .also { j -> if (c.club.isNotEmpty()) j.put("memberOf", JSONObject().put("@type", "Organization").put("name", c.club)) }
            .put("knowsAbout", JSONArray(listOf("Radioamateur", "Satellites radioamateurs", "SSTV", "QO-100")))
        return s.gabarit.reponse(q, Page("", "Site radioamateur de ${c.indicatif} — ${c.nom} (${c.locator})${if (c.club.isNotEmpty()) ", " + c.club else ""} : articles, images SSTV reçues, logbook, passages de satellites, Sked, Orbites et SatMe.",
            html, "/", jsonLd = jsonLd.toString(), classeCorps = "ecran", ogImage = s.statique.url("img/satme/pc-orbites-tableau.webp").substringBefore('?'),
            scripts = (if (ciel != null) listOf("/api/lib/satellite.js", "js/ciel-station.js") else emptyList()) +
                (if (reg.direct.actif && reg.direct.accueil) listOf("js/direct.js") else emptyList()))).cache("public, max-age=60")
    }

    /**
     * « Au-dessus de JN18FS »: the Earth seen from space with the station, the satellites and their footprints (a canvas the
     * script draws), the sky dial (drawn here, the script keeps it alive), the pass under way or the next AOS with its
     * countdown, the Sun and the Moon. Without a script: the dial and the next pass as they were when the page was made.
     */
    private fun carteCiel(sb: StringBuilder, i: CielStation.Instantane) {
        val c = s.config
        val z = c.fuseau
        val en = i.enCours
        val p = en ?: i.prochain
        sb.append("""<section class="card carte-ciel" style="@ciel@" aria-labelledby="h-ciel" data-ciel><header class="card-head"><h2 class="card-title" id="h-ciel">${Icones.svg("radar")}Au-dessus de ${h(c.locator)}</h2>
<span class="card-meta"><span data-ciel-nb>${enVue(i.vus.size)}</span></span></header><div class="card-body ciel-corps">
<div class="ciel-globe"><canvas data-ciel-globe tabindex="0" role="img" aria-label="La Terre vue de l'espace : la station ${h(c.locator)}, les satellites et leurs empreintes. Flèches : tourner ; Entrée : revenir à la station."></canvas>
<p class="ciel-globe-att" data-ciel-attente>La Terre se dessine…</p></div>
<div class="ciel-cote"><div class="ciel-aos" data-ciel-aos>""")
        if (p == null) sb.append("<span class=\"ciel-aos-l\">Aucun passage au-dessus de 5° dans les 24 heures</span>")
        else {
            sb.append("<span class=\"ciel-aos-l\">").append(if (en != null) "<span class=\"bd-led\"></span>Passage en cours" else "Prochain passage").append("</span>")
                .append("<b class=\"ciel-aos-nom\">").append(h(p.satName)).append("</b><span class=\"ciel-aos-compte\" data-ciel-compte>")
                .append(if (en != null) "<small>LOS dans</small>" + h(CielStation.compte(en.losEpochMs - i.t)) else "<small>dans</small>" + h(CielStation.compte(p.aosEpochMs - i.t))).append("</span>")
                .append("<span class=\"ciel-aos-det\">").append(h(Html.heure(p.aosEpochMs, z))).append("–").append(h(Html.heure(p.losEpochMs, z)))
                .append(" · max ").append(p.maxElevationDeg.toInt()).append("° · ").append(h(cap(p.aosAzimuthDeg))).append(" → ").append(h(cap(p.losAzimuthDeg))).append("</span>")
        }
        sb.append("</div>").append(CielStation.svg(i, "Le ciel de ${c.locator} maintenant : les satellites au-dessus de l'horizon et la trace du prochain passage"))
        sb.append("<ul class=\"ciel-astres\" data-ciel-astres><li class=\"soleil\"><span class=\"ast\" aria-hidden=\"true\"></span>Soleil <b>")
            .append(i.soleilEl.toInt()).append("°</b></li><li class=\"lune\" hidden><span class=\"ast\" aria-hidden=\"true\"></span>Lune <b></b></li></ul></div></div>")
        sb.append("<script type=\"application/json\" id=\"ciel-donnees\">").append(Html.jsonScript(CielStation.json(i, c.locator, s.ciel.satellites()).toString())).append("</script>")
        sb.append("<footer class=\"card-foot\">").append(liens("/sat/radar" to "Le ciel en direct", "/sat/orbites" to "Globe 3D", "/sat/pass" to "Passages")).append("</footer></section>\n")
    }

    private fun enVue(n: Int) = when (n) { 0 -> "aucun satellite en vue"; 1 -> "1 satellite en vue"; else -> "$n satellites en vue" }

    /** An azimuth as the compass says it (« NO », « SSE »). */
    private fun cap(az: Double): String {
        val n = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
        return n[(((CielStation.norm360(az) + 11.25) / 22.5).toInt()) % 16]
    }

    /** The next passes over the station (worked out here, SatMe's engine). */
    private fun passages(sb: StringBuilder, largeur: String) {
        val c = s.config
        val z = c.fuseau
        val reg = s.reglages.courant
        val obs = s.catalogue.observateur(c.locator)
        val sats = (reg.satellitesAccueil ?: c.satellitesAccueil).mapNotNull { s.catalogue.cherche(it) }.distinctBy { it.catalogNumber }
        val maintenant = System.currentTimeMillis()
        val passes = if (obs != null && sats.isNotEmpty()) s.catalogue.passages(sats, obs, 24, 5.0, maintenant).take(7) else emptyList()
        sb.append("""<section class="card $largeur" style="@passages@" aria-labelledby="h-passes"><header class="card-head"><h2 class="card-title" id="h-passes">${Icones.svg("pass")}Prochains passages</h2>
<span class="card-meta">24 h · heure de Paris</span></header><div class="card-body">""")
        if (passes.isEmpty()) sb.append("<p class=\"card-empty\">").append(if (s.catalogue.satellites.isEmpty()) "Les éléments orbitaux arrivent…" else "Aucun passage dans les 24 heures.").append("</p>")
        else {
            sb.append("<ul class=\"rows pass-rows\">")
            for (p in passes) {
                val enCours = p.aosEpochMs <= maintenant
                val el = p.maxElevationDeg.toInt()
                sb.append("<li><span class=\"name\">").append(h(p.satName)).append("</span><span class=\"num").append(if (enCours) " now" else "").append("\">")
                    .append(if (enCours) "en cours" else quand(p.aosEpochMs, maintenant)).append("</span><span class=\"num dur\">")
                    .append(Html.duree(p.losEpochMs - p.aosEpochMs)).append("</span><span class=\"num el ").append(if (el >= 45) "high" else if (el < 20) "low" else "")
                    .append("\">").append(el).append("°</span></li>")
            }
            sb.append("</ul>")
        }
        sb.append("</div><footer class=\"card-foot\">").append(liens("/sat/pass" to "Tous les passages", "/sat/sked" to "Sked"))
            .append("</footer></section>\n")
    }

    /** A pass's AOS, short: « 14:05 » today, « demain 06:12 », else « ven. 07:40 » (the station's time). */
    private fun quand(t: Long, maintenant: Long): String {
        val z = s.config.fuseau
        val j = java.time.Instant.ofEpochMilli(t).atZone(z).toLocalDate()
        val auj = java.time.Instant.ofEpochMilli(maintenant).atZone(z).toLocalDate()
        return when (j) {
            auj -> Html.heure(t, z)
            auj.plusDays(1) -> "demain " + Html.heure(t, z)
            else -> java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.FRANCE).format(java.time.Instant.ofEpochMilli(t).atZone(z))
        }
    }

    /** The last contacts. */
    private fun contacts(sb: StringBuilder, largeur: String) {
        val qsos = s.logbook.tous().take(7)
        sb.append("""<section class="card $largeur" style="@qso@" aria-labelledby="h-qso"><header class="card-head"><h2 class="card-title" id="h-qso">${Icones.svg("logbook")}Derniers QSO</h2>
<span class="card-meta">UTC</span></header><div class="card-body">""")
        if (qsos.isEmpty()) sb.append("<p class=\"card-empty\">Le logbook n'est pas disponible pour le moment.</p>")
        else {
            sb.append("<ul class=\"rows qso-rows\">")
            for (k in qsos) sb.append("<li><span class=\"num\">").append(Html.dateCourte(k.quandMs))
                .append("</span><span class=\"where\"><span class=\"call\">").append(h(k.indicatif)).append("</span>")
                .append(if (k.pays.isNotEmpty()) "<span class=\"dim\"> · " + h(k.pays.lowercase().replaceFirstChar { it.titlecase() }) + "</span>" else "")
                .append("</span><span class=\"num\">").append(if (k.satellite.isNotEmpty()) "<span class=\"sat\">" + h(k.satellite) + "</span>" else h(k.bande))
                .append("</span></li>")
            sb.append("</ul>")
        }
        sb.append("""</div><footer class="card-foot"><a href="/logbook">Logbook complet</a></footer></section>
""")
    }

    companion object {
        /**
         * The home page on one desktop screen: 12 columns, three rows and the tools' strip; the live block on the
         * left two thirds, the others beside it — or the others alone, side by side. CSS custom properties
         * (`--gc`, `--gr`), used only where the screen is large enough (site.css, « ecran »).
         */
        fun places(cartes: List<String>): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            fun p(k: String, c: String, r: String) { m[k] = "--gc:$c;--gr:$r" }
            // The big card on the left: the live block, else the station's sky; the others in the column beside it.
            val grand = if ("direct" in cartes) "direct" else if ("ciel" in cartes) "ciel" else null
            // The live block and the sky together: the sky a strip under the live block (its dial and countdown).
            val sousDirect = grand == "direct" && "ciel" in cartes
            val autres = cartes.filter { it != grand && it != "outils" && !(sousDirect && it == "ciel") }
            val gauche = if (autres.isEmpty()) "1/13" else "1/8"
            if (sousDirect) { p("direct", gauche, "1/3"); p("ciel", gauche, "3/4") }
            if (grand != null) {
                if (autres.isEmpty()) { if (!sousDirect) p(grand, "1/13", "1/4") }
                // The sky beside three or four cards: half the width, the others two by two (their lists two rows high).
                else if (grand == "ciel" && autres.size >= 3) {
                    p(grand, "1/7", "1/4")
                    p(autres[0], "7/10", "1/3"); p(autres[1], "10/13", "1/3")
                    if (autres.size == 3) p(autres[2], "7/13", "3/4") else { p(autres[2], "7/10", "3/4"); p(autres[3], "10/13", "3/4") }
                } else {
                    if (!sousDirect) p(grand, "1/8", "1/4")
                    when (autres.size) {
                        1 -> p(autres[0], "8/13", "1/4")
                        2 -> { p(autres[0], "8/13", "1/3"); p(autres[1], "8/13", "3/4") }
                        3 -> autres.forEachIndexed { i, k -> p(k, "8/13", "${i + 1}/${i + 2}") }
                        else -> { p(autres[0], "8/13", "1/2"); p(autres[1], "8/13", "2/3"); p(autres[2], "8/10", "3/4"); p(autres[3], "10/13", "3/4") }
                    }
                }
            } else when (autres.size) {
                0 -> {}
                1 -> p(autres[0], "1/13", "1/4")
                2 -> { p(autres[0], "1/7", "1/4"); p(autres[1], "7/13", "1/4") }
                3 -> autres.forEachIndexed { i, k -> p(k, "${1 + 4 * i}/${5 + 4 * i}", "1/4") }
                else -> { p(autres[0], "1/7", "1/3"); p(autres[1], "7/13", "1/3"); p(autres[2], "1/5", "3/4"); p(autres[3], "5/13", "3/4") }
            }
            if ("outils" in cartes) p("outils", "1/13", "4/5")
            return m
        }
    }

    /** A tool's tile; empty when its page is hidden. */
    private fun tuile(href: String, ico: String, titre: String, texte: String) = if (!s.visible(href)) "" else
        "<a href=\"$href\">${Icones.svg(ico)}<span><b>${h(titre)}</b><span>${h(texte)}</span></span></a>"

    /** A card's links to the pages that are shown. */
    private fun liens(vararg l: Pair<String, String>): String = l.filter { s.visible(it.first) }.joinToString("") { "<a href=\"${it.first}\">${h(it.second)}</a>" }
}
