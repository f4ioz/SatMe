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

    /** The tabs the phone serves so far; the page hides the others. QO-100 only when its extension is on. */
    val ONGLETS = listOf("station", "carnet", "agenda", "carres", "frise", "sked", "qo100", "son", "sstv", "aprs", "ft8", "journal", "planche", "reglages")

    private fun onglets(vm: MainViewModel) =
        ONGLETS.filter { it != "qo100" || vm.hasExtension(fr.f4ioz.satcombo.data.Extensions.QO100) }

    /**
     * The page: SatMe-PC's, the key from the desk put in a cookie for its
     * routes, and back to the desk's door when there is none.
     */
    fun page(): String = nomSatMe(PagePc.HTML).replace("<script>", "<script>window.SATME_TELEPHONE=true;(function(){" +
        "var k='';try{k=localStorage.getItem('satme-cle')||''}catch(e){}" +
        "var b=location.pathname.replace(/\\/pc\\/?$/,'/');" +
        "if(k)document.cookie='satme_cle='+k+';path='+b+';SameSite=Strict';" +
        "fetch('api/etat').then(function(r){if(r.status===403)location.href=b+'?vers=pc'})})();</script>\n<script>")

    /** Served by the phone, the pages are SatMe's, not SatMe-PC's: their name said so. */
    private fun nomSatMe(h: String) = h.replace("<h1>SatMe-<span class=\"cyan\">PC</span></h1>", "<h1>SatMe</h1>")
        .replace("<h1>SatMe-<span class=\"c\">PC</span></h1>", "<h1>SatMe</h1>")
        .replace("<title>SatMe — station PC</title>", "<title>SatMe — station</title>")

    /** The pass journal, under the same menu as the other pages (as SatMe-PC shows it). */
    fun journal(): String = PageJournal.html()
        .replace("<script>", "<script>window.SATME_TELEPHONE=true;</script>\n<style>${PagePc.STYLE_BARRE}</style>\n<script>")
        .replace("<div id=\"page\" class=\"cache\">", "<div id=\"page\" class=\"cache\">" + nomSatMe(PagePc.barre("journal")) + "<script>${PagePc.SCRIPT_BARRE}</script>")

    /** The SSTV sheet, the same. */
    fun planche(): String = PagePlanche.html()
        .replace("<script>", "<script>window.SATME_TELEPHONE=true;</script>\n<style>${PagePc.STYLE_BARRE}#pupitre,#journal{display:none}</style>\n<script>")
        .replace("<div id=\"editeur\" class=\"cache\">", "<div id=\"editeur\" class=\"cache\">" + nomSatMe(PagePc.barre("planche")) + "<script>${PagePc.SCRIPT_BARRE}</script>")

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private val NON = ReponseWeb("404 Not Found", "application/json", "{\"ok\":false}".toByteArray())
    private val OK get() = JSONObject().put("ok", true)

    /** One route of the page ([route] after the desk's address, `/api/…`), [p] its parameters. */
    fun sert(route: String, methode: String, p: (String) -> String): ReponseWeb {
        val vm = vm ?: return json(JSONObject().put("ok", false).put("erreur", "pas prêt"))
        val u = vm.ui.value
        return runCatching {
            // A gesture always goes to the gestures, even where a reading has the same address (api/reglages).
            if (methode == "POST") return json(action(vm, route, p))
            when (route) {
                "/api/capacites" -> json(JSONObject().put("onglets", JSONArray(onglets(vm))).put("serveur", "telephone"))
                "/api/etat" -> json(etat(vm))
                // Every satellite's passes over 24 h, as SatMe-PC lists them (the phone's own lists hold the followed one and the favourites).
                "/api/passages" -> {
                    val (l, calcul) = passagesTous(vm)
                    json(JSONObject().put("calcul", calcul).put("liste", JSONArray(l.take(300).map {
                        ApiStation.passage(it, fav = it.catalogNumber in u.favorites, agenda = vm.agendaForPass(it) != null)
                    })))
                }
                "/api/trace" -> {
                    val sat = u.selected
                    val pts = if (sat == null) emptyList() else runCatching { vm.predicteurPourPage().groundTrack(sat, System.currentTimeMillis(), 160) }.getOrDefault(emptyList())
                    // The track starts an eighth of an orbit ago: the point for now is the 20th of 160.
                    json(JSONObject().put("catnum", sat?.catalogNumber ?: 0).put("ici", 20)
                        .put("points", JSONArray(pts.map { (la, lo) -> JSONArray(listOf(Math.round(la * 100) / 100.0, Math.round(lo * 100) / 100.0)) })))
                }
                // QRZ, as the phone looks it up (its credentials, its session); the card shown, as on the phone.
                "/api/qrz" -> json(ApiStation.qrz(kotlinx.coroutines.runBlocking { vm.ficheQrz(p("c")) }, u.observer, true))
                "/api/carnet/passages" -> {
                    val sat = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() }
                    val obs = u.observer
                    json(ApiCalculs.passagesFinisJson(if (sat == null || obs == null) emptyList()
                        else ApiCalculs.passagesFinis(vm.predicteurPourPage(), sat, obs, System.currentTimeMillis(), 72, u.minElevDeg.toDouble())))
                }
                // ---- APRS and FT8: what the phone heard (the engine's words, as SatMe-PC's)
                "/api/aprs" -> {
                    val a = fr.f4ioz.satcombo.aprs.AprsHub.etat.value
                    json(ApiRadio.aprs(a.paquets, a.ecoute, a.nouveaux, u.observer))
                }
                "/api/ft8" -> {
                    val f = fr.f4ioz.satcombo.ft8.Ft8Hub.state.value
                    val faits = u.log.map { it.callsign.uppercase().substringBefore('/') }.toHashSet()
                    json(ApiRadio.ft8(f.enMarche, f.mode, f.avancement, f.niveau, f.tranches, f.enMarche, f.basseHz, f.hauteHz,
                        f.cascade, f.spectre, f.entendus.map { e ->
                            val (de, a, loc) = ApiRadio.decoupe(e.texte)
                            ApiRadio.Ligne(tranche(e.heure), e.texte, de, a, loc, e.frequenceHz, e.rapportDb, e.decalageS, e.mode)
                        }, u.callsign.uppercase(), u.observer, faits))
                }
                // ---- Settings: the phone's own, as SatMe-PC's page shows them
                "/api/reglages" -> {
                    val c = u.carnet
                    json(JSONObject().put("c", u.callsign).put("l", u.manualLocator).put("qrzUser", c.qrzUser)
                        .put("qrzMdp", c.qrzMdp.isNotEmpty()).put("carnetUrl", c.url).put("carnetCle", c.cle.isNotEmpty())
                        .put("carnetProfil", c.profil).put("minEl", u.minElevDeg).put("fiche", true)
                        .put("carnetAuto", c.auto).put("carnetDelai", c.autoDelaiS)
                        .put("delais", JSONArray(fr.f4ioz.satcombo.domain.EnvoiAuto.DELAIS_S))
                        .put("amsatAuto", c.amsatAuto).put("amsatEtat", c.amsatEtat))
                }
                // CelesTrak is the phone's to reach directly: no server rule here.
                "/api/sources" -> json(ApiStation.sources(vm.serveurGp(), vm.serveurGpSeul(), u.enabledSources,
                    if (u.loading) "chargement…" else "", u.satellites.size, celestrakParServeur = false))
                "/api/sources/essai" -> json(ApiStation.essaiServeurGp(p("serveur").ifBlank { vm.serveurGp() }))
                "/api/carnet/profils" -> {
                    val c0 = u.carnet
                    if (c0.url.isBlank() || c0.cle.isBlank()) return json(JSONObject().put("ok", false).put("erreur", "renseigne l'adresse et la clé du carnet en ligne"))
                    // Read again, as SatMe-PC does: a few seconds at most.
                    vm.relevProfils()
                    val fin = System.currentTimeMillis() + 10_000L
                    Thread.sleep(300)
                    while (System.currentTimeMillis() < fin && vm.ui.value.carnet.profilsEtat == fr.f4ioz.satcombo.i18n.t("profils_releve")) Thread.sleep(200)
                    val c = vm.ui.value.carnet
                    if (c.profilsListe.isEmpty()) return json(JSONObject().put("ok", false).put("erreur", c.profilsEtat.ifBlank { "le carnet en ligne ne donne aucun profil (adresse ou clé ?)" }))
                    val ici = vm.emplacementIci()
                    val pourIci = c.profils[ici] ?: c.profil.ifBlank { null }
                    json(ApiStation.profilsWavelog(c.profilsListe, ici, pourIci,
                        vm.lieuxDuCarnet().entries.sortedBy { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it.key) }
                            .map { (lieu, n) -> Triple(lieu, n, c.profils[lieu] ?: c.profil.ifBlank { null }) },
                        { id, lieu -> id != null && c.profils[lieu] == id }, c.profil, u.callsign))
                }
                "/api/carnet/station" -> json(ApiStation.historiqueJson(ApiStation.historique(u.log, p("c")), u.observer, true))
                // ---- Timeline, Sked, Squares: the engine's computations, from the phone's satellites, place and log
                "/api/frise" -> {
                    val obs = u.observer ?: return json(ApiCalculs.friseJson(System.currentTimeMillis(), emptyList()))
                    val debut = System.currentTimeMillis() / 60_000L * 60_000L
                    val sats = ApiCalculs.satellitesFrise(u.satellites, u.favorites, u.selected, u.agenda, debut)
                    json(ApiCalculs.friseJson(debut, ApiCalculs.frise(vm.predicteurPourPage(), obs, sats, debut, (p("h").toIntOrNull() ?: 12).coerceIn(2, 48))))
                }
                "/api/sked" -> {
                    val sat = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() }
                    val obs = u.observer
                    json(if (sat == null || obs == null) ApiCalculs.skedJson(emptyList())
                        else ApiCalculs.skedJson(ApiCalculs.sked(vm.predicteurPourPage(), sat, obs, p("loc"), u.minElevDeg.toDouble(),
                            p("minEl").toDoubleOrNull() ?: 0.0, System.currentTimeMillis())))
                }
                "/api/sked/trace" -> {
                    val sat = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() }
                    val obs = u.observer
                    json(ApiCalculs.traceSkedJson(if (sat == null || obs == null) null
                        else ApiCalculs.traceSked(vm.predicteurPourPage(), sat, obs, p("loc"), p("de").toLongOrNull() ?: 0L, p("a").toLongOrNull() ?: 0L)))
                }
                "/api/sked/video" -> json(JSONObject().put("ok", false).put("erreur", "la vidéo d'un sked se fait sur le téléphone, dans son écran Sked"))
                "/api/carres" -> json(ApiCalculs.carres(u.log, vm.myLocator(), u.observer))
                "/api/satellites" -> json(JSONArray(u.satellites.map { JSONObject().put("nom", it.name).put("catnum", it.catalogNumber) }))
                // The USB adapters plugged into the phone, listed only (none opened: RTS/DTR untouched).
                "/api/ports" -> json(JSONArray(vm.listeUsb().mapIndexed { i, d ->
                    JSONObject().put("index", i).put("libelle", d.label + (if (d.hasPermission) "" else " — autoriser sur le téléphone")).put("vendor", 0)
                }))
                "/api/son" -> json(son(vm))
                "/api/sstv/encours.png" -> {
                    val b = fr.f4ioz.satcombo.sstv.SstvHub.state.value.preview ?: return NON
                    val o = java.io.ByteArrayOutputStream()
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o)
                    ReponseWeb("200 OK", "image/png", o.toByteArray())
                }
                // ---- QO-100: the phone's QO-100 screen, its state in SatMe-PC's words
                "/api/qo100" -> {
                    if (p("plan") == "1" && !vm.ouvreQo100PourPage()) return json(JSONObject().put("ok", false).put("erreur", "QO-100 n'est pas activé sur le téléphone (Réglages → Extensions)"))
                    json(qo100(vm, p("plan") == "1"))
                }
                "/api/qo100/panorama" -> {
                    val r = vm.panoramaPourPage()
                    val pan = r?.first?.takeIf { it.isNotEmpty() }
                    if (pan != null && pan !== dernierPanorama) { dernierPanorama = pan; trames++ }
                    json(ApiQo100.panorama(r != null, pan, r?.second, p("n").toIntOrNull() ?: 512, trames,
                        if (pan != null && r.second != null) fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, r.second!!.first, r.second!!.second) else null))
                }
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
            // The satellite's two shifts: nudged, kept as its reference, or back to it.
            "/api/sat/reglage" -> {
                if (u.selected == null) return JSONObject().put("ok", false).put("erreur", "aucun satellite suivi")
                if (p("retour") == "1") vm.rappelleDecalages()
                p("calib").toLongOrNull()?.let { vm.setCalibShift(vm.ui.value.calibShiftHz + it) }
                p("tx").toLongOrNull()?.let { vm.setTxShift(vm.ui.value.txShiftHz + it) }
                if (p("memorise") == "1") vm.memoriseDecalages()
                OK
            }
            // Afterwards: the satellite and the time given (a past pass), its az/el then (the engine's, as on SatMe-PC).
            "/api/carnet/apres" -> {
                val t = p("t").toLongOrNull() ?: return JSONObject().put("ok", false)
                if (!ApiCalculs.heureApresValable(t, System.currentTimeMillis())) return JSONObject().put("ok", false).put("erreur", "heure hors du dernier mois")
                val sat = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() }
                    ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite")
                val e = ApiCalculs.contactApres(vm.predicteurPourPage(), sat, u.observer, t, p("c"), p("l"), p("rs"), p("rr"), p("n"), p("md"), vm.myLocator())
                if (!vm.ajouteAuCarnet(e)) return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite")
                OK.put("t", t)
            }
            "/api/carnet/ajoute" -> {
                if (p("t").isNotEmpty()) return action(vm, "/api/carnet/apres", p)
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
            // ---- the rig: the phone's own CAT, as its settings would set it
            "/api/cat/connecter" -> {
                val m = p("modele")
                when {
                    m.endsWith("SIM") -> return JSONObject().put("ok", false).put("erreur", "le poste simulé est sur SatMe-PC ; sur le téléphone, le banc d'essai (Réglages → Radio (CAT))")
                    m == "PAIRE" -> return JSONObject().put("ok", false).put("erreur", "une paire de postes se règle sur le téléphone (Réglages → Radio (CAT))")
                }
                if (m.isNotBlank() && m != u.rigModel) vm.setRigModel(m)
                p("port").toIntOrNull()?.takeIf { it >= 0 }?.let { vm.setCivUsbIndex(it) }
                p("baud").toIntOrNull()?.takeIf { it > 0 }?.let { vm.setCivBaud(it) }
                vm.connectCat(); OK
            }
            "/api/cat/deconnecter" -> { vm.disconnectCat(); OK }
            "/api/maintien" -> { if (u.dopplerHold != (p("on") == "1")) vm.toggleDopplerHold(); OK }
            // ---- the mast: the same StationRotor as SatMe-PC's, the same orders
            "/api/rotor/connecter" -> {
                val r = vm.rotorPourPage()
                r.setRotorSim(p("sim") == "1")
                r.setRotorLink(if (p("lien") == "ROTCTLD") "ROTCTLD" else "GS232")
                p("hote").takeIf { it.isNotBlank() }?.let { r.setRotorHost(it.trim()) }
                p("tcp").toIntOrNull()?.let { r.setRotorPort(it) }
                p("port").toIntOrNull()?.let { r.setRotorUsbIndex(it) }
                p("baud").toIntOrNull()?.let { r.setRotorBaud(it) }
                // A port chosen on the page: never searched among the others.
                r.setRotorAutoPort(false)
                r.connectRotor(); OK
            }
            "/api/rotor/deconnecter" -> { vm.rotorPourPage().disconnectRotor(); OK }
            "/api/rotor/suivre" -> { vm.rotorPourPage().setRotorEnabled(p("on") == "1"); OK }
            "/api/rotor/parquer" -> { vm.rotorPourPage().rotorParkNow(); OK }
            "/api/rotor/stop" -> { vm.rotorPourPage().rotorStopNow(); OK }
            "/api/rotor/aller" -> {
                val r = vm.rotorPourPage()
                p("az").toIntOrNull()?.let { r.setRotorManualAz(it) }
                p("el").toIntOrNull()?.let { r.setRotorManualEl(it) }
                r.rotorGotoManual(); OK
            }
            "/api/rotor/reglages" -> {
                val r = vm.rotorPourPage()
                fun n(k: String) = p(k).toIntOrNull()
                n("maxAz")?.let { r.setRotorMaxAz(it) }; n("maxEl")?.let { r.setRotorMaxEl(it) }
                p("butee").takeIf { it == "NORTH" || it == "SOUTH" }?.let { r.setRotorAzStop(it) }
                p("depuisButee").takeIf { it.isNotEmpty() }?.let { r.setRotorAzFromStop(it == "1") }
                p("flip").takeIf { it.isNotEmpty() }?.let { r.setRotorFlip(it == "1") }
                p("azSeul").takeIf { it.isNotEmpty() }?.let { r.setRotorAzOnly(it == "1") }
                n("parkAz")?.let { r.setRotorParkAz(it) }; n("parkEl")?.let { r.setRotorParkEl(it) }
                n("minEl")?.let { r.setRotorMinEl(it) }; n("ecart")?.let { r.setRotorDeadband(it) }; n("avantAos")?.let { r.setRotorPreAos(it) }
                OK
            }
            // ---- the agenda: the phone's own (the same AgendaStore as SatMe-PC's)
            "/api/agenda/passage" -> {
                val n = p("catnum").toIntOrNull(); val aos = p("aos").toLongOrNull()
                val pass = u.passes.firstOrNull { it.catalogNumber == n && it.aosEpochMs == aos }
                    ?: return JSONObject().put("ok", false).put("erreur", "passage inconnu")
                val mis = ApiCalculs.basculePassage(vm.stockageAgenda(), u.agenda, pass, System.currentTimeMillis())
                vm.refreshAgenda(); OK.put("ajoute", mis)
            }
            "/api/agenda/sked" -> {
                val sat = u.satellites.firstOrNull { it.catalogNumber == p("catnum").toIntOrNull() } ?: return JSONObject().put("ok", false)
                ApiCalculs.skedAgenda(vm.stockageAgenda(), sat, p("loc"), p("de").toLongOrNull() ?: 0L, p("a").toLongOrNull() ?: 0L, System.currentTimeMillis())
                vm.refreshAgenda(); OK
            }
            "/api/agenda/retire" -> {
                p("id").toLongOrNull()?.let { fr.f4ioz.satcombo.data.AgendaStore.remove(vm.stockageAgenda(), it) }
                vm.refreshAgenda(); OK
            }
            "/api/agenda/active" -> {
                val a = u.agenda.firstOrNull { it.id == p("id").toLongOrNull() } ?: return JSONObject().put("ok", false)
                fr.f4ioz.satcombo.data.AgendaStore.put(vm.stockageAgenda(), a.copy(enabled = !a.enabled))
                vm.refreshAgenda(); OK
            }
            "/api/agenda/suivi" -> JSONObject().put("ok", false).put("erreur", "sur le téléphone, ce sont les notifications du rendez-vous qui préviennent")
            // ---- FT8: the phone's decoder, started and stopped from the page
            "/api/ft8" -> {
                val f = fr.f4ioz.satcombo.ft8.Ft8Hub
                val ctx = vm.getApplication<android.app.Application>()
                when {
                    p("vide") == "1" -> f.vide()
                    p("on") == "0" -> f.arrete()
                    p("on") == "1" -> {
                        // The phone's microphone (or its audio input): Android's rules, as for a recording.
                        if (f.state.value.source != "SDR") pourquoiPasDeMicro(vm, "MIC")?.let { return JSONObject().put("ok", false).put("erreur", it) }
                        f.demarre(ctx, p("mode").takeIf { it == "FT8" || it == "FT4" } ?: f.state.value.mode)
                    }
                    // The mode alone: taken at once when it runs, at the next start otherwise.
                    p("mode") == "FT8" || p("mode") == "FT4" -> if (f.state.value.enMarche && f.state.value.mode != p("mode")) f.demarre(ctx, p("mode"))
                }
                OK
            }
            // ---- Settings
            "/api/reglages" -> {
                if (p("c").isNotEmpty()) vm.setCallsign(p("c").trim().uppercase())
                p("l").trim().takeIf { it.isNotEmpty() }?.let { l ->
                    if (fr.f4ioz.satcombo.location.Maidenhead.centre(l) == null) return JSONObject().put("ok", false).put("erreur", "locator non reconnu")
                    vm.setManualLocator(l.uppercase())
                }
                p("minEl").toIntOrNull()?.let { vm.setMinElev(it.coerceIn(0, 45)) }
                if (p("qrzUser").isNotEmpty()) vm.setQrzUser(p("qrzUser").trim())
                p("qrzMdp").takeIf { it.isNotEmpty() }?.let { vm.setQrzMdp(it) }
                if (p("carnetUrl").isNotEmpty()) vm.setCarnetUrl(p("carnetUrl").trim())
                p("carnetCle").takeIf { it.isNotEmpty() }?.let { vm.setCarnetCle(it.trim()) }
                if (p("carnetProfil").isNotEmpty()) vm.setCarnetProfil(p("carnetProfil").trim())
                p("carnetDelai").toIntOrNull()?.let { vm.setCarnetAutoDelai(it) }
                if (p("carnetAuto").isNotEmpty()) vm.setCarnetAuto(p("carnetAuto") == "1")
                if (p("amsatAuto").isNotEmpty()) vm.setAmsatAuto(p("amsatAuto") == "1")
                OK
            }
            "/api/sources" -> {
                p("serveur").takeIf { it.isNotEmpty() }?.let { vm.setServeurGp(it.trim()) }
                if (p("seul").isNotEmpty()) vm.setServeurGpSeul(p("seul") == "1")
                p("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().takeIf { it.isNotEmpty() }?.let { vm.setSources(it) }
                OK
            }
            "/api/carnet/profil" -> { vm.setCarnetProfil(p("id").trim()); OK }
            "/api/carnet/profils/cree" -> JSONObject().put("ok", false).put("erreur", "les profils se créent depuis SatMe-PC, ou dans Wavelog")
            "/api/convertisseurs" -> {
                // MHz on the page, Hz here; an empty field leaves it as it is.
                fun hz(k: String) = p(k).trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                hz("rxOl")?.let { vm.setConvRxOl(it); vm.setConvRxActif(it > 0) }
                if (p("rxInv").isNotEmpty()) vm.setConvRxInverseur(p("rxInv") == "1")
                hz("txOl")?.let { vm.setConvTxOl(it); vm.setConvTxActif(it > 0) }
                if (p("txInv").isNotEmpty()) vm.setConvTxInverseur(p("txInv") == "1")
                OK
            }
            "/api/amsat/rapport" -> {
                // Heard or not, on amsat.org's public page: the satellite followed, now (the phone's own report).
                val sat = u.selected ?: return JSONObject().put("ok", false).put("erreur", "aucun satellite suivi")
                if (u.callsign.isBlank()) return JSONObject().put("ok", false).put("erreur", "renseigne ton indicatif (Réglages)")
                vm.submitAmsatStatus(sat.name, p("entendu") == "1", sat.catalogNumber)
                val fin = System.currentTimeMillis() + 20_000L
                Thread.sleep(200)
                while (System.currentTimeMillis() < fin && vm.ui.value.amsatSubmitState == "busy") Thread.sleep(200)
                val ok = vm.ui.value.amsatSubmitState == "ok"
                JSONObject().put("ok", ok).put("nom", sat.name).put("erreur", if (ok) "" else "amsat.org n'a pas pris le rapport")
            }
            // ---- QO-100: the gestures of the phone's QO-100 screen
            "/api/qo100/frequence" -> { p("hz").toLongOrNull()?.let { vm.setQo100Descente(it) }; OK }
            "/api/qo100/pas" -> { p("hz").toLongOrNull()?.let { vm.qo100Pas(it) }; OK }
            "/api/qo100/balise" -> { vm.qo100AllerBalise(); OK }
            "/api/qo100/centre" -> { vm.qo100Centre(); OK }
            "/api/qo100/transpondeur" -> { vm.setQo100Transpondeur(p("cle")); OK }
            "/api/qo100/balayage" -> { vm.setQo100SansBride(p("on") == "1"); if (p("on") != "1") vm.setQo100Descente(u.qo100.descenteHz); OK }
            "/api/qo100/poste" -> { vm.setQo100AuPoste(p("on") == "1"); OK }
            "/api/qo100/cle" -> { vm.setQo100ALaCle(p("on") == "1"); OK }
            "/api/qo100/memoire" -> { vm.qo100PoseMemoire(p("nom")); OK }
            "/api/qo100/oublie" -> { p("hz").toLongOrNull()?.let { vm.qo100RetireMemoire(it) }; OK }
            "/api/qo100/chaine" -> { vm.setQo100Chaine(p("nom")); OK }
            "/api/qo100/nouvelle" -> { vm.qo100AjouteChaine(p("nom")); OK }
            "/api/qo100/ol" -> { p("hz").toLongOrNull()?.let { vm.qo100PoseOl(p("sens") == "descente", it) }; OK }
            "/api/qo100/mesure" -> {
                val (bon, message) = vm.qo100Mesure(p("sens") == "descente", ApiQo100.mhz(p("ciel")) ?: 0L, ApiQo100.mhz(p("poste")) ?: 0L)
                JSONObject().put("ok", bon).put("message", message)
            }
            "/api/qo100/balise-entendue" -> {
                val hz = ApiQo100.baliseEntendue(p("mhz")) ?: return JSONObject().put("ok", false).put("message", ApiQo100.BALISE_LOIN)
                vm.setQo100Calage(u.qo100.calageHz + hz - fr.f4ioz.satcombo.domain.Qo100.BALISE_MEDIANE_HZ); OK
            }
            "/api/qo100/caler-mesure" -> {
                // No measurement: nothing done — certainly not a calibration back to zero.
                val r = vm.panoramaPourPage()
                val m = r?.first?.takeIf { it.isNotEmpty() }?.let { pan -> r.second?.let { fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, it.first, it.second) } }
                    ?: u.qo100.balise ?: return JSONObject().put("ok", false).put("erreur", "La balise n'est pas vue par la clé.")
                vm.setQo100Calage(u.qo100.calageHz + m.ecartArrondiHz); OK
            }
            "/api/qo100/calage-zero" -> { vm.setQo100Calage(0L); OK }
            // ---- Sound: the phone's recorder (the source chosen, the recording started or stopped)
            "/api/son/carte" -> {
                val nom = p("nom")
                when {
                    nom.isBlank() -> { if (u.recording) vm.stopRecording(); OK }
                    SOURCES.none { it.first == nom } -> JSONObject().put("ok", false).put("erreur", "source inconnue")
                    else -> { vm.setRecorderSource(nom); OK.put("message", "le téléphone écoute en enregistrant : « Enregistrer »") }
                }
            }
            "/api/son/enregistrer" -> {
                if (p("on") != "1") { vm.stopRecording(); return OK }
                pourquoiPasDeMicro(vm, u.recorderSource)?.let { return JSONObject().put("ok", false).put("erreur", it) }
                if (!u.recorderEnabled) vm.setRecorderEnabled(true)
                vm.startRecording(); OK
            }
            "/api/son/options" -> {
                if (p("sstv").isNotEmpty()) vm.setSstvEnabled(p("sstv") == "1")
                if (p("aprs").isNotEmpty()) vm.setAprsActif(p("aprs") == "1")
                if (p("auto").isNotEmpty()) return JSONObject().put("ok", false).put("erreur", "sur le téléphone, l'enregistrement des passages se règle dans l'agenda et le CAT")
                OK
            }
            else -> JSONObject().put("ok", false).put("erreur", "pas encore sur le téléphone")
        }
    }

    /** Every satellite's passes, computed in the background and kept five minutes; and whether a computation runs. */
    @Volatile private var passagesCalcules: List<fr.f4ioz.satcombo.data.SatPass> = emptyList()
    @Volatile private var passagesDe = 0L
    @Volatile private var passagesPour = ""
    private val passagesEnCours = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun passagesTous(vm: MainViewModel): Pair<List<fr.f4ioz.satcombo.data.SatPass>, Boolean> {
        val u = vm.ui.value
        val obs = u.observer ?: return emptyList<fr.f4ioz.satcombo.data.SatPass>() to false
        val maintenant = System.currentTimeMillis()
        // Computed again when old, or for another place, catalogue or minimum elevation.
        val pour = "${obs.latDeg},${obs.lonDeg},${u.satellites.size},${u.minElevDeg}"
        if ((maintenant - passagesDe > 5 * 60_000L || pour != passagesPour) && passagesEnCours.compareAndSet(false, true)) {
            Thread {
                try {
                    val p = vm.predicteurPourPage()
                    val debut = System.currentTimeMillis()
                    passagesCalcules = u.satellites.flatMap { s ->
                        if (!fr.f4ioz.satcombo.domain.PassPredictor.elementsUtilisables(s)) emptyList()
                        else runCatching { p.upcomingPasses(s, obs, debut - 15 * 60_000L, 24, u.minElevDeg.toDouble()) }
                            .getOrDefault(emptyList()).filter { it.losEpochMs >= debut }
                    }.sortedBy { it.aosEpochMs }
                    passagesDe = debut; passagesPour = pour
                } finally { passagesEnCours.set(false) }
            }.apply { isDaemon = true; name = "SatMe passages page"; priority = Thread.MIN_PRIORITY }.start()
        }
        return passagesCalcules.filter { it.losEpochMs >= maintenant } to passagesEnCours.get()
    }

    /** An FT8 slot's start from its time of day ("HH:mm:ss", UTC): today's, or yesterday's when that is still to come. */
    private fun tranche(heure: String): Long = runCatching {
        val (h, m, s) = heure.split(':').map { it.toInt() }
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m); c.set(java.util.Calendar.SECOND, s); c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis > System.currentTimeMillis() + 60_000L) c.add(java.util.Calendar.DAY_OF_MONTH, -1)
        c.timeInMillis
    }.getOrDefault(0L)

    /** The sources the phone records from: the SatMe screen's choices. */
    private val SOURCES = listOf("MIC" to "Micro du téléphone", "BT" to "Bluetooth (HFP)", "USB" to "Carte son USB (le poste)")

    /** The last panorama seen, and how many new ones: the page redraws only a new line. */
    @Volatile private var dernierPanorama: FloatArray? = null
    @Volatile private var trames = 0L
    /** The picture under way, and how often it changed: the page reloads it when this moves. */
    @Volatile private var dernierApercu: Any? = null
    @Volatile private var versionApercu = 0L

    /** QO-100 as the phone's screen holds it, in the engine's words ([ApiQo100]). */
    private fun qo100(vm: MainViewModel, plan: Boolean): JSONObject {
        val u = vm.ui.value
        val q = u.qo100
        val e = fr.f4ioz.satcombo.station.PiloteQo100.Etat(transpondeur = q.transpondeur, sansBride = q.sansBride, descenteHz = q.descenteHz,
            auPoste = q.auPoste, aLaCle = q.aLaCle, posteRxHz = q.posteRxHz, posteTxHz = q.posteTxHz, posteAtteignable = q.posteAtteignable,
            calageHz = q.calageHz, surBalise = q.surBalise, memoires = q.memoires, chaines = q.chaines, chaine = q.chaine, statut = q.statut)
        val obs = u.observer
        val ciel = obs?.let { ApiQo100.Ciel(fr.f4ioz.satcombo.domain.Qo100.pointage(it.latDeg, it.lonDeg), q.soleilAzimutMs, q.soleilTransits) }
        return ApiQo100.etat(e, u.catConnected, q.cleRxHz, ciel, obs != null, plan)
    }

    /** The recorder and the SSTV decoder, in SatMe-PC's words ([ApiSon]). */
    private fun son(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        val ctx = vm.getApplication<android.app.Application>()
        val r = fr.f4ioz.satcombo.audio.RecorderService.state.value
        val s = fr.f4ioz.satcombo.sstv.SstvHub.state.value
        if (s.preview != null && s.preview !== dernierApercu) { dernierApercu = s.preview; versionApercu++ }
        val hauteur = s.preview?.height ?: 0
        val enCours = s.decoding && s.modeName != null
        return ApiSon.json(ApiSon.Etat(SOURCES, u.recorderSource, r.recording, if (r.recording) 44_100 else 0,
            if (r.recording) fr.f4ioz.satcombo.audio.MoniteurAudio.etat.value.crete else 0f, "",
            if (r.recording) r.fileName.orEmpty() else "", if (r.recording) (System.currentTimeMillis() - r.startMs) / 1000 else 0L,
            r.recording && r.autoStopMs != null, false, u.sstvEnabled, vm.aprsActif(),
            if (enCours) s.modeName.orEmpty() else "", (s.progress * hauteur).toInt(), hauteur, versionApercu, s.lastSaved.orEmpty(),
            enregistrements(ctx), fr.f4ioz.satcombo.sstv.SstvHub.shots(ctx).map { it.second }.sortedByDescending { it.timeMs }))
    }

    /**
     * Why the page cannot take the microphone (a recording, FT8), or null. Android lets the
     * microphone be taken only with its permission, and only while SatMe is
     * on screen: asked otherwise, the recorder would not start (the app
     * stopped by the system). The permission is asked on the phone, not here.
     */
    private fun pourquoiPasDeMicro(vm: MainViewModel, source: String): String? {
        val ctx = vm.getApplication<android.app.Application>()
        val perms = listOf(android.Manifest.permission.RECORD_AUDIO) +
            if (source == "BT" && android.os.Build.VERSION.SDK_INT >= 31) listOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        if (perms.any { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED })
            return "le micro n'est pas autorisé : lancer un premier enregistrement sur le téléphone (⏺), qui le demande"
        val moi = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(moi)
        if (moi.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            return "SatMe doit être à l'écran du téléphone pour prendre le micro (règle d'Android) : l'ouvrir, puis « Enregistrer »"
        return null
    }

    private fun dossierEnregistrements(ctx: android.content.Context) = java.io.File(ctx.getExternalFilesDir(null), "recordings")

    private fun enregistrements(ctx: android.content.Context): List<java.io.File> =
        (dossierEnregistrements(ctx).listFiles { f -> f.name.startsWith("SatMe_") && (f.name.endsWith(".mp3") || f.name.endsWith(".wav")) }
            ?: emptyArray()).sortedByDescending { it.lastModified() }

    /** A file of the page: an SSTV picture (`/sstv/…`) or a recording (`/enregistrements/…`); null when it is not one. */
    fun fichier(chemin: String): ReponseWeb? {
        val vm = vm ?: return null
        val ctx = vm.getApplication<android.app.Application>()
        fun nom(prefixe: String) = java.net.URLDecoder.decode(chemin.removePrefix(prefixe), "UTF-8")
        return when {
            chemin.startsWith("/sstv/") -> ApiSon.fichier(fr.f4ioz.satcombo.sstv.SstvHub.dir(ctx), nom("/sstv/"), ".png")
                ?.let { ReponseWeb("200 OK", "image/png", fichier = it) }
            chemin.startsWith("/enregistrements/") -> ApiSon.fichier(dossierEnregistrements(ctx), nom("/enregistrements/"), ".mp3", ".wav")
                ?.takeIf { it.name.startsWith("SatMe_") }
                ?.let { ReponseWeb("200 OK", if (it.name.endsWith(".mp3")) "audio/mpeg" else "audio/wav", fichier = it) }
            else -> null
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
        o.put("passagesSuivi", JSONArray(siens.take(8).map { ApiStation.passage(it, fav = it.catalogNumber in u.favorites, agenda = vm.agendaForPass(it) != null) }))
        o.put("agendaSuivi", false).put("agenda", ApiCalculs.agendaJson(u.agenda, maintenant)).put("repos", JSONObject.NULL)
        val (dl, ul) = ApiStation.frequencesDuCiel(u.transmitters.getOrNull(u.selectedTxIndex), u.livePosition?.rangeRateKmS ?: 0.0, null, u.calibShiftHz, u.txShiftHz)
        o.put("ciel", JSONObject().put("dl", dl ?: JSONObject.NULL).put("ul", ul ?: JSONObject.NULL))
        o.put("convertisseurs", JSONObject().put("rxOl", if (u.convRx.actif) u.convRx.olHz else 0).put("rxInv", u.convRx.inverseur)
            .put("txOl", if (u.convTx.actif) u.convTx.olHz else 0).put("txInv", u.convTx.inverseur))
        o.put("cat", ApiStation.cat(vm.etatBoucleCat(), u.rigModel, u.civUsbIndex, u.catConnected, false, u.catStatus, u.dopplerHold))
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
