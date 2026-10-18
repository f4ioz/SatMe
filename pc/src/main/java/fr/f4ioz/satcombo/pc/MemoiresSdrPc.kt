/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.MemoiresSdr
import fr.f4ioz.satcombo.domain.PlanBandes
import fr.f4ioz.satcombo.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The receiver's memories (`memoires-sdr.json`) and the band plans
 * (`plan-bandes.json` in the data folder replaces the bands it names).
 * Display and receive only: nothing here tunes a transmitter.
 */
class MemoiresSdrPc(dossier: File) {

    private val fichier = File(dossier, "memoires-sdr.json")
    private val fichierPlan = File(dossier, "plan-bandes.json")

    @Volatile private var liste: List<MemoiresSdr.Memoire> =
        runCatching { MemoiresSdr.nettoie(MemoiresSdr.lit(fichier.readText())) }.getOrDefault(emptyList())

    private var planLu = -1L
    private var plan: List<PlanBandes.Segment> = emptyList()
    private var planErreur = ""

    private fun json(o: Any, entetes: String = "") = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8), entetes)
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))

    private fun etat() = JSONObject().put("ok", true).put("memoires", JSONArray(liste.map { MemoiresSdr.json(it) }))
        .put("etiquettes", JSONArray(MemoiresSdr.etiquettes(liste)))

    private fun garde(l: List<MemoiresSdr.Memoire>) = synchronized(this) {
        liste = l
        fichier.parentFile?.mkdirs()
        val tmp = File(fichier.parentFile, fichier.name + ".tmp")
        tmp.writeText(MemoiresSdr.ecrit(l).toString(1))
        if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
    }

    /** The plan: the defaults, the file's bands over them (read again when it changes). */
    @Synchronized fun plan(): List<PlanBandes.Segment> {
        val t = if (fichierPlan.isFile) fichierPlan.lastModified() else 0L
        if (t != planLu || plan.isEmpty()) {
            planLu = t
            val defaut = PlanBandes.defaut { I18n.t("qo100_seg_$it") }
            val s = if (t == 0L) emptyList() else runCatching { PlanBandes.lit(fichierPlan.readText()) }.getOrDefault(emptyList())
            planErreur = if (t != 0L && s.isEmpty()) "plan-bandes.json illisible ou vide : le plan par défaut reste" else ""
            plan = PlanBandes.avecSurcharge(defaut, s)
        }
        return plan
    }

    /** `/api/memoires-sdr…` (the list, a memory posed or removed, export, import) and `/api/plan-bandes`. */
    fun sert(chemin: String, methode: String, p: (String) -> String, corps: (Long) -> ByteArray?): ReponseWeb = runCatching {
        when (chemin) {
            "/api/plan-bandes" -> {
                val l = if (p("defaut") == "1") PlanBandes.defaut { I18n.t("qo100_seg_$it") } else plan()
                json(PlanBandes.json(l).put("ok", true).put("fichier", fichierPlan.isFile).put("erreur", planErreur),
                    if (p("defaut") == "1") "Content-Disposition: attachment; filename=\"plan-bandes.json\"\r\n" else "")
            }
            "/api/memoires-sdr/export" -> json(MemoiresSdr.ecrit(liste).toString(1),
                "Content-Disposition: attachment; filename=\"satme-memoires-sdr.json\"\r\n")
            "/api/memoires-sdr/import" -> {
                if (methode != "POST") return@runCatching refus("POST")
                val texte = corps(MAX)?.toString(Charsets.UTF_8) ?: return@runCatching refus("fichier trop gros")
                val lues = MemoiresSdr.lit(texte)
                if (lues.isEmpty()) return@runCatching refus("aucune mémoire lisible dans ce fichier")
                val avant = liste.size
                garde(MemoiresSdr.fusionne(liste, lues))
                json(etat().put("ajoutees", liste.size - avant))
            }
            "/api/memoires-sdr" -> {
                if (methode != "POST") return@runCatching json(etat())
                val retire = p("retire")
                if (retire.isNotEmpty()) { garde(MemoiresSdr.retire(liste, retire)); return@runCatching json(etat()) }
                val texte = corps(64L * 1024)?.toString(Charsets.UTF_8) ?: return@runCatching refus("mémoire trop grosse")
                val m = MemoiresSdr.lit("[$texte]").firstOrNull()?.let { MemoiresSdr.valide(it) }
                    ?: return@runCatching refus("mémoire illisible (nom, fréquence)")
                garde(MemoiresSdr.pose(liste, m))
                json(etat())
            }
            else -> refus("route inconnue")
        }
    }.getOrElse { refus(it.message ?: "erreur") }

    companion object { const val MAX = 2L * 1024 * 1024 }
}
