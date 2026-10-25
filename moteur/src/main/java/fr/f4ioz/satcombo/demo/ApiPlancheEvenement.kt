/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlanchePolices
import fr.f4ioz.satcombo.sstv.PlancheStyle
import fr.f4ioz.satcombo.sstv.PlancheTitres
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SerieSstv
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.station.DessinMontage
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.PlansMontage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The sheet and its event, the same on SatMe-PC and on the phone (both
 * serve the sheet's page, both screens call it): the event a sheet is about
 * (chosen by itself from its pictures, changed by hand), the gallery as the
 * sheet's picker shows it (each picture's mark, its slide and where the
 * number comes from, its group, in the event or not), « Fill by slide »,
 * « Relancer l'analyse », and the event's report.
 *
 * Routes under `/planche`: `/evenements?id=`, `/evenement?id=&ev=` (""
 * chosen by itself again, « aucun » none), `/galerie?id=`,
 * `/remplit-diapos?id=&tous=&sat=&reinit=`, `/relance?oublie=`, `/analyse`,
 * `/case?id=&i=` (a box's versions), `/fusionner?id=&i=`, `/version-vignette?f=`,
 * `/plans?id=`, `/rapport?id=&chaine=&vue=&plan=&dl=&format=` (`pdf`: the PDF, else the
 * page; `vue=simple`: the chain in boxes, else the montage's drawing when
 * there is one).
 */
object ApiPlancheEvenement {

    /** What the platform gives. */
    interface Source {
        /** The SSTV pictures' folder. */
        val dossier: File
        val classement: ClassementGalerie
        /** The pictures received (test pictures left out). */
        fun images(): List<SstvMeta.SstvShot>
        /** The campaigns and activities ([EvenementSstv.liste]). */
        fun evenements(): List<EvenementSstv.Evenement>
        /** A satellite's NORAD number from its name, 0 unknown. */
        fun catnumDe(sat: String): Int
        /** [catnum]'s passes from [deMs] to [aMs] (from 0°), worked out with the elements loaded. */
        fun passes(catnum: Int, deMs: Long, aMs: Long): List<SatPass>
        /** Where [catnum] was (az, el) at [ts]; null unknown. */
        fun positions(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>?
        /** The epoch of [catnum]'s elements, null unknown. */
        fun epoqueElements(catnum: Int): Long?
        /** The autopilot's history and campaigns (none on the phone). */
        fun traces(): List<CampagneSstv.Trace> = emptyList()
        fun campagne(id: String): CampagneSstv.Campagne? = null
        fun elMin(): Double
        fun station(): RapportEvenement.Station
        /** The station's plans (id, name), the one shown first; empty: none. */
        fun plans(): List<Pair<String, String>> = emptyList()
        /** A plan's chain in columns, its name; null when there is none (the phone: what it knows of itself). */
        fun chaine(plan: String): Pair<String, List<List<RapportEvenement.Etape>>>?
        /** The sheet drawn, JPEG. */
        fun planche(m: Planche.Modele): ByteArray?
        /** A picture small, JPEG. */
        fun vignette(nom: String): ByteArray?
        fun maintenant(): Long = System.currentTimeMillis()
        /** The report as a PDF ([fr.f4ioz.satcombo.sstv.RapportPdf] drawn by the platform); null: none here. */
        fun pdf(r: RapportEvenement.Rapport): ByteArray? = null
        /** The processed versions (corrected, fused): what a box may show; null none here. */
        fun corrections(): ApiCorrectionSstv? = null
        /** A processed picture small (a box's version to choose), JPEG. */
        fun vignetteFichier(f: File): ByteArray? = null
        /** The station's montage, for the report's drawing; null: none here (the phone). */
        fun montage(): Montage? = null
        /** The frequency (MHz) the event was heard on: its campaign's, else its satellite's SSTV transmitter; null unknown. */
        fun frequenceMhz(ev: EvenementSstv.Evenement, campagne: CampagneSstv.Campagne?): Double? = null
        /** SatMe's logo (PNG) for the report's header; null: none. */
        fun logo(): ByteArray? = null
    }

    /**
     * The montage as the report draws it: the [plans], the owner's one
     * ([choisi]), each plan's photos (by element: the cut-out ones the page
     * shows), SatMe's functions now (their sound cards' names; null unknown).
     */
    class Montage(val plans: List<PlansMontage.Plan>, val choisi: String, val photos: (String) -> Map<String, DessinMontage.Photo>,
                  val instantane: FonctionsMontage.Instantane?)

    /**
     * The plan the report draws for [ev]: the one asked ([plan]), else the
     * one that draws its SSTV reception best, else the owner's.
     */
    fun planPour(ev: EvenementSstv.Evenement, src: Source, plan: String): PlansMontage.Plan? {
        val ms = runCatching { src.montage() }.getOrNull() ?: return null
        ms.plans.firstOrNull { it.id == plan }?.let { return it }
        val campagne = campagneDe(ev, src)
        val a = DessinMontage.pourSstv("", fr.f4ioz.satcombo.station.Montage.Chaine(), campagne?.chaine,
            runCatching { src.frequenceMhz(ev, campagne) }.getOrNull(), ms.instantane).activite
        return DessinMontage.meilleurPlan(ms.plans, a) ?: ms.plans.firstOrNull { it.id == ms.choisi } ?: ms.plans.firstOrNull()
    }

    private fun campagneDe(ev: EvenementSstv.Evenement, src: Source) =
        if (ev.genre == EvenementSstv.Genre.CAMPAGNE) runCatching { src.campagne(ev.id.removePrefix("c:")) }.getOrNull() else null

    /**
     * The sheet's boxes as drawn, [parNom] the gallery by name: each the
     * version chosen for it ([ApiCorrectionSstv.pourCases]); without the
     * processed versions here, the pictures as received.
     */
    fun cases(m: Planche.Modele, parNom: Map<String, Pair<File, SstvMeta.SstvShot>>, corrections: ApiCorrectionSstv?): ApiCorrectionSstv.Cases =
        corrections?.let { c -> runCatching { c.pourCases(m, parNom) }.getOrNull() }
            ?: ApiCorrectionSstv.Cases(m.images.mapNotNull { (i, f) -> parNom[f]?.let { i to it } }.toMap(), emptyList())

    /** The events a sheet may be about: the campaigns and activities, the runs of pictures no event holds. */
    fun tous(src: Source): List<EvenementSstv.Evenement> {
        val l = runCatching { src.evenements() }.getOrDefault(emptyList())
        return EvenementSstv.tous(l, src.images(), src::catnumDe)
    }

    /**
     * [m]'s event: the one chosen by hand, else the one its pictures (none:
     * the gallery's latest) fall in — kept on the sheet when it changed, so
     * older sheets find theirs the first time they are opened.
     */
    fun assure(m: Planche.Modele, src: Source, rangement: Planche.Rangement, verrou: Any): Planche.Modele {
        if (m.evenementMain) return m
        val shots = src.images()
        val parNom = shots.associateBy { it.fileName }
        val siennes = m.images.values.mapNotNull { parNom[it] }
        val ev = EvenementSstv.choisit(tous(src), siennes.ifEmpty { shots.filter { s -> shots.maxOfOrNull { it.timeMs }?.let { s.timeMs > it - 7 * 86_400_000L } == true } }, src.maintenant())
            ?: return m
        if (ev == m.evenement) return m
        val n = m.copy(evenement = ev)
        synchronized(verrou) { rangement.enregistre(n) }
        return n
    }

    /** The event's pictures sorted: its groups numbered (the timetable's estimates too). */
    fun prevision(ev: EvenementSstv.Evenement, src: Source, e: ClassementGalerie.Etat): SerieSstv.Prevision =
        SerieSstv.prevision(ev.serie(), e, emptyList(), emptyList(), src.elMin(), src.maintenant())

    private fun evJson(e: EvenementSstv.Evenement, n: Int = -1) = JSONObject().put("id", e.id).put("nom", e.nom)
        .put("genre", EvenementSstv.genreCle(e.genre)).put("sat", e.satNom).put("de", e.debutMs).put("a", e.finMs).put("nb", e.nb)
        .also { if (n >= 0) it.put("images", n) }

    /**
     * The gallery for the sheet's picker: each picture with its mark, its
     * slide (the event's estimates included), the number's origin, its
     * group and whether it is the group's best, in the sheet's event or not.
     */
    fun galerie(m: Planche.Modele?, src: Source, live: (SstvMeta.SstvShot) -> Boolean = { it.source == "live" }): JSONArray {
        val shots = src.images()
        val e = src.classement.etat(src.dossier, shots)
        val ev = m?.evenement
        val estimes = ev?.let { prevision(it, src, e).groupes.associateBy { g -> g.id } }.orEmpty()
        val groupeDe = HashMap<String, ClassementSstv.Groupe>()
        for (g0 in e.groupes) {
            val s = estimes[g0.id]
            val g = if (s != null && g0.origine == ClassementSstv.Origine.AUCUNE && s.origine == ClassementSstv.Origine.ESTIME)
                g0.copy(diapo = s.diapo, total = s.total, origine = s.origine) else g0
            for (x in g.images) groupeDe[x.fileName] = g
        }
        return JSONArray(shots.sortedByDescending { it.timeMs }.map { s ->
            val g = groupeDe[s.fileName]
            val note = g?.notes?.get(s.fileName) ?: e.analyses[s.fileName]?.note ?: -1
            JSONObject().put("f", s.fileName).put("s", s.satName).put("m", s.mode).put("t", s.timeMs).put("c", s.complete).put("d", live(s))
                .put("n", note).put("g", g?.id ?: s.fileName).put("di", g?.diapo ?: 0).put("tot", g?.total ?: 12)
                .put("o", g?.let { ClassementSstv.code(it.origine) } ?: "")
                .put("b", g?.let { ClassementSstv.badge(it.diapo, it.total, it.origine) } ?: "")
                .put("best", g == null || g.meilleure.fileName == s.fileName).put("copies", g?.images?.size ?: 1)
                .put("ev", ev != null && EvenementSstv.dans(ev, s))
        })
    }

    /** The pictures « Fill » takes: the sheet's event's (unless [tous]), one satellite's ([sat]) or all. */
    fun images(m: Planche.Modele?, src: Source, sat: String, tous: Boolean): List<SstvMeta.SstvShot> {
        val l = src.images()
        val ev = m?.evenement
        return when {
            sat.isNotBlank() -> l.filter { it.satName == sat }
            !tous && ev != null -> l.filter { EvenementSstv.dans(ev, it) }
            else -> l
        }
    }

    /**
     * « Fill by slide »: the event's groups (or the whole gallery's with [tous],
     * or one satellite's with [sat], as « Fill » takes them) in slide order;
     * the choices made by hand kept unless [reinitialise].
     */
    fun remplitParDiapo(m: Planche.Modele, src: Source, tous: Boolean, reinitialise: Boolean = false, sat: String = ""): Planche.ParDiapo {
        val e = src.classement.etat(src.dossier, src.images(), attendre = true)
        val ev = m.evenement
        return when {
            sat.isNotBlank() -> groupesDe(e.groupes, sat).let { g -> Planche.remplitParDiapo(m, g, totalDe(g), reinitialise) }
            ev != null && !tous -> Planche.remplitParDiapo(m, prevision(ev, src, e).groupes, ev.nb, reinitialise)
            else -> Planche.remplitParDiapo(m, e.groupes, 12, reinitialise)
        }
    }

    /** One satellite's groups ([sat], as the gallery names it): only its pictures, the others' groups left out. */
    fun groupesDe(groupes: List<ClassementSstv.Groupe>, sat: String): List<ClassementSstv.Groupe> = groupes.mapNotNull { g ->
        val l = g.images.filter { it.satName == sat }
        when { l.isEmpty() -> null; l.size == g.images.size -> g; else -> g.copy(images = l, notes = g.notes.filterKeys { k -> l.any { it.fileName == k } }) }
    }

    /** How many slides the series has: what its numbered groups say, else 12. */
    fun totalDe(groupes: List<ClassementSstv.Groupe>): Int =
        groupes.filter { it.diapo > 0 && it.origine != ClassementSstv.Origine.AUCUNE }.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 12

    /**
     * The event's report: its pictures, passes, the sheet [m] (if any), the
     * chain of [plan] when [avecChaine] — drawn as Station › Montage draws
     * it when [dessin] and the station has a montage, else in boxes.
     */
    fun rapport(ev: EvenementSstv.Evenement, m: Planche.Modele?, src: Source, avecChaine: Boolean, plan: String, dessin: Boolean = true): RapportEvenement.Rapport {
        val now = src.maintenant()
        val e = src.classement.etat(src.dossier, src.images(), attendre = true)
        val p = prevision(ev, src, e)
        val passes = if (ev.catnum > 0) runCatching { src.passes(ev.catnum, ev.debutMs - 3_600_000L, ev.finMs + 3_600_000L) }.getOrDefault(emptyList()) else emptyList()
        val positions: ((List<Long>) -> List<Pair<Double, Double>>?)? = if (ev.catnum > 0) { ts -> src.positions(ev.catnum, ts) } else null
        val campagne = campagneDe(ev, src)
        // The montage's drawing: the plan lit for what the autopilot set up (the rig's sound by default).
        val pl = if (avecChaine && dessin) planPour(ev, src, plan) else null
        val montage = pl?.takeIf { !it.chaine.vide }?.let { p0 ->
            runCatching {
                val ms = src.montage()!!
                DessinMontage.pourSstv(PlansMontage.nomVu(p0), p0.chaine, campagne?.chaine, src.frequenceMhz(ev, campagne), ms.instantane,
                    runCatching { ms.photos(p0.id) }.getOrDefault(emptyMap()))
            }.getOrNull()
        }
        val ch = if (avecChaine) runCatching { src.chaine(pl?.id ?: plan) }.getOrNull() else null
        return RapportEvenement.construit(
            evenement = ev, p = p, passes = passes, station = station(src.station(), m), elMin = src.elMin(), maintenant = now,
            positions = positions,
            geometrie = RapportEvenement.Geometrie(passes.isNotEmpty(), if (ev.catnum > 0) src.epoqueElements(ev.catnum) else null),
            traces = src.traces().filter { campagne == null || it.campagne == campagne.id },
            chaine = if (avecChaine) ch?.second ?: emptyList() else null, nomChaine = ch?.first.orEmpty(),
            pilote = if (avecChaine && campagne != null) RapportEvenement.pilote(campagne) else emptyList(),
            planche = m?.let { runCatching { src.planche(it) }.getOrNull() }, nomPlanche = m?.nom.orEmpty(),
            vignette = { runCatching { src.vignette(it) }.getOrNull() }, analyses = e.analyses,
            montage = montage, logo = runCatching { src.logo() }.getOrNull())
    }

    /** The station the report names: the callsign and locator typed on the sheet (« Textes »), else the station's. */
    fun station(s: RapportEvenement.Station, m: Planche.Modele?): RapportEvenement.Station =
        if (m == null) s else s.copy(indicatif = m.indicatif.trim().ifBlank { s.indicatif }, locator = m.locator.trim().ifBlank { s.locator })

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    /**
     * One of the routes above ([chemin] after `/planche`), or null when it
     * is not one of them. [modele] finds a sheet by its id.
     */
    fun sert(chemin: String, p: (String) -> String, src: Source, rangement: Planche.Rangement, verrou: Any,
             modele: (String) -> Planche.Modele?): ReponseWeb? = when (chemin) {
        "/evenements" -> {
            val m = modele(p("id"))?.let { assure(it, src, rangement, verrou) }
            val shots = src.images()
            val l = tous(src)
            json(JSONObject().put("evenement", m?.evenement?.let { evJson(it) } ?: JSONObject.NULL).put("main", m?.evenementMain == true)
                .put("liste", JSONArray(l.map { e -> evJson(e, shots.count { EvenementSstv.dans(e, it) }) })))
        }
        "/evenement" -> {
            val m = modele(p("id")) ?: return null
            val v = p("ev")
            val n = when (v) {
                "" -> m.copy(evenementMain = false)
                "aucun" -> m.copy(evenement = null, evenementMain = true)
                else -> tous(src).firstOrNull { it.id == v }?.let { m.copy(evenement = it, evenementMain = true) }
            }
            if (n == null) json(JSONObject().put("ok", false).put("raison", "evenement"))
            else {
                synchronized(verrou) { rangement.enregistre(n) }
                val a = assure(n, src, rangement, verrou)
                json(JSONObject().put("ok", true).put("evenement", a.evenement?.let { evJson(it) } ?: JSONObject.NULL))
            }
        }
        "/galerie" -> json(galerie(modele(p("id"))?.let { assure(it, src, rangement, verrou) }, src))
        "/remplit-diapos" -> {
            val m = modele(p("id"))?.let { assure(it, src, rangement, verrou) }
            if (m == null) null else {
                val r = remplitParDiapo(m, src, p("tous") == "1", p("reinit") == "1", p("sat"))
                synchronized(verrou) { rangement.enregistre(r.modele) }
                json(JSONObject().put("ok", true).put("cases", r.modele.cases.size).put("manquantes", JSONArray(r.manquantes))
                    .put("sansNumero", r.sansNumero).put("horsCases", r.horsCases).put("gardes", r.gardes))
            }
        }
        "/relance" -> {
            val n = src.classement.relance(src.dossier, src.images(), p("oublie") == "1")
            src.classement.etat(src.dossier, src.images())
            json(JSONObject().put("ok", true).put("n", n))
        }
        "/analyse" -> {
            val shots = src.images()
            val e = src.classement.etat(src.dossier, shots)
            json(JSONObject().put("restent", e.restent).put("total", shots.size).put("groupes", e.groupes.size)
                .put("lues", e.analyses.values.count { it.lu > 0 }))
        }
        // A box's panel: the versions of its slide to choose from (each its mark and a small picture).
        "/case" -> {
            val m = modele(p("id")) ?: return null
            val i = p("i").toIntOrNull()?.takeIf { it in m.cases.indices } ?: return null
            json(src.corrections()?.pourCase(m, i) ?: JSONObject().put("i", i).put("choix", Planche.version(m, i).code)
                .put("f", m.images[i] ?: JSONObject.NULL).put("versions", JSONArray()).put("copies", JSONArray()).put("sans", true))
        }
        // « Créer la fusion » from a box: its slide's copies fused (the gallery's own action), in the background.
        "/fusionner" -> {
            val m = modele(p("id")) ?: return null
            val f = p("i").toIntOrNull()?.let { m.images[it] }
            val c = src.corrections()
            if (f == null || c == null) json(JSONObject().put("ok", false).put("erreur", "introuvable"))
            else json(c.action("/api/sstv/fusionner") { k -> if (k == "nom") f else "" } ?: JSONObject().put("ok", false))
        }
        // A processed version small (only a file made here: never another one).
        "/version-vignette" -> {
            val f = src.corrections()?.fichier(p("f"))
            val b = f?.let { runCatching { src.vignetteFichier(it) }.getOrNull() }
            if (b == null) ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())
            else ReponseWeb("200 OK", "image/jpeg", b, "Cache-Control: no-store\r\n")
        }
        // What a text and a sheet may be dressed with: the bundled fonts, the effects, the frames, the styled titles.
        "/styles" -> json(JSONObject()
            .put("polices", JSONArray(PlanchePolices.LISTE.map { JSONObject().put("id", it.id).put("nom", it.nom) }))
            .put("styles", JSONArray(PlancheStyle.Style.values().map { JSONObject().put("id", it.code).put("nom", t(it.cle)) }))
            .put("cadres", JSONArray(PlancheStyle.Cadre.values().map { JSONObject().put("id", it.code).put("nom", t(it.cle)) }))
            .put("titres", JSONArray(PlancheTitres.LISTE.map { JSONObject().put("id", it.id).put("nom", t(it.cle)) }))
            .put("lueurs", JSONArray(PlancheStyle.LUEURS)))
        // A bundled font, for the page's @font-face (nothing fetched from outside).
        "/police" -> PlanchePolices.de(p("f"))?.let { PlanchePolices.octets(it) }
            ?.let { ReponseWeb("200 OK", "font/ttf", it, "Cache-Control: max-age=86400\r\n") }
            ?: ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())
        // A styled title laid on the sheet (« aucun »: the plain title back), its words from the sheet's event.
        "/titre-style" -> {
            val m = modele(p("id"))?.let { assure(it, src, rangement, verrou) }
            if (m == null) null else {
                val n = PlancheTitres.applique(m, p("t"), PlancheTitres.infos(m, t("planche_titre_serie_mot")))
                synchronized(verrou) { rangement.enregistre(n) }
                json(JSONObject().put("ok", true).put("textes", n.textes.size))
            }
        }
        // The plans, the one drawing the sheet's event best first; whether the montage can be drawn here.
        "/plans" -> {
            val l = src.plans()
            val ev = modele(p("id"))?.evenement
            val premier = ev?.let { runCatching { planPour(it, src, "") }.getOrNull()?.id }
            json(JSONArray(l.sortedBy { if (it.first == premier) 0 else 1 }.map { (id, nom) ->
                JSONObject().put("id", id).put("nom", nom).put("dessin", runCatching { src.montage() }.getOrNull() != null)
            }))
        }
        "/rapport" -> {
            val m = modele(p("id"))?.let { assure(it, src, rangement, verrou) }
            val ev = m?.evenement ?: tous(src).firstOrNull { it.id == p("ev") }
            if (ev == null) ReponseWeb("404 Not Found", "text/plain; charset=utf-8", "pas d'événement".toByteArray())
            else {
                val r = rapport(ev, m, src, p("chaine") == "1", p("plan"), p("vue") != "simple")
                val pdf = p("format") == "pdf"
                val nom = RapportEvenement.nomFichier(r, if (pdf) "pdf" else "html")
                val corps = if (pdf) runCatching { src.pdf(r) }.getOrNull() else RapportEvenement.html(r).toByteArray(Charsets.UTF_8)
                if (corps == null) ReponseWeb("501 Not Implemented", "text/plain; charset=utf-8", "pas de PDF".toByteArray())
                else ReponseWeb("200 OK", if (pdf) "application/pdf" else "text/html; charset=utf-8", corps,
                    (if (p("dl") == "1") "Content-Disposition: attachment; filename=\"$nom\"\r\n" else "Content-Disposition: inline; filename=\"$nom\"\r\n") +
                        "Cache-Control: no-store\r\n")
            }
        }
        else -> null
    }
}
