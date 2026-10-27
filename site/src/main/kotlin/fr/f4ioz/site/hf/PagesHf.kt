/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The « Trafic & propagation » pages: their HTML is a frame the page's
 * script fills from the site's APIs ([ApiHf]); what the script needs to start
 * (the station's call and locator…) is given as JSON in the page. Maps are
 * Leaflet, served by the site, on OpenStreetMap's tiles fetched and kept by
 * the site (`/journal/tuile`).
 */
abstract class PageHf(protected val s: Site) : ModuleSite {

    protected fun donnees(extra: JSONObject = JSONObject()): String {
        val c = s.config
        val o = JSONObject().put("indicatif", c.indicatif).put("locator", c.locator)
        for (k in extra.keySet()) o.put(k, extra.get(k))
        return "<script type=\"application/json\" id=\"hf-donnees\">" + Html.jsonScript(o.toString()) + "</script>"
    }

    protected fun page(q: Requete, titre: String, description: String, chemin: String, corps: String, script: String, carte: Boolean = true): Reponse {
        val styles = if (carte) listOf("vendor/leaflet/leaflet.css", "css/hf.css") else listOf("css/hf.css")
        val scripts = (if (carte) listOf("vendor/leaflet/leaflet.js") else emptyList()) + listOf("js/hf-commun.js", script)
        return s.gabarit.reponse(q, Page(titre, description, corps, chemin, styles = styles, scripts = scripts, classeCorps = "page-hf"))
            .cache("public, max-age=300")
    }

    protected fun tete(titre: String, texte: String, etat: String = "hf-etat"): String =
        "<header class=\"page-head hf-tete\"><h1>" + h(titre) + " <span class=\"hf-statut\" id=\"$etat\" role=\"status\" aria-live=\"polite\">…</span></h1><p>" + texte + "</p></header>"

    companion object {
        /** A `<select>`'s options, [choisi] selected. */
        fun options(l: List<Pair<String, String>>, choisi: String) = l.joinToString("") { (v, t) ->
            "<option value=\"" + h(v) + "\"" + (if (v == choisi) " selected" else "") + ">" + h(t) + "</option>"
        }

        val BANDES_SEL = listOf("" to "Toutes") + listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm").map { it to it }
    }
}

/** DX : PSKReporter's reports, live — a table or a map, with HamQSL's conditions above. */
class PageDx(s: Site) : PageHf(s) {
    override val menu = listOf(EntreeMenu(Groupe.TRAFIC, 10, "/dx", "DX cluster", "dx", "Les reports PSKReporter en direct"))
    override fun plan() = listOf("/dx" to 0.6)
    override fun installe(r: Routeur) { r.get("/dx") { q -> rend(q) } }

    private fun rend(q: Requete): Reponse {
        val modes = listOf("" to "Tous") + PskReporter.MODES.filter { it.isNotEmpty() }.map { it to it }
        val corps = tete("DX cluster", "Qui entend qui, en ce moment : les reports de réception de <a href=\"https://pskreporter.info/\" rel=\"noopener\">PSKReporter</a> " +
            "(modes numériques et skimmers CW, le monde entier). Le site interroge PSKReporter au plus une fois toutes les cinq minutes par mode, pour tous les visiteurs. Heures UTC.") +
            solaireBandeau() +
            """<section class="card"><header class="card-head"><h2 class="card-title">Filtres</h2><div class="hf-onglets" role="tablist">
<button type="button" class="hf-onglet actif" data-vue="table" role="tab" aria-selected="true">Tableau</button><button type="button" class="hf-onglet" data-vue="carte" role="tab" aria-selected="false">Carte</button></div></header>
<div class="card-body hf-filtres">
<label>Bande <select id="f-band">${options(BANDES_SEL, "20m")}</select></label>
<label>Mode <select id="f-mode">${options(modes, "FT8")}</select></label>
<label>Fenêtre <select id="f-since">${options(listOf("120" to "2 min", "300" to "5 min", "600" to "10 min", "1800" to "30 min"), "300")}</select></label>
<label>Limite <select id="f-limit">${options(listOf("50" to "50", "100" to "100", "200" to "200", "500" to "500"), "100")}</select></label>
<label>Indicatif <input type="text" id="f-call" placeholder="F4* ou EA?ABC" maxlength="12" autocapitalize="characters" size="10"></label>
<label>Continent <select id="f-cont">${options(listOf("" to "Tous", "EU" to "Europe", "NA" to "Amérique du Nord", "SA" to "Amérique du Sud", "AS" to "Asie", "AF" to "Afrique", "OC" to "Océanie"), "")}</select></label>
<label><input type="checkbox" id="f-lotw"> LoTW seuls</label>
<label><input type="checkbox" id="f-bip"> Bip à chaque nouveau</label>
<button type="button" class="btn" id="dx-rafraichir">Rafraîchir</button>
</div>
<div class="hf-stats"><div><span>Spots</span><b id="s-count">—</b></div><div><span>Émetteurs</span><b id="s-tx">—</b></div><div><span>DXCC</span><b id="s-dxcc">—</b></div><div><span>Le plus récent</span><b id="s-age">—</b></div></div>
<div id="dx-carte" class="hf-carte" hidden></div>
<div class="table-wrap" id="dx-table-wrap"><table class="hf-table dx-table"><thead><tr><th>Cont.</th><th>Émetteur</th><th>DXCC</th><th class="num">Loc TX</th><th>Récepteur</th><th class="num">Loc RX</th><th class="num">kHz</th><th>Bande</th><th>Mode</th><th class="num">SNR</th><th class="num">Âge</th></tr></thead>
<tbody id="dx-corps"><tr><td colspan="11" class="vide">Chargement…</td></tr></tbody></table></div>
</section>
<p class="hf-pied">Source : <a href="https://pskreporter.info/" rel="noopener">PSKReporter</a> (Philip Gladstone, N1DQ) · conditions <a href="https://www.hamqsl.com/solar.html" rel="noopener">HamQSL / N0NBH</a> · rafraîchi toutes les 90 s (données renouvelées au plus toutes les 5 min) · cliquer une fréquence la copie.</p>
""" + donnees()
        return page(q, "DX cluster — reports PSKReporter en direct", "Reports de réception PSKReporter en direct (FT8, FT4, CW…) : tableau filtrable par bande, mode, continent, et carte des trajets.",
            "/dx", corps, "js/dx.js")
    }

    companion object {
        fun solaireBandeau() = """<section class="card hf-solaire"><header class="card-head"><h2 class="card-title">Conditions HF</h2><span class="card-meta">HamQSL · relu toutes les heures</span></header>
<div class="card-body hf-soleil" id="hf-soleil">${listOf("sfi" to "SFI", "sn" to "SN", "a" to "A", "k" to "K", "xray" to "Rayons X", "aurora" to "Aurore", "muf" to "MUF", "es" to "Es Europe", "maj" to "Mis à jour")
            .joinToString("") { (k, t) -> "<div><span>$t</span><b id=\"sol-$k\">—</b></div>" }}</div></section>"""
    }
}

/** Bandes : HamQSL read in words, the bands by period at the QTH, the propagation map (PSKReporter, WSPR or RBN, KC2G's model). */
class PageBandes(s: Site) : PageHf(s) {
    override val menu = listOf(EntreeMenu(Groupe.TRAFIC, 20, "/bands", "Bandes", "bands", "Conditions et carte de propagation"))
    override fun plan() = listOf("/bands" to 0.6)
    override fun installe(r: Routeur) { r.get("/bands") { q -> rend(q) } }

    private fun rend(q: Requete): Reponse {
        val corps = tete("Bandes", "Les conditions de propagation HF et VHF expliquées : flux solaire, indices A et K (échelles NOAA), rayons X, " +
            "conditions par bande de jour et de nuit à votre QTH, et la carte de ce qui passe vraiment (PSKReporter, WSPR ou RBN), avec le modèle ionosphérique de KC2G.") +
            """<form class="card hf-qth" id="qth-form"><div class="card-body"><label>Votre locator <input type="text" id="qth" value="${h(s.config.locator)}" maxlength="6" size="7" autocapitalize="characters" autocomplete="off" pattern="[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?"></label>
<span class="dim" id="qth-err" role="alert"></span><span class="hf-jour" id="daynight"></span></div></form>
<section class="card"><header class="card-head"><h2 class="card-title">Indices solaires et géomagnétiques</h2><span class="card-meta" id="bands-maj">—</span></header><div class="card-body"><div class="hf-indices" id="solar-indices"></div></div></section>
<div class="hf-deux">
<section class="card"><header class="card-head"><h2 class="card-title">Conditions HF par bande</h2></header><div class="card-body"><div id="hf-matrix"></div><div class="hf-meilleures" id="best-bands"></div></div></section>
<section class="card"><header class="card-head"><h2 class="card-title">VHF, E sporadique, aurore</h2><span class="card-meta">50–144 MHz</span></header><div class="card-body"><div id="vhf-cond"></div>
<p class="hf-aide"><b>Lecture rapide :</b> SFI élevé et K bas : bandes hautes (10–20 m) ouvertes et stables. Un K élevé (tempête G) dégrade surtout les chemins polaires et peut ouvrir l'aurore en VHF. Une éruption X coupe la HF côté jour. Sur la <i>gray line</i> (lever, coucher au QTH), le DX en bas de bande est favorisé.</p></div></section>
</div>
<section class="card"><header class="card-head"><h2 class="card-title">Carte de propagation</h2><span class="card-meta" id="prop-status">—</span></header><div class="card-body">
<div class="hf-puces" id="prop-src"><span class="hf-puces-titre">Source</span><button type="button" class="puce actif" data-src="psk" title="Reports de réception FT8/FT4… (PSKReporter)">PSKReporter</button><button type="button" class="puce" data-src="wspr" title="Signaux faibles, ouvertures marginales (wspr.live)">WSPR</button><button type="button" class="puce" data-src="rbn" title="Skimmers CW/RTTY en direct (Reverse Beacon Network)">RBN</button></div>
<div class="hf-puces" id="prop-iono"><span class="hf-puces-titre">Modèle</span><button type="button" class="puce actif" data-iono="">Aucun</button><button type="button" class="puce" data-iono="muf" title="Fréquence maximale utilisable (MUF 3000 km), KC2G">MUF</button><button type="button" class="puce" data-iono="fof2" title="Fréquence critique de la couche F2, KC2G">foF2</button></div>
<div class="hf-puces" id="prop-p2p"><span class="hf-puces-titre">Trajet</span><input type="text" id="p2p-dx" placeholder="locator ou indicatif" maxlength="12" size="14" autocapitalize="characters" autocomplete="off"><button type="button" class="puce" id="p2p-go">Tracer</button><button type="button" class="puce" id="p2p-clear" aria-label="Effacer le trajet">✕</button><span class="dim" id="p2p-info"></span></div>
<div class="hf-puces" id="prop-bands"></div>
<div id="prop-map" class="hf-carte"></div>
<div class="hf-legende"><span><i class="lg-tache"></i>tache : où l'on reçoit (taille ∝ nombre de reports)</span><span><i class="lg-plein"></i>on vous entend (trait plein)</span><span><i class="lg-tirets"></i>vous entendez (pointillés)</span><span><i class="lg-gris"></i>gray line</span><span><i class="lg-iono"></i>contours MUF / foF2 (MHz)</span><span><i class="lg-sp"></i>trajet court · <i class="lg-lp"></i>trajet long</span></div>
<div class="hf-analyse" id="prop-analysis"></div>
<p class="hf-aide">Les <b>taches</b> regroupent les reports par région de réception, sur la dernière quinzaine de minutes. Les <b>lignes</b> partent de votre région : trait plein, une station de chez vous entendue au loin ; pointillés, une station lointaine entendue chez vous. Un indicatif tapé dans « Trajet » est placé au centre de son pays (sans annuaire) ; un locator, à sa place exacte. L'analyse croise les mesures de la source choisie avec le modèle HamQSL.</p>
</div></section>
<p class="hf-pied">Conditions <a href="https://www.hamqsl.com/solar.html" rel="noopener">HamQSL / N0NBH</a> · modèle ionosphérique © <a href="https://prop.kc2g.com/" rel="noopener">KC2G</a>, <a href="https://creativecommons.org/licenses/by/4.0/" rel="noopener">CC BY 4.0</a> · reports <a href="https://pskreporter.info/" rel="noopener">PSKReporter</a>, <a href="https://wspr.live/" rel="noopener">wspr.live</a> (données WSPRnet), <a href="https://www.reversebeacon.net/" rel="noopener">Reverse Beacon Network</a> · interprétation indicative.</p>
""" + donnees()
        return page(q, "Bandes — conditions et carte de propagation", "Conditions de propagation HF/VHF en clair (SFI, A, K, rayons X), bandes ouvertes de jour et de nuit à votre QTH, carte PSKReporter/WSPR/RBN et MUF KC2G.",
            "/bands", corps, "js/bandes.js")
    }
}

/** Balises : the NCDXF/IARU beacons, worked out in the browser from the UTC clock. */
class PageBalises(s: Site) : PageHf(s) {
    override val menu = listOf(EntreeMenu(Groupe.TRAFIC, 30, "/beacons", "Balises", "beacons", "Les balises NCDXF en ce moment"))
    override fun plan() = listOf("/beacons" to 0.5)
    override fun installe(r: Routeur) { r.get("/beacons") { q -> rend(q) } }

    private fun rend(q: Requete): Reponse {
        val corps = tete("Balises NCDXF", "Les 18 balises de l'<a href=\"https://www.ncdxf.org/beacon/\" rel=\"noopener\">International Beacon Project</a> (NCDXF/IARU) " +
            "se relaient sur 14,100, 18,110, 21,150, 24,930 et 28,200 MHz : dix secondes chacune, un cycle de trois minutes. Leur indicatif en CW à 100 W, puis quatre traits " +
            "à 100, 10, 1 et 0,1 W : celui qu'on entend encore dit la marge. Calculé dans votre navigateur sur l'heure UTC (réglez votre horloge).", "bn-horloge") +
            """<form class="card hf-qth" id="qth-form"><div class="card-body"><label>Votre locator <input type="text" id="qth" value="${h(s.config.locator)}" maxlength="6" size="7" autocapitalize="characters" autocomplete="off"></label><span class="dim" id="qth-err" role="alert"></span></div></form>
<section class="card"><header class="card-head"><h2 class="card-title">Émet maintenant</h2></header><div class="card-body"><div class="bn-maintenant" id="beacon-now"></div></div></section>
<div class="hf-deux">
<section class="card"><header class="card-head"><h2 class="card-title">Carte</h2></header><div class="card-body"><canvas id="beacon-map" class="bn-carte" aria-label="Carte du monde : les balises qui émettent"></canvas></div></section>
<section class="card"><header class="card-head"><h2 class="card-title">Ordre de passage</h2></header><div class="card-body"><div id="beacon-grid"></div></div></section>
</div>
<p class="hf-pied">Programme officiel du NCDXF · traits de côte Natural Earth (domaine public).</p>
""" + donnees()
        return page(q, "Balises NCDXF/IARU — qui émet maintenant", "Les balises NCDXF/IARU du International Beacon Project en direct : qui émet sur 20, 17, 15, 12 et 10 m, azimut et distance depuis votre QTH.",
            "/beacons", corps, "js/balises.js", carte = false)
    }
}

/** WSPR : the bands' activity (wspr.live, or RBN) and a call's footprint. */
class PageWspr(s: Site) : PageHf(s) {
    override val menu = listOf(EntreeMenu(Groupe.TRAFIC, 40, "/wspr", "WSPR", "wspr", "Activité des bandes, qui vous entend"))
    override fun plan() = listOf("/wspr" to 0.5)
    override fun installe(r: Routeur) { r.get("/wspr") { q -> rend(q) } }

    private fun rend(q: Requete): Reponse {
        val corps = tete("WSPR et RBN", "L'activité des bandes et la portée réelle, mesurées par les signaux faibles WSPR (<a href=\"https://wspr.live/\" rel=\"noopener\">wspr.live</a>, données WSPRnet) " +
            "ou par les skimmers CW/RTTY du Reverse Beacon Network ; puis l'empreinte d'un indicatif : qui l'entend, qui il entend. Heures UTC.") +
            """<section class="card"><header class="card-head"><h2 class="card-title">Activité par bande</h2><div class="hf-onglets" id="wspr-src" role="tablist"><button type="button" class="hf-onglet actif" data-src="wspr" role="tab" aria-selected="true">WSPR</button><button type="button" class="hf-onglet" data-src="rbn" role="tab" aria-selected="false">RBN</button></div></header>
<div class="card-body"><div class="hf-filtres"><label>Fenêtre <select id="wspr-minutes">${options(listOf("15" to "15 min", "30" to "30 min", "60" to "1 h", "120" to "2 h"), "30")}</select></label><span class="dim" id="wspr-note"></span></div>
<div id="wspr-bands" class="hf-barres"></div></div></section>
<section class="card"><header class="card-head"><h2 class="card-title">Empreinte</h2><span class="card-meta" id="wspr-fp-status">—</span></header><div class="card-body">
<div class="hf-filtres"><label>Indicatif <input type="text" id="wspr-call" value="${h(s.config.indicatif)}" maxlength="12" size="10" autocapitalize="characters" autocomplete="off"></label>
<span class="hf-onglets" id="wspr-dir" role="tablist"><button type="button" class="hf-onglet actif" data-dir="tx" role="tab" aria-selected="true">Qui l'entend</button><button type="button" class="hf-onglet" data-dir="rx" role="tab" aria-selected="false">Qui il entend</button></span>
<label id="wspr-hours-fld">Depuis <select id="wspr-hours">${options(listOf("2" to "2 h", "6" to "6 h", "12" to "12 h", "24" to "24 h"), "6")}</select></label>
<button type="button" class="btn" id="wspr-go">Afficher</button></div>
<div class="hf-resume" id="wspr-summary"></div>
<div id="wspr-map" class="hf-carte"></div></div></section>
<p class="hf-pied">Données <a href="https://wspr.live/" rel="noopener">wspr.live</a> (base ClickHouse des spots <a href="https://www.wsprnet.org/" rel="noopener">WSPRnet</a>, usage libre non commercial) · <a href="https://www.reversebeacon.net/" rel="noopener">Reverse Beacon Network</a> · gardé 2 min par le site.</p>
""" + donnees()
        return page(q, "WSPR — activité des bandes et empreinte", "Activité WSPR par bande (wspr.live), portée médiane et maximale, et empreinte d'un indicatif sur la carte : qui l'entend, qui il entend.",
            "/wspr", corps, "js/wspr.js")
    }
}

/** Ouvertures : when the bands open from the station's locator, from RBN's archives. */
class PageOuvertures(s: Site) : PageHf(s) {
    override val menu = listOf(EntreeMenu(Groupe.TRAFIC, 50, "/openings", "Ouvertures", "openings", "Les bandes heure par heure (RBN)"))
    override fun plan() = listOf("/openings" to 0.5)
    override fun installe(r: Routeur) { r.get("/openings") { q -> rend(q) } }

    private fun rend(q: Requete): Reponse {
        val locs = s.config.locatorsOuvertures
        val corps = tete("Ouvertures", "À quelle heure chaque bande s'ouvre depuis la région de ${h(locs.first())} (450 km autour), d'après les archives quotidiennes du " +
            "<a href=\"https://www.reversebeacon.net/\" rel=\"noopener\">Reverse Beacon Network</a> : les stations de la région entendues par les skimmers du monde, " +
            "bande par bande et heure par heure (UTC), puis où elles sont entendues.") +
            """<section class="card"><header class="card-head"><h2 class="card-title">Bande × heure UTC</h2></header><div class="card-body">
<div class="hf-filtres"><label>Locator <select id="oh-loc">${options(locs.map { it to it }, locs.first())}</select></label>
<label>Jours <select id="oh-days">${options(listOf("1" to "1", "3" to "3", "7" to "7", "14" to "14", "30" to "30"), if (s.config.ouverturesJours >= 14) "14" else "7")}</select></label><span class="dim" id="oh-jours"></span></div>
<div class="oh-grille-wrap"><div id="oh-grid" class="oh-grille"><p class="vide">Chargement…</p></div></div>
<p class="oh-resume" id="oh-summary"></p></div></section>
<section class="card"><header class="card-head"><h2 class="card-title">Où la région est entendue</h2><span class="card-meta" id="oh-heat-status">—</span></header><div class="card-body">
<div class="hf-puces" id="oh-bands"></div>
<div class="hf-filtres"><label>Heure UTC <input type="range" id="oh-hour" min="-1" max="23" step="1" value="-1"> <b id="oh-hour-lbl">toutes</b></label></div>
<div id="oh-map" class="hf-carte"></div></div></section>
<p class="hf-pied">Archives <a href="https://www.reversebeacon.net/" rel="noopener">Reverse Beacon Network</a> (data.reversebeacon.net), lues une fois par jour pour les locators de la station seulement, résumées et gardées 32 jours. Cliquer une case de la grille montre ses skimmers sur la carte.</p>
""" + donnees(JSONObject().put("locators", JSONArray(locs)))
        return page(q, "Ouvertures — les bandes heure par heure", "Heures d'ouverture des bandes HF depuis ${locs.first()}, d'après les archives du Reverse Beacon Network : grille bande × heure UTC et carte des skimmers.",
            "/openings", corps, "js/ouvertures.js")
    }
}
