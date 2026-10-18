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

    /** The tabs the phone serves; the page hides the others (and those whose extension is off, [EXTENSIONS]). */
    val ONGLETS = listOf("station", "carnet", "agenda", "carres", "frise", "sked", "qo100", "datv", "sdr", "sondes", "meteor", "son", "sstv", "aprs", "ft8", "journal", "planche", "reglages")

    /** The tabs behind one of the phone's extensions: shown only when it is on, as its screens. */
    private val EXTENSIONS = mapOf("qo100" to fr.f4ioz.satcombo.data.Extensions.QO100, "meteor" to fr.f4ioz.satcombo.data.Extensions.METEOR,
        "sdr" to fr.f4ioz.satcombo.data.Extensions.SDR, "sondes" to fr.f4ioz.satcombo.data.Extensions.SONDE,
        "aprs" to fr.f4ioz.satcombo.data.Extensions.APRS, "sstv" to fr.f4ioz.satcombo.data.Extensions.SSTV)

    private fun onglets(vm: MainViewModel) = ONGLETS.filter { o -> EXTENSIONS[o]?.let { vm.hasExtension(it) } ?: true }

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

    /** The FT8 tab's own settings (the stations waited for), in their own preferences. */
    private fun stockageFt8(vm: fr.f4ioz.satcombo.MainViewModel) = fr.f4ioz.satcombo.data.stockage(vm.getApplication(), "satcombo_ft8")

    private fun json(o: Any) =ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private const val MONTAGE_SUR_PC = "le montage de la station se compose et s'enregistre sur SatMe-PC"
    private const val PAS_DE_SPECTRE_LARGE = "le spectre de la bande large est sur SatMe-PC"
    private const val PAS_D_EMISSION_DATV ="pas d'émission DATV depuis le téléphone : c'est SatMe-PC qui émet"
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
                "/api/police-drapeaux" -> PagePc.POLICE_DRAPEAUX?.let { ReponseWeb("200 OK", "font/woff2", it) } ?: NON
                "/api/capacites" -> json(JSONObject().put("onglets", JSONArray(onglets(vm))).put("serveur", "telephone"))
                "/api/etat" -> json(etat(vm))
                // Every satellite's passes over 24 h, as SatMe-PC lists them (the phone's own lists hold the followed one and the favourites).
                "/api/passages" -> {
                    val (l, calcul) = passagesTous(vm)
                    json(JSONObject().put("calcul", calcul).put("liste", JSONArray(l.take(300).map {
                        ApiStation.passage(it, fav = it.catalogNumber in u.favorites, agenda = vm.agendaForPass(it) != null,
                            skeds = if (u.skedsEnabled) vm.skedsForPass(it.catalogNumber, it.aosEpochMs, it.losEpochMs).size else 0)
                    })))
                }
                // The passes listed, as a calendar (the list's filters, or one satellite).
                "/api/passages.ics" -> {
                    val maintenant = System.currentTimeMillis()
                    val l = fr.f4ioz.satcombo.domain.CalendrierPassages.choisis(passagesTous(vm).first, maintenant, p("f"),
                        u.favorites.takeIf { p("fav") == "1" }, p("catnum").toIntOrNull()) {
                        p("actifs") != "1" || vm.amsatFor(it.satName, it.catalogNumber)?.recent?.name == "ACTIVE"
                    }
                    ReponseWeb("200 OK", fr.f4ioz.satcombo.domain.CalendrierPassages.TYPE,
                        fr.f4ioz.satcombo.domain.CalendrierPassages.ics(l, maintenant).toByteArray(),
                        "Content-Disposition: attachment; filename=\"" + fr.f4ioz.satcombo.domain.CalendrierPassages.nomFichier(maintenant) + "\"\r\n")
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
                // ---- the SDR dongle on the phone's USB port
                "/api/sdr" -> json(sdr(vm))
                "/api/sdr/nettoyage" -> json(nettoyage(vm))
                "/api/sdr/spectre" -> {
                    val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
                    val sp = fr.f4ioz.satcombo.sdr.SdrHub.spectrum.value
                    if (sp !== dernierSpectre && sp.isNotEmpty()) { dernierSpectre = sp; tramesSpectre++ }
                    val span = p("span").toIntOrNull() ?: 48_000
                    val dx = ApiSdr.decalageVue(span, p("dx").toIntOrNull() ?: 0)
                    json(ApiSdr.spectre(sp, fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble() / fr.f4ioz.satcombo.sdr.Dsp.DECIM_1 / sp.size.coerceAtLeast(1),
                        (st.dopplerFineHz + st.offsetHz + dx).toDouble(), span, p("n").toIntOrNull() ?: 512,
                        tramesSpectre, st.centerHz - st.dopplerFineHz).put("dx", dx))
                }
                // ---- radiosondes: the phone's receiver (its dongle, or its audio input)
                "/api/sondes" -> {
                    val s = fr.f4ioz.satcombo.sonde.SondeHub.state.value
                    val d = fr.f4ioz.satcombo.sdr.SdrHub.state.value
                    val ctx = vm.getApplication<android.app.Application>()
                    val r = ApiSondes.Recepteur(s.running, d.running && !fr.f4ioz.satcombo.sonde.SondeHub.active, d.error.orEmpty(), u.sondeFreqHz,
                        d.offsetHz, d.levelDb.toDouble(), u.sondeModel, false, s.frames, s.rejected, s.swing, s.serial, s.type)
                    json(ApiSondes.json(r, s.last, fr.f4ioz.satcombo.sonde.SondeHub.currentFlight, u.observer, fr.f4ioz.satcombo.sonde.SondeHub.logs(ctx)))
                }
                // ---- METEOR: the phone's LRPT receiver, its next pass, its pictures
                "/api/meteor" -> {
                    val m = fr.f4ioz.satcombo.meteor.MeteorHub.etat.value
                    if (m.apercu != null && m.apercu !== dernierApercuMeteor) { dernierApercuMeteor = m.apercu; apercusMeteor++ }
                    val d = fr.f4ioz.satcombo.sdr.SdrHub.state.value
                    val r = ApiMeteor.Recepteur(m.actif, m.source, m.satName, m.qualiteDb, m.verrou, m.trames, m.tramesRatees, m.lignes, m.canaux,
                        m.frequenceHz, m.perdus, m.progression, m.fichier.orEmpty(), m.erreur.orEmpty(), apercusMeteor)
                    json(ApiMeteor.json(r, vm.meteorAuto(), vm.meteorCotes(), d.running, d.error.orEmpty(), vm.estMeteor(),
                        m.dernierEnregistre.orEmpty(), vm.meteorProchainPassage(48), fr.f4ioz.satcombo.meteor.MeteorHub.shots(vm.getApplication())))
                }
                // ---- DATV transmission's sources: SatMe-PC only (the phone sends no DATV)
                "/api/datv/sources/liste", "/api/datv/sources/envois", "/api/datv/emission/incrustation" ->
                    json(JSONObject().put("ok", false).put("erreur", PAS_D_EMISSION_DATV))
                "/api/meteor/apercu.png" -> {
                    val b = fr.f4ioz.satcombo.meteor.MeteorHub.etat.value.apercu ?: return NON
                    val o = java.io.ByteArrayOutputStream()
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o)
                    ReponseWeb("200 OK", "image/png", o.toByteArray())
                }
                // ---- DATV: the wide band's spectrum is SatMe-PC's (hidden on the phone's page)
                "/api/datv/large" -> json(JSONObject().put("ok", false).put("erreur", PAS_DE_SPECTRE_LARGE))
                // ---- DATV: the phone's, through a SatMe-Node (its picture on the phone's own screen)
                "/api/datv" -> {
                    val d = vm.datv
                    // Following the node's status again when it receives and nothing follows it (the DATV screen closed).
                    val maintenant = System.currentTimeMillis()
                    if (maintenant - datvRevu > 5_000L) { datvRevu = maintenant; if (!d.ui.value.etat.actif) d.revient() }
                    val x = d.ui.value
                    json(fr.f4ioz.satcombo.datv.Datv.json(x.etat, x.reglage, x.points)
                        .put("noeud", x.adresse).put("nomNoeud", x.nom).put("programme", JSONObject.NULL).put("chemin", "")
                        .put("windows", false).put("telephone", true).put("erreur", x.message.ifBlank { x.etat.erreur })
                        .put("debits", JSONArray(fr.f4ioz.satcombo.datv.Datv.DEBITS)).put("canaux", JSONArray(fr.f4ioz.satcombo.datv.Datv.CANAUX_KHZ))
                        .put("balise", fr.f4ioz.satcombo.datv.Datv.BALISE_KHZ))
                }
                // ---- APRS and FT8: what the phone heard (the engine's words, as SatMe-PC's)
                "/api/aprs" -> {
                    val a = fr.f4ioz.satcombo.aprs.AprsHub.etat.value
                    json(ApiRadio.aprs(a.paquets, a.ecoute, a.nouveaux, u.observer))
                }
                "/api/aprs/trophees" -> {
                    val a = fr.f4ioz.satcombo.aprs.AprsHub.etat.value
                    json(ApiRadio.trophees(fr.f4ioz.satcombo.aprs.AprsHub.trophees.value, a.paquets, vm.indicatif(), u.observer,
                        emptyList(), System.currentTimeMillis()).put("present", true))
                }
                // The TH-D72 panel (the phone's).
                "/api/thd72" -> json(ApiStation.thd72(vm.thd72.etat.value, u.rigModel == "THD72" && u.catConnected, vm.thd72.bandeTx()))
                // The KISS radio on the phone's USB (its APRS screen's): its adapters listed only, none opened here.
                "/api/kiss" -> {
                    val l = fr.f4ioz.satcombo.aprs.TncKiss.appareils(vm.getApplication())
                    val s = vm.aprs
                    json(ApiRadio.kiss(fr.f4ioz.satcombo.aprs.TncKiss.etat.value, l.mapIndexed { i, a -> i to a.nom },
                        l.indexOfFirst { it.cle == s.kissCle() }.takeIf { it >= 0 }, s.travail(), s.kissDoppler(),
                        lignesLevees = false, lignesAuChoix = false, voulue = null).let { o ->
                        ApiRadio.kissEmission(o, s.source(), s.cheminParDefaut(), s.envoi.value, vm.aprsBaliseIss(), s.positionFloue(), s.ssid())
                    })
                }
                "/api/ft8" -> {
                    val f = fr.f4ioz.satcombo.ft8.Ft8Hub.state.value
                    val faits = u.log.map { it.callsign.uppercase().substringBefore('/') }.toHashSet()
                    val paysFaits = u.log.mapNotNull { fr.f4ioz.satcombo.domain.Dxcc.entite(it.callsign)?.nom }.toHashSet()
                    val carresFaits = u.log.mapNotNull { it.theirLocator.trim().uppercase().take(4).takeIf { l -> l.length == 4 } }.toHashSet()
                    json(ApiRadio.ft8(f.enMarche, f.mode, f.avancement, f.niveau, f.tranches, f.enMarche, f.basseHz, f.hauteHz,
                        f.cascade, f.spectre, f.entendus.map { e ->
                            val (de, a, loc) = ApiRadio.decoupe(e.texte)
                            ApiRadio.Ligne(tranche(e.heure), e.texte, de, a, loc, e.frequenceHz, e.rapportDb, e.decalageS, e.mode)
                        }, u.callsign.uppercase(), u.observer, faits, paysFaits, carresFaits,
                        guettees = fr.f4ioz.satcombo.domain.Ft8Veille.lit(stockageFt8(vm)), panne = f.panne))
                }
                // ---- Settings: the phone's own, as SatMe-PC's page shows them
                "/api/reglages" -> {
                    val c = u.carnet
                    json(JSONObject().put("c", u.callsign).put("l", u.manualLocator).put("qrzUser", c.qrzUser)
                        .put("qrzMdp", c.qrzMdp.isNotEmpty()).put("carnetUrl", c.url).put("carnetCle", c.cle.isNotEmpty())
                        .put("carnetProfil", c.profil).put("minEl", u.minElevDeg).put("fiche", true)
                        .put("carnetAuto", c.auto).put("carnetDelai", c.autoDelaiS)
                        .put("delais", JSONArray(fr.f4ioz.satcombo.domain.EnvoiAuto.DELAIS_S))
                        .put("amsatAuto", c.amsatAuto).put("amsatEtat", c.amsatEtat)
                        .put("pskReporter", c.pskReporter).put("pskEtat", c.pskEtat)
                        .put("horizon", JSONArray(vm.masqueHorizon().elevationsDeg)))
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
                "/api/veille" -> json(veille(vm))
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
                        if (pan != null && r.second != null) fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, r.second!!.first, r.second!!.second) else null,
                        ApiQo100.fenetre(p("bas").ifEmpty { null }, p("haut").ifEmpty { null })))
                }
                // The QO-100 cockpit: the phone's receiver and QO-100 screen; no signal classifier here yet.
                "/api/qo100/trafic" -> json(trafic(vm))
                "/api/qo100/signaux" -> json(JSONObject().put("actif", false).put("absent", true))
                // The rigctld server for logging programs is SatMe-PC's: its card and status hidden here.
                "/api/rigctld" -> json(JSONObject().put("absent", true))
                // The USB knob card is SatMe-PC's: the phone reads its knob itself (Réglages › clavier).
                "/api/molette" -> json(JSONObject().put("absent", true))
                "/api/carnet" -> json(carnet(vm))
                "/api/carnet/records" -> json(ApiStation.records(fr.f4ioz.satcombo.domain.RecordsCarnet.calcule(u.log, vm.myLocator())))
                // The contacts ticked on the page: ADIF, or the batch for another phone (as Settings › Backup makes it).
                "/api/carnet/adif", "/api/carnet/lot" -> {
                    val choisis = p("t").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                    val l = u.log.filter { it.callsign.isNotBlank() && (choisis.isEmpty() || it.timeMs in choisis) }.sortedBy { it.timeMs }
                    val date = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                    if (route == "/api/carnet/adif") ReponseWeb("200 OK", "text/plain; charset=utf-8",
                        fr.f4ioz.satcombo.data.LogStore.toAdif(l, vm.indicatif()).toByteArray(),
                        "Content-Disposition: attachment; filename=\"SatMe_selection_$date.adi\"\r\n")
                    else ReponseWeb("200 OK", "application/json; charset=utf-8",
                        fr.f4ioz.satcombo.data.LotContacts.ecrit(l, vm.indicatif()).toByteArray(),
                        "Content-Disposition: attachment; filename=\"satme-contacts-$date.json\"\r\n")
                }
                "/api/carnet/propositions" -> {
                    val saisie = p("c")
                    val m = u.express.memoire
                    val l = fr.f4ioz.satcombo.domain.Indicatifs.suggestions(saisie, m, System.currentTimeMillis(), u.selected?.name.orEmpty(), max = 4)
                    json(JSONObject().put("etat", fr.f4ioz.satcombo.domain.Indicatifs.etat(saisie, m).name).put("propositions", JSONArray(l.map { k ->
                        JSONObject().put("c", k.indicatif).put("nm", k.nom).put("n", k.contacts).put("sat", k.dernierSat)
                            .put("l", fr.f4ioz.satcombo.domain.Indicatifs.locatorPropose(k, k.indicatif))
                    })))
                }
                // The station's chain is drawn and kept on SatMe-PC: here an empty one (its catalogue all the same).
                "/api/montage" -> json(JSONObject().put("ok", true).put("telephone", true)
                    .put("chaine", fr.f4ioz.satcombo.station.Montage.ecrit(fr.f4ioz.satcombo.station.Montage.Chaine()))
                    .put("plan", fr.f4ioz.satcombo.station.DispositionMontage.json(fr.f4ioz.satcombo.station.DispositionMontage.dispose(fr.f4ioz.satcombo.station.Montage.Chaine())))
                    .put("etat", JSONObject()).put("photos", JSONArray()))
                "/api/montage/catalogue" -> json(fr.f4ioz.satcombo.station.Montage.catalogueJson())
                // The receiver's memories are kept on SatMe-PC: none here; the band plans, SatMe's own.
                "/api/memoires-sdr", "/api/memoires-sdr/export" -> json(fr.f4ioz.satcombo.domain.MemoiresSdr.ecrit(emptyList())
                    .put("ok", true).put("telephone", true).put("etiquettes", JSONArray()))
                "/api/plan-bandes" -> json(fr.f4ioz.satcombo.domain.PlanBandes.json(fr.f4ioz.satcombo.domain.PlanBandes.defaut {
                    fr.f4ioz.satcombo.i18n.I18n.t("qo100_seg_$it") }).put("ok", true))
                "/api/montage/suggestion" -> json(JSONObject().put("ok", false).put("erreur", MONTAGE_SUR_PC))
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
            // ---- the SDR dongle: the phone's own gestures (its SDR screen's)
            "/api/sdr/demarrer" -> {
                if (!fr.f4ioz.satcombo.sdr.SdrHub.state.value.connected && fr.f4ioz.satcombo.sdr.SdrHub.devicePresent(vm.getApplication()) == null)
                    return JSONObject().put("ok", false).put("erreur", "aucune clé SDR branchée sur le téléphone")
                vm.startSdr()
                Thread.sleep(800)
                val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
                when {
                    st.awaitingPermission -> JSONObject().put("ok", false).put("erreur", "autoriser la clé sur l'écran du téléphone, puis Démarrer")
                    st.error != null && !st.running -> JSONObject().put("ok", false).put("erreur", st.error)
                    else -> OK
                }
            }
            "/api/sdr/arreter" -> { vm.stopSdr(); OK }
            "/api/sdr/decalage" -> { p("hz").toIntOrNull()?.let { vm.setSdrOffset(it) }; OK }
            "/api/sdr/pic" -> { vm.tuneSdrPeak(30_000); OK }
            "/api/sdr/nettoyage" -> {
                val q = listOf("nb", "nbSeuil", "nbLargeur", "notchAuto", "notch", "notchHz", "notchLargeur", "nr", "cag")
                    .associateWith { p(it) }.filterValues { it.isNotEmpty() }
                vm.setSdrNettoyage(q, fr.f4ioz.satcombo.sdr.RxMode.values().firstOrNull { it.name == p("mode") })
                nettoyage(vm)
            }
            "/api/sdr/reglage" -> {
                p("gain").toIntOrNull()?.let { vm.setSdrGain(it.takeIf { g -> g >= 0 }) }
                if (p("agc").isNotEmpty()) vm.setSdrAgc(p("agc") == "1")
                p("ppm").toIntOrNull()?.let { vm.setSdrPpm(it) }
                p("mode").takeIf { it.isNotEmpty() }?.let { m -> fr.f4ioz.satcombo.sdr.RxMode.values().firstOrNull { it.name == m }?.let { vm.setSdrMode(it) } }
                p("bw").toIntOrNull()?.let { vm.setSdrBandwidth(it) }
                p("squelch").toIntOrNull()?.let { vm.setSdrSquelch(it) }
                if (p("son").isNotEmpty()) vm.setSdrAudio(p("son") == "1")
                if (p("deemph").isNotEmpty()) vm.setSdrDeemph(p("deemph") == "1")
                if (p("doppler").isNotEmpty()) vm.setSdrDopplerTrack(p("doppler") == "1")
                // The phone's dongle follows the satellite followed; no other frequency, no rtl_tcp link.
                if (p("mhz").isNotEmpty() || p("suit") == "0") return JSONObject().put("ok", false).put("erreur", "sur le téléphone, la clé écoute le satellite suivi (onglet Station)")
                OK
            }
            // ---- radiosondes: the gestures of the phone's radiosonde screen
            "/api/sondes/demarrer" -> {
                val src = u.sondeSource
                if (src == "MIC" || src == "USB") pourquoiPasDeMicro(vm, src)?.let { return JSONObject().put("ok", false).put("erreur", it) }
                else if (!fr.f4ioz.satcombo.sdr.SdrHub.state.value.connected && fr.f4ioz.satcombo.sdr.SdrHub.devicePresent(vm.getApplication()) == null)
                    return JSONObject().put("ok", false).put("erreur", "aucune clé SDR branchée sur le téléphone")
                vm.startSondeRx(); OK
            }
            "/api/sondes/arreter" -> { vm.stopSondeRx(); OK }
            "/api/sondes/frequence" -> { p("khz").replace(',', '.').toDoubleOrNull()?.let { vm.setSondeFreq(Math.round(it * 1000)) }; OK }
            "/api/sondes/pas" -> { p("n").toIntOrNull()?.let { vm.stepSondeFreq(it) }; OK }
            "/api/sondes/modele" -> { p("m").takeIf { m -> fr.f4ioz.satcombo.sonde.SondeModel.ALL.any { it.id == m } }?.let { vm.setSondeModel(it) }; OK }
            "/api/sondes/recentre" -> { fr.f4ioz.satcombo.sdr.SdrHub.tuneCentroid(); OK }
            "/api/sondes/auto" -> JSONObject().put("ok", false).put("erreur", "le recentrage automatique est sur SatMe-PC ; ici, « Recentrer »")
            "/api/sondes/oublie" -> { vm.clearSondeFlight(); OK }
            "/api/sondes/export" -> {
                val f = fr.f4ioz.satcombo.sonde.SondeHub.export(vm.getApplication(), p("kml") == "1")
                    ?: return JSONObject().put("ok", false).put("erreur", "Pas de vol à exporter.")
                OK.put("nom", f.name)
            }
            // ---- METEOR: the gestures of the phone's METEOR screen
            "/api/meteor/demarrer" -> {
                if (!fr.f4ioz.satcombo.sdr.SdrHub.state.value.connected && fr.f4ioz.satcombo.sdr.SdrHub.devicePresent(vm.getApplication()) == null)
                    return JSONObject().put("ok", false).put("erreur", "aucune clé SDR branchée sur le téléphone")
                // The followed satellite if it is a METEOR, otherwise the one of the next pass.
                if (!vm.estMeteor()) {
                    val (sat, _) = vm.meteorProchainPassage(48) ?: return JSONObject().put("ok", false).put("erreur", "Aucun METEOR-M LRPT dans le catalogue.")
                    vm.select(sat)
                }
                vm.prepareMeteor()
                vm.startSdr(); OK
            }
            "/api/meteor/arreter" -> { vm.stopSdr(); OK }
            "/api/meteor/auto" -> { vm.setMeteorAuto(p("on") == "1"); OK }
            "/api/meteor/cotes" -> { vm.setMeteorCotes(p("on") == "1"); OK }
            "/api/meteor/enregistre" -> {
                fr.f4ioz.satcombo.meteor.MeteorHub.enregistreMaintenant(vm.getApplication())
                    ?: return JSONObject().put("ok", false).put("erreur", "Pas encore assez de lignes (80 au moins).")
                OK
            }
            "/api/meteor/fichier" -> JSONObject().put("ok", false).put("erreur", "sur le téléphone, un fichier se décode depuis son écran METEOR")
            "/api/meteor/annule" -> { fr.f4ioz.satcombo.meteor.MeteorHub.annuleFichier(); OK }
            "/api/meteor/supprime" -> {
                val d = fr.f4ioz.satcombo.meteor.MeteorHub.dir(vm.getApplication())
                ApiSon.fichier(d, p("nom"), ".png")?.let { fr.f4ioz.satcombo.meteor.MeteorHub.supprime(it) }
                OK
            }
            // ---- DATV: the gestures of the phone's DATV screen
            "/api/datv/demarre" -> {
                val d = vm.datv
                if (!d.ui.value.associe) return JSONObject().put("ok", false).put("erreur", "rejoindre d'abord un SatMe-Node (son adresse et son code)")
                val r0 = d.ui.value.reglage
                fun khz(k: String) = p(k).replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1000.0) }
                // The LNB supply only when chosen, each time.
                d.regle(r0.copy(cielKhz = khz("ciel") ?: r0.cielKhz, srKs = p("sr").toIntOrNull() ?: r0.srKs, olKhz = khz("ol") ?: r0.olKhz,
                    entreeB = if (p("entree").isNotEmpty()) p("entree") == "b" else r0.entreeB, lnb = p("lnb").takeIf { it in setOf("v", "h") } ?: "",
                    son = if (p("son").isNotEmpty()) p("son") == "1" else r0.son))
                d.recois(); OK
            }
            "/api/datv/arrete" -> { vm.datv.arrete(); OK }
            "/api/datv/cale" -> { vm.datv.cale(); OK }
            "/api/datv/noeud" -> { vm.datv.rejoins(p("adresse").trim(), p("code").trim()); OK }
            "/api/datv/sources/envoi", "/api/datv/sources/supprime", "/api/datv/emission/apercu", "/api/datv/emission/incrustation" ->
                JSONObject().put("ok", false).put("erreur", PAS_D_EMISSION_DATV)
            "/api/datv/large/reglage", "/api/datv/large/accorde", "/api/datv/large/veille" ->
                JSONObject().put("ok", false).put("erreur", PAS_DE_SPECTRE_LARGE)
            "/api/datv/enregistre", "/api/datv/vlc", "/api/datv/chemin" ->
                JSONObject().put("ok", false).put("erreur", "sur le téléphone, l'image et le son sont sur son écran DATV")
            // ---- the TH-D72 panel: the gestures of the phone's
            "/api/thd72/lire" -> { vm.thd72.lire(); OK }
            "/api/thd72/frequence" -> {
                val b = p("b").toIntOrNull()?.coerceIn(0, 1) ?: return JSONObject().put("ok", false)
                val hz = ApiQo100.mhz(p("mhz")) ?: return JSONObject().put("ok", false).put("erreur", "une fréquence en MHz")
                vm.thd72.frequence(b, hz); OK
            }
            "/api/thd72/pas" -> { vm.thd72.pas(p("b").toIntOrNull()?.coerceIn(0, 1) ?: 0, if (p("sens") == "-1") -1 else 1); OK }
            "/api/thd72/puissance" -> { vm.thd72.puissance(p("b").toIntOrNull()?.coerceIn(0, 1) ?: 0, (p("p").toIntOrNull() ?: 0).coerceIn(0, 2)); OK }
            "/api/thd72/bandetx" -> { vm.thd72.setBandeTx((p("b").toIntOrNull() ?: 0).coerceIn(0, 1)); OK }
            // ---- the KISS radio: the gestures of the phone's APRS screen
            "/api/kiss/connecter" -> {
                val a = fr.f4ioz.satcombo.aprs.TncKiss.appareils(vm.getApplication()).getOrNull(p("port").toIntOrNull() ?: -1)
                    ?: return JSONObject().put("ok", false).put("erreur", "adaptateur inconnu")
                vm.aprs.kissConnecte(a.cle, p("vitesse").toIntOrNull() ?: 9600); OK
            }
            "/api/kiss/deconnecter" -> { vm.aprs.kissDeconnecte(); OK }
            // A frame through the phone's KISS radio: only when the operator asks, its checks first (EmissionKiss).
            "/api/kiss/emet" -> {
                val s = vm.aprs
                val info = when (p("type")) {
                    "message" -> {
                        if (p("dest").isBlank() || p("texte").isBlank()) return JSONObject().put("ok", false).put("erreur", "à qui, et quoi ?")
                        fr.f4ioz.satcombo.aprs.AprsEmission.message(p("dest"), p("texte"), if (p("numero") == "1") s.numeroSuivant() else null)
                    }
                    "position" -> {
                        val (la, lo) = s.positionEmise() ?: return JSONObject().put("ok", false).put("erreur", "position inconnue")
                        fr.f4ioz.satcombo.aprs.AprsEmission.position(la, lo, "/-", p("texte").ifBlank { "SatMe " + vm.myLocator().take(4) })
                    }
                    "statut" -> fr.f4ioz.satcombo.aprs.AprsEmission.statut(p("texte").ifBlank { return JSONObject().put("ok", false).put("erreur", "le statut est vide") })
                    else -> return JSONObject().put("ok", false).put("erreur", "quoi émettre ?")
                }
                val chemin = when (p("chemin")) { "ARISS" -> listOf("ARISS"); "WIDE" -> listOf("WIDE1-1", "WIDE2-1"); else -> s.cheminParDefaut() }
                s.envoi.value = ""
                s.emetKiss(fr.f4ioz.satcombo.aprs.AprsEmission.trame(s.source(), chemin, info), p("confirme") == "1")
                val fin = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < fin && s.envoi.value.isEmpty()) Thread.sleep(100)
                val m = s.envoi.value
                JSONObject().put("ok", m == fr.f4ioz.satcombo.i18n.t("aprs_kiss_ok")).put("erreur", m).put("message", m)
            }
            "/api/kiss/reglage" -> {
                if (p("balise").isNotEmpty()) vm.setAprsBaliseIss(p("balise") == "1")
                if (p("floue").isNotEmpty()) vm.aprs.setPositionFloue(p("floue") == "1")
                p("ssid").toIntOrNull()?.let { vm.aprs.setSsid(it.coerceIn(0, 15)) }
                // One setting a call: the Doppler, or else where it listens ("POSTE": its own frequency, left alone).
                if (p("doppler").isNotEmpty()) vm.aprs.setKissDoppler(p("doppler") == "1")
                else p("travail").takeIf { it in setOf("TERRE", "ISS", "AUTO", "POSTE") }?.let { vm.aprs.setTravail(it) }
                OK
            }
            // ---- FT8: the phone's decoder, started and stopped from the page
            "/api/ft8" -> {
                val f = fr.f4ioz.satcombo.ft8.Ft8Hub
                val ctx = vm.getApplication<android.app.Application>()
                // The stations waited for: a setting of the phone's, as SatMe-PC's.
                if (p("guettees").isNotEmpty() || p("guettees_vide") == "1")
                    return JSONObject().put("ok", true).put("guettees", JSONArray(fr.f4ioz.satcombo.domain.Ft8Veille.ecrit(stockageFt8(vm), p("guettees"))))
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
                if (p("pskReporter").isNotEmpty()) vm.setPskReporter(p("pskReporter") == "1")
                OK
            }
            "/api/horizon" -> { vm.setMasqueHorizon(fr.f4ioz.satcombo.domain.MasqueHorizon.lis(p("m"))); OK }
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
            "/api/qo100/demarrer" -> {
                if (!vm.ouvreQo100PourPage()) return JSONObject().put("ok", false).put("erreur", "QO-100 n'est pas activé sur le téléphone (Réglages → Extensions)")
                vm.setQo100ALaCle(true)
                if (fr.f4ioz.satcombo.sdr.SdrHub.state.value.running) OK else action(vm, "/api/sdr/demarrer", p)
            }
            "/api/qo100/arreter" -> { vm.stopSdr(); vm.setQo100ALaCle(false); OK }
            "/api/qo100/accorde" -> {
                val hz = p("hz").toLongOrNull() ?: return JSONObject().put("ok", false)
                val a = p("classe").takeIf { it.isNotBlank() }?.let { fr.f4ioz.satcombo.domain.TraficQo100.accord(it, hz) }
                vm.setQo100Descente(a?.cadranHz ?: hz, fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE)
                vm.setSdrOffset(0)
                a?.let { OK.put("cadran", it.cadranHz).put("mode", it.modeCarnet).put("decodeur", it.decodeur).put("ft8Mode", it.ft8Mode) } ?: OK
            }
            "/api/qo100/mode" -> OK
            "/api/qo100/reprise", "/api/qo100/reprise-auto" ->
                JSONObject().put("ok", false).put("erreur", "sur le téléphone, la réception QO-100 se reprend depuis son écran QO-100")
            "/api/qo100/frequence" -> { p("hz").toLongOrNull()?.let { vm.setQo100Descente(it,
                if (p("auto") == "1") fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE else fr.f4ioz.satcombo.domain.GardeQo100.Origine.MAIN) }; OK }
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
            "/api/skeds/reglages" -> {
                if (p("on").isNotEmpty()) vm.setSkedsEnabled(p("on") == "1")
                if (p("cle").isNotEmpty()) vm.setSkedsToken(p("cle"))
                OK
            }
            "/api/veille" -> {
                if (ApiVeille.action({ k -> p(k).ifEmpty { null } }, vm.settingsStore(), u.favorites, System.currentTimeMillis())) vm.veille.change()
                OK
            }
            "/api/son/options" -> {
                if (p("coupe").isNotEmpty()) vm.setEnregCoupeLos(p("coupe") == "1")
                if (p("sstv").isNotEmpty()) vm.setSstvEnabled(p("sstv") == "1")
                if (p("aprs").isNotEmpty()) vm.setAprsActif(p("aprs") == "1")
                if (p("auto").isNotEmpty()) return JSONObject().put("ok", false).put("erreur", "sur le téléphone, l'enregistrement des passages se règle dans l'agenda et le CAT")
                OK
            }
            // ---- SSTV: the phone's controls (SstvHub), from the page
            "/api/sstv/reglage" -> {
                val ctx = vm.getApplication<android.app.Application>()
                if (p("source").isNotEmpty()) return JSONObject().put("ok", false).put("erreur", "sur le téléphone, la SSTV écoute l'enregistrement (onglet Son) ou la clé SDR qui la décode")
                if (p("force").isNotEmpty()) fr.f4ioz.satcombo.sstv.SstvHub.setForcedMode(ctx, p("force").takeIf { it != "auto" })
                if (p("continu").isNotEmpty()) fr.f4ioz.satcombo.sstv.SstvHub.setContinu(ctx, p("continu") == "1")
                if (p("nettoyage").isNotEmpty()) fr.f4ioz.satcombo.sstv.SstvHub.setNettoyage(ctx, p("nettoyage") == "1")
                if (p("afc").isNotEmpty()) fr.f4ioz.satcombo.sstv.SstvHub.setAfc(ctx, p("afc") == "1")
                if (p("actif").isNotEmpty()) vm.setSstvEnabled(p("actif") == "1")
                OK
            }
            "/api/sstv/afc/zero" -> { fr.f4ioz.satcombo.sstv.SstvHub.remiseAZeroAfc(); OK }
            "/api/sstv/caler" -> {
                val ctx = vm.getApplication<android.app.Application>()
                if (p("mode").isNotEmpty()) fr.f4ioz.satcombo.sstv.SstvHub.setForcedMode(ctx, p("mode"))
                if (fr.f4ioz.satcombo.sstv.SstvHub.forceStart()) OK
                else JSONObject().put("ok", false).put("erreur", fr.f4ioz.satcombo.sstv.SstvHub.state.value.erreur ?: "impossible")
            }
            "/api/sstv/abandon" -> { fr.f4ioz.satcombo.sstv.SstvHub.abortFrame(); OK }
            "/api/sstv/annule" -> { fr.f4ioz.satcombo.sstv.SstvHub.cancelFileDecode(); OK }
            "/api/sstv/redecode" -> {
                val ctx = vm.getApplication<android.app.Application>()
                if (fr.f4ioz.satcombo.sstv.SstvHub.state.value.fileProgress >= 0f) return JSONObject().put("ok", false).put("erreur", "un décodage est déjà en cours")
                val image = p("image").takeIf { it.isNotEmpty() }?.let { n -> fr.f4ioz.satcombo.sstv.SstvHub.shots(ctx).firstOrNull { it.first.name == n } }
                val f = if (image != null) fr.f4ioz.satcombo.sstv.SstvSon.fichier(image.first).takeIf { it.isFile }
                    else ApiSon.fichier(dossierEnregistrements(ctx), p("nom"), ".mp3", ".wav")
                if (f == null) return JSONObject().put("ok", false).put("erreur", "enregistrement introuvable")
                Thread { runCatching { fr.f4ioz.satcombo.sstv.SstvHub.decodeFile(ctx, f, image?.second) } }.apply { isDaemon = true }.start()
                OK
            }
            "/api/sstv/supprime" -> {
                val ctx = vm.getApplication<android.app.Application>()
                val f = ApiSon.fichier(fr.f4ioz.satcombo.sstv.SstvHub.dir(ctx), p("nom"), ".png") ?: return JSONObject().put("ok", false).put("erreur", "image introuvable")
                fr.f4ioz.satcombo.sstv.SstvHub.delete(f); OK
            }
            "/api/sstv/gif" -> {
                val ctx = vm.getApplication<android.app.Application>()
                val (png, shot) = fr.f4ioz.satcombo.sstv.SstvHub.shots(ctx).firstOrNull { it.first.name == p("nom") }
                    ?: return JSONObject().put("ok", false).put("erreur", "image introuvable")
                if (gifEnCours.isNotEmpty()) return JSONObject().put("ok", false).put("erreur", "un GIF est déjà en cours")
                gifEnCours = png.name
                Thread { try { fr.f4ioz.satcombo.sstv.SstvVideo.fabriqueGif(ctx, png, shot) } finally { gifEnCours = "" } }.apply { isDaemon = true }.start()
                OK
            }
            "/api/montage", "/api/montage/photo" -> JSONObject().put("ok", false).put("erreur", MONTAGE_SUR_PC)
            "/api/memoires-sdr", "/api/memoires-sdr/import" -> JSONObject().put("ok", false).put("erreur", "les mémoires du récepteur se gardent sur SatMe-PC")
            "/api/molette" -> JSONObject().put("ok", false).put("erreur", "absent")
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
        val masque = vm.masqueHorizon()
        val pour = "${obs.latDeg},${obs.lonDeg},${u.satellites.size},${u.minElevDeg},${masque.texte()}"
        if ((maintenant - passagesDe > 5 * 60_000L || pour != passagesPour) && passagesEnCours.compareAndSet(false, true)) {
            Thread {
                try {
                    val p = vm.predicteurPourPage()
                    val debut = System.currentTimeMillis()
                    passagesCalcules = u.satellites.flatMap { s ->
                        if (!fr.f4ioz.satcombo.domain.PassPredictor.elementsUtilisables(s)) emptyList()
                        else runCatching { p.upcomingPasses(s, obs, debut - 15 * 60_000L, 24, u.minElevDeg.toDouble(), masque) }
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

    /** When the page last had the node's status followed again. */
    @Volatile private var datvRevu = 0L

    /** The METEOR preview seen, and how often it changed: the page reloads it when this moves. */
    @Volatile private var dernierApercuMeteor: Any? = null
    @Volatile private var apercusMeteor = 0

    /** The last spectrum seen, and how many: the page draws a new line of its waterfall only for a new one. */
    @Volatile private var dernierSpectre: FloatArray? = null
    @Volatile private var tramesSpectre = 0L

    /** The phone's dongle in SatMe-PC's words (its SDR tab). */
    private fun sdr(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        val statut = when {
            st.awaitingPermission -> "à autoriser sur le téléphone"
            st.running -> "réception"
            st.connected -> "clé branchée"
            else -> "aucune clé branchée"
        }
        return JSONObject().put("actif", st.running).put("statut", statut).put("erreur", st.error.orEmpty()).put("tuner", st.deviceName.orEmpty())
            .put("ciel", vm.cleVersSat(st.centerHz)).put("repos", vm.sdrRestHz()).put("pll", st.centerHz - st.dopplerFineHz)
            .put("fine", st.dopplerFineHz + st.offsetHz).put("decalage", st.offsetHz)
            .put("niveau", Math.round(st.levelDb * 10) / 10.0).put("saturation", if (st.clipping) 1.0 else 0.0)
            .put("debit", Math.round(st.mbRead * 1024)).put("perdus", 0).put("satellite", st.satName ?: u.selected?.name.orEmpty())
            .put("gain", vm.sdrGainReglage()).put("agc", u.sdrAgc).put("ppm", u.sdrPpm).put("mode", vm.sdrMode().name).put("bw", u.sdrBandwidthHz)
            .put("squelch", u.sdrSquelchDb).put("son", u.sdrAudio).put("deemph", u.sdrDeemph).put("doppler", u.sdrDopplerTrack)
            .put("suit", true).put("manuelle", 0).put("hote", "").put("port", 0).put("lance", false).put("bias", false)
    }

    /** The phone's sound cleaning, in SatMe-PC's words. */
    private fun nettoyage(vm: MainViewModel): JSONObject {
        val h = fr.f4ioz.satcombo.sdr.SdrHub
        return ApiSdr.nettoyage(vm.sdrMode(), fr.f4ioz.satcombo.sdr.RxCleaning.parMode(vm.settingsStore().sdrNettoyage), h.notchesVus, h.tauxBlanc)
    }

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

    /** The QO-100 cockpit's state, in SatMe-PC's words ([ApiQo100.trafic]). */
    private fun trafic(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        val q = u.qo100
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        val ctx = fr.f4ioz.satcombo.domain.TraficQo100.contexte(q.aLaCle, st.running, q.auPoste, q.descenteHz, "SSB",
            u.selected?.name, u.selected?.catalogNumber ?: 0)
        val r = ApiQo100.Recepteur(true, st.running, st.deviceName ?: "clé SDR du téléphone",
            if (st.running) "réception" else if (st.connected) "clé branchée" else "aucune clé branchée", st.error.orEmpty(),
            Math.round(st.levelDb * 10) / 10.0, vm.sdrMode().name, u.sdrAudio, u.sdrSquelchDb, u.sdrAgc)
        return ApiQo100.trafic(ctx, q.aLaCle, q.auPoste, r, enCours = false, repriseAuto = null, classifieur = false,
            calageAuto = null, ecartBaliseHz = null, serveur = "telephone")
    }

    /** The automatic mode, in SatMe-PC's words ([ApiVeille]). */
    private fun veille(vm: MainViewModel): JSONObject {
        val u = vm.ui.value
        return ApiVeille.json(vm.settingsStore(), { n -> u.satellites.firstOrNull { it.catalogNumber == n }?.name }, vm.veille.plan.value,
            System.currentTimeMillis(), ApiVeille.Pret(true, u.catConnected, u.rotorConnected, u.rotorEnabled, vm.veille.calcul))
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
            enregistrements(ctx), fr.f4ioz.satcombo.sstv.SstvHub.shots(ctx).map { it.second }.sortedByDescending { it.timeMs },
            vm.enregCoupeLos(), detail = sstvDetail(s, r.recording), dossierImages = fr.f4ioz.satcombo.sstv.SstvHub.dir(ctx)))
    }

    /** A GIF being made from the page (the picture's name). */
    @Volatile private var gifEnCours = ""

    /** The phone's SSTV decoder in SatMe-PC's words: its settings and state (the source is the recorder or the dongle). */
    private fun sstvDetail(s: fr.f4ioz.satcombo.sstv.SstvHub.SstvState, enregistre: Boolean): ApiSon.Sstv = ApiSon.Sstv(
        source = "", sourceActive = if (s.listening) (if (fr.f4ioz.satcombo.sdr.SdrHub.state.value.running && !enregistre) "clé SDR du téléphone" else "enregistrement du téléphone") else "",
        depuisMs = if (s.listening) 0L else -1L,
        niveau = if (enregistre) fr.f4ioz.satcombo.audio.MoniteurAudio.etat.value.crete else 0f,
        force = s.forcedMode.orEmpty(), continu = s.continu, nettoyage = s.nettoyage, decodage = s.decoding, progres = s.progress,
        gardees = s.savedCount, erreur = s.erreur.orEmpty(),
        conseil = if (s.listening) "" else "Le téléphone décode en enregistrant : « Enregistrer » (onglet Son), ou avec sa clé SDR.",
        redecode = if (s.fileProgress >= 0f) s.fileName.orEmpty() else "", redecodeProgres = s.fileProgress, redecodeImages = s.fileImages,
        gif = gifEnCours,
        calage = ApiSon.Calage(actif = s.afc, mesureHz = if (s.listening) s.afcMesureHz else null, totalHz = s.afcTotalHz,
            applique = s.afc && s.afcApplique,
            voie = if (!s.listening) "" else if (fr.f4ioz.satcombo.sdr.SdrHub.state.value.running && !enregistre) "décalage fin de la clé SDR du téléphone"
                else "poste par CAT du téléphone, avec les écritures du Doppler",
            conseil = if (s.listening) fr.f4ioz.satcombo.ui.afcConseil(s).orEmpty() else ""))

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
            chemin.startsWith("/meteor/") -> ApiSon.fichier(fr.f4ioz.satcombo.meteor.MeteorHub.dir(ctx), nom("/meteor/"), ".png")
                ?.let { ReponseWeb("200 OK", "image/png", fichier = it) }
            chemin.startsWith("/sondes/") -> ApiSon.fichier(fr.f4ioz.satcombo.sonde.SondeHub.logDir(ctx), nom("/sondes/"), ".csv", ".kml", ".gpx", ".txt", ".log")
                ?.let { ReponseWeb("200 OK", "application/octet-stream", fichier = it, entetes = "Content-Disposition: attachment; filename=\"${it.name}\"\r\n") }
            chemin.startsWith("/sstv/") -> ApiSon.fichier(fr.f4ioz.satcombo.sstv.SstvHub.dir(ctx), nom("/sstv/"), ".png", ".wav", ".gif")
                ?.let { ReponseWeb("200 OK", when (it.extension) { "wav" -> "audio/wav"; "gif" -> "image/gif"; else -> "image/png" }, fichier = it) }
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
        o.put("amsatAuto", u.carnet.amsatAuto).put("amsatEtat", u.carnet.amsatEtat)
        o.put("convertisseurs", JSONObject().put("rxOl", if (u.convRx.actif) u.convRx.olHz else 0).put("rxInv", u.convRx.inverseur)
            .put("txOl", if (u.convTx.actif) u.convTx.olHz else 0).put("txInv", u.convTx.inverseur))
        o.put("cat", ApiStation.cat(vm.etatBoucleCat(), u.rigModel, u.civUsbIndex, u.catConnected, false, u.catStatus, u.dopplerHold))
        o.put("rotor", ApiStation.rotor(u.rotor.mat))
        // The log's pass, as SatMe-PC gives it: the skeds announced for it, the contacts of the one under way (or just over).
        if (sat != null && passage != null) {
            val c = if (passage.aosEpochMs <= maintenant) passage else u.passes.filter { it.catalogNumber == sat.catalogNumber &&
                it.losEpochMs <= maintenant && it.losEpochMs > maintenant - 3_600_000L }.maxByOrNull { it.losEpochMs } ?: passage
            val skeds = if (u.skedsEnabled) vm.skedsForPass(sat.catalogNumber, passage.aosEpochMs, passage.losEpochMs) else emptyList()
            o.put("passageCarnet", ApiStation.passageCarnet(passage.aosEpochMs, passage.losEpochMs, skeds, c.aosEpochMs, c.losEpochMs,
                fr.f4ioz.satcombo.domain.SkedsPassage.contacts(u.log, sat.catalogNumber, sat.name, c.aosEpochMs, c.losEpochMs)))
        }
        o.put("skedsOn", u.skedsEnabled)
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
