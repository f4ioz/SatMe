/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.HoraireDiapos
import fr.f4ioz.satcombo.sstv.SerieSstv
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The SSTV gallery sorted, for the page (SatMe-PC's and the phone's, the
 * same answers): `GET /api/sstv/classement` — the groups of copies of one
 * slide with each picture's mark, the series under way, its timetable and
 * the slides expected on the passes to come; `POST /api/sstv/diapo?nom=&n=&sur=`
 * (a picture's slide, set on its group; n=0 takes it off),
 * `POST /api/sstv/groupe?nom=&vers=` (into a group by its id, « seule »,
 * or "" back to sorting by itself).
 */
object ApiClassementSstv {

    private fun origine(o: ClassementSstv.Origine) = when (o) {
        ClassementSstv.Origine.MAIN -> "main"; ClassementSstv.Origine.LU -> "lu"
        ClassementSstv.Origine.ESTIME -> "estime"; ClassementSstv.Origine.AUCUNE -> ""
    }

    private fun groupe(g: ClassementSstv.Groupe, e: ClassementGalerie.Etat, serie: Boolean) = JSONObject()
        .put("id", g.id).put("diapo", g.diapo).put("total", g.total).put("origine", origine(g.origine))
        .put("sat", g.meilleure.satName).put("serie", serie)
        .put("images", JSONArray(g.images.map { s ->
            val a = e.analyses[s.fileName]
            JSONObject().put("nom", s.fileName).put("t", s.timeMs).put("note", g.notes[s.fileName] ?: a?.note ?: -1)
                .put("etoiles", ((g.notes[s.fileName] ?: a?.note ?: -10) + 10).div(20).coerceIn(0, 5))
                .put("lu", a?.lu ?: 0).put("main", s.diapo).put("groupeMain", s.groupe.isNotEmpty())
                .put("manque", a?.let { Math.round(it.manque * 100) } ?: 0).put("abimees", a?.let { Math.round(it.abimees * 100) } ?: 0)
        }))

    fun json(e: ClassementGalerie.Etat, p: SerieSstv.Prevision?, t: Long): JSONObject {
        val dansSerie = p?.groupes?.flatMap { g -> g.images.map { it.fileName } }?.toHashSet() ?: hashSetOf()
        // The series' groups carry their number (estimated ones too); the others as sorted.
        val parId = p?.groupes?.associateBy { it.id }.orEmpty()
        val o = JSONObject().put("restent", e.restent)
            .put("groupes", JSONArray(e.groupes.map { g ->
                val gs = parId[g.id]
                val n = if (gs != null && gs.origine == ClassementSstv.Origine.ESTIME && g.origine == ClassementSstv.Origine.AUCUNE)
                    g.copy(diapo = gs.diapo, total = gs.total, origine = gs.origine) else g
                groupe(n, e, g.images.any { it.fileName in dansSerie })
            }))
        if (p == null) return o.put("serie", JSONObject.NULL)
        val s = p.serie
        o.put("serie", JSONObject().put("nom", s.nom).put("sat", s.satNom).put("de", s.debutMs).put("a", s.finMs)
            .put("periodeAnnonceeS", s.periodeS).put("nb", s.nb).put("enCours", s.enCours(t)))
        o.put("elMin", p.elMin)
        o.put("recues", JSONArray(p.recues.sorted()))
        // One line per slide: its surest group (by hand, read, estimated), the most copies.
        val parDiapo = p.groupes.filter { it.diapo > 0 }.groupBy { it.diapo }.values.map { l ->
            l.minWith(compareBy<ClassementSstv.Groupe> { it.origine.ordinal }.thenByDescending { it.images.size })
        }.sortedBy { it.diapo }
        o.put("diapos", JSONArray(parDiapo.map { g ->
            JSONObject().put("n", g.diapo).put("origine", origine(g.origine)).put("copies", g.images.size).put("meilleure", g.meilleure.fileName)
                .put("note", g.notes[g.meilleure.fileName] ?: -1)
        }))
        o.put("images", JSONArray(p.images.map { (im, aos) ->
            val g = p.groupes.firstOrNull { gr -> gr.images.any { it.fileName == im.fileName } }
            JSONObject().put("nom", im.fileName).put("t", im.timeMs).put("passage", aos).put("diapo", g?.diapo ?: 0)
                .put("origine", g?.let { origine(it.origine) } ?: "").put("note", g?.notes?.get(im.fileName) ?: -1).put("complete", im.complete)
        }))
        val m = p.modele
        o.put("horaire", m?.let {
            JSONObject().put("periodeS", Math.round(it.periodeS * 100) / 100.0).put("ajustee", it.ajustee)
                .put("sigmaS", Math.round(it.sigmaPeriodeS * 100) / 100.0).put("residuS", Math.round(it.residuS * 10) / 10.0)
                .put("reperes", it.reperes).put("reperesDernier", it.reperesDernier).put("ruptures", JSONArray(it.ruptures))
                .put("origineMs", it.origineMs).put("dernierMs", it.dernierMs)
                .put("maintenant", HoraireDiapos.diapo(it, HoraireDiapos.creneauA(it, t)))
        } ?: JSONObject.NULL)
        val tab = p.tableau
        o.put("manquantes", JSONArray(tab?.manquantes ?: (1..s.nb).filter { it !in p.recues }))
        o.put("passages", JSONArray(tab?.passages.orEmpty().map { ps ->
            JSONObject().put("aos", ps.passage.aosEpochMs).put("los", ps.passage.losEpochMs).put("el", Math.round(ps.passage.maxElevationDeg))
                .put("haut", ps.debutHautMs).put("finHaut", ps.finHautMs).put("incertitudeS", Math.round(ps.incertitudeS))
                .put("diapos", JSONArray(ps.prevues.map { d ->
                    JSONObject().put("n", d.diapo).put("debut", d.debutMs).put("fin", d.finMs).put("entiere", d.entiere).put("manque", d.diapo !in p.recues)
                }))
        }))
        o.put("prochaines", JSONObject().apply {
            tab?.prochaines?.forEach { (n, l) -> put(n.toString(), JSONArray(l.take(4).map { ps ->
                val d = ps.prevues.first { it.diapo == n && it.entiere }
                JSONObject().put("aos", ps.passage.aosEpochMs).put("el", Math.round(ps.passage.maxElevationDeg)).put("debut", d.debutMs)
            })) }
        })
        return o
    }

    /** The page's actions on the sorting; null when [route] is not one. */
    fun action(route: String, p: (String) -> String, dossier: File, galerie: ClassementGalerie, shots: List<SstvMeta.SstvShot>): JSONObject? = when (route) {
        "/api/sstv/diapo" -> {
            val n = p("n").toIntOrNull() ?: -1
            val sur = p("sur").toIntOrNull() ?: 12
            if (galerie.numerote(dossier, shots, p("nom"), n, sur)) JSONObject().put("ok", true)
            else JSONObject().put("ok", false).put("erreur", if (n !in 0..99) "numéro refusé" else "image introuvable")
        }
        "/api/sstv/groupe" -> {
            if (galerie.deplace(dossier, shots, p("nom"), p("vers"))) JSONObject().put("ok", true)
            else JSONObject().put("ok", false).put("erreur", "image ou groupe introuvable")
        }
        else -> null
    }
}
