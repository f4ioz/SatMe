/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.vue

import fr.f4ioz.site.Config
import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.vue.Html.h

/**
 * One page of the site: its words for the search engines and the social
 * networks, its body, what it adds to the head and the foot.
 */
class Page(
    /** The title's own part (« Articles »); the site's name follows it. Empty: the home page. */
    val titre: String,
    val description: String,
    val corps: String,
    /** The canonical path (and query, a list's page 2), from the site's root. */
    val chemin: String,
    /** The path the rail lights up (the page's own by default). */
    val actif: String = chemin.substringBefore('?'),
    val ogType: String = "website",
    /** A picture for the social networks (a path of the site, or absolute). */
    val ogImage: String? = null,
    val jsonLd: String? = null,
    val robots: String = "index, follow",
    val styles: List<String> = emptyList(),
    /** Scripts of the site (paths under /static or /api), loaded at the end of the page in this order. */
    val scripts: List<String> = emptyList(),
    /** A script written in the page, after [scripts]. */
    val scriptEnLigne: String = "",
    /** One of SatMe's tabs (Orbites, Sked): the whole width, the tab's own styles and script (CSP « appli »). */
    val appli: Boolean = false,
    /** The Orbites page frames the ISS's live video (YouTube). */
    val youtube: Boolean = false,
    /** Classes of `<body>` beside the shell's. */
    val classeCorps: String = "",
    val statut: Int = 200,
)

/**
 * The shell every page shares, in SatMe-PC's spirit: the rail on the left
 * on a large screen (icons and names grouped), icons only on a medium one,
 * a bar and a drawer on a phone (without JavaScript the menu is simply
 * unfolded at the top); the UTC clock; the light or dark theme (dark by
 * default, kept in the browser); the footer. The head carries the page's
 * title, description, canonical address, Open Graph and its JSON-LD.
 */
class Gabarit(
    private val config: Config,
    private val menu: () -> List<EntreeMenu>,
    private val statique: Statique,
    /** The banner of the auto-pilot's events on (« Admin › Paramètres »): its place at the top, filled by `direct.js`. */
    private val bandeau: () -> Boolean = { false },
) {

    fun reponse(r: Requete, p: Page): Reponse {
        val rep = Reponse.html(html(r, p), p.statut)
        rep.csp = if (p.appli) Reponse.Csp.APPLI else Reponse.Csp.CONTENU
        rep.youtube = p.youtube
        return rep
    }

    fun html(r: Requete, p: Page): String {
        val c = config
        val nomSite = "${c.indicatif} — ${c.nom}"
        val titre = if (p.titre.isEmpty()) "$nomSite · radioamateur ${c.locator}" else "${p.titre} — $nomSite"
        val canon = c.urlBaseNette + p.chemin
        val og = p.ogImage?.let { if (it.startsWith("http")) it else c.urlBaseNette + it }
        val n = r.nonce
        val sb = StringBuilder(16_384)
        sb.append("<!doctype html>\n<html lang=\"fr\" data-theme=\"dark\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(h(titre)).append("</title>\n")
        sb.append("<meta name=\"description\" content=\"").append(h(p.description)).append("\">\n")
        sb.append("<meta name=\"robots\" content=\"").append(h(p.robots)).append("\">\n")
        sb.append("<link rel=\"canonical\" href=\"").append(h(canon)).append("\">\n")
        sb.append("<meta property=\"og:site_name\" content=\"").append(h(nomSite)).append("\">\n")
        sb.append("<meta property=\"og:locale\" content=\"fr_FR\">\n")
        sb.append("<meta property=\"og:type\" content=\"").append(h(p.ogType)).append("\">\n")
        sb.append("<meta property=\"og:title\" content=\"").append(h(p.titre.ifEmpty { titre })).append("\">\n")
        sb.append("<meta property=\"og:description\" content=\"").append(h(p.description)).append("\">\n")
        sb.append("<meta property=\"og:url\" content=\"").append(h(canon)).append("\">\n")
        if (og != null) sb.append("<meta property=\"og:image\" content=\"").append(h(og)).append("\">\n")
        sb.append("<meta name=\"twitter:card\" content=\"").append(if (og != null) "summary_large_image" else "summary").append("\">\n")
        sb.append("<meta name=\"theme-color\" content=\"#0A0F14\">\n")
        sb.append("<link rel=\"icon\" href=\"").append(statique.url("img/f4ioz-mark.svg")).append("\" type=\"image/svg+xml\">\n")
        sb.append("<link rel=\"alternate\" type=\"application/atom+xml\" title=\"Articles de ").append(h(c.indicatif)).append("\" href=\"/articles/feed.xml\">\n")
        sb.append("<link rel=\"preload\" href=\"").append(statique.url("fonts/NotoSans-Regular.woff2")).append("\" as=\"font\" type=\"font/woff2\" crossorigin>\n")
        // The theme before the first paint: no flash of the other one.
        sb.append("<script nonce=\"").append(n).append("\">(function(){var t=null;try{t=localStorage.getItem('theme')}catch(e){}")
            .append("document.documentElement.setAttribute('data-theme',t==='light'?'light':'dark');document.documentElement.classList.add('js-nav')})();</script>\n")
        sb.append("<link rel=\"stylesheet\" href=\"").append(statique.url("css/site.css")).append("\">\n")
        for (s in p.styles) sb.append("<link rel=\"stylesheet\" href=\"").append(h(statique.urlOuTel(s))).append("\">\n")
        if (p.jsonLd != null) sb.append("<script type=\"application/ld+json\" nonce=\"").append(n).append("\">").append(Html.jsonScript(p.jsonLd)).append("</script>\n")
        sb.append("</head>\n<body class=\"has-rail").append(if (p.appli) " appli" else "").append(if (p.classeCorps.isNotEmpty()) " " + h(p.classeCorps) else "").append("\">\n")
        sb.append(coque(p.actif))
        sb.append("<main class=\"site-main").append(if (p.appli) " site-appli" else "").append("\" id=\"contenu\" tabindex=\"-1\">\n")
        val avecBandeau = !p.appli && p.robots.startsWith("index") && bandeau()
        if (avecBandeau) sb.append("<div class=\"bandeau-direct\" data-bandeau role=\"status\" aria-live=\"polite\" hidden></div>\n")
        sb.append(p.corps)
        sb.append("\n</main>\n")
        if (!p.appli) sb.append(pied())
        sb.append("<script src=\"").append(statique.url("js/site.js")).append("\" nonce=\"").append(n).append("\"></script>\n")
        val scripts = if (avecBandeau && "js/direct.js" !in p.scripts) p.scripts + "js/direct.js" else p.scripts
        for (s in scripts) sb.append("<script src=\"").append(h(statique.urlOuTel(s))).append("\" nonce=\"").append(n).append("\"></script>\n")
        if (p.scriptEnLigne.isNotEmpty()) sb.append("<script nonce=\"").append(n).append("\">\n").append(p.scriptEnLigne).append("\n</script>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    /** The rail (and the phone's bar), the entries of every module by group. */
    private fun coque(actif: String): String {
        val c = config
        val sb = StringBuilder()
        sb.append("<a class=\"skip-link\" href=\"#contenu\">Aller au contenu</a>\n")
        sb.append("<div class=\"shell-top\"><a class=\"shell-brand\" href=\"/\" aria-label=\"").append(h(c.indicatif)).append(" — accueil\">")
            .append(Icones.marque(30, "t")).append("<span class=\"shell-call\">").append(h(c.indicatif)).append("</span><span class=\"shell-loc\">")
            .append(h(c.locator)).append("</span></a><span class=\"shell-clock\" data-utc-clock aria-hidden=\"true\">--:-- UTC</span>")
            .append("<button type=\"button\" class=\"shell-menu\" aria-controls=\"site-rail\" aria-expanded=\"false\" aria-label=\"Ouvrir le menu\">")
            .append(Icones.svg("menu")).append("<span class=\"lbl\">Menu</span></button></div>\n")
        sb.append("<nav id=\"site-rail\" class=\"rail\" aria-label=\"Navigation principale\">\n")
        sb.append("<a class=\"rail-brand\" href=\"/\" title=\"").append(h(c.indicatif)).append(" — accueil\" aria-label=\"").append(h(c.indicatif)).append(" — accueil\">")
            .append(Icones.marque(36, "r")).append("<span class=\"rail-brand-txt\"><span class=\"rail-call\">").append(h(c.indicatif))
            .append("</span><span class=\"rail-sub\">").append(h(c.locator)).append("</span></span></a>\n")
        sb.append("<div class=\"rail-clock\" aria-hidden=\"true\"><span data-utc-clock>--:--:-- UTC</span></div>\n")
        val entrees = menu()
        for (g in Groupe.values()) {
            val l = entrees.filter { it.groupe == g }.sortedBy { it.ordre }
            if (l.isEmpty()) continue
            sb.append("<div class=\"rail-group\"><span class=\"rail-title\">").append(h(g.titre)).append("</span>\n")
            for (e in l) {
                val titre = if (e.description.isNotEmpty()) e.libelle + " — " + e.description else e.libelle
                if (e.bientot) {
                    sb.append("<span class=\"rail-soon\" title=\"").append(h(e.libelle)).append(" — bientôt\" aria-disabled=\"true\">")
                        .append(Icones.svg(e.icone)).append("<span class=\"lbl\">").append(h(e.libelle)).append("</span><small class=\"lbl soon-tag\">bientôt</small></span>\n")
                } else {
                    val on = e.actifPour(actif)
                    sb.append("<a href=\"").append(h(e.chemin)).append("\" title=\"").append(h(titre)).append("\"")
                        .append(if (on) " class=\"active\" aria-current=\"page\"" else "").append(">")
                        .append(Icones.svg(e.icone)).append("<span class=\"lbl\">").append(h(e.libelle)).append("</span></a>\n")
                }
            }
            sb.append("</div>\n")
        }
        sb.append("<div class=\"rail-foot\"><button type=\"button\" id=\"theme-toggle\" class=\"theme-toggle\" aria-label=\"Basculer thème clair/sombre\" title=\"Basculer thème clair/sombre\">")
            .append("<span class=\"theme-icon\" aria-hidden=\"true\">☀</span><span class=\"lbl\">Thème</span></button></div>\n")
        sb.append("</nav>\n<div class=\"rail-scrim\" hidden></div>\n")
        return sb.toString()
    }

    private fun pied(): String {
        val c = config
        return "<footer class=\"site-footer\"><div class=\"container\"><p>© ${h(c.indicatif)} — ${h(c.nom)} · ${h(c.locator)} · " + (if (c.club.isNotEmpty()) "${h(c.club)} · " else "") +
            "<a href=\"${h(c.qrz)}\" rel=\"noopener\">QRZ</a> · <a href=\"/satme\">SatMe</a> · <a href=\"${h(c.github)}\" rel=\"noopener\">GitHub</a> · " +
            "<a href=\"/articles/feed.xml\">Flux Atom</a></p></div></footer>\n"
    }
}
