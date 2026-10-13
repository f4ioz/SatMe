/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ApiQo100
import fr.f4ioz.satcombo.demo.ApiSon
import fr.f4ioz.satcombo.demo.PagePc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.demo.RequeteHttp
import org.json.JSONArray
import org.json.JSONObject
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.sdr.Dsp
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * The PC station's page and its API, on `http://localhost:8090/` by default.
 *
 * Hand-written like the phone's server (a handful of routes). **On this PC
 * only** unless asked ([reseau]): the page drives a rig and a mast, and
 * nobody else on the network should. What changes the station is a POST,
 * never a GET a browser could fetch ahead by itself.
 */
class ServeurPc(private val station: StationPc, private val port: Int, private val reseau: Boolean = false,
                private val carnet: CarnetPc? = null, private val son: SonStation? = null,
                private val journal: JournalWebPc? = null, private val importPc: ImportPc? = null,
                private val sdr: SdrPc? = null, private val videoSked: VideoSked? = null,
                private val telephone: TelephonePc? = null, private val appairage: AppairagePc? = null,
                private val datv: DatvPc? = null, private val planche: PlancheWebPc? = null) {

    private var socket: ServerSocket? = null
    val portReel: Int get() = socket?.localPort ?: port

    fun demarre() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(if (reseau) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        socket = s
        thread(isDaemon = true, name = "satme-web") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "satme-web-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
    }

    fun arrete() { runCatching { socket?.close() } }

    private fun sert(c: Socket) {
        c.soTimeout = 15_000
        val r = RequeteHttp.lit(c.getInputStream()) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = parametres(r.chemin.substringAfter('?', ""))
        val sortie = c.getOutputStream()
        if (chemin == "/api/datv/video.mjpeg" && datv != null) {
            // The DATV pictures as they come, one JPEG after the other (an <img> shows them as a video).
            c.soTimeout = 0
            sortie.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=satme\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            var vu: ByteArray? = null
            var calme = 0
            while (true) {
                val img = datv.image
                if (img != null && img !== vu) {
                    vu = img; calme = 0
                    val ok = runCatching {
                        sortie.write(("--satme\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n").toByteArray())
                        sortie.write(img); sortie.write("\r\n".toByteArray()); sortie.flush()
                    }.isSuccess
                    if (!ok) return
                } else {
                    // Nothing new for a long while and the reception stopped: the page asks again when it starts.
                    if (!datv.etat.actif && ++calme > 600) return
                    Thread.sleep(20)
                }
            }
        }
        // The SSTV sheet: its page, and its routes (some POST a template or a picture).
        if (planche != null && (chemin == "/planche" || chemin == "/planche/")) {
            envoie(sortie, "200 OK", "text/html; charset=utf-8", planche.page().toByteArray()); return
        }
        if (planche != null && chemin.startsWith("/planche/")) {
            val rep = planche.sert(chemin.removePrefix("/planche"), r.methode, { q[it].orEmpty() }) {
                RequeteHttp.corps(c.getInputStream(), r, PlancheWebPc.IMPORT_MAX)
            }
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        if (r.methode == "POST" && chemin == "/api/telephone/zip") {
            // A pass file from the phone (up to 300 MB with its sound): read as it comes, never held whole.
            val t = telephone
            val b = if (t == null || r.longueur <= 0 || r.longueur > JournalPaquet.MAX_OCTETS) null
                else runCatching { t.importeZip(Borne(c.getInputStream(), r.longueur), q["enLigne"] != "0") }.getOrNull()
            envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.bilanTelephone(b).toString().toByteArray())
            return
        }
        if (r.methode == "POST" && chemin == "/api/import/adif") {
            val corps = RequeteHttp.corps(c.getInputStream(), r, 50L * 1024 * 1024)
            val b = if (corps == null || importPc == null) null else importPc.depuisAdif(String(corps, Charsets.UTF_8), q["enLigne"] == "1")
            envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.bilan(b).toString().toByteArray())
            return
        }
        if (r.methode == "POST" && chemin == "/api/import") {
            // The phone's backup, sent as the request's body (a few hundred kilobytes with the log).
            val corps = RequeteHttp.corps(c.getInputStream(), r, 20L * 1024 * 1024)
            val res = if (corps == null) JSONObject().put("ok", false).put("erreur", "fichier trop gros ou coupé")
                else station.importeTelephone(String(corps, Charsets.UTF_8), carnet?.store).fold(
                    { JSONObject().put("ok", true).put("n", it.first).put("contacts", it.second) },
                    { JSONObject().put("ok", false).put("erreur", it.message ?: "fichier illisible") })
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        // The phone's journal page, from the PC's passes.
        if (journal != null && (chemin == "/journal" || chemin == "/journal/")) {
            envoie(sortie, "200 OK", "text/html; charset=utf-8", journal.page().toByteArray()); return
        }
        if (journal != null && chemin.startsWith("/journal/")) {
            val rep = journal.sert(chemin.removePrefix("/journal"), { q[it].orEmpty() }) { f ->
                // A pass's recording or picture, written to disk as it comes.
                r.methode == "POST" && RequeteHttp.versFichier(c.getInputStream(), r, fr.f4ioz.satcombo.domain.NouveauPassage.OCTETS_MAX, f)
            }
            val f = rep.fichier
            if (f != null) envoieFichier(sortie, r, f, rep.type, rep.entetes)
            else envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes)
            return
        }
        if (r.methode == "POST") {
            val res = runCatching { action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        when (chemin) {
            "/", "/index.html", "/pc" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PagePc.HTML.toByteArray())
            // Every tab here; the phone offers some of them.
            "/api/capacites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                JSONObject().put("onglets", JSONArray(PagePc.TOUS)).put("serveur", "pc").toString().toByteArray())
            "/api/etat" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.etat(station).toString().toByteArray())
            "/api/passages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.passages(station).toString().toByteArray())
            "/api/satellites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.satellites(station).toString().toByteArray())
            "/api/ports" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.ports(station).toString().toByteArray())
            "/api/trace" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject()
                .put("catnum", station.etat.value.suivi?.catalogNumber ?: 0)
                // The track starts an eighth of an orbit ago: the point for now is the 20th of 160.
                .put("ici", 20)
                .put("points", JSONArray(station.traceAuSol().map { (la, lo) -> JSONArray(listOf(Math.round(la * 100) / 100.0, Math.round(lo * 100) / 100.0)) }))
                .toString().toByteArray())
            "/api/qo100/panorama" -> {
                val d = sdr
                val actif = d != null && d.etat.value.actif
                if (actif) d!!.demandePanorama()
                val o = ApiQo100.panorama(actif, d?.panorama, d?.axeQo100(), q["n"]?.toIntOrNull() ?: 512, d?.panoramaTrames ?: 0L, if (actif) d!!.mesureBalise() else null)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/qo100" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.qo100(station, q["plan"] == "1").toString().toByteArray())
            "/api/sdr" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (sdr?.let { JsonPc.sdr(it) } ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/sdr/spectre" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (sdr?.let { JsonPc.spectre(it, q["span"]?.toIntOrNull() ?: 48_000, q["n"]?.toIntOrNull() ?: 512) } ?: JSONObject()).toString().toByteArray())
            "/api/sondes" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (sdr?.let { JsonPc.sondes(it) } ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/meteor" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (sdr?.let { JsonPc.meteor(it) } ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/meteor/apercu.png" -> {
                val png = sdr?.meteor?.apercuPng()
                if (png != null) envoie(sortie, "200 OK", "image/png", png) else envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "pas d'aperçu".toByteArray())
            }
            "/api/sked/video" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (videoSked?.etat?.let { e ->
                JSONObject().put("enCours", e.enCours).put("progression", e.progression.toDouble()).put("nom", e.nom).put("erreur", e.erreur) }
                ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/telephone/liste" -> {
                val t = telephone
                val o = if (t == null || !t.connecte) JSONObject().put("ok", false)
                    else runCatching { t.liste().put("ok", true).put("adresse", t.adresse).put("version", t.version) }
                        .getOrElse { JSONObject().put("ok", false).put("erreur", "téléphone injoignable") }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/telephone/qr" -> {
                // The QR code's progress: waiting, the phone joined, or why not.
                val e = appairage?.verifie() ?: AppairagePc.Etat(erreur = "indisponible")
                val o = JSONObject().put("ouvert", e.ouvert).put("joint", e.joint).put("telephone", e.telephone).put("erreur", e.erreur)
                    .put("connecte", telephone?.connecte == true).put("adresse", telephone?.adresse.orEmpty()).put("version", telephone?.version.orEmpty())
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/telephone/etat" -> {
                val r2 = telephone?.rapatriement
                val o = JSONObject().put("enCours", r2?.enCours == true).put("fait", r2?.fait ?: 0).put("total", r2?.total ?: 0)
                    .put("bilans", JSONArray(r2?.bilans.orEmpty().map { JsonPc.bilanTelephone(it) }))
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/datv" -> {
                val d = datv
                val o = if (d == null) JSONObject().put("ok", false) else JsonPc.datv(d)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/datv/image.jpg" -> {
                val img = datv?.image
                if (img == null) envoie(sortie, "404 Not Found", "text/plain", "pas d'image".toByteArray())
                else envoie(sortie, "200 OK", "image/jpeg", img, "Cache-Control: no-store\r\n")
            }
            "/api/frise" -> {
                val (debut, lignes) = kotlinx.coroutines.runBlocking { station.frise((q["h"]?.toIntOrNull() ?: 12).coerceIn(2, 48)) }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", fr.f4ioz.satcombo.demo.ApiCalculs.friseJson(debut, lignes).toString().toByteArray())
            }
            "/api/sked" -> {
                val f = kotlinx.coroutines.runBlocking { station.sked(q["catnum"]?.toIntOrNull() ?: 0, q["loc"].orEmpty(), q["minEl"]?.toDoubleOrNull() ?: 0.0) }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", fr.f4ioz.satcombo.demo.ApiCalculs.skedJson(f).toString().toByteArray())
            }
            "/api/sked/trace" -> {
                val tr = kotlinx.coroutines.runBlocking { station.traceSked(q["catnum"]?.toIntOrNull() ?: 0, q["loc"].orEmpty(),
                    q["de"]?.toLongOrNull() ?: 0L, q["a"]?.toLongOrNull() ?: 0L) }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", fr.f4ioz.satcombo.demo.ApiCalculs.traceSkedJson(tr).toString().toByteArray())
            }
            "/api/wavelog/profils" -> {
                val (l, err) = importPc?.let { kotlinx.coroutines.runBlocking { it.profils() } } ?: (emptyList<fr.f4ioz.satcombo.domain.ProfilsStation.Profil>() to "indisponible")
                envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("erreur", err ?: "")
                    .put("profils", JSONArray(l.map { JSONObject().put("id", it.id).put("carre", it.carre).put("indicatif", it.indicatif).put("nom", it.nom) })).toString().toByteArray())
            }
            "/api/carres" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiCalculs.carres(carnet?.contacts().orEmpty(), station.etat.value.locator, station.etat.value.observateur).toString().toByteArray())
            "/api/carnet/propositions" -> {
                val saisie = q["c"].orEmpty()
                val o = carnet?.let { c ->
                    val (etat, l) = c.propositions(saisie)
                    JSONObject().put("etat", etat.name).put("propositions", JSONArray(l.map { k ->
                        JSONObject().put("c", k.indicatif).put("nm", k.nom).put("n", k.contacts).put("sat", k.dernierSat)
                            .put("l", fr.f4ioz.satcombo.domain.Indicatifs.locatorPropose(k, k.indicatif)) }))
                } ?: JSONObject().put("etat", "VIDE").put("propositions", JSONArray())
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/carnet/station" -> {
                val l = carnet?.historique(q["c"].orEmpty()) ?: emptyList()
                val o = fr.f4ioz.satcombo.demo.ApiStation.historiqueJson(l, station.etat.value.observateur, station.ficheQrz)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/carnet/passages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiCalculs.passagesFinisJson(station.passagesFinis(q["catnum"]?.toIntOrNull() ?: 0)).toString().toByteArray())
            "/api/carnet" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carnet(carnet).toString().toByteArray())
            "/api/reglages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.reglages(station).toString().toByteArray())
            "/api/sources" -> {
                // The orbital elements' choice, as on the phone.
                val c = station.choixSources()
                val o = fr.f4ioz.satcombo.demo.ApiStation.sources(c.serveur, c.seul, c.sources, station.etat.value.catalogue,
                    station.etat.value.satellites.size, celestrakParServeur = true)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/qrz" -> {
                // Read only (a directory lookup), so a GET; nothing changes in the station.
                val f = carnet?.let { kotlinx.coroutines.runBlocking { it.ficheQrz(q["c"].orEmpty()) } }
                val o = fr.f4ioz.satcombo.demo.ApiStation.qrz(f, station.etat.value.observateur, station.ficheQrz)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/ft8" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.ft8(son, station, carnet).toString().toByteArray())
            "/api/aprs" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.aprs(son, station).toString().toByteArray())
            "/api/son" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.son(son).toString().toByteArray())
            "/api/sstv/encours.png" -> son?.imageEnCours()?.let { envoie(sortie, "200 OK", "image/png", it) }
                ?: envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            "/carnet.adi" -> {
                val nom = "SatMe_carnet_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".adi"
                envoie(sortie, "200 OK", "text/plain; charset=utf-8", (carnet?.adif() ?: "").toByteArray(),
                    "Content-Disposition: attachment; filename=\"$nom\"\r\n")
            }
            else -> {
                // A recording (seekable, by parts) or an SSTV picture.
                val f = when {
                    chemin.startsWith("/enregistrements/") -> son?.fichierEnregistrement(java.net.URLDecoder.decode(chemin.removePrefix("/enregistrements/"), "UTF-8"))
                    chemin.startsWith("/sstv/") -> son?.fichierImage(java.net.URLDecoder.decode(chemin.removePrefix("/sstv/"), "UTF-8"))
                    // A sonde's log or track: only a plain name, inside its folder.
                    chemin.startsWith("/sked-video/") -> videoSked?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/sked-video/"), "UTF-8"))
                    chemin.startsWith("/meteor/") -> sdr?.meteor?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/meteor/"), "UTF-8"))
                    chemin.startsWith("/sondes/") -> java.net.URLDecoder.decode(chemin.removePrefix("/sondes/"), "UTF-8")
                        .takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }?.let { n -> sdr?.sondes?.journaux()?.firstOrNull { it.name == n } }
                    else -> null
                }
                if (f != null) envoieFichier(sortie, r, f, typeDonne = if (f.name.endsWith(".mp4")) "video/mp4" else null,
                    entetes = if (chemin.startsWith("/sondes/") || chemin.startsWith("/sked-video/")) "Content-Disposition: attachment; filename=\"${f.name}\"\r\n" else "")
                else envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "introuvable".toByteArray())
            }
        }
    }

    /** What a POST asks; the answer says whether it was done. */
    private fun action(chemin: String, q: Map<String, String>): JSONObject {
        val ok = JSONObject().put("ok", true)
        val rotor = station.stationRotor
        fun entier(k: String) = q[k]?.trim()?.toIntOrNull()
        when (chemin) {
            "/api/suivre" -> return ok.put("ok", entier("catnum")?.let { station.suis(it) } == true)
            "/api/emetteur" -> station.choisitEmetteur(entier("i") ?: 0)
            "/api/favori" -> entier("catnum")?.let { station.favori(it, q["on"] == "1") }
            "/api/sat/reglage" -> {
                val e = station.etat.value
                station.reglageSat(calibHz = q["calib"]?.toLongOrNull()?.let { e.calibHz + it },
                    txHz = q["tx"]?.toLongOrNull()?.let { e.txShiftHz + it },
                    memorise = q["memorise"] == "1", retour = q["retour"] == "1")
            }
            "/api/agenda/passage" -> {
                val n = entier("catnum"); val aos = q["aos"]?.toLongOrNull()
                val p = station.etat.value.let { e -> (e.passages + e.passagesSuivi).firstOrNull { it.catalogNumber == n && it.aosEpochMs == aos } }
                    ?: return JSONObject().put("ok", false).put("erreur", "passage inconnu")
                return ok.put("ajoute", station.basculeAgenda(p))
            }
            "/api/agenda/sked" -> {
                station.skedAgenda(entier("catnum") ?: 0, q["loc"].orEmpty(), q["de"]?.toLongOrNull() ?: 0L, q["a"]?.toLongOrNull() ?: 0L)
            }
            "/api/wavelog/importe" -> {
                val i = importPc ?: return JSONObject().put("ok", false)
                val ids = q["profils"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                return JsonPc.bilan(kotlinx.coroutines.runBlocking { i.depuisEnLigne(ids) })
            }
            "/api/agenda/retire" -> q["id"]?.toLongOrNull()?.let { station.retireAgenda(it) }
            "/api/agenda/active" -> q["id"]?.toLongOrNull()?.let { station.activeAgenda(it) }
            "/api/agenda/suivi" -> station.agendaSuivi(q["on"] == "1")
            "/api/amsat/rapport" -> {
                // Heard or not, on amsat.org's public page: the satellite followed, my callsign and square, now.
                val e = station.etat.value
                val sat = e.suivi ?: return JSONObject().put("ok", false).put("erreur", "aucun satellite suivi")
                if (e.indicatif.isBlank()) return JSONObject().put("ok", false).put("erreur", "renseigne ton indicatif (Réglages)")
                val nom = station.statutAmsat(sat.name)?.name ?: sat.name
                val envoye = kotlinx.coroutines.runBlocking {
                    fr.f4ioz.satcombo.data.AmsatSubmit.send(nom, if (q["entendu"] == "1") fr.f4ioz.satcombo.data.AmsatSubmit.Report.HEARD
                        else fr.f4ioz.satcombo.data.AmsatSubmit.Report.NOT_HEARD, e.indicatif, e.locator)
                }
                if (envoye) station.rafraichisAmsat()
                return JSONObject().put("ok", envoye).put("nom", nom).put("erreur", if (envoye) "" else "amsat.org n'a pas pris le rapport")
            }
            "/api/repos" -> station.deplaceRepos(q["d"]?.toLongOrNull() ?: 0L)
            "/api/maintien" -> station.maintien(q["on"] == "1")
            "/api/locator" -> return ok.put("ok", station.poseLocator(q["l"].orEmpty()))
            "/api/indicatif" -> station.poseIndicatif(q["c"].orEmpty())
            "/api/catalogue" -> station.rafraichisCatalogue()
            "/api/cat/connecter" -> station.connecteCat(StationPc.ChoixCat(q["modele"].orEmpty(),
                port = entier("port") ?: -1, baud = entier("baud") ?: 0, portTx = entier("portTx") ?: -1,
                rxIc705 = q["rxIc705"] == "1", txIc705 = q["txIc705"] == "1", bandeTx = entier("bandeTx") ?: 0))
            "/api/cat/deconnecter" -> station.deconnecteCat()
            "/api/rotor/connecter" -> {
                val lien = if (q["lien"] == "ROTCTLD") "ROTCTLD" else "GS232"
                rotor.setRotorSim(q["sim"] == "1")
                rotor.setRotorLink(lien)
                q["hote"]?.takeIf { it.isNotBlank() }?.let { rotor.setRotorHost(it.trim()) }
                entier("tcp")?.let { rotor.setRotorPort(it) }
                entier("port")?.let { rotor.setRotorUsbIndex(it) }
                entier("baud")?.let { rotor.setRotorBaud(it) }
                rotor.setRotorAutoPort(false)
                rotor.connectRotor()
            }
            "/api/rotor/deconnecter" -> rotor.disconnectRotor()
            "/api/rotor/suivre" -> rotor.setRotorEnabled(q["on"] == "1")
            "/api/rotor/parquer" -> rotor.rotorParkNow()
            "/api/rotor/stop" -> rotor.rotorStopNow()
            "/api/sdr/demarrer" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                if (!d.demarre()) return JSONObject().put("ok", false).put("erreur", d.etat.value.erreur)
            }
            "/api/sdr/arreter" -> sdr?.arrete()
            "/api/sdr/decalage" -> q["hz"]?.toIntOrNull()?.let { sdr?.decalageHz = it }
            "/api/sdr/pic" -> sdr?.calePic()
            "/api/sdr/reglage" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val st = station.settings
                q["gain"]?.toIntOrNull()?.let { d.gain(it.takeIf { g -> g >= 0 }) }
                q["agc"]?.let { d.agc(it == "1") }
                q["ppm"]?.toIntOrNull()?.let { d.ppm(it) }
                q["mode"]?.let { d.modeChoisi = it }
                q["bw"]?.toIntOrNull()?.let { st.sdrBandwidthHz = it.coerceIn(0, 24_000) }
                q["squelch"]?.toIntOrNull()?.let { st.sdrSquelchDb = it.coerceIn(-120, 0) }
                q["son"]?.let { st.sdrAudio = it == "1" }
                q["deemph"]?.let { st.sdrDeemph = it == "1" }
                q["doppler"]?.let { st.sdrDopplerTrack = it == "1" }
                q["suit"]?.let { d.suitSatellite = it == "1" }
                q["mhz"]?.replace(',', '.')?.toDoubleOrNull()?.let { d.manuelleHz = Math.round(it * 1e6); d.decalageHz = 0 }
                q["hote"]?.let { d.hote = it }
                q["port"]?.toIntOrNull()?.let { d.port = it }
                q["lance"]?.let { d.lance = it == "1" }
                q["bias"]?.let { d.biasTee = it == "1" }
            }
            "/api/sondes/demarrer" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                if (!d.demarreSondes()) return JSONObject().put("ok", false).put("erreur", d.etat.value.erreur)
            }
            "/api/sondes/arreter" -> sdr?.arrete()
            "/api/sondes/frequence" -> q["khz"]?.replace(',', '.')?.toDoubleOrNull()?.let { sdr?.frequenceSonde(Math.round(it * 1000)) }
            "/api/sondes/pas" -> q["n"]?.toIntOrNull()?.let { n -> sdr?.frequenceSonde(station.settings.sondeFreqHz + n * fr.f4ioz.satcombo.sonde.SondeSites.SCAN_STEP_HZ) }
            "/api/sondes/modele" -> q["m"]?.takeIf { m -> fr.f4ioz.satcombo.sonde.SondeModel.ALL.any { it.id == m } }?.let { sdr?.modeleSonde(it) }
            "/api/sondes/recentre" -> sdr?.recentre()
            "/api/sondes/auto" -> sdr?.recentreAuto = q["on"] == "1"
            "/api/sondes/oublie" -> sdr?.sondes?.oublieVol()
            "/api/sondes/export" -> {
                val f = sdr?.sondes?.exporte(q["kml"] == "1") ?: return JSONObject().put("ok", false).put("erreur", "Pas de vol à exporter.")
                return JSONObject().put("ok", true).put("nom", f.name)
            }
            "/api/meteor/demarrer" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                // The followed satellite if it is a METEOR, otherwise the one of the next pass.
                if (!station.estMeteor()) {
                    val (sat, _) = station.prochainPassageMeteor(48) ?: return JSONObject().put("ok", false).put("erreur", "Aucun METEOR-M LRPT dans le catalogue.")
                    station.suis(sat)
                }
                d.suitSatellite = true
                if (!d.demarre()) return JSONObject().put("ok", false).put("erreur", d.etat.value.erreur)
            }
            "/api/meteor/arreter" -> sdr?.arrete()
            "/api/meteor/auto" -> station.settings.meteorAuto = q["on"] == "1"
            "/api/meteor/cotes" -> { station.settings.meteorCotes = q["on"] == "1"; sdr?.meteor?.recepteur?.cotes = station.settings.meteorCotes }
            "/api/meteor/enregistre" -> {
                val m = sdr?.meteor ?: return JSONObject().put("ok", false)
                val noms = m.ecrit(m.recepteur.imagesMaintenant())
                if (noms.isEmpty()) return JSONObject().put("ok", false).put("erreur", "Pas encore assez de lignes (80 au moins).")
            }
            "/api/meteor/fichier" -> {
                val m = sdr?.meteor ?: return JSONObject().put("ok", false)
                m.decodeFichier(q["chemin"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/meteor/annule" -> sdr?.meteor?.recepteur?.annuleFichier()
            "/api/meteor/supprime" -> sdr?.meteor?.supprime(q["nom"].orEmpty())
            "/api/sked/video" -> {
                val v = videoSked ?: return JSONObject().put("ok", false)
                fun l(k: String) = q[k]?.toLongOrNull() ?: 0L
                val catnum = entier("catnum") ?: 0
                val loc = q["loc"].orEmpty().trim().uppercase()
                val tr = kotlinx.coroutines.runBlocking { station.traceSked(catnum, loc, l("debutTrace"), l("finTrace")) }
                    ?: return JSONObject().put("ok", false).put("erreur", "sked introuvable")
                val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == catnum }?.name ?: "?"
                if (!v.lance(VideoSked.Sked(tr, l("de"), l("a"), sat, station.settings.callsign.ifBlank { station.etat.value.locator }.uppercase(), loc)))
                    return JSONObject().put("ok", false).put("erreur", v.etat.erreur.ifBlank { "une vidéo est déjà en cours" })
            }
            "/api/telephone/qr" -> {
                // A QR code for the phone to scan: this PC's address and a fresh token.
                val a = appairage ?: return JSONObject().put("ok", false).put("erreur", "indisponible")
                val e = a.ouvre(q["adresse"])
                if (!e.ouvert) return JSONObject().put("ok", false).put("erreur", e.erreur)
                return ok.put("url", e.url).put("svg", AppairagePc.svg(e.url)).put("adresses", JSONArray(e.adresses))
                    .put("ip", e.url.substringAfter("://").substringBefore(':')).put("jusquA", e.jusquA)
            }
            "/api/telephone/qr/ferme" -> appairage?.ferme()
            "/api/datv/demarre" -> {
                val d = datv ?: return JSONObject().put("ok", false)
                val r0 = d.reglage
                fun khz(k: String) = q[k]?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 1000.0) }
                val r = r0.copy(cielKhz = khz("ciel") ?: r0.cielKhz, srKs = q["sr"]?.toIntOrNull() ?: r0.srKs, olKhz = khz("ol") ?: r0.olKhz,
                    entreeB = q["entree"]?.let { it == "b" } ?: r0.entreeB, lnb = q["lnb"]?.takeIf { it in setOf("", "v", "h") } ?: "",
                    son = q["son"]?.let { it == "1" } ?: r0.son)
                d.demarre(r)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/datv/arrete" -> datv?.arrete()
            "/api/datv/enregistre" -> return ok.put("nom", datv?.enregistre(q["on"] == "1").orEmpty())
            "/api/datv/vlc" -> datv?.ouvreVlc()?.let { return JSONObject().put("ok", false).put("erreur", it) }
            "/api/datv/chemin" -> datv?.chemin = q["p"].orEmpty()
            "/api/datv/noeud" -> {
                // The MiniTiouner on a SatMe-Node (address and its code), or back on this PC (no address).
                val d = datv ?: return JSONObject().put("ok", false)
                d.rejoins(q["adresse"].orEmpty(), q["code"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/datv/cale" -> {
                // The beacon where the MiniTiouner found it: the LNB's oscillator corrected so that it falls where it is.
                val d = datv ?: return JSONObject().put("ok", false)
                val e = d.etat
                if (!e.verrouille || e.fiTrouveeKhz <= 0) return JSONObject().put("ok", false).put("erreur", "accrocher d'abord un signal (la balise)")
                val ecart = e.fiTrouveeKhz - d.reglage.fiKhz
                d.demarre(d.reglage.copy(olKhz = d.reglage.olKhz - ecart))?.let { return JSONObject().put("ok", false).put("erreur", it) }
                return ok.put("ecart", ecart)
            }
            "/api/telephone/connecte" -> {
                val t = telephone ?: return JSONObject().put("ok", false)
                t.connecte(q["adresse"].orEmpty(), q["code"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/telephone/rapatrie" -> {
                val t = telephone ?: return JSONObject().put("ok", false)
                val ids = q["ids"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (!t.lanceRapatriement(ids, q["enLigne"] != "0")) return JSONObject().put("ok", false).put("erreur", "rien à faire, ou déjà en cours")
            }
            "/api/qo100/frequence" -> q["hz"]?.toLongOrNull()?.let { station.qo100.descente(it) }
            "/api/qo100/pas" -> q["hz"]?.toLongOrNull()?.let { station.qo100.pas(it) }
            "/api/qo100/balise" -> station.qo100.balise()
            "/api/qo100/centre" -> station.qo100.centre()
            "/api/qo100/transpondeur" -> station.qo100.transpondeur(q["cle"].orEmpty())
            "/api/qo100/balayage" -> station.qo100.sansBride(q["on"] == "1")
            "/api/qo100/poste" -> station.qo100AuPoste(q["on"] == "1")
            "/api/qo100/memoire" -> station.qo100.poseMemoire(q["nom"].orEmpty())
            "/api/qo100/oublie" -> q["hz"]?.toLongOrNull()?.let { station.qo100.retireMemoire(it) }
            "/api/qo100/chaine" -> station.qo100.chaine(q["nom"].orEmpty())
            "/api/qo100/nouvelle" -> station.qo100.ajouteChaine(q["nom"].orEmpty())
            "/api/qo100/ol" -> q["hz"]?.toLongOrNull()?.let { station.qo100.poseOl(q["sens"] == "descente", it) }
            "/api/qo100/mesure" -> {
                // MHz on the page.
                val (bon, message) = station.qo100.mesure(q["sens"] == "descente", ApiQo100.mhz(q["ciel"]) ?: 0L, ApiQo100.mhz(q["poste"]) ?: 0L)
                return JSONObject().put("ok", bon).put("message", message)
            }
            "/api/qo100/balise-entendue" -> {
                val hz = ApiQo100.baliseEntendue(q["mhz"]) ?: return JSONObject().put("ok", false).put("message", ApiQo100.BALISE_LOIN)
                station.qo100.caleSurLaBalise(hz)
            }
            "/api/qo100/cle" -> station.qo100.aLaCle(q["on"] == "1")
            "/api/qo100/caler-mesure" -> {
                // No measurement: nothing done — certainly not a calibration back to zero.
                val m = sdr?.mesureBalise() ?: return JSONObject().put("ok", false).put("erreur", "La balise n'est pas vue par la clé.")
                station.qo100.calage(station.qo100.etat.value.calageHz + m.ecartArrondiHz)
            }
            "/api/qo100/calage-zero" -> station.qo100.annuleCalage()
            "/api/convertisseurs" -> {
                // MHz on the page, Hz here; an empty field leaves it as it is.
                fun hz(k: String) = q[k]?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                station.poseConvertisseurs(hz("rxOl"), q["rxInv"]?.let { it == "1" }, hz("txOl"), q["txInv"]?.let { it == "1" })
            }
            "/api/carnet/ajoute" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                val fiche = q["c"]?.takeIf { it.isNotBlank() }?.let { kotlinx.coroutines.runBlocking { c.ficheQrz(it) } }
                    ?.takeIf { it.erreur.isBlank() && !it.vide }
                // Afterwards: the satellite and the time given (a past pass), not the followed one now.
                val t = q["t"]?.toLongOrNull()
                val e = if (t != null) {
                    if (!fr.f4ioz.satcombo.demo.ApiCalculs.heureApresValable(t, System.currentTimeMillis()))
                        return JSONObject().put("ok", false).put("erreur", "heure hors du dernier mois")
                    c.ajouteApres(q["catnum"]?.toIntOrNull() ?: 0, t, q["c"].orEmpty(), q["l"].orEmpty(), q["rs"].orEmpty(), q["rr"].orEmpty(),
                        q["n"].orEmpty(), q["md"].orEmpty(), fiche)
                        ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite")
                } else c.ajoute(q["c"].orEmpty(), q["l"].orEmpty(), q["rs"].orEmpty(), q["rr"].orEmpty(), q["n"].orEmpty(), fiche)
                    ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite suivi")
                return ok.put("t", e.timeMs)
            }
            "/api/carnet/modifie" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                val t = q["t"]?.toLongOrNull() ?: return JSONObject().put("ok", false)
                val avant = c.contacts().firstOrNull { it.timeMs == t } ?: return JSONObject().put("ok", false)
                c.modifie(t, avant.copy(callsign = q["c"] ?: avant.callsign, theirLocator = q["l"] ?: avant.theirLocator,
                    rstSent = q["rs"] ?: avant.rstSent, rstRcvd = q["rr"] ?: avant.rstRcvd, note = q["n"] ?: avant.note,
                    mode = q["md"] ?: avant.mode, nom = q["nm"] ?: avant.nom, qth = q["qth"] ?: avant.qth,
                    locatorOrigine = q["lo"] ?: avant.locatorOrigine))
            }
            "/api/carnet/supprime" -> {
                // One contact (t=…) or a selection (t=a,b,c).
                val l = q["t"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                val c = carnet ?: return JSONObject().put("ok", false)
                return ok.put("n", if (l.isEmpty()) 0 else c.supprime(l))
            }
            "/api/carnet/depose" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                val t = q["t"]?.toLongOrNull()
                return ok.put("dit", kotlinx.coroutines.runBlocking { if (t != null) c.depose(t) else c.deposeTout() })
            }
            "/api/reglages" -> {
                val s = station.settings
                q["c"]?.let { station.poseIndicatif(it) }
                q["l"]?.takeIf { it.isNotBlank() }?.let { if (!station.poseLocator(it)) return JSONObject().put("ok", false).put("erreur", "locator non reconnu") }
                q["qrzUser"]?.let { s.qrzUser = it.trim() }
                q["qrzMdp"]?.takeIf { it.isNotEmpty() }?.let { s.qrzMdp = it }
                q["carnetUrl"]?.let { s.carnetUrl = it.trim() }
                q["carnetCle"]?.takeIf { it.isNotEmpty() }?.let { s.carnetCle = it.trim() }
                q["carnetProfil"]?.let { s.carnetProfil = it.trim() }
                q["minEl"]?.toIntOrNull()?.let { s.minElevDeg = it.coerceIn(0, 45); kotlinx.coroutines.runBlocking { station.recalcule() } }
                q["fiche"]?.let { station.ficheQrz = it == "1" }
                q["carnetDelai"]?.toIntOrNull()?.let { s.carnetAutoDelaiS = it }
                q["carnetAuto"]?.let { carnet?.setAuto(it == "1") }
                q["amsatAuto"]?.let { s.amsatAuto = it == "1" }
            }
            "/api/sources" -> station.regleSources(q["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
                q["serveur"], q["seul"]?.let { it == "1" })
            "/api/sources/essai" -> {
                // The server's groups and their counts, or why it does not answer.
                return fr.f4ioz.satcombo.demo.ApiStation.essaiServeurGp(q["serveur"].orEmpty().ifBlank { station.settings.serveurGp })
            }
            "/api/carnet/retiens" -> {
                val t = q["t"]?.toLongOrNull() ?: return JSONObject().put("ok", false)
                carnet?.retiens(t, q["on"] == "1")
            }
            "/api/carnet/profils" -> {
                // The online log's station locations, and which one each place of the log goes to (here first).
                val c = carnet ?: return JSONObject().put("ok", false)
                kotlinx.coroutines.runBlocking { c.releveProfils() }?.let { return JSONObject().put("ok", false).put("erreur", it) }
                if (c.profils.isEmpty()) return JSONObject().put("ok", false).put("erreur", "le carnet en ligne ne donne aucun profil (adresse ou clé ?)")
                return fr.f4ioz.satcombo.demo.ApiStation.profilsWavelog(c.profils, c.ici(), c.profilPourLieu(c.ici()), c.lieuxDuCarnet(),
                    { id, lieu -> c.couvre(id, lieu) }, station.settings.carnetProfil, station.settings.callsign)
            }
            "/api/carnet/profil" -> station.settings.carnetProfil = q["id"].orEmpty().trim()
            "/api/carnet/profils/cree" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                val lieux = q["lieux"].orEmpty().split('|').map { l -> l.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet() }.filter { it.isNotEmpty() }
                if (lieux.isEmpty()) return JSONObject().put("ok", false).put("erreur", "aucun lieu")
                return ok.put("dit", kotlinx.coroutines.runBlocking { c.creeProfils(lieux, q["dxcc"].orEmpty(), q["pays"].orEmpty(), q["cq"].orEmpty(), q["itu"].orEmpty()) })
            }
            "/api/son/carte" -> {
                val s = son ?: return JSONObject().put("ok", false)
                val nom = q["nom"].orEmpty()
                if (nom.isBlank()) { s.arreteEnregistrement(); s.son.arrete(); s.carte = "" }
                else if (s.son.demarre(nom)) s.carte = nom
                else return JSONObject().put("ok", false).put("erreur", s.son.etat.value.erreur)
            }
            "/api/son/enregistrer" -> {
                val s = son ?: return JSONObject().put("ok", false)
                if (q["on"] == "1") s.demarreEnregistrement()?.let { return JSONObject().put("ok", false).put("erreur", it) }
                else s.arreteEnregistrement()
            }
            "/api/ft8" -> {
                val f = son?.ft8 ?: return JSONObject().put("ok", false)
                when {
                    q["vide"] == "1" -> f.vide()
                    q["on"] == "1" -> { if (son.son.etat.value.actif) f.demarre(q["mode"] ?: f.mode) else return JSONObject().put("ok", false).put("erreur", "démarre d'abord la carte son (onglet Son)") }
                    q["on"] == "0" -> f.arrete()
                    q["mode"] != null -> if (f.enMarche) f.demarre(q["mode"]!!) else f.demarre(q["mode"]!!).also { f.arrete() }
                }
            }
            "/api/son/options" -> {
                val s = son ?: return JSONObject().put("ok", false)
                q["auto"]?.let { s.enregAuto = it == "1" }
                q["sstv"]?.let { s.sstvActif = it == "1" }
                q["aprs"]?.let { s.aprsActif = it == "1" }
            }
            "/api/rotor/reglages" -> {
                entier("maxAz")?.let { rotor.setRotorMaxAz(it) }
                entier("maxEl")?.let { rotor.setRotorMaxEl(it) }
                q["butee"]?.takeIf { it == "NORTH" || it == "SOUTH" }?.let { rotor.setRotorAzStop(it) }
                q["depuisButee"]?.let { rotor.setRotorAzFromStop(it == "1") }
                q["flip"]?.let { rotor.setRotorFlip(it == "1") }
                q["azSeul"]?.let { rotor.setRotorAzOnly(it == "1") }
                entier("parkAz")?.let { rotor.setRotorParkAz(it) }
                entier("parkEl")?.let { rotor.setRotorParkEl(it) }
                entier("minEl")?.let { rotor.setRotorMinEl(it) }
                entier("ecart")?.let { rotor.setRotorDeadband(it) }
                entier("avantAos")?.let { rotor.setRotorPreAos(it) }
            }
            "/api/rotor/aller" -> {
                entier("az")?.let { rotor.setRotorManualAz(it) }
                entier("el")?.let { rotor.setRotorManualEl(it) }
                rotor.rotorGotoManual()
            }
            else -> return JSONObject().put("ok", false).put("erreur", "inconnu : $chemin")
        }
        return ok
    }

    /** A file as it is on disk, by parts when the browser asks (a sound it seeks in). */
    private fun envoieFichier(s: OutputStream, r: RequeteHttp.Requete, f: java.io.File, typeDonne: String? = null, entetes: String = "") {
        val type = typeDonne ?: when {
            f.name.endsWith(".png") -> "image/png"
            f.name.endsWith(".wav") -> "audio/wav"
            f.name.endsWith(".mp3") -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        val taille = f.length()
        val p = RequeteHttp.plage(r.plage, taille)
        val de = p?.first ?: 0L
        val n = if (p != null) p.last - p.first + 1 else taille
        s.write((("HTTP/1.1 " + (if (p != null) "206 Partial Content" else "200 OK")) + "\r\nContent-Type: $type\r\n" +
            "Content-Length: $n\r\nAccept-Ranges: bytes\r\n" + entetes +
            (if (p != null) "Content-Range: bytes ${p.first}-${p.last}/$taille\r\n" else "") +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(de)
            val buf = ByteArray(64 * 1024)
            var reste = n
            while (reste > 0) {
                val k = raf.read(buf, 0, minOf(buf.size.toLong(), reste).toInt())
                if (k <= 0) break
                s.write(buf, 0, k); reste -= k
            }
        }
        s.flush()
    }

    private fun parametres(qs: String): Map<String, String> =
        qs.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun envoie(s: OutputStream, statut: String, type: String, corps: ByteArray, entetes: String = "") {
        s.write(("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\n" + entetes +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        s.write(corps)
        s.flush()
    }
}

/** The station as the page reads it. */
object JsonPc {
    fun bilanTelephone(b: TelephonePc.Bilan?): JSONObject =
        if (b == null) JSONObject().put("ok", false).put("erreur", "fichier illisible ou trop gros")
        else JSONObject().put("ok", b.erreur.isEmpty()).put("erreur", b.erreur).put("sat", b.satellite).put("de", b.debutMs)
            .put("images", b.images).put("sons", b.sons).put("contacts", b.contacts).put("dejaLa", b.contactsDejaLa)

    /** METEOR: the receiver, the next pass, the pictures. */
    fun meteor(d: SdrPc): JSONObject {
        val m = d.meteor ?: return JSONObject().put("absent", true)
        val e = m.recepteur.etat
        val st = d.station
        val r = fr.f4ioz.satcombo.demo.ApiMeteor.Recepteur(e.actif, e.source, e.satellite, e.qualiteDb, e.verrou, e.trames, e.tramesRatees,
            e.lignes, e.canaux, e.frequenceHz, e.perdus, e.progression, e.fichier ?: "", e.erreur ?: "", e.apercus)
        return fr.f4ioz.satcombo.demo.ApiMeteor.json(r, st.settings.meteorAuto, st.settings.meteorCotes, d.etat.value.actif, d.etat.value.erreur,
            st.estMeteor(), m.dernier ?: "", st.prochainPassageMeteor(48), m.images())
    }

    /** The radiosondes: the dongle on them, the decoder, the sites around, the flight. */
    fun sondes(d: SdrPc): JSONObject {
        val st = d.station.settings
        val e = d.sondes.etat
        val r = fr.f4ioz.satcombo.demo.ApiSondes.Recepteur(d.pourSonde && d.etat.value.actif, d.etat.value.actif && !d.pourSonde,
            d.etat.value.erreur, st.sondeFreqHz, d.etat.value.decalageHz, d.etat.value.niveauDb.toDouble(), st.sondeModel, d.recentreAuto,
            e.trames, e.rejetees, e.excursion, e.serie, e.type)
        return fr.f4ioz.satcombo.demo.ApiSondes.json(r, e.derniere, d.sondes.vol, d.station.etat.value.observateur, d.sondes.journaux())
    }

    /** The dongle: its state and its settings. */
    fun sdr(d: SdrPc): JSONObject {
        val e = d.etat.value
        val st = d.station.settings
        return JSONObject().put("actif", e.actif).put("statut", e.statut).put("erreur", e.erreur).put("tuner", e.tuner)
            .put("ciel", e.cielHz).put("repos", e.reposHz).put("pll", e.pllHz).put("fine", e.fineHz).put("decalage", e.decalageHz)
            .put("niveau", Math.round(e.niveauDb * 10) / 10.0).put("saturation", e.saturation.toDouble()).put("debit", e.debitKo).put("perdus", e.perdus)
            .put("satellite", e.satellite)
            .put("gain", st.sdrGainTenthDb).put("agc", st.sdrAgc).put("ppm", st.sdrPpm).put("mode", d.modeChoisi).put("bw", st.sdrBandwidthHz)
            .put("squelch", st.sdrSquelchDb).put("son", st.sdrAudio).put("deemph", st.sdrDeemph).put("doppler", st.sdrDopplerTrack)
            .put("suit", d.suitSatellite).put("manuelle", d.manuelleHz).put("hote", d.hote).put("port", d.port).put("lance", d.lance).put("bias", d.biasTee)
    }

    /**
     * The last spectrum, [n] values (dB, the strongest of the bins each one
     * covers) over [span] Hz around the Doppler target — the signal stays in
     * the middle while the satellite passes. Null values before the first.
     */
    fun spectre(d: SdrPc, span: Int, n: Int): JSONObject {
        val sp = d.spectre ?: return JSONObject().put("trames", 0)
        val e = d.etat.value
        return fr.f4ioz.satcombo.demo.ApiSdr.spectre(sp, Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size, e.fineHz.toDouble(), span, n, d.trames.toLong(), e.pllHz)
    }

    /** QO-100: the pilot's state, the dish and the Sun; with [plan], the band plan and the presets (they never change). */
    fun qo100(st: StationPc, plan: Boolean): JSONObject {
        // The tab opened: what is stored read again (the Station tab sets the same calibration).
        if (plan) st.qo100.ouvre()
        val e = st.qo100.etat.value
        return ApiQo100.etat(e, st.etat.value.catConnecte, st.versCle(e.descenteHz + e.calageHz),
            st.cielQo100()?.let { k -> ApiQo100.Ciel(k.pointage, k.soleilAzimutMs, k.transits) }, st.etat.value.observateur != null, plan)
    }

    fun etat(st: StationPc): JSONObject {
        val e = st.etat.value
        val r = st.stationRotor.etat.value
        val c = st.stationCat.etat.value
        val (dl, ul) = st.frequencesDuCiel()
        val o = JSONObject()
            .put("maintenant", e.maintenant)
            .put("locator", e.locator).put("indicatif", e.indicatif)
            .put("observateur", e.observateur?.let { JSONObject().put("lat", it.latDeg).put("lon", it.lonDeg) } ?: JSONObject.NULL)
            .put("catalogue", e.catalogue).put("nbSatellites", e.satellites.size)
            .put("calcul", e.calculEnCours)
        e.suivi?.let { s -> o.put("suivi", JSONObject().put("nom", s.name).put("catnum", s.catalogNumber)
            .put("fav", s.catalogNumber in e.favoris)
            .put("amsat", st.statutAmsat(s.name)?.let { r -> JSONObject().put("statut", r.recent.name).put("mode", r.mode).put("actifs", r.activeReports) } ?: JSONObject.NULL)) }
        e.position?.let { p -> o.put("position", fr.f4ioz.satcombo.demo.ApiStation.position(p)) }
        e.passage?.let { p -> o.put("passage", passage(p)) }
        o.put("arc", JSONArray(e.arc.map { JSONArray().put(it.first).put(it.second) }))
        o.put("emetteurs", fr.f4ioz.satcombo.demo.ApiStation.emetteurs(e.emetteurs))
        o.put("emetteur", e.emetteur)
        o.put("reglageSat", JSONObject().put("calib", e.calibHz).put("tx", e.txShiftHz)
            .put("refCalib", e.refCalibHz ?: JSONObject.NULL).put("refTx", e.refTxHz ?: JSONObject.NULL))
        o.put("passagesSuivi", JSONArray(e.passagesSuivi.map { passage(it, st) }))
        o.put("agendaSuivi", e.agendaSuivi)
        o.put("agenda", fr.f4ioz.satcombo.demo.ApiCalculs.agendaJson(e.agenda, e.maintenant))
        o.put("repos", e.repos ?: JSONObject.NULL)
        o.put("ciel", JSONObject().put("dl", dl ?: JSONObject.NULL).put("ul", ul ?: JSONObject.NULL))
        val rx = st.convRx(); val tx = st.convTx()
        o.put("convertisseurs", JSONObject()
            .put("rxOl", if (rx.configure) rx.olHz else 0).put("rxInv", rx.inverseur)
            .put("txOl", if (tx.configure) tx.olHz else 0).put("txInv", tx.inverseur))
        o.put("cat", fr.f4ioz.satcombo.demo.ApiStation.cat(c, e.catModele, st.settings.civUsbIndex, e.catConnecte, e.catSimule, e.catStatut, e.maintienDoppler))
        o.put("rotor", fr.f4ioz.satcombo.demo.ApiStation.rotor(r))
        return o
    }

    private fun passage(p: fr.f4ioz.satcombo.data.SatPass, st: StationPc? = null) = fr.f4ioz.satcombo.demo.ApiStation.passage(p,
        fav = st?.etat?.value?.favoris?.contains(p.catalogNumber) == true, agenda = st?.auAgenda(p) == true,
        amsat = st?.statutAmsat(p.satName)?.recent?.name ?: "")

    fun passages(st: StationPc): JSONObject {
        val e = st.etat.value
        return JSONObject().put("calcul", e.calculEnCours)
            .put("liste", JSONArray(e.passages.take(300).map { passage(it, st) }))
    }

    fun satellites(st: StationPc) = JSONArray(st.etat.value.satellites.map {
        JSONObject().put("nom", it.name).put("catnum", it.catalogNumber)
    })

    fun carnet(c: CarnetPc?): JSONObject {
        val l = c?.contacts().orEmpty()
        val maintenant = System.currentTimeMillis()
        return JSONObject().put("carres", c?.carres() ?: 0).put("nonDeposes", l.count { it.envoyeMs == 0L }).put("total", l.size)
            .put("auto", c?.autoEtat.orEmpty())
            .put("contacts", JSONArray(l.take(6000).map { e ->
                // The automatic upload: waiting (seconds left), held back (and why), or nothing to say.
                val etat = c?.etatEnvoi(e, maintenant)
                JSONObject().put("ea", etat?.name?.lowercase() ?: "hors")
                    .also { o -> if (etat == fr.f4ioz.satcombo.domain.EnvoiAuto.Etat.ATTENTE) o.put("reste", c?.resteEnvoiS(e, maintenant) ?: 0L) }
                    .also { o -> if (e.retenu) o.put("pause", true).put("refus", c?.refusEnvoi(e.timeMs).orEmpty()) }
                    .put("t", e.timeMs).put("c", e.callsign).put("l", e.theirLocator).put("sat", e.satName)
                    .put("md", e.mode).put("rs", e.rstSent).put("rr", e.rstRcvd).put("n", e.note).put("nm", e.nom)
                    .put("qth", e.qth).put("az", e.azimuthDeg).put("el", e.elevationDeg).put("dl", e.downlinkMhz)
                    .put("ul", e.uplinkMhz).put("envoye", e.envoyeMs > 0).put("ml", e.myLocator)
            }))
    }

    /**
     * The squares worked (4 characters), from the log: how many contacts,
     * the first one, who, on which satellites; and mine with its neighbours.
     */
    /** The settings shown on the page; passwords and keys never come back, only whether they are set. */
    fun datv(d: DatvPc): JSONObject = Datv.json(d.etat, d.reglage, d.constellation())
        .put("noeud", d.noeud).put("nomNoeud", d.nomNoeud)
        .put("programme", d.programme() ?: JSONObject.NULL).put("chemin", d.chemin).put("windows", OutilsPc.windows)
        .put("debits", JSONArray(Datv.DEBITS)).put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("balise", Datv.BALISE_KHZ)

    fun reglages(st: StationPc): JSONObject {
        val s = st.settings
        return JSONObject().put("c", s.callsign).put("l", s.manualLocator).put("qrzUser", s.qrzUser)
            .put("qrzMdp", s.qrzMdp.isNotEmpty()).put("carnetUrl", s.carnetUrl).put("carnetCle", s.carnetCle.isNotEmpty())
            .put("carnetProfil", s.carnetProfil).put("minEl", s.minElevDeg).put("fiche", st.ficheQrz)
            .put("carnetAuto", s.carnetAuto).put("carnetDelai", s.carnetAutoDelaiS)
            .put("amsatAuto", s.amsatAuto).put("amsatEtat", st.pushAmsat.etat)
            .put("delais", JSONArray(fr.f4ioz.satcombo.domain.EnvoiAuto.DELAIS_S))
    }

    /**
     * Where a station is from mine: its place (QRZ's coordinates, else its
     * square's centre), the distance and the bearing (great circle).
     */
    fun son(s: SonStation?): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        val c = s.son.etat.value
        val e = s.etat.value
        return ApiSon.json(ApiSon.Etat(s.son.appareils().map { it.nom to it.description }, s.carte, c.actif, c.rate, c.niveau, c.erreur,
            e.enregistrement, e.secondes, e.auto, s.enregAuto, s.sstvActif, s.aprsActif,
            e.sstvMode, e.sstvLignes, e.sstvHauteur, e.sstvVersion, e.sstvDerniere, s.enregistrements(), s.images()))
    }

    fun bilan(b: ImportPc.Bilan?): JSONObject = if (b == null) JSONObject().put("ok", false).put("erreur", "fichier illisible ou trop gros")
        else JSONObject().put("ok", b.message.isEmpty() || b.lus > 0).put("lus", b.lus).put("ajoutes", b.ajoutes).put("passages", b.passages)
            .put("inconnus", b.inconnus).put("erreur", b.message)

    /** FT8 heard: the decodes with what they mean here (CQ, for me, how far, already worked), the waterfall. */
    fun ft8(s: SonStation?, st: StationPc, c: CarnetPc?): JSONObject {
        val f = s?.ft8 ?: return JSONObject().put("present", false)
        val faits = c?.contacts().orEmpty().map { it.callsign.uppercase().substringBefore('/') }.toHashSet()
        val k = f.cascade
        return fr.f4ioz.satcombo.demo.ApiRadio.ft8(f.enMarche, f.mode, f.avancement(), f.niveau, f.tranches, s.son.etat.value.actif,
            Ft8Pc.BASSE_HZ, Ft8Pc.HAUTE_HZ, k?.lignes?.toList().orEmpty(), k?.dernierSpectre ?: FloatArray(0),
            f.entendus.map { e -> fr.f4ioz.satcombo.demo.ApiRadio.Ligne(e.tranche, e.texte, e.appelant, e.appele, e.locator,
                e.frequenceHz, e.rapportDb, e.decalageS, e.mode) },
            st.etat.value.indicatif.uppercase(), st.etat.value.observateur, faits)
    }

    /** APRS heard: the frames (newest first) and what they add up to. */
    fun aprs(s: SonStation?, st: StationPc): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        return fr.f4ioz.satcombo.demo.ApiRadio.aprs(s.aprs.paquets, s.aprsActif && s.son.etat.value.actif, s.aprs.recues, st.etat.value.observateur)
    }

    fun ports(st: StationPc) = JSONArray(st.ports.liste().map {
        JSONObject().put("index", it.index).put("libelle", it.libelle).put("vendor", it.vendorId)
    })
}

/** [n] bytes of [e] at most: a request's body read as a stream. */
private class Borne(private val e: java.io.InputStream, private var reste: Long) : java.io.InputStream() {
    override fun read(): Int { if (reste <= 0) return -1; val b = e.read(); if (b >= 0) reste--; return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (reste <= 0) return -1
        val n = e.read(b, off, minOf(len.toLong(), reste).toInt())
        if (n > 0) reste -= n
        return n
    }
}
