/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.direct

import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.http.TropGros
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.securite.Limiteur
import fr.f4ioz.site.vue.Html.h
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.vue.Page
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live part of the site: what SatMe-PC pushes (with the publication
 * token, under `/api/publication/direct…`) and what visitors read of it —
 * the banner of the auto-pilot's events, the « En direct » block (QO-100's
 * spectrum and waterfall, the DATV picture, or BATC's public pages) on the
 * home page and on `/direct`. Read-only for the public, polled (no
 * connection held open), each address braked; nothing here reaches the
 * station, ever.
 *
 * - `POST /api/publication/direct` — `{"t", "pilote": {…}|null, "datv": {…}|null}` (8 KB);
 * - `POST /api/publication/direct/spectre` — one line `{centreHz, largeurHz, minDb, maxDb, bins}` (8 KB);
 * - `POST /api/publication/direct/datv-image` — a JPEG (200 KB);
 * - `DELETE /api/publication/direct` — all forgotten;
 * - `GET /api/direct/etat`, `/api/direct/spectre?depuis=<seq>`, `/api/direct/datv.jpg` — for the pages.
 */
class ApiDirect(private val s: Site) : ModuleSite {

    private val etat get() = s.direct
    private val freinEtat = Limiteur(40, 60_000L)
    private val freinSpectre = Limiteur(150, 60_000L)
    private val freinImage = Limiteur(40, 60_000L)

    private val r get() = s.reglages.courant

    /** The page « En direct »: in the menu only while the block is on and has its page. */
    override val menu: List<EntreeMenu> get() = if (r.direct.actif && r.direct.page)
        listOf(EntreeMenu(Groupe.STATION, 5, "/direct", "En direct", "direct", "QO-100 et la DATV reçus à la station")) else emptyList()
    override val idReglage: String? get() = null
    override fun plan() = if (r.direct.actif && r.direct.page) listOf("/direct" to 0.5) else emptyList()

    override fun installe(r: Routeur) {
        r.post("/api/publication/direct") { q -> s.apiPublication.autorise(q) { recoit(q) } }
        r.delete("/api/publication/direct") { q -> s.apiPublication.autorise(q) { etat.efface(); Reponse.json(JSONObject().put("ok", true)) } }
        r.post("/api/publication/direct/spectre") { q -> s.apiPublication.autorise(q) { spectre(q) } }
        r.post("/api/publication/direct/datv-image") { q -> s.apiPublication.autorise(q) { image(q) } }
        r.post("/api/publication/direct/sstv-image") { q -> s.apiPublication.autorise(q) { imageSstv(q) } }
        r.get("/api/direct/etat") { q -> public(q, freinEtat) { Reponse.json(etatPublic()) } }
        r.get("/api/direct/spectre") { q -> public(q, freinSpectre) {
            if (!this.r.direct.station || !this.r.direct.spectre) Reponse.erreur(404, "pas de spectre")
            // A new visitor gets the last minute of the waterfall, then one line at a time (≈ 1.5 KB).
            else etat.spectre(q.param("depuis")?.toLongOrNull() ?: 0L, 60)?.let { Reponse.json(it) } ?: Reponse.json(JSONObject().put("ok", true).put("actif", false))
        } }
        r.get("/api/direct/datv.jpg") { q -> public(q, freinImage) {
            if (!this.r.direct.station || !this.r.direct.datv) s.pageErreur(q, 404)
            else etat.image()?.let { Reponse.octets(it, "image/jpeg").cache("no-store") } ?: s.pageErreur(q, 404)
        } }
        r.get("/api/direct/sstv.jpg") { q -> public(q, freinImage) {
            if (!this.r.direct.actif || !this.r.direct.basculeSstv) s.pageErreur(q, 404)
            else etat.imageSstv()?.let { Reponse.octets(it, "image/jpeg").cache("no-store") } ?: s.pageErreur(q, 404)
        } }
        r.get("/direct") { q -> if (!this.r.direct.actif || !this.r.direct.page) s.pageErreur(q, 404) else page(q) }
    }

    // ------------------------------------------------------------------ SatMe-PC's side

    private fun refus(e: EtatDirect.Refus) = Reponse.json(JSONObject().put("ok", false).put("erreur", e.message).put("refuse", e.statut == 403), e.statut)

    private fun recoit(q: Requete): Reponse = try {
        val o = JSONObject(String(q.corps(8 * 1024L), Charsets.UTF_8))
        val d = r.direct
        etat.recoit(o, pilotePermis = r.bandeau.actif || d.actif, datvPermis = d.station && d.datv)
        Reponse.json(JSONObject().put("ok", true))
    } catch (e: EtatDirect.Refus) { refus(e) } catch (e: TropGros) { Reponse.erreur(413, "trop gros (8 Ko au plus)") }
    catch (e: org.json.JSONException) { Reponse.erreur(400, "JSON illisible") }

    private fun spectre(q: Requete): Reponse = try {
        val d = r.direct
        if (!d.station || !d.spectre) throw EtatDirect.Refus(403, "le site n'affiche pas le spectre (Admin › Paramètres)")
        etat.recoitSpectre(JSONObject(String(q.corps(8 * 1024L), Charsets.UTF_8)))
        Reponse.json(JSONObject().put("ok", true).put("seq", etat.seq))
    } catch (e: EtatDirect.Refus) { refus(e) } catch (e: TropGros) { Reponse.erreur(413, "ligne trop grosse (8 Ko au plus)") }
    catch (e: org.json.JSONException) { Reponse.erreur(400, "JSON illisible") }

    private fun image(q: Requete): Reponse = try {
        val d = r.direct
        if (!d.station || !d.datv) throw EtatDirect.Refus(403, "le site n'affiche pas l'image DATV (Admin › Paramètres)")
        etat.recoitImage(q.corps(EtatDirect.IMAGE_MAX.toLong()))
        Reponse.json(JSONObject().put("ok", true).put("numero", etat.imageNumero))
    } catch (e: EtatDirect.Refus) { refus(e) } catch (e: TropGros) { Reponse.erreur(413, "image trop grosse (max ${EtatDirect.IMAGE_MAX / 1024} Ko)") }

    private fun imageSstv(q: Requete): Reponse = try {
        if (!r.direct.actif || !r.direct.basculeSstv) throw EtatDirect.Refus(403, "le site ne montre pas les passages SSTV en direct (Admin › Paramètres)")
        etat.recoitImageSstv(q.corps(EtatDirect.IMAGE_MAX.toLong()))
        Reponse.json(JSONObject().put("ok", true).put("numero", etat.imageSstvNumero))
    } catch (e: EtatDirect.Refus) { refus(e) } catch (e: TropGros) { Reponse.erreur(413, "image trop grosse (max ${EtatDirect.IMAGE_MAX / 1024} Ko)") }

    // ------------------------------------------------------------------ the visitors' side

    private fun public(q: Requete, frein: Limiteur, suite: () -> Reponse): Reponse {
        if (!r.bandeau.actif && !r.direct.actif) return s.pageErreur(q, 404)
        if (!frein.accepte(q.ip)) return Reponse.erreur(429, "trop de requêtes").entete("Retry-After", "20")
        return suite()
    }

    /** What the banner and the block show now, as the settings allow. */
    fun etatPublic(): JSONObject {
        val reg = r
        val t = System.currentTimeMillis()
        val o = JSONObject().put("ok", true).put("t", t)
        val p = etat.pilote()
        val vue = if (reg.direct.actif) etat.vue(reg.direct.basculeSstv, reg.direct.maintienS * 1000L) else VueDirect.Vue.QO100
        if (reg.bandeau.actif) {
            val b = JSONObject()
            if (reg.bandeau.receptionEnCours) p?.optJSONObject("enCours")?.let { b.put("enCours", it) }
            if (reg.bandeau.prochainPassage) p?.optJSONObject("prochain")?.takeIf { it.optLong("los") > t }?.let { b.put("prochain", it) }
            if (reg.bandeau.derniereImage) derniereImage(p)?.let { b.put("derniere", it) }
            // The badge « Passage en cours », to the live block.
            if (vue == VueDirect.Vue.SSTV) b.put("passageEnCours", true)
            o.put("bandeau", b)
        }
        if (reg.direct.actif) {
            val d = JSONObject().put("source", reg.direct.source).put("vue", vue.nom).put("page", reg.direct.page).put("accueil", reg.direct.accueil)
            if (vue == VueDirect.Vue.SSTV) etat.passageSstv()?.let { d.put("sstv", it.put("maintienMs", reg.direct.maintienS * 1000L)) }
            if (reg.direct.station) {
                if (reg.direct.spectre) d.put("spectre", etat.spectreActif())
                if (reg.direct.datv) etat.datv()?.let { d.put("datv", it) }
            }
            o.put("direct", d)
        }
        return o
    }

    /** The last SSTV picture: the newest of the one published (with its page) and the one the auto-pilot said. */
    private fun derniereImage(p: JSONObject?): JSONObject? {
        val pub = s.publications.visibles(TypePublication.SSTV).firstOrNull()?.takeIf { s.visible("/gallery") }?.let { x ->
            JSONObject().put("utc", x.quandMs).put("sat", x.satellite).put("mode", x.texte("mode")).put("url", "/gallery/item/" + x.id)
                .put("vignette", if (x.vignette) "/media/${x.id}/vignette.jpg" else "")
        }
        val pil = p?.optJSONObject("derniere")
        return when {
            pub == null -> pil
            pil == null -> pub
            pil.optLong("utc") > pub.optLong("utc") + 60_000L -> pil
            else -> pub
        }
    }

    private fun page(q: Requete): Reponse {
        val sb = StringBuilder("<header class=\"page-head\"><h1>En direct</h1><p>Ce que la station reçoit en ce moment : le transpondeur étroit de QO-100 " +
            "(spectre et chute d'eau), l'image DATV du transpondeur large, et l'auto-pilote SSTV. Tout est envoyé par SatMe-PC ; " +
            "rien ici ne commande la station.</p></header>")
        sb.append("<div class=\"home-grid\">").append(bloc(s, grand = true, place = "--gc:1/13;--gr:1/5")).append("</div>")
        val p = Page("En direct", "QO-100 et la DATV reçus en direct à la station ${s.config.indicatif}.", sb.toString(), "/direct",
            scripts = listOf("js/direct.js"), classeCorps = "ecran")
        return s.gabarit.reponse(q, p).cache("public, max-age=60")
    }

    companion object {
        /** BATC's public pages for QO-100 (they cannot be framed: links). */
        const val BATC_LARGE = "https://eshail.batc.org.uk/wb/"
        const val BATC_ETROIT = "https://eshail.batc.org.uk/nb/"

        /**
         * The narrow band's plan for the drawing: the transponders, the segments (their French names, SatMe's)
         * and the beacons.
         */
        fun plan(): JSONObject {
            val noms = fr.f4ioz.satcombo.i18n.FR
            return JSONObject()
                .put("transpondeurs", JSONArray(Qo100.TRANSPONDEURS.map { t ->
                    JSONObject().put("nom", noms["qo100_tp_" + t.cle] ?: t.cle).put("bas", t.descenteBasHz).put("haut", t.descenteHautHz) }))
                .put("segments", JSONArray(Qo100.SEGMENTS.map { g ->
                    JSONObject().put("nom", noms["qo100_seg_" + g.cle] ?: g.cle).put("bas", g.basHz).put("haut", g.hautHz).put("usage", g.usage.name.lowercase())
                        .also { o -> g.repereHz?.let { o.put("repere", it) } } }))
                .put("balises", JSONArray(listOf(Qo100.BALISE_BASSE_HZ, Qo100.BALISE_MEDIANE_HZ, Qo100.BALISE_MULTIMEDIA_HZ, Qo100.BALISE_HAUTE_HZ,
                    fr.f4ioz.satcombo.datv.Datv.BALISE_KHZ * 1000L)))
        }

        /** The « En direct » block, filled by `direct.js`; [grand]: the page's (taller drawing). */
        fun bloc(s: Site, grand: Boolean = false, place: String = ""): String {
            val d = s.reglages.courant.direct
            val sb = StringBuilder()
            sb.append("<section class=\"card span-12 direct-bloc").append(if (grand) " grand" else "").append("\" id=\"direct\" data-direct aria-labelledby=\"h-direct\"")
                .append(if (place.isNotEmpty()) " style=\"$place\"" else "").append(">")
                .append("<header class=\"card-head\"><h2 class=\"card-title\" id=\"h-direct\">").append(Icones.svg("direct")).append("<span data-direct-titre>En direct de la station</span></h2>")
                .append("<span class=\"card-meta\" data-direct-etat role=\"status\">…</span></header><div class=\"card-body direct-corps\">")
            if (d.station && d.spectre) sb.append("<figure class=\"direct-spectre\" data-direct-spectre hidden data-plan=\"").append(h(plan().toString())).append("\">")
                .append("<canvas class=\"direct-echelle\" data-echelle aria-hidden=\"true\"></canvas>")
                .append("<canvas class=\"direct-trace\" data-trace width=\"1024\" height=\"").append(if (grand) 140 else 100).append("\" aria-hidden=\"true\"></canvas>")
                .append("<canvas class=\"direct-chute\" data-chute width=\"1024\" height=\"").append(if (grand) 200 else 120).append("\" role=\"img\" aria-label=\"Spectre et chute d'eau du transpondeur étroit de QO-100\"></canvas>")
                .append("<figcaption><b>QO-100 · transpondeur étroit</b> <span class=\"dim\" data-spectre-legende></span></figcaption></figure>")
            if (d.station && d.datv) sb.append("<figure class=\"direct-datv\" data-direct-datv hidden><div class=\"datv-cadre\"><img data-datv-img alt=\"Image DATV reçue à la station\" width=\"640\" height=\"360\">")
                .append("<span class=\"datv-hors\" data-datv-hors hidden>hors ligne</span></div>")
                .append("<figcaption><b data-datv-nom>DATV</b> <span class=\"dim\" data-datv-info></span><br><small class=\"dim\" data-datv-age></small></figcaption></figure>")
            sb.append("<div class=\"direct-batc\" data-direct-batc").append(if (d.source == "station") " data-jamais" else "").append(" hidden>")
                .append("<a class=\"batc-carte\" href=\"").append(BATC_LARGE).append("\" rel=\"noopener\" target=\"_blank\">").append(Icones.svg("external"))
                .append("<span><b>Transpondeur large (DATV)</b><span>Le spectre public de la station sol du BATC, à Goonhilly</span></span></a>")
                .append("<a class=\"batc-carte\" href=\"").append(BATC_ETROIT).append("\" rel=\"noopener\" target=\"_blank\">").append(Icones.svg("external"))
                .append("<span><b>Transpondeur étroit (WebSDR)</b><span>Écouter QO-100 dans le navigateur, sur le WebSDR du BATC</span></span></a>")
                .append("<p class=\"dim petit\">Pages du British Amateur Television Club, ouvertes dans un nouvel onglet.</p></div>")
            // The auto-pilot's SSTV pass, shown instead of QO-100 while it is received.
            if (d.basculeSstv) sb.append("<div class=\"direct-sstv\" data-direct-sstv hidden><div class=\"sstv-cadre\"><img data-sstv-img alt=\"Image SSTV en cours de réception à la station\" hidden>")
                .append("<span class=\"sstv-attente\" data-sstv-attente>En attente de la première image…</span></div>")
                .append("<div class=\"sstv-info\"><p class=\"sstv-titre\"><span class=\"bd-led\"></span><b data-sstv-sat>Passage SSTV</b></p>")
                .append("<p class=\"sstv-compte\" data-sstv-compte></p><p class=\"sstv-mesures\" data-sstv-mesures></p><p class=\"dim petit\" data-sstv-etat></p></div></div>")
            sb.append("<p class=\"card-empty\" data-direct-vide hidden>La station n'envoie rien en direct pour le moment.</p>")
            sb.append("</div></section>\n")
            return sb.toString()
        }
    }
}
