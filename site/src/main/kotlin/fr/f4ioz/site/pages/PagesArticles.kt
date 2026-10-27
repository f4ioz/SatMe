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
import fr.f4ioz.site.contenu.Articles
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.vue.Page
import org.json.JSONArray
import org.json.JSONObject

/**
 * The articles: the list (by tag, ten a page), each article (its picture,
 * its tags, its JSON-LD), and their Atom feed — at the old site's
 * addresses, `/articles/<année>/<slug>`.
 */
class PagesArticles(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SITE, 20, "/articles", "Articles", "articles", "Activations, expéditions, retours technique"))
    override fun plan() = listOf("/articles" to 0.8)

    override fun installe(r: Routeur) {
        r.get("/articles") { q -> liste(q) }
        r.get("/articles/feed.xml") { _ -> flux() }
        r.get("/feed.xml") { _ -> Reponse.redirige("/articles/feed.xml", 301) }
        r.get("/articles/{annee}/{slug}") { q -> article(q) }
    }

    private fun liste(q: Requete): Reponse {
        val tag = q.param("tag")?.lowercase()?.takeIf { Articles.TAG.matches(it) }
        val tous = s.articles.tous().filter { tag == null || tag in it.tags }
        val pages = maxOf(1, (tous.size + PAR_PAGE - 1) / PAR_PAGE)
        val page = (q.param("page")?.toIntOrNull() ?: 1).coerceIn(1, pages)
        val z = s.config.fuseau
        val sb = StringBuilder()
        sb.append("<header class=\"page-head\"><h1>").append(if (tag != null) "Articles · <span class=\"tag\">#${h(tag)}</span>" else "Articles").append("</h1>")
        if (tag != null) sb.append("<p><a href=\"/articles\">← tous les articles</a></p>")
        else {
            val tags = s.articles.tags()
            if (tags.isNotEmpty()) sb.append("<p class=\"taglist\">").append(tags.joinToString("") { (t, n) ->
                "<a href=\"/articles?tag=${Html.u(t)}\" class=\"tag\">#${h(t)}<span class=\"tag-count\">$n</span></a>" }).append("</p>")
        }
        sb.append("<p class=\"flux\"><a href=\"/articles/feed.xml\">").append(Icones.svg("rss")).append("Flux Atom</a></p></header>")
        val l = tous.drop((page - 1) * PAR_PAGE).take(PAR_PAGE)
        if (l.isEmpty()) sb.append("<p class=\"card-empty\">Aucun article ").append(if (tag != null) "avec ce tag " else "").append("pour l'instant.</p>")
        else {
            sb.append("<ul class=\"article-list\">")
            for (a in l) {
                sb.append("<li class=\"article-item\"><h2><a href=\"").append(a.url).append("\">").append(h(a.titre)).append("</a></h2><p class=\"meta\"><time datetime=\"")
                    .append(a.date).append("\">").append(Html.date(a.ms(z), z)).append("</time>")
                a.tags.forEach { t -> sb.append(" · <a href=\"/articles?tag=").append(Html.u(t)).append("\" class=\"tag\">#").append(h(t)).append("</a>") }
                sb.append("</p>")
                if (a.resume.isNotEmpty()) sb.append("<p>").append(h(a.resume)).append("</p>")
                sb.append("</li>")
            }
            sb.append("</ul>")
        }
        if (pages > 1) {
            val base = "/articles?" + (if (tag != null) "tag=${Html.u(tag)}&" else "")
            sb.append("<nav class=\"pager\" aria-label=\"Pagination\">")
            sb.append(if (page > 1) "<a href=\"${base}page=${page - 1}\">← page précédente</a>" else "<span></span>")
            sb.append("<span>page $page / $pages</span>")
            sb.append(if (page < pages) "<a href=\"${base}page=${page + 1}\">page suivante →</a>" else "<span></span>")
            sb.append("</nav>")
        }
        val chemin = "/articles" + listOfNotNull(tag?.let { "tag=" + Html.u(it) }, if (page > 1) "page=$page" else null).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val c = s.config
        return s.gabarit.reponse(q, Page(if (tag != null) "Articles · #$tag" else "Articles",
            if (tag != null) "Articles de ${c.indicatif} sur le thème #$tag." else "Tous les articles de ${c.indicatif} — ${c.nom} : activations, expéditions, satellites, retours technique.",
            sb.toString(), chemin, actif = "/articles", robots = if (page > 1) "noindex, follow" else "index, follow")).cache("public, max-age=60")
    }

    private fun article(q: Requete): Reponse {
        val a = s.articles.article(q.variable("annee").toIntOrNull() ?: return s.pageErreur(q, 404), q.variable("slug")) ?: return s.pageErreur(q, 404)
        val c = s.config
        val z = c.fuseau
        val sb = StringBuilder("<article class=\"article\"><header class=\"article-head\"><h1>").append(h(a.titre)).append("</h1><p class=\"meta\"><time datetime=\"")
            .append(a.date).append("\">").append(Html.dateLongue(a.ms(z), z)).append("</time>")
        a.tags.forEach { t -> sb.append(" · <a href=\"/articles?tag=").append(Html.u(t)).append("\" class=\"tag\">#").append(h(t)).append("</a>") }
        sb.append("</p>")
        if (a.image != null) sb.append("<img class=\"hero-image\" src=\"").append(h(a.image)).append("\" alt=\"\">")
        sb.append("</header><div class=\"article-body\">").append(a.html).append("</div><footer class=\"article-foot\"><p><a href=\"/articles\">← retour aux articles</a></p></footer></article>")
        val ld = JSONObject().put("@context", "https://schema.org").put("@type", "Article").put("headline", a.titre).put("datePublished", a.date.toString())
            .put("author", JSONObject().put("@type", "Person").put("name", c.nom).put("alternateName", c.indicatif))
            .put("url", c.urlBaseNette + a.url)
        if (a.resume.isNotEmpty()) ld.put("description", a.resume)
        if (a.tags.isNotEmpty()) ld.put("keywords", a.tags.joinToString(", "))
        if (a.image != null) ld.put("image", if (a.image.startsWith("http")) a.image else c.urlBaseNette + a.image)
        return s.gabarit.reponse(q, Page(a.titre, a.resume.ifEmpty { a.titre + " — article de ${c.indicatif}." }, sb.toString(), a.url, actif = "/articles",
            ogType = "article", ogImage = a.image, jsonLd = ld.toString())).cache("public, max-age=300")
    }

    /** The articles' Atom feed (the twenty newest). */
    private fun flux(): Reponse {
        val c = s.config
        val l = s.articles.tous().take(20)
        val z = c.fuseau
        val maj = l.maxOfOrNull { it.modifie } ?: 0L
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
        sb.append("<title>").append(h("${c.indicatif} — articles")).append("</title>\n<link href=\"").append(h(c.urlBaseNette)).append("/articles/feed.xml\" rel=\"self\"/>\n")
            .append("<link href=\"").append(h(c.urlBaseNette)).append("/articles\"/>\n<id>").append(h(c.urlBaseNette)).append("/articles</id>\n<updated>")
            .append(Html.iso(maj)).append("</updated>\n<author><name>").append(h("${c.nom} ${c.indicatif}")).append("</name></author>\n")
        for (a in l) {
            sb.append("<entry><title>").append(h(a.titre)).append("</title><link href=\"").append(h(c.urlBaseNette + a.url)).append("\"/><id>")
                .append(h(c.urlBaseNette + a.url)).append("</id><published>").append(Html.iso(a.ms(z))).append("</published><updated>")
                .append(Html.iso(maxOf(a.ms(z), a.modifie))).append("</updated>")
            if (a.resume.isNotEmpty()) sb.append("<summary>").append(h(a.resume)).append("</summary>")
            a.tags.forEach { sb.append("<category term=\"").append(h(it)).append("\"/>") }
            sb.append("<content type=\"html\">").append(h(a.html)).append("</content></entry>\n")
        }
        sb.append("</feed>\n")
        return Reponse.texte(sb.toString(), type = "application/atom+xml; charset=utf-8").cache("public, max-age=600")
    }

    /** The pages of `contenu/pages/` at `/<slug>` (« /a-propos »): last of the routes. */
    class PagesTexte(private val s: Site) : ModuleSite {
        override fun plan() = s.articles.pages().map { "/" + it.slug to 0.5 }
        override fun installe(r: Routeur) {
            r.get("/{slug}") { q ->
                val p = s.articles.page(q.variable("slug")) ?: return@get s.pageErreur(q, 404)
                s.gabarit.reponse(q, Page(p.titre, p.resume.ifEmpty { p.titre }, "<article class=\"article\"><header class=\"article-head\"><h1>" + h(p.titre) +
                    "</h1></header><div class=\"article-body\">" + p.html + "</div></article>", "/" + p.slug)).cache("public, max-age=300")
            }
        }
    }

    companion object { const val PAR_PAGE = 10 }
}
