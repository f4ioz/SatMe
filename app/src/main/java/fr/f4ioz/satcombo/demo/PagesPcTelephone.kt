/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.MainViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * SatMe-PC's page served by the phone, when there is no PC: the same page
 * (PagePc), its `api/…` routes answered from what the phone knows — tab
 * by tab, the others hidden ([ONGLETS], `api/capacites`). Behind the
 * control desk's door: its code, its key (sent as a cookie by the page).
 */
object PagesPcTelephone {

    /** The ViewModel, set when it starts. */
    @Volatile var vm: MainViewModel? = null

    /** The tabs the phone serves so far; the page hides the others. */
    val ONGLETS = listOf("station", "carnet", "journal", "planche")

    /**
     * The page: SatMe-PC's, the key from the desk put in a cookie for its
     * routes, and back to the desk's door when there is none.
     */
    fun page(): String = PagePc.HTML.replace("<script>", "<script>window.SATME_TELEPHONE=true;(function(){" +
        "var k='';try{k=localStorage.getItem('satme-cle')||''}catch(e){}" +
        "var b=location.pathname.replace(/\\/pc\\/?$/,'/');" +
        "if(k)document.cookie='satme_cle='+k+';path='+b+';SameSite=Strict';" +
        "fetch('api/etat').then(function(r){if(r.status===403)location.href=b+'?vers=pc'})})();</script>\n<script>")

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private val NON = ReponseWeb("404 Not Found", "application/json", "{\"ok\":false}".toByteArray())
    private val OK get() = JSONObject().put("ok", true)

    /** One route of the page ([route] after the desk's address, `/api/…`), [p] its parameters. */
    fun sert(route: String, methode: String, p: (String) -> String): ReponseWeb {
        val vm = vm ?: return json(JSONObject().put("ok", false).put("erreur", "pas prêt"))
        val u = vm.ui.value
        return runCatching {
            when (route) {
                "/api/capacites" -> json(JSONObject().put("onglets", JSONArray(ONGLETS)).put("serveur", "telephone"))
                "/api/etat" -> json(etat(vm))
                "/api/passages" -> json(JSONObject().put("calcul", u.loading).put("liste", JSONArray(u.passes.take(300).map {
                    ApiStation.passage(it, fav = it.catalogNumber in u.favorites)
                })))
                "/api/satellites" -> json(JSONArray(u.satellites.map { JSONObject().put("nom", it.name).put("catnum", it.catalogNumber) }))
                // The phone drives its own rig and rotator: no port from here.
                "/api/ports" -> json(JSONArray())
                "/api/son" -> json(JSONObject().put("present", false))
                "/api/carnet" -> json(carnet(vm))
                "/api/carnet/propositions" -> {
                    val saisie = p("c")
                    val m = u.express.memoire
                    val l = fr.f4ioz.satcombo.domain.Indicatifs.suggestions(saisie, m, System.currentTimeMillis(), u.selected?.name.orEmpty(), max = 4)
                    json(JSONObject().put("etat", fr.f4ioz.satcombo.domain.Indicatifs.etat(saisie, m).name).put("propositions", JSONArray(l.map { k ->
                        JSONObject().put("c", k.indicatif).put("nm", k.nom).put("n", k.contacts).put("sat", k.dernierSat)
                            .put("l", fr.f4ioz.satcombo.domain.Indicatifs.locatorPropose(k, k.indicatif))
                    })))
                }
                else -> if (methode == "POST") json(action(vm, route, p)) else NON
            }
        }.getOrElse { json(JSONObject().put("ok", false).put("erreur", it.message ?: it.javaClass.simpleName)) }
    }

    /** What the page asks the phone to do: the gestures of the tabs served, nothing else. */
    private fun action(vm: MainViewModel, route: String, p: (String) -> String): JSONObject {
        val u = vm.ui.value
        return when (route) {
            "/api/suivre" -> {
                val t = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() } ?: return JSONObject().put("ok", false)
                vm.select(t); OK
            }
            "/api/favori" -> {
                val n = p("catnum").toIntOrNull() ?: return JSONObject().put("ok", false)
                // A star asked for as it already is: nothing to change.
                if ((n in u.favorites) != (p("on") == "1")) vm.toggleFavorite(n)
                OK
            }
            "/api/emetteur" -> { vm.selectTransmitter(p("i").toIntOrNull() ?: 0); OK }
            "/api/carnet/ajoute" -> {
                val sat = u.selected ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite suivi")
                if (p("c").isBlank()) return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite suivi")
                val avant = u.log.map { it.timeMs }.toSet()
                vm.ajouteContactDirect(sat, p("c"), p("l"), p("rs"), p("rr"), "COMMANDE")
                val nouveau = vm.ui.value.log.firstOrNull { it.timeMs !in avant }
                if (nouveau != null && p("n").isNotBlank()) vm.updateLogEntry(nouveau.timeMs, nouveau.copy(note = p("n")))
                OK.put("t", nouveau?.timeMs ?: 0L)
            }
            "/api/carnet/modifie" -> {
                val t = p("t").toLongOrNull() ?: return JSONObject().put("ok", false)
                val a = u.log.firstOrNull { it.timeMs == t } ?: return JSONObject().put("ok", false)
                fun v(k: String, d: String) = p(k).takeIf { it.isNotEmpty() } ?: d
                vm.updateLogEntry(t, a.copy(callsign = v("c", a.callsign).uppercase(), theirLocator = v("l", a.theirLocator).uppercase(),
                    rstSent = v("rs", a.rstSent), rstRcvd = v("rr", a.rstRcvd), note = v("n", a.note), mode = v("md", a.mode),
                    nom = v("nm", a.nom), qth = v("qth", a.qth), locatorOrigine = v("lo", a.locatorOrigine)))
                OK
            }
            "/api/carnet/supprime" -> {
                val l = p("t").split(',').mapNotNull { it.trim().toLongOrNull() }
                OK.put("n", l.count { PontCommande.supprime?.invoke(it) == true })
            }
            "/api/carnet/retiens" -> {
                val t = p("t").toLongOrNull() ?: return JSONObject().put("ok", false)
                PontCommande.retiens?.invoke(t, p("on") == "1"); OK
            }
            else -> JSONObject().put("ok", false).put("erreur", "pas encore sur le téléphone")
        }
    }

    /** The station's state, in SatMe-PC's words ([ApiStation] for the shared pieces). */
    private fun etat(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        val maintenant = System.currentTimeMillis()
        val sat = u.selected
        val o = JSONObject().put("maintenant", maintenant).put("locator", vm.myLocator()).put("indicatif", vm.indicatif())
            .put("observateur", u.observer?.let { JSONObject().put("lat", it.latDeg).put("lon", it.lonDeg) } ?: JSONObject.NULL)
            .put("catalogue", "").put("nbSatellites", u.satellites.size).put("calcul", u.loading)
        val siens = if (sat == null) emptyList() else u.passes.filter { it.catalogNumber == sat.catalogNumber && it.losEpochMs > maintenant }
        sat?.let { s -> o.put("suivi", JSONObject().put("nom", s.name).put("catnum", s.catalogNumber).put("fav", s.catalogNumber in u.favorites).put("amsat", JSONObject.NULL)) }
        u.livePosition?.let { o.put("position", ApiStation.position(it)) }
        val passage = siens.firstOrNull()
        passage?.let { o.put("passage", ApiStation.passage(it, fav = it.catalogNumber in u.favorites)) }
        o.put("arc", JSONArray(passage?.track.orEmpty().map { JSONArray().put(it.first).put(it.second) }))
        o.put("emetteurs", ApiStation.emetteurs(u.transmitters))
        o.put("emetteur", u.selectedTxIndex)
        o.put("reglageSat", JSONObject().put("calib", u.calibShiftHz).put("tx", u.txShiftHz)
            .put("refCalib", u.catUi.refCalibShiftHz ?: JSONObject.NULL).put("refTx", u.catUi.refTxShiftHz ?: JSONObject.NULL))
        o.put("passagesSuivi", JSONArray(siens.take(8).map { ApiStation.passage(it, fav = it.catalogNumber in u.favorites) }))
        o.put("agendaSuivi", false).put("agenda", JSONArray()).put("repos", JSONObject.NULL)
        val (dl, ul) = ApiStation.frequencesDuCiel(u.transmitters.getOrNull(u.selectedTxIndex), u.livePosition?.rangeRateKmS ?: 0.0, null, u.calibShiftHz, u.txShiftHz)
        o.put("ciel", JSONObject().put("dl", dl ?: JSONObject.NULL).put("ul", ul ?: JSONObject.NULL))
        o.put("convertisseurs", JSONObject().put("rxOl", 0).put("rxInv", false).put("txOl", 0).put("txInv", false))
        o.put("cat", ApiStation.cat(vm.etatBoucleCat(), u.rigModel, -1, u.catConnected, false, u.catStatus, u.dopplerHold))
        o.put("rotor", ApiStation.rotor(u.rotor.mat))
        return o
    }

    /** The log, in SatMe-PC's words, with each contact's upload state. */
    private fun carnet(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        val maintenant = System.currentTimeMillis()
        val l = u.log.sortedByDescending { it.timeMs }
        return JSONObject().put("carres", l.mapNotNull { it.theirLocator.takeIf { x -> x.length >= 4 }?.substring(0, 4)?.uppercase() }.distinct().size)
            .put("nonDeposes", l.count { it.envoyeMs == 0L }).put("total", l.size).put("auto", u.carnet.autoEtat)
            .put("contacts", JSONArray(l.take(6000).map { e ->
                val etat = vm.etatEnvoi(e, maintenant)
                JSONObject().put("ea", etat.name.lowercase())
                    .also { o -> if (etat == fr.f4ioz.satcombo.domain.EnvoiAuto.Etat.ATTENTE) o.put("reste", vm.resteEnvoiS(e, maintenant)) }
                    .also { o -> if (e.retenu) o.put("pause", true).put("refus", vm.refusEnvoi(e.timeMs)) }
                    .put("t", e.timeMs).put("c", e.callsign).put("l", e.theirLocator).put("sat", e.satName)
                    .put("md", e.mode).put("rs", e.rstSent).put("rr", e.rstRcvd).put("n", e.note).put("nm", e.nom)
                    .put("qth", e.qth).put("az", e.azimuthDeg).put("el", e.elevationDeg).put("dl", e.downlinkMhz)
                    .put("ul", e.uplinkMhz).put("envoye", e.envoyeMs > 0).put("ml", e.myLocator)
            }))
    }
}
