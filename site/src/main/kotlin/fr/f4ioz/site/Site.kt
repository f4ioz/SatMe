/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.satcombo.data.AgentHttp
import fr.f4ioz.site.carnet.Logbook
import fr.f4ioz.site.contenu.Albums
import fr.f4ioz.site.contenu.Articles
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.http.ServeurHttp
import fr.f4ioz.site.direct.EtatDirect
import fr.f4ioz.site.publication.ApiPublication
import fr.f4ioz.site.publication.Publications
import fr.f4ioz.site.reglages.ReglagesSite
import fr.f4ioz.site.sat.CatalogueSite
import fr.f4ioz.site.vue.Gabarit
import fr.f4ioz.site.vue.Page
import fr.f4ioz.site.vue.Statique
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The site put together: its settings, what it keeps (the elements, the
 * articles, the publications, the logbook), the shell, the modules and the
 * server. [Modules.liste] says which pages there are.
 */
class Site(val config: Config, private val horloge: () -> Long = System::currentTimeMillis) {

    val statique = Statique()

    /** Who the site says it is to the servers it asks: « f4ioz-site/<version> (+https://f4ioz.fr) », so they can let it in. */
    val agent: String get() = agentDe(config)
    val catalogue = CatalogueSite(File(config.donnees, "cache/tle"), config.tleServeur, config.horsLigne, horloge, config.gpSources, agent)
    val articles = Articles(config.contenu, horloge)
    val albums = Albums(File(config.contenu, "galeries"), File(config.donnees, "cache/albums"))
    val publications = Publications(File(config.donnees, "publications"), horloge)
    val logbook = Logbook(File(config.donnees, "cache/logbook.json"), config.wavelogUrl, config.wavelogCle, config.wavelogStations,
        publications, config.horsLigne, horloge)

    /** « Admin › Paramètres »: what is shown, changed live (`donnees/reglages-site.json`; none: everything as it was). */
    val reglages = ReglagesSite(File(config.donnees, "reglages-site.json")).also { it.charge() }
    /** What SatMe-PC says live (the auto-pilot, the DATV, QO-100's spectrum), in memory. */
    val direct = EtatDirect(horloge)

    val admin by lazy { fr.f4ioz.site.admin.Admin(this) }
    val apiPublication by lazy { ApiPublication(config, publications, accepte = { reglages.courant.publication(it).accepte }) }
    val modules: List<ModuleSite> by lazy { Modules.liste(this) }
    val gabarit = Gabarit(config, ::menuVisible, statique, bandeau = { reglages.courant.bandeau.actif })

    init { publications.typePublic = { reglages.courant.publication(it).publique } }

    /** False for a module hidden in « Admin › Paramètres » (those that cannot be hidden are always shown). */
    fun visible(m: ModuleSite): Boolean = m.idReglage?.let { reglages.courant.module(it).visible } ?: true

    /** False when the page at [chemin] (a module's, `/gallery`) is hidden: the home page leaves out its links. */
    fun visible(chemin: String): Boolean = reglages.courant.module(chemin).visible

    /** The rail: the shown modules' entries, with the names and places set in « Admin › Paramètres ». */
    fun menuVisible(): List<EntreeMenu> {
        val r = reglages.courant
        return modules.filter { visible(it) }.flatMap { m ->
            val reg = m.idReglage?.let { r.module(it) }
            m.menu.mapIndexed { i, e -> if (reg == null || i > 0) e else e.copy(libelle = reg.libelle.ifEmpty { e.libelle }, ordre = reg.ordre ?: e.ordre) }
        }
    }

    /** Background work: the elements every six hours, the logbook every quarter of an hour (each module's own). */
    val taches = Executors.newScheduledThreadPool(2) { r -> Thread(r, "site-taches").apply { isDaemon = true } }

    private var serveur: ServeurHttp? = null

    /** Every module's routes; a module that can be hidden answers 404 while it is (checked at each request). */
    fun routeur(): Routeur {
        val r = Routeur()
        modules.forEach { m ->
            val id = m.idReglage
            r.garde = if (id == null) null else { q -> if (reglages.courant.module(id).visible) null else pageErreur(q, 404) }
            m.installe(r)
        }
        r.garde = null
        return r
    }

    /** An error page in the site's look. */
    fun pageErreur(r: Requete, statut: Int): Reponse {
        val (titre, texte) = when (statut) {
            400 -> "Requête invalide" to "Cette adresse n'est pas comprise."
            401, 403 -> "Accès refusé" to "Cette page n'est pas publique."
            404 -> "Page introuvable" to "Cette page n'existe pas, ou plus : le site a été refait. Le menu mène à tout ce qu'il contient."
            405 -> "Méthode refusée" to "Cette page ne se demande pas ainsi."
            410 -> "Page retirée" to "Cette page n'existe plus sur le site."
            413 -> "Trop gros" to "Ce qui a été envoyé dépasse ce que le site accepte."
            429 -> "Trop de requêtes" to "Merci de patienter quelques instants avant de recommencer."
            else -> "Erreur du site" to "Une erreur s'est produite. Elle est notée ; réessayez dans un moment."
        }
        if (r.chemin.startsWith("/api/")) return Reponse.erreur(statut, titre.lowercase())
        val corps = """<section class="erreur card"><div class="card-body"><p class="erreur-code">$statut</p><h1>$titre</h1><p>$texte</p>
<p class="actions"><a class="btn solid" href="/">Accueil</a>${listOf("/articles" to "Articles", "/sat/pass" to "Passages").filter { visible(it.first) }
            .joinToString("") { " <a class=\"btn\" href=\"" + it.first + "\">" + it.second + "</a>" }}</p></div></section>"""
        return gabarit.reponse(r, Page(titre, texte, corps, r.chemin, robots = "noindex", statut = statut)).cache("no-store")
    }

    fun demarre(): Int {
        AgentHttp.version = "site"; AgentHttp.plateforme = "f4ioz.fr"
        config.donnees.mkdirs()
        catalogue.charge(); publications.charge(); logbook.charge()
        modules.forEach { it.demarre() }
        taches.scheduleWithFixedDelay({ runCatching { catalogue.rafraichis() } }, if (catalogue.luLe > horloge() - 6 * 3_600_000L) 6 * 60L else 0L, 6 * 60L, TimeUnit.MINUTES)
        taches.scheduleWithFixedDelay({ runCatching { logbook.rafraichis() } }, 1L, 15L, TimeUnit.MINUTES)
        val s = ServeurHttp(config.ecoute, config.port, routeur(), config.proxies, ::pageErreur)
        s.demarre()
        serveur = s
        return s.portReel
    }

    companion object {
        /** The program's version (the package's), « dev » in a build tree. */
        val VERSION: String = Site::class.java.`package`?.implementationVersion ?: "dev"

        fun agentDe(c: Config): String = "f4ioz-site/$VERSION (+${c.urlBaseNette})"
    }

    fun arrete() { serveur?.arrete(); taches.shutdownNow() }
}
