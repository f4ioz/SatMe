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
            if (v == Planche.Version.AUTO && !m.corrigees) return@mapNotNull i to p
            val g = gs.firstOrNull { gr -> gr.images.any { it.fileName == f } }
            val c = runCatching { corrections.pourCase(d, f, g?.notes?.get(f) ?: -1, g, v, m.corrigees) }.getOrNull() ?: return@mapNotNull i to p
            if (c.repli) replis += i
            i to (if (c.fichier != null && c.fiche != null) c.fichier to p.second.copy(traitement = etiquette(c.fiche)) else p)
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
            "/api/sstv/correction/oublier" -> {
                p("nom").takeIf { it.isNotEmpty() && images().any { s -> s.fileName == it } }?.let { corrections.oublie(d, it) }
                p("groupe").takeIf { it.isNotEmpty() }?.let { corrections.oublieFusion(d, it) }
                JSONObject().put("ok", true)
            }
            else -> null
        }
    }
}
