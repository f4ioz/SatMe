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
import fr.f4ioz.site.publication.Publication
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.vue.Page
import fr.f4ioz.site.vue.Statique
import java.io.File

/**
 * The galleries: what SatMe-PC published — SSTV pictures grouped by event
 * (or satellite), planches, reports, the journal's videos — and the photo
 * albums put by hand. Each picture has its page (when, which satellite,
 * which frequency and mode, which pass). The files themselves are served
 * under `/media/`, by the names the site gave them; a hidden one is not
 * served at all; a report in HTML is shown sandboxed (never run).
 */
class PagesGaleries(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SITE, 30, "/gallery", "Galeries", "gallery", "Images SSTV, planches, photos"))

    override fun plan(): List<Pair<String, Double>> = listOf("/gallery" to 0.7) +
        groupes().keys.map { "/gallery/sstv/" + Html.slug(it) to 0.5 } + s.albums.tous().map { "/gallery/album/" + it.nom to 0.5 }

    override fun installe(r: Routeur) {
        r.get("/gallery") { q -> apercu(q) }
        r.get("/gallery/sstv/{groupe}") { q -> groupe(q) }
        r.get("/gallery/planches") { q -> grille(q, TypePublication.PLANCHE, "Planches", "Les planches SSTV composées par SatMe-PC : les images d'une série sur un modèle.") }
        r.get("/gallery/item/{id}") { q -> item(q) }
        r.get("/gallery/album/{nom}") { q -> album(q) }
        r.get("/media/{id}/{fichier}") { q -> media(q) }
        r.get("/media/album/{nom}/{fichier}") { q -> photo(q, false) }
        r.get("/media/album/{nom}/v/{fichier}") { q -> photo(q, true) }
        r.prefixe("GET", "/images/") { q -> image(q) }
    }

    private fun groupes(): Map<String, List<Publication>> =
        s.publications.visibles(TypePublication.SSTV).groupBy { it.groupe }.toList().sortedByDescending { (_, l) -> l.maxOf { it.quandMs } }.toMap()

    private fun vignette(p: Publication, legende: Boolean = true): String =
        "<figure class=\"vign\"><a href=\"/gallery/item/${p.id}\"><img src=\"/media/${p.id}/vignette.jpg\" alt=\"${h(p.titre)}\" loading=\"lazy\"" +
            (if (p.largeur > 0 && p.hauteur > 0) " width=\"${minOf(480, p.largeur)}\" height=\"${(minOf(480, p.largeur) * p.hauteur / p.largeur)}\"" else "") +
            "></a>" + (if (legende) "<figcaption>${h(if (p.type == TypePublication.PLANCHE) p.titre else p.satellite.ifEmpty { p.type.libelle })}<span>${h(Html.utc(p.quandMs))}</span></figcaption>" else "") + "</figure>"

    private fun apercu(q: Requete): Reponse {
        val sb = StringBuilder("<header class=\"page-head\"><h1>Galeries</h1><p>Les images reçues des satellites et publiées par SatMe-PC à mesure qu'elles arrivent, " +
            "les planches et les rapports de mes campagnes SSTV, et quelques albums photo.</p></header>")
        val g = groupes()
        sb.append("<section class=\"gal-section\"><h2 class=\"sm-section-title\">").append(Icones.svg("sstv")).append("Images SSTV</h2>")
        if (g.isEmpty()) sb.append("<p class=\"card-empty\">Aucune image publiée pour l'instant : elles arrivent de SatMe-PC au fil des passages.</p>")
        for ((nom, l) in g) {
            sb.append("<div class=\"gal-groupe card\"><header class=\"card-head\"><h3 class=\"card-title\"><a href=\"/gallery/sstv/").append(Html.slug(nom)).append("\">")
                .append(h(nom)).append("</a></h3><span class=\"card-meta\">").append(l.size).append(if (l.size > 1) " images" else " image").append(" · ")
                .append(h(Html.date(l.maxOf { it.quandMs }, s.config.fuseau))).append("</span></header><div class=\"card-body vign-grille\">")
            l.take(8).forEach { sb.append(vignette(it)) }
            sb.append("</div>")
            if (l.size > 8) sb.append("<footer class=\"card-foot\"><a href=\"/gallery/sstv/").append(Html.slug(nom)).append("\">Les ").append(l.size).append(" images</a></footer>")
            sb.append("</div>")
        }
        sb.append("</section>")
        val planches = s.publications.visibles(TypePublication.PLANCHE)
        if (planches.isNotEmpty()) {
            sb.append("<section class=\"gal-section\"><h2 class=\"sm-section-title\">").append(Icones.svg("gallery")).append("Planches</h2><div class=\"vign-grille large\">")
            planches.take(6).forEach { sb.append(vignette(it)) }
            sb.append("</div>")
            if (planches.size > 6) sb.append("<p><a href=\"/gallery/planches\">Toutes les planches (").append(planches.size).append(")</a></p>")
            sb.append("</section>")
        }
        val rapports = s.publications.visibles(TypePublication.RAPPORT)
        val videos = s.publications.visibles(TypePublication.VIDEO)
        if (rapports.isNotEmpty() || videos.isNotEmpty()) {
            sb.append("<section class=\"gal-section\"><h2 class=\"sm-section-title\">").append(Icones.svg("report")).append("Rapports et vidéos</h2><ul class=\"rows docs\">")
            (rapports + videos).sortedByDescending { it.quandMs }.forEach { p ->
                sb.append("<li><span>").append(Icones.svg(if (p.type == TypePublication.VIDEO) "video" else "report")).append("</span><a href=\"/gallery/item/").append(p.id).append("\">")
                    .append(h(p.titre)).append("</a><span class=\"num\">").append(h(Html.date(p.quandMs, s.config.fuseau))).append("</span><span class=\"num dim\">")
                    .append(h(Html.taille(p.taille))).append("</span></li>")
            }
            sb.append("</ul></section>")
        }
        val albums = s.albums.tous()
        if (albums.isNotEmpty()) {
            sb.append("<section class=\"gal-section\"><h2 class=\"sm-section-title\">").append(Icones.svg("gallery")).append("Albums photo</h2><div class=\"albums\">")
            for (a in albums) {
                sb.append("<a class=\"album card\" href=\"/gallery/album/").append(a.nom).append("\">")
                a.photos.firstOrNull()?.let { sb.append("<img src=\"/media/album/").append(a.nom).append("/v/").append(Html.u(it)).append("\" alt=\"\" loading=\"lazy\">") }
                sb.append("<span class=\"card-body\"><b>").append(h(a.titre)).append("</b><span>").append(a.photos.size).append(" photos")
                    .append(if (a.resume.isNotEmpty()) " · " + h(a.resume) else "").append("</span></span></a>")
            }
            sb.append("</div></section>")
        }
        return s.gabarit.reponse(q, Page("Galeries", "Images SSTV reçues des satellites par ${s.config.indicatif}, planches, rapports de campagne et albums photo.",
            sb.toString(), "/gallery")).cache("public, max-age=60")
    }

    private fun groupe(q: Requete): Reponse {
        val (nom, l) = groupes().entries.firstOrNull { Html.slug(it.key) == q.variable("groupe") } ?: return s.pageErreur(q, 404)
        val sb = StringBuilder("<header class=\"page-head\"><h1>").append(h(nom)).append("</h1><p><a href=\"/gallery\">← les galeries</a> · ")
            .append(l.size).append(if (l.size > 1) " images SSTV" else " image SSTV").append("</p></header><div class=\"vign-grille\">")
        l.forEach { sb.append(vignette(it)) }
        sb.append("</div>")
        return s.gabarit.reponse(q, Page("$nom — images SSTV", "Les images SSTV de $nom reçues par ${s.config.indicatif} avec SatMe.", sb.toString(),
            "/gallery/sstv/" + q.variable("groupe"), actif = "/gallery", ogImage = l.firstOrNull()?.let { "/media/${it.id}/vignette.jpg" })).cache("public, max-age=60")
    }

    private fun grille(q: Requete, t: TypePublication, titre: String, texte: String): Reponse {
        val l = s.publications.visibles(t)
        val sb = StringBuilder("<header class=\"page-head\"><h1>").append(h(titre)).append("</h1><p><a href=\"/gallery\">← les galeries</a> · ").append(h(texte)).append("</p></header><div class=\"vign-grille large\">")
        l.forEach { sb.append(vignette(it)) }
        sb.append("</div>")
        return s.gabarit.reponse(q, Page(titre, texte, sb.toString(), q.chemin, actif = "/gallery")).cache("public, max-age=60")
    }

    private fun item(q: Requete): Reponse {
        val p = s.publications.publique(q.variable("id")) ?: return s.pageErreur(q, 404)
        val sb = StringBuilder("<article class=\"item\"><header class=\"page-head\"><h1>").append(h(p.titre)).append("</h1><p><a href=\"/gallery\">← les galeries</a>")
        if (p.type == TypePublication.SSTV) sb.append(" · <a href=\"/gallery/sstv/").append(Html.slug(p.groupe)).append("\">").append(h(p.groupe)).append("</a>")
        sb.append("</p></header>")
        val f = "/media/${p.id}/${p.fichier}"
        when (p.type) {
            TypePublication.SSTV, TypePublication.PLANCHE -> sb.append("<figure class=\"item-image\"><a href=\"").append(f).append("\"><img src=\"").append(f)
                .append("\" alt=\"").append(h(p.titre)).append("\"").append(if (p.largeur > 0) " width=\"${p.largeur}\" height=\"${p.hauteur}\"" else "").append("></a></figure>")
            TypePublication.VIDEO -> sb.append("<video class=\"item-video\" controls preload=\"metadata\" src=\"").append(f).append("\"></video>")
            TypePublication.RAPPORT -> sb.append("<p><a class=\"btn solid\" href=\"").append(f).append("\">").append(Icones.svg("report"))
                .append(if (p.fichier.endsWith(".pdf")) "Ouvrir le rapport (PDF)" else "Ouvrir le rapport").append("</a> <span class=\"dim\">").append(h(Html.taille(p.taille))).append("</span></p>")
            TypePublication.QSO -> sb.append("<p><a href=\"/logbook\">Voir le logbook</a></p>")
        }
        sb.append("<dl class=\"item-meta\">")
        fun ligne(k: String, v: String) { if (v.isNotBlank()) sb.append("<dt>").append(h(k)).append("</dt><dd>").append(h(v)).append("</dd>") }
        ligne("Quand", Html.utc(p.quandMs) + " (" + Html.dateHeure(p.quandMs, s.config.fuseau) + " heure de Paris)")
        ligne("Satellite", p.satellite)
        ligne("Fréquence", p.texte("frequence").let { if (it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' }) "$it MHz" else it })
        ligne("Mode", p.texte("mode"))
        ligne("Passage", p.texte("passage"))
        ligne("Événement", p.texte("evenement").ifEmpty { p.texte("campagne") })
        ligne("Station", p.texte("station").ifEmpty { s.config.indicatif + " · " + s.config.locator })
        ligne("Description", p.texte("description"))
        ligne("Image", if (p.largeur > 0) "${p.largeur} × ${p.hauteur} px · " + Html.taille(p.taille) else Html.taille(p.taille))
        sb.append("</dl><p class=\"dim\">Reçu et publié avec <a href=\"/satme\">SatMe</a>.</p></article>")
        return s.gabarit.reponse(q, Page(p.titre, "${p.titre} — ${Html.utc(p.quandMs)}, reçu par ${s.config.indicatif} avec SatMe.", sb.toString(),
            "/gallery/item/${p.id}", actif = "/gallery", ogImage = if (p.vignette) "/media/${p.id}/vignette.jpg" else null)).cache("public, max-age=300")
    }

    private fun album(q: Requete): Reponse {
        val a = s.albums.album(q.variable("nom")) ?: return s.pageErreur(q, 404)
        val sb = StringBuilder("<header class=\"page-head\"><h1>").append(h(a.titre)).append("</h1><p><a href=\"/gallery\">← les galeries</a>")
        if (a.resume.isNotEmpty()) sb.append(" · ").append(h(a.resume))
        sb.append("</p></header>")
        if (a.texteHtml.isNotEmpty()) sb.append("<div class=\"article-body\">").append(a.texteHtml).append("</div>")
        sb.append("<div class=\"vign-grille\">")
        a.photos.forEach { ph ->
            sb.append("<figure class=\"vign\"><a href=\"/media/album/").append(a.nom).append("/").append(Html.u(ph)).append("\"><img src=\"/media/album/")
                .append(a.nom).append("/v/").append(Html.u(ph)).append("\" alt=\"").append(h(ph.substringBeforeLast('.'))).append("\" loading=\"lazy\"></a></figure>")
        }
        sb.append("</div>")
        return s.gabarit.reponse(q, Page(a.titre, a.resume.ifEmpty { "Album photo de ${s.config.indicatif} : ${a.titre}." }, sb.toString(), "/gallery/album/" + a.nom,
            actif = "/gallery", ogImage = a.photos.firstOrNull()?.let { "/media/album/${a.nom}/v/" + Html.u(it) })).cache("public, max-age=300")
    }

    private fun media(q: Requete): Reponse {
        val p = s.publications.publique(q.variable("id")) ?: return s.pageErreur(q, 404)
        return when (val n = q.variable("fichier")) {
            "vignette.jpg" -> s.publications.vignetteDe(p)?.let { Reponse.fichier(it, "image/jpeg").cache("public, max-age=86400") } ?: s.pageErreur(q, 404)
            p.fichier -> {
                val f = s.publications.fichierDe(p).takeIf { it.isFile } ?: return s.pageErreur(q, 404)
                val rep = Reponse.fichier(f, p.typeMime).cache("public, max-age=86400")
                if (p.fichier.endsWith(".html")) rep.csp = Reponse.Csp.AUCUN
                if (p.fichier.endsWith(".pdf") || p.fichier.endsWith(".html")) rep.entete("Content-Disposition", "inline; filename=\"rapport-${p.id}.${n.substringAfterLast('.')}\"")
                rep
            }
            else -> s.pageErreur(q, 404)
        }
    }

    private fun photo(q: Requete, vignette: Boolean): Reponse {
        val nom = q.variable("nom"); val f = q.variable("fichier")
        val fichier = (if (vignette) s.albums.vignette(nom, f) else s.albums.photo(nom, f)) ?: return s.pageErreur(q, 404)
        return Reponse.fichier(fichier, if (vignette) "image/jpeg" else Statique.typeDe(f.lowercase())).cache("public, max-age=86400")
    }

    /** The articles' pictures: `contenu/images/…`. */
    private fun image(q: Requete): Reponse {
        val reste = q.variable("reste")
        if (!IMAGE.matches(reste)) return s.pageErreur(q, 404)
        val base = File(s.config.contenu, "images")
        val f = File(base, reste)
        if (!f.isFile || !f.canonicalPath.startsWith(base.canonicalPath + File.separator)) return s.pageErreur(q, 404)
        return Reponse.fichier(f, Statique.typeDe(f.name.lowercase())).cache("public, max-age=86400")
    }

    companion object {
        private val IMAGE = Regex("^([a-z0-9][a-z0-9_-]{0,63}/){0,3}[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\\.(jpg|jpeg|png|gif|webp|JPG|JPEG|PNG)$")
    }
}
