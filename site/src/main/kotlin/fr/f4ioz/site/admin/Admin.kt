/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.admin

import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.securite.Jetons
import fr.f4ioz.site.securite.Limiteur
import fr.f4ioz.site.vue.Html
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Page
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The site's small private page: what SatMe published, to hide, show again
 * or delete; the elements and the logbook read again on demand. One
 * password (its PBKDF2 hash in site.conf), a session cookie (Secure,
 * HttpOnly, SameSite=Strict, eight hours), a token in every form, eight
 * tries a quarter of an hour per address. Not in the menu, not indexed.
 */
class Admin(private val s: Site, private val horloge: () -> Long = System::currentTimeMillis) : ModuleSite {

    private class Session(val expire: Long, val csrf: String)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val essais = Limiteur(8, 15 * 60_000L)

    private val actif: Boolean get() = s.config.adminMotDePasse.isNotBlank()

    override fun installe(r: Routeur) {
        r.get("/admin") { q -> if (!actif) s.pageErreur(q, 404) else session(q)?.let { tableau(q, it) } ?: connexion(q, "") }
        r.post("/admin/connexion") { q -> if (!actif) s.pageErreur(q, 404) else connecte(q) }
        r.post("/admin/deconnexion") { q -> avecSession(q) { _, cle -> sessions.remove(cle); Reponse.redirige("/admin", 303).entete("Set-Cookie", cookie(q, "", 0)) } }
        r.post("/admin/publication/{id}/{action}") { q -> avecSession(q) { _, _ -> action(q) } }
        r.post("/admin/rafraichir/{quoi}") { q -> avecSession(q) { _, _ ->
            when (q.variable("quoi")) { "tle" -> s.catalogue.rafraichis(); "logbook" -> s.logbook.rafraichis() }
            Reponse.redirige("/admin", 303) } }
        r.get("/admin/vignette/{id}") { q -> session(q)?.let { _ -> s.publications.parId(q.variable("id"))?.let { s.publications.vignetteDe(it) }
            ?.let { Reponse.fichier(it, "image/jpeg").cache("private, no-store") } ?: s.pageErreur(q, 404) } ?: s.pageErreur(q, 404) }
    }

    private fun nomCookie(q: Requete) = if (securise(q)) "__Host-f4ioz_admin" else "f4ioz_admin"
    private fun securise(q: Requete) = q.https || s.config.cookiesSecurises

    private fun cookie(q: Requete, valeur: String, maxAge: Int): String =
        nomCookie(q) + "=" + valeur + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (if (securise(q)) "; Secure" else "")

    private fun session(q: Requete): Pair<String, Session>? {
        val cle = q.cookie(nomCookie(q))?.takeIf { it.length in 30..60 } ?: return null
        val se = sessions[cle] ?: return null
        if (se.expire < horloge()) { sessions.remove(cle); return null }
        return cle to se
    }

    /** A POST of the private page: its session, its form's token, and the Origin when the browser says it. */
    private fun avecSession(q: Requete, suite: (Map<String, String>, String) -> Reponse): Reponse {
        val (cle, se) = session(q) ?: return Reponse.redirige("/admin", 303)
        if (!origineSure(q)) return s.pageErreur(q, 403)
        val f = q.formulaire()
        if (!MessageDigest.isEqual(f["csrf"].orEmpty().toByteArray(), se.csrf.toByteArray())) return s.pageErreur(q, 403)
        return suite(f, cle).cache("no-store")
    }

    private fun origineSure(q: Requete): Boolean {
        val o = q.entete("origin") ?: return true
        val base = s.config.urlBaseNette
        val hote = q.entete("x-forwarded-host") ?: q.entete("host") ?: ""
        return o == base || o.removePrefix("http://").removePrefix("https://") == hote
    }

    private fun connecte(q: Requete): Reponse {
        if (!origineSure(q)) return s.pageErreur(q, 403)
        if (!essais.accepte(q.ip)) return connexion(q, "Trop d'essais : réessayer dans un quart d'heure.", 429)
        val f = q.formulaire()
        if (!Jetons.verifieMotDePasse(f["mot_de_passe"].orEmpty(), s.config.adminMotDePasse)) {
            Thread.sleep(800)
            return connexion(q, "Mot de passe refusé.", 401)
        }
        essais.oublie(q.ip)
        sessions.entries.removeIf { it.value.expire < horloge() }
        val cle = Jetons.nouveau(30)
        sessions[cle] = Session(horloge() + DUREE_MS, Jetons.nouveau(24))
        return Reponse.redirige("/admin", 303).entete("Set-Cookie", cookie(q, cle, (DUREE_MS / 1000).toInt()))
    }

    private fun connexion(q: Requete, message: String, statut: Int = 200): Reponse {
        val corps = """<section class="card login-box"><div class="card-body"><h1>Administration</h1>
${if (message.isNotEmpty()) "<p class=\"erreur-ligne\" role=\"alert\">" + h(message) + "</p>" else ""}
<form method="post" action="/admin/connexion"><label>Mot de passe <input type="password" name="mot_de_passe" autocomplete="current-password" required autofocus></label>
<button class="btn solid" type="submit">Entrer</button></form></div></section>"""
        return s.gabarit.reponse(q, Page("Administration", "Page privée.", corps, "/admin", robots = "noindex, nofollow", statut = statut)).cache("no-store")
    }

    private fun tableau(q: Requete, ses: Pair<String, Session>): Reponse {
        val csrf = ses.second.csrf
        val z = s.config.fuseau
        val l = s.publications.toutes()
        val sb = StringBuilder("<header class=\"page-head\"><h1>Administration</h1><p>").append(l.size).append(" publications de SatMe · éléments lus le ")
            .append(if (s.catalogue.luLe > 0) h(Html.dateHeure(s.catalogue.luLe, z)) else "jamais").append(" (").append(s.catalogue.satellites.size).append(" satellites) · Wavelog ")
            .append(if (!s.logbook.configure) "non configuré" else if (s.logbook.luLe > 0) "lu le " + h(Html.dateHeure(s.logbook.luLe, z)) else "jamais lu")
            .append(if (s.logbook.erreur.isNotEmpty()) " (" + h(s.logbook.erreur) + ")" else "").append("</p>")
        fun bouton(action: String, texte: String, classe: String = "btn", confirmer: String = "") =
            "<form method=\"post\" action=\"$action\" class=\"inline\"" + (if (confirmer.isNotEmpty()) " data-confirmer=\"${h(confirmer)}\"" else "") +
                "><input type=\"hidden\" name=\"csrf\" value=\"${h(csrf)}\"><button class=\"$classe\" type=\"submit\">${h(texte)}</button></form>"
        sb.append("<p class=\"actions\">").append(bouton("/admin/rafraichir/tle", "Relire les éléments")).append(bouton("/admin/rafraichir/logbook", "Relire Wavelog"))
            .append(bouton("/admin/deconnexion", "Se déconnecter")).append("</p></header>")
        if (l.isEmpty()) sb.append("<p class=\"card-empty\">Rien n'a encore été publié par SatMe-PC.</p>")
        else {
            sb.append("<div class=\"table-wrap card\"><table class=\"admin\"><thead><tr><th></th><th>Type</th><th>Titre</th><th>Quand (UTC)</th><th>Reçu</th><th>Taille</th><th>État</th><th>Actions</th></tr></thead><tbody>")
            for (p in l) {
                sb.append("<tr").append(if (p.cache) " class=\"cache\"" else "").append("><td>")
                    .append(if (p.vignette) "<img src=\"/admin/vignette/${p.id}\" alt=\"\" width=\"64\" loading=\"lazy\">" else "").append("</td><td>").append(h(p.type.libelle))
                    .append("</td><td>").append(if (!p.cache && p.type.nom != "qso") "<a href=\"/gallery/item/${p.id}\">" + h(p.titre) + "</a>" else h(p.titre))
                    .append(if (p.cle.isNotEmpty()) "<br><small class=\"dim\">" + h(p.cle) + "</small>" else "")
                    .append("</td><td class=\"num\">").append(h(Html.utc(p.quandMs).removeSuffix(" UTC"))).append("</td><td class=\"num\">").append(h(Html.dateHeure(p.recuMs, z)))
                    .append("</td><td class=\"num\">").append(h(Html.taille(p.taille))).append("</td><td>").append(if (p.cache) "caché" else "public").append("</td><td class=\"act\">")
                    .append(if (p.cache) bouton("/admin/publication/${p.id}/montrer", "Montrer") else bouton("/admin/publication/${p.id}/cacher", "Cacher"))
                    .append(bouton("/admin/publication/${p.id}/supprimer", "Supprimer", "btn danger", "Supprimer « " + p.titre + " » du site ? SatMe-PC ne le republiera que si on le lui demande.")).append("</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }
        return s.gabarit.reponse(q, Page("Administration", "Page privée.", sb.toString(), "/admin", robots = "noindex, nofollow")).cache("no-store")
    }

    private fun action(q: Requete): Reponse {
        val id = q.variable("id")
        when (q.variable("action")) {
            "cacher" -> s.publications.cache(id, true)
            "montrer" -> s.publications.cache(id, false)
            "supprimer" -> s.publications.supprime(id)
            else -> return s.pageErreur(q, 404)
        }
        return Reponse.redirige("/admin", 303)
    }


    companion object { const val DUREE_MS = 8 * 3_600_000L }
}
