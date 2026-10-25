/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.OptionsJournal
import fr.f4ioz.satcombo.domain.OptionsJournal.Element
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.CorrectionsGalerie
import fr.f4ioz.satcombo.sstv.ElementsJournal
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.ToileSvg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The journal's SSTV pictures and the report's panels, the same on SatMe-PC
 * and on the phone (both serve the journal's page, both make its videos):
 *  - which version of each picture of a pass its pictures and videos show
 *    (as received, corrected, fused, the best marked), « Créer la fusion »
 *    from there (the gallery's own action, [ApiCorrectionSstv]);
 *  - the report's panels ([ElementsJournal]) for the pass: its event found
 *    as the sheet finds one ([ApiPlancheEvenement]), its report built as the
 *    sheet's, this pass picked in it (else made from the journal's track);
 *  - all of it kept with the pass ([OptionsJournal]).
 *
 * Routes under `/journal`: `/sstv?id=` (where the pass stands),
 * `POST /sstv/choix?id=&quoi=` (`defaut`, `image&f=`, `marque`, `plan`,
 * `element&k=&incruste=&carte=&cadre=`; `v` the value), `POST /sstv/fusionner?id=&f=`,
 * `/sstv/element.svg?id=&k=` (a panel's preview), `/sstv/version?f=` (a
 * processed picture, small or whole).
 *
 * Receive only: nothing is sent, nothing received is changed — a choice is
 * a line in the pass's file, a fusion a new picture beside the gallery.
 */
class ApiJournalSstv(private val p: Plateforme) {

    /** What the platform gives. */
    interface Plateforme {
        /** The sheet's source (events, the gallery sorted, corrections, the montage); null: not ready. */
        fun source(): ApiPlancheEvenement.Source?
        /** The sheets kept (one may be about the pass's event). */
        fun planches(): List<Planche.Modele> = emptyList()
        fun passage(id: String): JournalPassage.Entree?
        /** The pass kept again, with its choices. */
        fun enregistre(e: JournalPassage.Entree)
        /** The pass's SSTV pictures as the journal links them (those set aside left out). */
        fun images(e: JournalPassage.Entree): List<Pair<File, SstvMeta.SstvShot>>
        fun maintenant(): Long = System.currentTimeMillis()
    }

    private fun src() = runCatching { p.source() }.getOrNull()
    private fun corrections(): ApiCorrectionSstv? = src()?.let { s -> runCatching { s.corrections() }.getOrNull() }

    private fun groupes(): List<ClassementSstv.Groupe> = src()?.let { s ->
        runCatching { s.classement.etat(s.dossier, s.images()).groupes }.getOrNull()
    }.orEmpty()

    // ------------------------------------------------------------ the versions

    /** What a picture of the pass shows: the file, its fiche (null: as received), whether the version chosen is missing ([repli], Auto shown). */
    class Montre(val fichier: File, val fiche: CorrectionsGalerie.Fiche?, val repli: Boolean = false) {
        /** « corrigée », « fusion de 3 copies »; "" as received. */
        val etiquette: String get() = fiche?.let { ApiCorrectionSstv.etiquette(it) } ?: ""
    }

    /**
     * What picture [png] (file [f]) shows when [v] is chosen for it: [VersionsJournal.choisit]
     * with this station's corrections and sorting.
     */
    fun montre(f: File, png: String, v: Planche.Version, gs: List<ClassementSstv.Groupe> = groupes()): Montre {
        val c = corrections()?.corrections ?: return Montre(f, null)
        val dossier = src()?.dossier ?: return Montre(f, null)
        val g = gs.firstOrNull { gr -> gr.images.any { it.fileName == png } }
        return VersionsJournal.choisit(c, dossier, f, png, g?.notes?.get(png) ?: -1, g, v)
    }

    /** Each picture of [e] (its file as received; [images] those the screen shows) and what it shows with the pass's choices. */
    fun montrees(e: JournalPassage.Entree, images: List<Pair<File, SstvMeta.SstvShot>> = p.images(e)): Map<String, Montre> {
        if (images.isEmpty()) return emptyMap()
        val gs = groupes()
        return images.associate { (f, s) -> f.absolutePath to montre(f, s.fileName, e.options.versionDe(s.fileName), gs) }
    }

    /**
     * The pass's marks with each SSTV picture as chosen ([OptionsJournal]):
     * the file of the version shown in its place — the replay, the full
     * frames and the summary draw that one. With [Planche]'s own default
     * (as received) nothing changes.
     */
    fun marques(e: JournalPassage.Entree, marques: List<JournalPassage.Marque>, m: Map<String, Montre> = montrees(e)): List<JournalPassage.Marque> =
        marques.map { k -> k.fichier?.let { m[it] }?.let { k.copy(fichier = it.fichier.absolutePath) } ?: k }

    /** The words on a processed picture (by the file shown), when the pass asks it said ([OptionsJournal.marqueTraitees]). */
    fun etiquettes(e: JournalPassage.Entree, m: Map<String, Montre> = montrees(e)): Map<String, String> =
        if (!e.options.marqueTraitees) emptyMap()
        else m.values.filter { it.fiche != null }.associate { it.fichier.absolutePath to it.etiquette }

    /** The file the page shows for picture [nom] of pass [id] (the version chosen); null: not one of its pictures. */
    fun image(id: String, nom: String): File? {
        val e = p.passage(id) ?: return null
        val (f, s) = p.images(e).firstOrNull { it.first.name == nom } ?: return null
        return montre(f, s.fileName, e.options.versionDe(s.fileName)).fichier
    }

    // ------------------------------------------------------------ the report's panels

    /** The event of pass [e]: the one holding its pictures, else the one under way then on its satellite. */
    fun evenement(e: JournalPassage.Entree): EvenementSstv.Evenement? {
        val s = src() ?: return null
        val tous = runCatching { ApiPlancheEvenement.tous(s) }.getOrDefault(emptyList())
        val shots = p.images(e).map { it.second }
        if (shots.isNotEmpty()) return EvenementSstv.choisit(tous, shots, e.debutMs)
        return tous.filter { (it.catnum == e.catnum || JournalPassage.memeSatellite(it.satNom, e.satName)) &&
            e.debutMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }.minByOrNull { it.finMs - it.debutMs }
    }

    private class Cache(val cle: String, val quand: Long, val d: ElementsJournal.Donnees, val ev: EvenementSstv.Evenement?)
    @Volatile private var cache: Cache? = null

    /**
     * What the panels of [e] draw: the event's report (built as the sheet's,
     * its montage's plan [OptionsJournal.plan] or the one drawing the
     * reception best), this pass in it — else the journal's own track —, the
     * event's sheet, SatMe's logo. Kept two minutes (the page's previews ask
     * for it panel by panel).
     */
    fun donnees(e: JournalPassage.Entree): ElementsJournal.Donnees = donneesEtEvenement(e).first

    fun donneesEtEvenement(e: JournalPassage.Entree): Pair<ElementsJournal.Donnees, EvenementSstv.Evenement?> {
        val cle = e.id + "|" + e.options.plan + "|" + p.images(e).joinToString(",") { it.first.name }
        val maintenant = p.maintenant()
        cache?.let { if (it.cle == cle && maintenant - it.quand < 120_000L) return it.d to it.ev }
        val s = src()
        val ev = runCatching { evenement(e) }.getOrNull()
        val planche = ev?.let { x -> runCatching { p.planches() }.getOrDefault(emptyList()).filter { it.evenement?.id == x.id }.maxByOrNull { it.images.size } }
        val r = if (s != null && ev != null) runCatching { ApiPlancheEvenement.rapport(ev, planche, s, true, e.options.plan, true) }
            .onFailure { System.err.println("journal : rapport de ${e.id} : $it") }.getOrNull() else null
        val passage = r?.passages?.filter { it.losMs >= e.debutMs - 300_000L && it.aosMs <= e.finMs + 300_000L }
            ?.maxByOrNull { minOf(it.losMs, e.finMs) - maxOf(it.aosMs, e.debutMs) }
            ?: run {
                val gs = groupes()
                ElementsJournal.passageDuJournal(e, p.images(e).map { (_, sh) ->
                    val g = gs.firstOrNull { gr -> gr.images.any { it.fileName == sh.fileName } }
                    sh to ((g?.notes?.get(sh.fileName) ?: -1) to (g?.diapo ?: 0))
                })
            }
        val d = ElementsJournal.Donnees(
            rapport = r, passage = passage, elMin = s?.let { runCatching { it.elMin() }.getOrNull() } ?: 0.0,
            montage = r?.montage, chaine = r?.chaine?.takeIf { r.montage == null && it.isNotEmpty() }
                ?: if (r == null && s != null) runCatching { s.chaine(e.options.plan) }.getOrNull()?.second else null,
            nomChaine = r?.nomChaine.orEmpty(),
            planche = r?.planche, nomPlanche = r?.nomPlanche.orEmpty(),
            logo = s?.let { runCatching { it.logo() }.getOrNull() })
        cache = Cache(cle, maintenant, d, ev)
        return d to ev
    }

    /** Forgotten: a choice changed what the panels draw (the plan). */
    fun oublie() { cache = null }

    // ------------------------------------------------------------ the page

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private val NON = ReponseWeb("404 Not Found", "application/json; charset=utf-8", "{\"ok\":false}".toByteArray())

    /** Where pass [e] stands: its pictures and their versions, its choices, the panels it may show. */
    fun etat(e: JournalPassage.Entree): JSONObject {
        val o = e.options
        val c = corrections()
        val dossier = src()?.dossier
        val gs = groupes()
        val images = JSONArray()
        for ((f, s) in p.images(e)) {
            val g = gs.firstOrNull { gr -> gr.images.any { it.fileName == s.fileName } }
            val note = g?.notes?.get(s.fileName) ?: -1
            val versions = JSONArray().put(JSONObject().put("v", Planche.Version.RECUE.code).put("note", note))
            val corr = if (c != null && dossier != null) runCatching { c.corrections.corrigee(dossier, s.fileName) }.getOrNull() else null
            corr?.let { versions.put(JSONObject().put("v", Planche.Version.CORRIGEE.code).put("note", it.noteApres).put("fichier", it.fichier)) }
            val fusion = if (c != null && dossier != null && g != null && g.images.size > 1) runCatching { c.corrections.fusion(dossier, g.id) }.getOrNull() else null
            fusion?.let { versions.put(JSONObject().put("v", Planche.Version.FUSION.code).put("note", it.noteApres).put("fichier", it.fichier).put("copies", it.copies.size)) }
            val m = montre(f, s.fileName, o.versionDe(s.fileName), gs)
            images.put(JSONObject().put("f", s.fileName).put("t", s.timeMs).put("mode", s.mode)
                .put("choix", o.versions[s.fileName]?.code ?: "").put("versions", versions)
                .put("fusionPossible", c != null && g != null && g.images.size > 1 && fusion == null).put("copies", g?.images?.size ?: 1)
                .put("montre", when (m.fiche?.methode) { null -> Planche.Version.RECUE; CorrectionsGalerie.Methode.FUSION -> Planche.Version.FUSION; else -> Planche.Version.CORRIGEE }.code)
                .put("montreNote", m.fiche?.noteApres ?: note).put("montreFichier", m.fiche?.fichier ?: "").put("repli", m.repli)
                .put("etiquette", m.etiquette))
        }
        val (d, ev) = donneesEtEvenement(e)
        val mesure = ToileSvg(1f, 1f)
        val elements = JSONArray(Element.values().map { k ->
            val r = o.reglage(k)
            JSONObject().put("k", k.code).put("nom", ElementsJournal.nom(k)).put("dispo", ElementsJournal.disponible(k, d))
                .put("incruste", r.incruste).put("carte", r.carte).put("cadre", r.cadre?.texte() ?: "").put("defaut", k.defaut.texte())
                .put("ratio", ElementsJournal.ratio(k, d, mesure).toDouble()).put("min", k.min.toDouble())
        })
        val s = src()
        val plans = s?.let { runCatching { it.plans() }.getOrNull() }.orEmpty()
        val premier = ev?.let { x -> s?.let { runCatching { ApiPlancheEvenement.planPour(x, it, "") }.getOrNull()?.id } }
        return JSONObject().put("ok", true).put("defaut", o.version.code).put("marque", o.marqueTraitees).put("plan", o.plan)
            .put("evenement", ev?.let { JSONObject().put("id", it.id).put("nom", it.nom).put("de", it.debutMs).put("a", it.finMs).put("sat", it.satNom) } ?: JSONObject.NULL)
            .put("plans", JSONArray(plans.sortedBy { if (it.first == premier) 0 else 1 }.map { (id, nom) -> JSONObject().put("id", id).put("nom", nom) }))
            .put("dessin", s?.let { runCatching { it.montage() }.getOrNull() } != null)
            .put("corrections", c != null).put("enCours", c?.enCours ?: "").put("erreur", c?.erreur ?: "")
            .put("images", images).put("elements", elements)
    }

    /** [o] changed by one of the page's choices ([quoi], [v] the value; [q] reads the others); null: not one. */
    fun change(o: OptionsJournal, quoi: String, v: String, q: (String) -> String): OptionsJournal? = when (quoi) {
        "defaut" -> Planche.Version.de(v)?.let { o.copy(version = it) }
        // "" back to the pass's own.
        "image" -> q("f").takeIf { it.isNotBlank() && '\t' !in it && '\n' !in it }?.let { f ->
            if (v.isBlank()) o.copy(versions = o.versions - f) else Planche.Version.de(v)?.let { o.copy(versions = o.versions + (f to it)) }
        }
        "marque" -> o.copy(marqueTraitees = v == "1")
        "plan" -> o.copy(plan = v.take(80).filter { it >= ' ' && it != '\t' })
        "element" -> Element.de(q("k"))?.let { k ->
            val r = o.reglage(k)
            fun b(n: String, d: Boolean) = when (q(n)) { "1" -> true; "0" -> false; else -> d }
            val cadre = when (val c = q("cadre")) { "" -> r.cadre; "auto" -> null; else -> CadreSstv.lit(c, k.min) ?: r.cadre }
            o.copy(elements = o.elements + (k to OptionsJournal.Reglage(b("incruste", r.incruste), b("carte", r.carte), cadre)))
        }
        else -> null
    }

    /** One of the routes above ([route] after `/journal`), or null when it is not one of them. */
    fun sert(route: String, q: (String) -> String, methode: String = "GET"): ReponseWeb? = when (route) {
        "/sstv" -> p.passage(q("id"))?.let { json(etat(it)) } ?: NON
        "/sstv/choix" -> {
            if (methode != "POST") ReponseWeb("400 Bad Request", "application/json", "{\"ok\":false}".toByteArray())
            else {
                val e = p.passage(q("id"))
                val n = e?.let { change(it.options, q("quoi"), q("v"), q) }
                if (e == null || n == null) json(JSONObject().put("ok", false))
                else {
                    val e2 = e.copy(options = n)
                    p.enregistre(e2)
                    if (q("quoi") == "plan") oublie()
                    json(etat(e2))
                }
            }
        }
        // « Créer la fusion » for a picture of the pass: its slide's copies fused, in the background.
        "/sstv/fusionner" -> {
            val e = p.passage(q("id"))
            val c = corrections()
            val f = q("f").takeIf { nom -> e != null && p.images(e).any { it.first.name == nom } }
            if (methode != "POST" || c == null || f == null) json(JSONObject().put("ok", false).put("erreur", "introuvable"))
            else json(c.action("/api/sstv/fusionner") { k -> if (k == "nom") f else "" } ?: JSONObject().put("ok", false))
        }
        "/sstv/element.svg" -> {
            val e = p.passage(q("id")); val k = Element.de(q("k"))
            val svg = if (e != null && k != null) runCatching { ElementsJournal.svg(k, donnees(e)) }.getOrNull() else null
            if (svg == null) ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())
            else ReponseWeb("200 OK", "image/svg+xml; charset=utf-8", svg.toByteArray(Charsets.UTF_8), "Cache-Control: no-store\r\n")
        }
        // A processed picture made here (never another file).
        "/sstv/version" -> corrections()?.fichier(q("f"))?.let { ReponseWeb("200 OK", "image/png", fichier = it, entetes = "Cache-Control: no-store\r\n") }
            ?: ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())
        else -> null
    }
}

/**
 * Which file a picture of the journal shows ([OptionsJournal]'s version):
 * the gallery's corrected pictures ([CorrectionsGalerie.pourCase], the
 * sheet's own choice), Auto taking the best marked of the slide's versions;
 * a version chosen that is not there (deleted, never made) shows Auto, said
 * so ([ApiJournalSstv.Montre.repli]).
 */
object VersionsJournal {
    fun choisit(c: CorrectionsGalerie, galerie: File, f: File, png: String, note: Int, g: ClassementSstv.Groupe?, v: Planche.Version): ApiJournalSstv.Montre {
        val r = runCatching { c.pourCase(galerie, png, note, g, v, auto = true) }.getOrNull() ?: return ApiJournalSstv.Montre(f, null)
        return ApiJournalSstv.Montre(r.fichier ?: f, r.fiche, r.repli)
    }
}
