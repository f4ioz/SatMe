/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.CorrectionsGalerie
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.RetouchesGalerie
import fr.f4ioz.satcombo.sstv.CouleurSstv
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The corrected pictures for the page (SatMe-PC's and the phone's, the same
 * answers), [CorrectionsGalerie] behind:
 *  - `GET /api/sstv/correction?nom=` — a picture: has it a sound, its
 *    corrected version, its group and the group's fused picture, the work
 *    under way;
 *  - `POST /api/sstv/corriger?nom=&methode=` (auto, son, image),
 *    `POST /api/sstv/fusionner?groupe=` (or `?nom=` of one of its pictures),
 *    `POST /api/sstv/correction/oublier?nom=` or `?groupe=` — in the
 *    background, one at a time (decoding a picture again takes a second or
 *    two, a group of six ten);
 *  - the files: `sstv/corrigees/<name>` ([fichier]).
 */
class ApiCorrectionSstv(
    val corrections: CorrectionsGalerie,
    private val dossier: () -> File,
    private val images: () -> List<SstvMeta.SstvShot>,
    private val groupes: () -> List<ClassementSstv.Groupe>,
) {
    /** The finish of the pictures (colour, IA: [RetouchesGalerie]); SatMe-PC sets it, the phone not yet. */
    @Volatile var retouches: RetouchesGalerie? = null

    /** Told when a picture's IA version was made here (the viewer's): the web site sends it again. Set by SatMe-PC. */
    @Volatile var iaFaite: (String) -> Unit = {}

    /** What is being done: the picture's name or the group's id, "" nothing. */
    @Volatile var enCours = ""; private set
    /** The last action's error code ([CorrectionsGalerie.Issue.erreur]) and on what. */
    @Volatile var erreur = ""; private set
    @Volatile var erreurSur = ""; private set

    companion object {
        const val PREFIXE = "sstv/corrigees/"

        /** What a sheet writes on a processed picture. */
        fun etiquette(f: CorrectionsGalerie.Fiche): String =
            if (f.methode == CorrectionsGalerie.Methode.FUSION) tf("sstv_planche_fusion", f.copies.size) else t("sstv_planche_corrigee")

        fun fiche(f: CorrectionsGalerie.Fiche?): Any = if (f == null) JSONObject.NULL else JSONObject()
            .put("fichier", f.fichier).put("url", PREFIXE + f.fichier).put("methode", f.methode.code)
            .put("avant", f.noteAvant).put("apres", f.noteApres).put("quand", f.quandMs)
            .put("copies", JSONArray(f.copies)).put("duSon", f.duSon)
            .put("ppm", f.derivePpm).put("bruit", f.bruitHz).put("gain", Math.round(f.gain * 100) / 100.0)
    }

    private fun groupeDe(nom: String) = groupes().firstOrNull { g -> g.images.any { it.fileName == nom } }

    /** `GET /api/sstv/correction?nom=`. */
    fun etat(nom: String): JSONObject {
        val d = dossier()
        val s = images().firstOrNull { it.fileName == nom }
        val o = JSONObject().put("nom", nom).put("enCours", enCours).put("erreur", erreur).put("erreurSur", erreurSur)
        if (s == null) return o.put("existe", false)
        o.put("existe", true).put("son", corrections.aUnSon(d, s)).put("corrigee", fiche(corrections.corrigee(d, nom)))
        val g = groupeDe(nom)
        o.put("groupe", if (g == null || g.images.size < 2) JSONObject.NULL else JSONObject().put("id", g.id).put("copies", g.images.size)
            .put("avecSon", g.images.count { corrections.aUnSon(d, it) }).put("fusion", fiche(corrections.fusion(d, g.id))))
        return o
    }

    /** What the gallery by slide shows of a group: its fused picture, its pictures corrected. */
    fun pourGroupe(g: ClassementSstv.Groupe): JSONObject {
        val d = dossier()
        return JSONObject().put("fusion", fiche(corrections.fusion(d, g.id)))
            .put("corrigees", JSONArray(g.images.filter { corrections.corrigee(d, it.fileName) != null }.map { it.fileName }))
    }

    /**
     * A picture [p] (its file, what is known of it) as a sheet's Auto box shows
     * it with [Planche.Modele.corrigees]: the best marked of its slide's
     * versions ([CorrectionsGalerie.pourPlanche]), what it is kept in
     * [SstvMeta.SstvShot.traitement] (shown only with [Planche.Modele.marqueTraitees]); else as it is.
     */
    fun pourPlanche(p: Pair<File, SstvMeta.SstvShot>): Pair<File, SstvMeta.SstvShot> {
        val g = groupeDe(p.second.fileName)
        val note = g?.notes?.get(p.second.fileName) ?: -1
        val (f, fiche) = runCatching { corrections.pourPlanche(dossier(), p.second.fileName, note, g) }.getOrNull() ?: return p
        return f to p.second.copy(traitement = etiquette(fiche))
    }

    /** The boxes of a sheet as drawn ([places]), and those whose chosen version is gone, shown as Auto ([replis]). */
    class Cases(val places: Map<Int, Pair<File, SstvMeta.SstvShot>>, val replis: List<Int>)

    /**
     * Sheet [m]'s boxes as drawn, [parNom] the gallery by picture name: each
     * the version chosen for it ([Planche.Modele.versions]), Auto the best
     * marked when the sheet takes them ([Planche.Modele.corrigees]); what it
     * is kept in [SstvMeta.SstvShot.traitement] (shown only with
     * [Planche.Modele.marqueTraitees]).
     */
    fun pourCases(m: Planche.Modele, parNom: Map<String, Pair<File, SstvMeta.SstvShot>>): Cases {
        val gs = runCatching { groupes() }.getOrDefault(emptyList())
        val d = dossier()
        val replis = ArrayList<Int>()
        val places = m.images.mapNotNull { (i, f) ->
            val p = parNom[f] ?: return@mapNotNull null
            val v = Planche.version(m, i)
            if (v == Planche.Version.AUTO && !m.corrigees) return@mapNotNull i to retouchee(f, p)
            val g = gs.firstOrNull { gr -> gr.images.any { it.fileName == f } }
            val c = runCatching { corrections.pourCase(d, f, g?.notes?.get(f) ?: -1, g, v, m.corrigees) }.getOrNull() ?: return@mapNotNull i to retouchee(f, p)
            if (c.repli) replis += i
            i to retouchee(f, if (c.fichier != null && c.fiche != null) c.fichier to p.second.copy(traitement = etiquette(c.fiche)) else p)
        }.toMap()
        return Cases(places, replis.sorted())
    }

    /**
     * Box [i] of sheet [m] for its panel: its picture, the versions of its
     * slide there are (as received, corrected, fused — each its mark and its
     * file), whether a fusion could be made, the slide's copies, the version
     * chosen and the one shown (Auto's own), the work under way.
     */
    fun pourCase(m: Planche.Modele, i: Int): JSONObject {
        val d = dossier()
        val o = JSONObject().put("i", i).put("choix", Planche.version(m, i).code).put("copieMain", m.versions[i]?.copieMain == true)
            .put("auto", m.corrigees).put("enCours", enCours).put("erreur", erreur).put("erreurSur", erreurSur)
        val f = m.images[i] ?: return o.put("f", JSONObject.NULL)
        val g = groupeDe(f)
        val note = g?.notes?.get(f) ?: -1
        o.put("f", f).put("note", note)
        val versions = JSONArray().put(JSONObject().put("v", Planche.Version.RECUE.code).put("note", note).put("f", f))
        corrections.corrigee(d, f)?.let { versions.put(JSONObject().put("v", Planche.Version.CORRIGEE.code).put("note", it.noteApres).put("fichier", it.fichier)) }
        val fusion = g?.takeIf { it.images.size > 1 }?.let { corrections.fusion(d, it.id) }
        fusion?.let { versions.put(JSONObject().put("v", Planche.Version.FUSION.code).put("note", it.noteApres).put("fichier", it.fichier).put("copies", it.copies.size)) }
        o.put("versions", versions).put("fusionPossible", g != null && g.images.size > 1 && fusion == null).put("groupe", g?.id ?: JSONObject.NULL)
        o.put("copies", JSONArray(g?.images.orEmpty().sortedByDescending { g?.notes?.get(it.fileName) ?: -1 }.map { s ->
            JSONObject().put("f", s.fileName).put("note", g?.notes?.get(s.fileName) ?: -1).put("t", s.timeMs)
                .put("corrigee", corrections.corrigee(d, s.fileName) != null) }))
        val c = runCatching { corrections.pourCase(d, f, note, g, Planche.version(m, i), m.corrigees) }.getOrNull()
        val montre = when (c?.fiche?.methode) { null -> Planche.Version.RECUE; CorrectionsGalerie.Methode.FUSION -> Planche.Version.FUSION; else -> Planche.Version.CORRIGEE }
        o.put("montre", montre.code).put("montreFichier", c?.fiche?.fichier ?: f).put("repli", c?.repli == true)
        // What Auto would show: said on its own choice.
        val a = runCatching { corrections.pourCase(d, f, note, g, Planche.Version.AUTO, m.corrigees) }.getOrNull()
        o.put("autoMontre", when (a?.fiche?.methode) { null -> Planche.Version.RECUE; CorrectionsGalerie.Methode.FUSION -> Planche.Version.FUSION; else -> Planche.Version.CORRIGEE }.code)
            .put("autoFichier", a?.fiche?.fichier ?: f).put("autoNote", a?.fiche?.noteApres ?: note)
        return o
    }

    // ------------------------------------------------------------ the finish: colour, IA

    /**
     * Version [p] (its file, what is known) of picture [png] as it goes out
     * (sheets, the web site): finished as the picture's switches say
     * ([RetouchesGalerie]), what was done added to its
     * [SstvMeta.SstvShot.traitement], « IA » marked; else as it is.
     */
    fun retouchee(png: String, p: Pair<File, SstvMeta.SstvShot>): Pair<File, SstvMeta.SstvShot> {
        val r = retouches ?: return p
        val d = dossier()
        val e = runCatching { r.effet(d, png) }.getOrNull()?.takeIf { it.actif } ?: return p
        val f = runCatching { r.finie(d, p.first, e) }.getOrNull() ?: return p
        val dit = listOfNotNull(p.second.traitement.takeIf { it.isNotEmpty() }, if (f.fiche.couleur) t("sstv_planche_couleur") else null,
            if (f.fiche.ia.isNotEmpty()) t("sstv_planche_ia") else null).joinToString(" · ")
        return f.fichier to p.second.copy(traitement = dit, retoucheIa = f.fiche.ia.isNotEmpty())
    }

    /** Which version of an SSTV picture goes to the owner's web site: as received (the default), its colour corrected, its IA's (with its colour). */
    enum class VersionSite(val code: String) { RECUE("recue"), COULEURS("couleurs"), IA("ia");
        companion object { fun de(c: String?) = values().firstOrNull { it.code == c } ?: RECUE } }

    /** What goes to the site: the file, what is known (its [SstvMeta.SstvShot.traitement] said), [retouche] "" (as received), "couleurs" or "ia", the model. */
    class PourSite(val fichier: File, val shot: SstvMeta.SstvShot, val retouche: String = "", val modeleIa: String = "")

    /**
     * Picture [png] (its file as received and what is known, [p]) as it goes
     * to the web site when [version] is chosen: that version when there is
     * one, else as received. The colour's is made now (quick), and only when
     * a step changed something; the IA's only when already made (by hand or
     * by [fr.f4ioz.satcombo.sstv.IaAuto]) — never computed here: the site
     * gets the picture as received at once, and the IA's version when it is
     * made ([iaFaite]).
     */
    fun pourSite(png: String, p: Pair<File, SstvMeta.SstvShot>, version: VersionSite): PourSite {
        val recue = PourSite(p.first, p.second)
        if (version == VersionSite.RECUE) return recue
        val r = retouches ?: return recue
        val d = dossier()
        val e = runCatching { r.effet(d, png) }.getOrNull() ?: return recue
        fun dit(couleur: Boolean, ia: Boolean) = listOfNotNull(p.second.traitement.takeIf { it.isNotEmpty() }, if (couleur) t("sstv_planche_couleur") else null,
            if (ia) t("sstv_planche_ia") else null).joinToString(" · ")
        return when (version) {
            VersionSite.COULEURS -> {
                if (e.options.rien) return recue
                val f = runCatching { r.finie(d, p.first, RetouchesGalerie.Effet(true, e.options, false), avecIa = false) }.getOrNull() ?: return recue
                // Nothing measured clearly enough to be set right: the picture is as received.
                if (f.fiche.plan.vide) return recue
                PourSite(f.fichier, p.second.copy(traitement = dit(true, false)), "couleurs")
            }
            VersionSite.IA -> {
                if (!e.ia || r.ia == null) return recue
                val f = runCatching { r.existante(d, p.first, e) }.getOrNull()?.takeIf { it.fiche.ia.isNotEmpty() } ?: return recue
                PourSite(f.fichier, p.second.copy(traitement = dit(f.fiche.couleur, true), retoucheIa = true), "ia", f.fiche.iaNom)
            }
            VersionSite.RECUE -> recue
        }
    }

    /** The version of picture [png] a viewer asks for: as received, corrected, fused (else as received). */
    private fun base(png: String, version: String): File? {
        val d = dossier()
        val brute = File(d, png).takeIf { File(png).name == png && png.endsWith(".png") && it.isFile } ?: return null
        return when (version) {
            "corr" -> corrections.corrigee(d, png)?.let { File(CorrectionsGalerie.dossier(d), it.fichier) }
            "fus" -> groupeDe(png)?.takeIf { it.images.size > 1 }?.let { corrections.fusion(d, it.id) }?.let { File(CorrectionsGalerie.dossier(d), it.fichier) }
            else -> null
        }?.takeIf { it.isFile } ?: brute
    }

    private fun analyseJson(a: CouleurSstv.Analyse, p: CouleurSstv.Plan): JSONObject {
        fun r(v: Double, k: Int = 1) = Math.round(v * Math.pow(10.0, k.toDouble())) / Math.pow(10.0, k.toDouble())
        return JSONObject().put("lignes", a.lignes)
            .put("dominante", JSONObject().put("cb", r(a.dominanteCb)).put("cr", r(a.dominanteCr)).put("taille", r(a.dominante))
                .put("appui", r(a.appuiNeutre * 100)).put("sure", a.dominanteSure).put("accordHz", a.accordHz?.let { r(it, 0) } ?: JSONObject.NULL)
                .put("corrigee", p.dominante))
            .put("chroma", JSONObject().put("cbX", a.cbX).put("cbY", a.cbY).put("crX", a.crX).put("crY", a.crY).put("correlation", r(a.correlationChroma, 2))
                .put("corrigee", p.chroma))
            .put("niveaux", JSONObject().put("noir", r(a.noir)).put("blanc", r(a.blanc)).put("gain", p.gain).put("corriges", p.niveaux))
            .put("saturation", JSONObject().put("p90", r(a.saturation)).put("facteur", p.saturation))
            .put("bruit", JSONObject().put("y", r(a.bruitY)).put("c", r(a.bruitC)).put("neige", a.bruitDominant))
    }

    /** What the viewer shows of a picture's finish: its switches, the defaults, the analysis of [version], the finished files. */
    fun retouche(png: String, version: String, ia: RetoucheIa? = null): JSONObject {
        val o = JSONObject().put("nom", png).put("version", version)
        val r = retouches ?: return o.put("ok", false).put("erreur", "absent")
        val d = dossier()
        val base = base(png, version) ?: return o.put("ok", false).put("erreur", "introuvable")
        val reg = r.reglage(d, png); val e = r.effet(reg)
        fun b(v: Boolean?) = v ?: JSONObject.NULL
        o.put("ok", true).put("base", base.name)
            .put("reglage", JSONObject().put("couleur", b(reg.couleur)).put("dominante", b(reg.dominante)).put("chroma", b(reg.chroma))
                .put("niveaux", b(reg.niveaux)).put("saturation", b(reg.saturation)).put("ia", b(reg.ia)).put("iaAuto", reg.iaAuto))
            .put("effet", JSONObject().put("couleur", e.couleur).put("dominante", e.options.dominante).put("chroma", e.options.chroma)
                .put("niveaux", e.options.niveaux).put("saturation", e.options.saturation).put("ia", e.ia))
        // Measured with the steps the picture asks (all of them when its colour is off: to show what would be done).
        val opts = if (e.couleur && !e.options.rien) e.options else CouleurSstv.Options(true, true, true, e.options.saturation)
        r.analyse(base, opts)?.let { (res, px) ->
            val noteAv = runCatching { ClassementSstv.analyse(px.px, px.w, px.h).note }.getOrDefault(-1)
            val noteAp = if (res.plan.vide) noteAv else runCatching { ClassementSstv.analyse(res.px, px.w, px.h).note }.getOrDefault(-1)
            o.put("analyse", analyseJson(res.analyse, res.plan).put("noteAvant", noteAv).put("noteApres", noteAp).put("rien", res.plan.vide))
        }
        // The colour's version is quick: made now. The IA's only when made (or being made).
        val couleur = if (e.couleur && !e.options.rien) runCatching { r.finie(d, base, RetouchesGalerie.Effet(true, e.options, false), avecIa = false) }.getOrNull() else null
        o.put("couleur", couleur?.let { PREFIXE + it.fichier.name + "?v=" + it.fiche.quandMs } ?: JSONObject.NULL)
        val iaFaite = if (e.ia && r.ia != null) r.existante(d, base, e) else null
        o.put("ia", iaFaite?.let { JSONObject().put("url", PREFIXE + it.fichier.name + "?v=" + it.fiche.quandMs).put("modele", it.fiche.iaNom).put("ms", it.fiche.iaMs) } ?: JSONObject.NULL)
        o.put("iaPossible", r.ia != null).put("enCours", enCours).put("erreur", erreur).put("erreurSur", erreurSur)
        ia?.let { o.put("iaModele", it.etat()) }
        return o
    }

    /** SatMe-PC's learnt denoiser as the page sees it (installed, its name, its download). */
    fun interface RetoucheIa { fun etat(): JSONObject }

    /** The finish's actions: a switch (`k`, `v` 1, 0 or `defaut`), the IA made in the background. */
    private fun actionRetouche(route: String, p: (String) -> String): JSONObject? {
        val r = retouches ?: return JSONObject().put("ok", false).put("erreur", "absent")
        val d = dossier()
        val png = p("nom")
        if (images().none { it.fileName == png }) return JSONObject().put("ok", false).put("erreur", "introuvable")
        return when (route) {
            "/api/sstv/retouche/reglage" -> {
                val v = when (p("v")) { "1" -> true; "0" -> false; else -> null }
                val a = r.reglage(d, png)
                val n = when (p("k")) {
                    "couleur" -> a.copy(couleur = v); "dominante" -> a.copy(dominante = v); "chroma" -> a.copy(chroma = v)
                    "niveaux" -> a.copy(niveaux = v); "saturation" -> a.copy(saturation = v); "ia" -> a.copy(ia = v)
                    // Back to the defaults: the IA made by itself stays (it is what the defaults gave).
                    "tout" -> RetouchesGalerie.Reglage(iaAuto = a.iaAuto)
                    else -> return JSONObject().put("ok", false).put("erreur", "réglage inconnu")
                }
                if (!r.change(d, png, n)) return JSONObject().put("ok", false).put("erreur", CorrectionsGalerie.ECRITURE)
                // The IA turned on: made now, in the background, on the version shown.
                if (n.iaVoulue && !a.iaVoulue && r.ia != null) lanceIa(png, p("version"))
                JSONObject().put("ok", true)
            }
            "/api/sstv/retouche/ia" -> {
                if (r.ia == null) return JSONObject().put("ok", false).put("erreur", "ia_absente")
                if (!r.reglage(d, png).iaVoulue) return JSONObject().put("ok", false).put("erreur", "ia_coupee")
                if (lanceIa(png, p("version"))) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", "occupe")
            }
            else -> null
        }
    }

    private fun lanceIa(png: String, version: String): Boolean {
        val r = retouches ?: return false
        val d = dossier()
        val base = base(png, version) ?: return false
        return lance(png) {
            val f = r.finie(d, base, r.effet(d, png))
            if (f != null && f.fiche.ia.isNotEmpty()) runCatching { iaFaite(png) }
            CorrectionsGalerie.Issue(if (f == null) null else CorrectionsGalerie.Fiche(png, f.fichier.name, CorrectionsGalerie.Methode.IMAGE, -1, -1, f.fiche.quandMs),
                if (f == null) "ia_echec" else "")
        }
    }

    /** A file of `sstv/corrigees/`, or null. */
    fun fichier(nom: String): File? = corrections.fichier(dossier(), nom)

    @Synchronized private fun lance(sur: String, travail: () -> CorrectionsGalerie.Issue): Boolean {
        if (enCours.isNotEmpty()) return false
        enCours = sur; erreur = ""; erreurSur = ""
        Thread({
            val r = runCatching(travail).getOrElse { CorrectionsGalerie.Issue(null, "erreur : " + (it.message ?: it.javaClass.simpleName)) }
            if (r.fiche == null) { erreur = r.erreur; erreurSur = sur }
            enCours = ""
        }, "satme-sstv-correction").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        return true
    }

    /** The page's actions; null when [route] is not one. */
    fun action(route: String, p: (String) -> String): JSONObject? {
        val d = dossier()
        return when (route) {
            "/api/sstv/corriger" -> {
                val s = images().firstOrNull { it.fileName == p("nom") } ?: return JSONObject().put("ok", false).put("erreur", "introuvable")
                val m = when (p("methode")) { "son" -> CorrectionsGalerie.Methode.SON; "image" -> CorrectionsGalerie.Methode.IMAGE; else -> null }
                if (lance(s.fileName) { corrections.corrige(d, s, m) }) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", "occupe")
            }
            "/api/sstv/fusionner" -> {
                val g = (p("groupe").takeIf { it.isNotEmpty() }?.let { id -> groupes().firstOrNull { it.id == id } } ?: groupeDe(p("nom")))
                    ?: return JSONObject().put("ok", false).put("erreur", "introuvable")
                if (g.images.size < 2) return JSONObject().put("ok", false).put("erreur", CorrectionsGalerie.UNE_COPIE)
                if (lance(g.id) { corrections.fusionne(d, g) }) JSONObject().put("ok", true).put("groupe", g.id) else JSONObject().put("ok", false).put("erreur", "occupe")
            }
            "/api/sstv/retouche/reglage", "/api/sstv/retouche/ia" -> actionRetouche(route, p)
            "/api/sstv/correction/oublier" -> {
                p("nom").takeIf { it.isNotEmpty() && images().any { s -> s.fileName == it } }?.let { corrections.oublie(d, it) }
                p("groupe").takeIf { it.isNotEmpty() }?.let { corrections.oublieFusion(d, it) }
                JSONObject().put("ok", true)
            }
            else -> null
        }
    }
}
