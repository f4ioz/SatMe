/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.admin

import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.reglages.Bandeau
import fr.f4ioz.site.reglages.Direct
import fr.f4ioz.site.reglages.ReglageModule
import fr.f4ioz.site.reglages.ReglagePublication
import fr.f4ioz.site.reglages.Reglages
import fr.f4ioz.site.reglages.SectionsAccueil
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page

/**
 * « Admin › Paramètres »: what the site shows, changed while it runs (no
 * restart, nothing written in site.conf) — each page shown or hidden, its
 * name and place in the menu; each kind SatMe-PC publishes taken or
 * refused, public or not; the banner of the auto-pilot's events; the live
 * block and its source; the home page's cards and satellites. One form, its
 * session's token like the rest of the admin.
 */
class ParametresAdmin(private val s: Site) {

    /** The modules that can be hidden, with their first menu entry (their default name and place). */
    private fun reglables() = s.modules.mapNotNull { m -> m.idReglage?.let { id -> m.menu.firstOrNull()?.let { id to it } } }

    fun page(q: Requete, csrf: String, enregistre: Boolean): Reponse {
        val r = s.reglages.courant
        val sb = StringBuilder()
        sb.append("<header class=\"page-head\"><h1>Paramètres du site</h1><p><a href=\"/admin\">← administration</a> · Ce que le site montre, changé tout de suite ")
            .append("(sans redémarrer ni toucher à site.conf, qui garde les secrets).</p>")
        if (enregistre) sb.append("<p class=\"ok-ligne\" role=\"status\">Enregistré : le site l'applique déjà (les pages gardées par les navigateurs suivent en une minute).</p>")
        sb.append("</header>")
        sb.append("<form method=\"post\" action=\"/admin/parametres\" class=\"parametres\"><input type=\"hidden\" name=\"csrf\" value=\"").append(h(csrf)).append("\">")

        // The pages.
        sb.append(carte("Pages et menu", "Une page cachée disparaît du menu, du plan du site et de l'accueil ; son adresse répond « introuvable ». " +
            "Le nom et la place (un nombre, plus petit en haut) sont facultatifs ; vides, ceux d'origine."))
        sb.append("<div class=\"table-wrap\"><table class=\"admin params\"><thead><tr><th>Page</th><th>Montrée</th><th>Nom dans le menu</th><th>Place</th></tr></thead><tbody>")
        for ((id, e) in reglables()) {
            val m = r.module(id)
            sb.append("<tr").append(if (!m.visible) " class=\"cache\"" else "").append("><td><b>").append(h(e.libelle)).append("</b><br><small class=\"dim\">")
                .append(h(e.groupe.titre)).append(" · ").append(h(id)).append("</small></td><td>").append(case("mv:$id", m.visible, "Montrer « " + e.libelle + " »"))
                .append("</td><td><input name=\"ml:").append(h(id)).append("\" value=\"").append(h(m.libelle)).append("\" placeholder=\"").append(h(e.libelle))
                .append("\" maxlength=\"40\" aria-label=\"Nom de « ").append(h(e.libelle)).append(" » dans le menu\"></td><td><input type=\"number\" min=\"0\" max=\"999\" name=\"mo:")
                .append(h(id)).append("\" value=\"").append(m.ordre?.toString() ?: "").append("\" placeholder=\"").append(e.ordre).append("\" class=\"court\" aria-label=\"Place de « ")
                .append(h(e.libelle)).append(" »\"></td></tr>")
        }
        sb.append("</tbody></table></div><p class=\"dim petit\">L'accueil, l'administration et l'API de publication ne se cachent pas.</p></div></section>")

        // The publications.
        sb.append(carte("Publications de SatMe-PC", "« Acceptées » : le site les prend ; sinon SatMe-PC reçoit un refus clair et l'affiche. " +
            "« Publiques » : montrées aux visiteurs (galeries, accueil, logbook) ; décochées, elles restent gardées et réapparaissent si on recoche."))
        sb.append("<div class=\"table-wrap\"><table class=\"admin params\"><thead><tr><th>Quoi</th><th>Acceptées</th><th>Publiques</th><th>Sur le site</th></tr></thead><tbody>")
        val toutes = s.publications.toutes()
        for (t in TypePublication.values()) {
            val p = r.publication(t)
            val l = toutes.filter { it.type == t }
            sb.append("<tr><td><b>").append(h(t.libelle)).append("</b></td><td>").append(case("pa:" + t.nom, p.accepte, "Accepter « " + t.libelle + " »"))
                .append("</td><td>").append(case("pp:" + t.nom, p.publique, "Montrer « " + t.libelle + " »")).append("</td><td><a href=\"/admin?type=").append(t.nom).append("\">")
                .append(when (l.size) { 0 -> "aucune"; 1 -> "1 publiée"; else -> "${l.size} publiées" }).append(if (l.any { it.cache }) " (" + l.count { it.cache } + " cachées)" else "").append("</a></td></tr>")
        }
        sb.append("</tbody></table></div><p class=\"dim petit\">Chaque élément se cache ou se supprime un par un dans la <a href=\"/admin\">liste de l'administration</a>.</p></div></section>")

        // The banner.
        val b = r.bandeau
        sb.append(carte("Bandeau de l'auto-pilote", "En haut des pages, ce que l'auto-pilote SSTV de SatMe-PC annonce (envoyé par SatMe-PC, « Direct sur le site » › Auto-pilote)."))
        sb.append("<div class=\"choix\">").append(caseLibelle("b_actif", b.actif, "Montrer le bandeau"))
            .append(caseLibelle("b_prochain", b.prochainPassage, "Prochain passage d'une campagne, avec le compte à rebours"))
            .append(caseLibelle("b_encours", b.receptionEnCours, "Réception en cours (image, progression, prochaine image)"))
            .append(caseLibelle("b_derniere", b.derniereImage, "Dernière image reçue")).append("</div></div></section>")

        // The live block.
        val d = r.direct
        val e = s.direct
        sb.append(carte("En direct", "Le spectre QO-100 et l'image DATV envoyés par SatMe-PC, ou les pages publiques du BATC ; pendant un passage SSTV " +
            "de l'auto-pilote, le bloc montre le passage (l'image qui arrive, le compte à rebours) puis revient tout seul. " +
            "Le site ne se connecte jamais à la station : c'est SatMe-PC qui envoie, avec le jeton de publication."))
        sb.append("<div class=\"choix\">").append(caseLibelle("d_actif", d.actif, "Montrer le bloc « En direct »"))
            .append("<label>Source <select name=\"d_source\">")
        for ((v, t) in listOf("auto" to "La station, sinon le BATC", "station" to "La station seulement", "batc" to "Le BATC seulement (liens)"))
            sb.append("<option value=\"").append(v).append("\"").append(if (d.source == v) " selected" else "").append(">").append(h(t)).append("</option>")
        sb.append("</select></label>")
            .append(caseLibelle("d_accueil", d.accueil, "Sur l'accueil")).append(caseLibelle("d_page", d.page, "Page « En direct » (/direct, dans le menu)"))
            .append(caseLibelle("d_spectre", d.spectre, "Spectre et chute d'eau QO-100")).append(caseLibelle("d_datv", d.datv, "Image DATV"))
            .append(caseLibelle("d_bascule", d.basculeSstv, "Basculer sur un passage SSTV en cours"))
            .append("<label>puis revenir <input type=\"number\" name=\"d_maintien\" min=\"").append(Direct.MAINTIEN_MIN).append("\" max=\"").append(Direct.MAINTIEN_MAX)
            .append("\" value=\"").append(d.maintienS).append("\" class=\"court\"> s après le LOS ou la dernière image</label>")
            .append("</div><p class=\"dim petit\">Reçu de SatMe-PC : auto-pilote ")
            .append(if (e.piloteRecuMs() > 0) "il y a " + h(ilYa(System.currentTimeMillis() - e.piloteRecuMs())) else "jamais")
            .append(" · spectre ").append(if (e.spectreActif()) "en cours" else "arrêté")
            .append(" · DATV ").append(e.datv()?.let { if (it.optBoolean("enLigne")) "en ligne" else "hors ligne" } ?: "rien").append(".</p></div></section>")

        // The home page.
        val a = r.accueil
        sb.append(carte("Accueil", "Les cartes de la page d'accueil."))
        sb.append("<div class=\"choix\">").append(caseLibelle("a_passages", a.passages, "Prochains passages")).append(caseLibelle("a_qso", a.qso, "Derniers QSO"))
            .append(caseLibelle("a_image", a.image, "Dernière image SSTV ou planche")).append(caseLibelle("a_article", a.article, "Dernier article"))
            .append(caseLibelle("a_satme", a.satme, "SatMe")).append(caseLibelle("a_outils", a.outils, "Outils")).append("</div>")
            .append("<label class=\"large\">Satellites des prochains passages <input name=\"satellites\" value=\"").append(h(r.satellitesAccueil?.joinToString(", ").orEmpty()))
            .append("\" placeholder=\"").append(h(s.config.satellitesAccueil.joinToString(", "))).append("\" maxlength=\"600\"></label>")
            .append("<p class=\"dim petit\">Noms ou numéros NORAD séparés par des virgules ; vide : ceux de site.conf (<code>satellites.accueil</code>).</p></div></section>")

        sb.append("<p class=\"actions params-actions\"><button class=\"btn solid\" type=\"submit\">Enregistrer</button>")
            .append("<a class=\"btn\" href=\"/?apercu=").append(System.currentTimeMillis() / 1000).append("\" target=\"_blank\" rel=\"noopener\">Aperçu de l'accueil</a></p></form>")
        sb.append("<form method=\"post\" action=\"/admin/parametres/retablir\" class=\"inline\" data-confirmer=\"Remettre tous les paramètres comme à l'origine ? Les publications ne sont pas touchées.\">")
            .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(h(csrf)).append("\"><button class=\"btn danger\" type=\"submit\">Rétablir les valeurs d'origine</button></form>")
        return s.gabarit.reponse(q, Page("Paramètres", "Page privée.", sb.toString(), "/admin/parametres", robots = "noindex, nofollow")).cache("no-store")
    }

    /** The form [f] made settings (unknown keys of the file kept). */
    fun enregistre(f: Map<String, String>) {
        s.reglages.change { ancien -> lis(f, ancien) }
    }

    internal fun lis(f: Map<String, String>, ancien: Reglages): Reglages {
        fun c(k: String) = f[k] == "1"
        val modules = LinkedHashMap<String, ReglageModule>()
        for ((id, _) in reglables()) {
            val m = ReglageModule(c("mv:$id"), Reglages.libelleNet(f["ml:$id"].orEmpty()), f["mo:$id"]?.trim()?.toIntOrNull()?.coerceIn(0, 999))
            if (m != ReglageModule()) modules[id] = m
        }
        // A module not here now (another version's): kept as it was.
        ancien.modules.forEach { (k, v) -> if (reglables().none { it.first == k }) modules[k] = v }
        val pubs = LinkedHashMap<TypePublication, ReglagePublication>()
        for (t in TypePublication.values()) {
            val p = ReglagePublication(c("pa:" + t.nom), c("pp:" + t.nom))
            if (p != ReglagePublication()) pubs[t] = p
        }
        val sats = Reglages.satellitesNets(f["satellites"].orEmpty().split(',', ';'))
        return ancien.copy(
            modules = modules, publications = pubs,
            bandeau = Bandeau(c("b_actif"), c("b_prochain"), c("b_encours"), c("b_derniere")),
            direct = Direct(c("d_actif"), f["d_source"]?.takeIf { it in Direct.SOURCES } ?: "auto", c("d_accueil"), c("d_page"), c("d_spectre"), c("d_datv"),
                c("d_bascule"), f["d_maintien"]?.trim()?.toIntOrNull()?.coerceIn(Direct.MAINTIEN_MIN, Direct.MAINTIEN_MAX) ?: 60),
            accueil = SectionsAccueil(c("a_passages"), c("a_qso"), c("a_image"), c("a_article"), c("a_satme"), c("a_outils")),
            satellitesAccueil = sats.takeIf { it.isNotEmpty() },
        )
    }

    private fun ilYa(ms: Long): String = (ms / 1000).let { s -> if (s < 90) "$s s" else if (s < 5400) "${s / 60} min" else Html.duree(ms) }

    private fun carte(titre: String, texte: String) =
        "<section class=\"card\"><header class=\"card-head\"><h2 class=\"card-title\">${h(titre)}</h2></header><div class=\"card-body\"><p class=\"dim\">${h(texte)}</p>"

    private fun case(nom: String, on: Boolean, aria: String) =
        "<input type=\"checkbox\" name=\"${h(nom)}\" value=\"1\"${if (on) " checked" else ""} aria-label=\"${h(aria)}\">"

    private fun caseLibelle(nom: String, on: Boolean, texte: String) =
        "<label><input type=\"checkbox\" name=\"${h(nom)}\" value=\"1\"${if (on) " checked" else ""}> ${h(texte)}</label>"
}
