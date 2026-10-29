/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page's side of « Assistant et domotique » (`/api/commandes…`): the
 * voice assistant, Home Assistant and the site's remote commands, their
 * settings and the commands given lately. A POST comes from this page's
 * own origin only (checked by [ServeurPc]); a password is taken, never
 * given back.
 */
class CommandesWebPc(
    private val stockage: Stockage,
    val commandes: CommandesPc,
    val assistant: AssistantVocalPc?,
    val domotique: DomotiquePc?,
    val site: CommandesSitePc?,
) {
    private fun rep(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8), "Cache-Control: no-store\r\n")

    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val post = methode == "POST"
        return rep(when (chemin) {
            "/api/commandes" -> json(p("depuis").toLongOrNull() ?: 0L)
            // A sentence typed on the page: the same door as the voice (never a transmission).
            "/api/commandes/texte" -> if (!post) refus() else {
                val t = p("texte").trim().take(255)
                if (t.isEmpty()) JSONObject().put("ok", false).put("erreur", "rien à comprendre")
                else (assistant?.repondA(t, source = "page") ?: commandes.phrase(t, "page")).json().put("ok", true)
            }
            "/api/commandes/ecoute" -> if (!post) refus() else assistant?.bouton() ?: absent()
            "/api/commandes/assistant" -> if (!post) refus() else assistant?.let { a ->
                if (!a.actif) JSONObject().put("ok", false).put("erreur", AssistantVocalPc.MODULE_COUPE) else {
                    p("micro").let { if (p("microDonne") == "1") a.micro = it }
                    p("modele").takeIf { it.isNotEmpty() }?.let { a.modele = it }
                    p("langue").takeIf { it.isNotEmpty() }?.let { a.langue = it }
                    p("parle").takeIf { it.isNotEmpty() }?.let { a.parle = it == "1" }
                    p("sensibilite").toIntOrNull()?.let { a.sensibilite = it }
                    p("ecoute").takeIf { it.isNotEmpty() }?.let { a.ecoute = it == "1" }
                    JSONObject().put("ok", true)
                }
            } ?: absent()
            "/api/commandes/assistant/installe" -> if (!post) refus() else assistant?.installe() ?: absent()
            "/api/commandes/assistant/micros" -> JSONObject().put("ok", true).put("micros", assistant?.micros() ?: JSONArray())
            "/api/commandes/domotique" -> if (!post) refus() else domotique?.regle(p) ?: absent()
            "/api/commandes/site" -> if (!post) refus() else site?.let { s ->
                if (!ModulesStation.actif(stockage, ModulesStation.SITE)) JSONObject().put("ok", false).put("erreur", "Module « Publication web » désactivé")
                else s.regle(p)
            } ?: absent()
            else -> JSONObject().put("ok", false).put("erreur", "inconnu")
        })
    }

    private fun refus() = JSONObject().put("ok", false).put("erreur", "POST attendu")
    private fun absent() = JSONObject().put("ok", false).put("absent", true)

    fun json(depuis: Long): JSONObject = JSONObject().put("ok", true)
        .put("assistant", assistant?.json() ?: JSONObject().put("module", false))
        .put("domotique", domotique?.json() ?: JSONObject().put("module", false))
        .put("site", site?.json()?.put("module", ModulesStation.actif(stockage, ModulesStation.SITE)) ?: JSONObject().put("module", false))
        .put("seq", commandes.dernierSeq)
        .put("traces", JSONArray(commandes.traces(depuis).map { it.json() }))
        .put("intentions", JSONArray(Intention.values().filter { it != Intention.INCONNU }.map { JSONObject().put("id", it.id).put("action", it.action) }))
}
