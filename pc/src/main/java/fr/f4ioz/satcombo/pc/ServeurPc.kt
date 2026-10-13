/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

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
                private val datv: DatvPc? = null) {

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
        if (r.methode == "POST") {
            val res = runCatching { action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        // The phone's journal page, from the PC's passes.
        if (journal != null && (chemin == "/journal" || chemin == "/journal/")) {
            envoie(sortie, "200 OK", "text/html; charset=utf-8", journal.page().toByteArray()); return
        }
        if (journal != null && chemin.startsWith("/journal/")) {
            val rep = journal.sert(chemin.removePrefix("/journal")) { q[it].orEmpty() }
            val f = rep.fichier
            if (f != null) envoieFichier(sortie, r, f, rep.type, rep.entetes)
            else envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes)
            return
        }
        when (chemin) {
            "/", "/index.html" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PagePc.HTML.toByteArray())
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
                val o = JSONObject().put("sdr", d?.etat?.value?.actif == true)
                if (d != null && d.etat.value.actif) {
                    d.demandePanorama()
                    val pan = d.panorama; val axe = d.axeQo100()
                    if (pan != null && axe != null) {
                        val cols = fr.f4ioz.satcombo.domain.PanoramaQo100.colonnes(pan, axe.first, axe.second, (q["n"]?.toIntOrNull() ?: 512).coerceIn(64, 1024))
                        o.put("trames", d.panoramaTrames)
                        o.put("colonnes", cols?.let { c -> JSONArray(c.map { v -> if (v == fr.f4ioz.satcombo.domain.PanoramaQo100.HORS) JSONObject.NULL else Math.round(v) }) } ?: JSONObject.NULL)
                    }
                    d.mesureBalise()?.let { m ->
                        o.put("balise", JSONObject().put("ecart", m.ecartArrondiHz).put("rapport", Math.round(m.rapportDb * 10) / 10.0)
                            .put("ok", fr.f4ioz.satcombo.domain.MesureBalise.calageSuffisant(m)))
                    }
                }
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
                envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("debut", debut)
                    .put("lignes", JSONArray(lignes.map { l -> JSONObject().put("nom", l.sat.name).put("catnum", l.sat.catalogNumber)
                        .put("el", JSONArray(l.elevations.map { Math.round(it * 10) / 10.0 })) })).toString().toByteArray())
            }
            "/api/sked" -> {
                val f = kotlinx.coroutines.runBlocking { station.sked(q["catnum"]?.toIntOrNull() ?: 0, q["loc"].orEmpty(), q["minEl"]?.toDoubleOrNull() ?: 0.0) }
                val o = if (f == null) JSONObject().put("ok", false).put("erreur", "carré non reconnu")
                    else JSONObject().put("ok", true).put("fenetres", JSONArray(f.map { w -> JSONObject().put("de", w.deMs).put("a", w.aMs)
                        .put("elMoi", w.elMoi).put("elAutre", w.elAutre).put("azMoi", Math.round(w.azMoi)).put("azAutre", Math.round(w.azAutre))
                        .put("debutTrace", w.debutTrace).put("finTrace", w.finTrace) }))
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/sked/trace" -> {
                val tr = kotlinx.coroutines.runBlocking { station.traceSked(q["catnum"]?.toIntOrNull() ?: 0, q["loc"].orEmpty(),
                    q["de"]?.toLongOrNull() ?: 0L, q["a"]?.toLongOrNull() ?: 0L) }
                fun r(x: Double, n: Int) = Math.round(x * n) / n.toDouble()
                val o = if (tr == null) JSONObject().put("ok", false)
                    else JSONObject().put("ok", true)
                        .put("moi", JSONObject().put("lat", tr.moi.latDeg).put("lon", tr.moi.lonDeg))
                        .put("autre", JSONObject().put("lat", tr.autre.latDeg).put("lon", tr.autre.lonDeg))
                        .put("points", JSONArray(tr.points.map { p -> JSONArray(listOf(p.t, r(p.azMoi, 10), r(p.elMoi, 10), r(p.azAutre, 10), r(p.elAutre, 10),
                            r(p.lat, 100), r(p.lon, 100), Math.round(p.altKm))) }))
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/wavelog/profils" -> {
                val (l, err) = importPc?.let { kotlinx.coroutines.runBlocking { it.profils() } } ?: (emptyList<fr.f4ioz.satcombo.domain.ProfilsStation.Profil>() to "indisponible")
                envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("erreur", err ?: "")
                    .put("profils", JSONArray(l.map { JSONObject().put("id", it.id).put("carre", it.carre).put("indicatif", it.indicatif).put("nom", it.nom) })).toString().toByteArray())
            }
            "/api/carres" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carres(carnet, station).toString().toByteArray())
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
                val o = JSONObject().put("total", l.size).put("fiche", station.ficheQrz)
                    .put("premier", l.lastOrNull()?.timeMs ?: 0L).put("dernier", l.firstOrNull()?.timeMs ?: 0L)
                    .put("sats", JSONArray(l.map { it.satName }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { "${it.key} ×${it.value}" }))
                    .put("carres", JSONArray(l.map { it.theirLocator.take(4).uppercase() }.filter { it.length == 4 }.distinct()))
                    .put("contacts", JSONArray(l.take(100).map { e -> JSONObject().put("t", e.timeMs).put("c", e.callsign).put("l", e.theirLocator)
                        .put("sat", e.satName).put("md", e.mode).put("rs", e.rstSent).put("rr", e.rstRcvd).put("nm", e.nom).put("qth", e.qth).put("n", e.note) }))
                // Where it is from the log when QRZ is not asked: its most frequent square.
                l.mapNotNull { it.theirLocator.takeIf { x -> x.length >= 4 } }.groupingBy { it.uppercase() }.eachCount().maxByOrNull { it.value }?.key
                    ?.let { JsonPc.ouEstIl(o, station, null, null, it) }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/carnet" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carnet(carnet).toString().toByteArray())
            "/api/reglages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.reglages(station).toString().toByteArray())
            "/api/sources" -> {
                // The orbital elements' choice, as on the phone.
                val c = station.choixSources()
                val o = JSONObject().put("serveur", c.serveur).put("seul", c.seul).put("defaut", fr.f4ioz.satcombo.data.ServeurGp.DEFAUT)
                    .put("etat", station.etat.value.catalogue).put("nombre", station.etat.value.satellites.size)
                    .put("sources", JSONArray(fr.f4ioz.satcombo.data.Sources.ALL.map { s ->
                        JSONObject().put("id", s.id).put("nom", s.label).put("actif", s.id in c.sources)
                            // CelesTrak: from this PC through the server only.
                            .put("celestrak", s.url.contains("celestrak", true))
                            .put("detail", if (s.id == fr.f4ioz.satcombo.data.Sources.IMAGES) fr.f4ioz.satcombo.data.SatellitesImages.LISTE.joinToString(", ") { it.nom + " (" + it.images + ")" } else "")
                    }))
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/qrz" -> {
                // Read only (a directory lookup), so a GET; nothing changes in the station.
                val f = carnet?.let { kotlinx.coroutines.runBlocking { it.ficheQrz(q["c"].orEmpty()) } }
                val o = if (f == null) JSONObject().put("ok", false).put("erreur", "identifiants QRZ absents ou réseau")
                    else if (f.erreur.isNotBlank()) JSONObject().put("ok", false).put("erreur", f.erreur)
                    else JSONObject().put("ok", !f.vide).put("nom", f.nom).put("prenom", f.prenom).put("qth", f.qth)
                        .put("pays", f.pays).put("carre", f.carre).put("indicatif", f.indicatif)
                        .put("adresse", f.adresse).put("etat", f.etat).put("comte", f.comte).put("zoneCq", f.zoneCq).put("zoneItu", f.zoneItu)
                        .put("classe", f.classe).put("lotw", f.lotw).put("eqsl", f.eqsl).put("qslPapier", f.qslPapier)
                        .put("qslManager", f.qslManager).put("site", f.site).put("surnom", f.surnom).put("depuis", f.depuis)
                        .also { o -> JsonPc.ouEstIl(o, station, f.lat, f.lon, f.carre) }
                        // The photo only when the operator wants the QRZ card (Settings).
                        .put("image", if (station.ficheQrz) f.image else "")
                        .put("fiche", station.ficheQrz)
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
                fun hz(k: String) = q[k]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: 0L
                val (bon, message) = station.qo100.mesure(q["sens"] == "descente", hz("ciel"), hz("poste"))
                return JSONObject().put("ok", bon).put("message", message)
            }
            "/api/qo100/balise-entendue" -> {
                val hz = q["mhz"]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                    ?.takeIf { Math.abs(it - fr.f4ioz.satcombo.domain.Qo100.BALISE_MEDIANE_HZ) < 2_000_000L }
                    ?: return JSONObject().put("ok", false).put("message", "Une fréquence près de 10 489,750 MHz, en MHz.")
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
                val e = c.ajoute(q["c"].orEmpty(), q["l"].orEmpty(), q["rs"].orEmpty(), q["rr"].orEmpty(), q["n"].orEmpty(), fiche)
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
            }
            "/api/sources" -> station.regleSources(q["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
                q["serveur"], q["seul"]?.let { it == "1" })
            "/api/sources/essai" -> {
                // The server's groups and their counts, or why it does not answer.
                val base = fr.f4ioz.satcombo.data.ServeurGp.normalise(q["serveur"].orEmpty().ifBlank { station.settings.serveurGp })
                if (base.isEmpty()) return JSONObject().put("ok", false).put("erreur", "aucune adresse")
                val r = runCatching {
                    val c = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                    c.newCall(okhttp3.Request.Builder().url(fr.f4ioz.satcombo.data.ServeurGp.index(base)).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()).execute().use { rep ->
                        if (!rep.isSuccessful) return JSONObject().put("ok", false).put("erreur", "HTTP " + rep.code)
                        val g = JSONObject(rep.body?.string().orEmpty()).getJSONArray("groupes")
                        (0 until g.length()).joinToString(" · ") { val x = g.getJSONObject(it); x.getString("id") + " " + x.optInt("nombre") }
                    }
                }.getOrElse { return JSONObject().put("ok", false).put("erreur", "pas de réponse (" + it.javaClass.simpleName + ")") }
                return ok.put("groupes", r)
            }
            "/api/carnet/retiens" -> {
                val t = q["t"]?.toLongOrNull() ?: return JSONObject().put("ok", false)
                carnet?.retiens(t, q["on"] == "1")
            }
            "/api/carnet/profils" -> {
                // The station profiles the key gives: a connection test, and the id to choose.
                val s = station.settings
                if (s.carnetUrl.isBlank() || s.carnetCle.isBlank()) return JSONObject().put("ok", false).put("erreur", "renseigne l'adresse et la clé, puis Enregistrer")
                val l = runCatching { kotlinx.coroutines.runBlocking { fr.f4ioz.satcombo.data.CarnetEnLigne.profils(s.carnetUrl, s.carnetCle) } }
                    .getOrElse { return JSONObject().put("ok", false).put("erreur", "pas de réponse : " + (it.message ?: it.javaClass.simpleName)) }
                if (l.isEmpty()) return JSONObject().put("ok", false).put("erreur", "le carnet en ligne ne donne aucun profil (adresse ou clé ?)")
                return ok.put("profils", JSONArray(l.map { JSONObject().put("id", it.id).put("indicatif", it.indicatif).put("carre", it.carre).put("nom", it.nom) }))
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
        val o = JSONObject().put("actif", e.actif).put("source", e.source).put("satellite", e.satellite)
            .put("qualite", Math.round(e.qualiteDb * 10) / 10.0).put("verrou", e.verrou).put("trames", e.trames).put("ratees", e.tramesRatees)
            .put("lignes", e.lignes).put("canaux", JSONArray(e.canaux)).put("frequence", e.frequenceHz).put("perdus", e.perdus)
            .put("progression", e.progression.toDouble()).put("fichier", e.fichier ?: "").put("erreur", e.erreur ?: "").put("apercus", e.apercus)
            .put("auto", st.settings.meteorAuto).put("cotes", st.settings.meteorCotes).put("sdrActif", d.etat.value.actif)
            .put("sdrErreur", d.etat.value.erreur).put("suiviMeteor", st.estMeteor()).put("dernier", m.dernier ?: "")
        st.prochainPassageMeteor(48)?.let { (sat, p) ->
            o.put("prochain", JSONObject().put("sat", sat.name).put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("el", Math.round(p.maxElevationDeg)))
        }
        o.put("images", JSONArray(m.images().take(60).map { (f, s) ->
            JSONObject().put("nom", f.name).put("sat", s.satName).put("t", s.timeMs).put("mode", s.mode).put("note", s.note)
                .put("complete", s.complete).put("source", s.source) }))
        return o
    }

    /** The radiosondes: the dongle on them, the decoder, the sites around, the flight. */
    fun sondes(d: SdrPc): JSONObject {
        val st = d.station.settings
        val e = d.sondes.etat
        val obs = d.station.etat.value.observateur
        val o = JSONObject().put("actif", d.pourSonde && d.etat.value.actif).put("sdrActif", d.etat.value.actif && !d.pourSonde)
            .put("erreur", d.etat.value.erreur).put("freq", st.sondeFreqHz).put("decalage", d.etat.value.decalageHz)
            .put("niveau", Math.round(d.etat.value.niveauDb * 10) / 10.0).put("modele", st.sondeModel).put("auto", d.recentreAuto)
            .put("modeles", JSONArray(fr.f4ioz.satcombo.sonde.SondeModel.ALL.map { JSONObject().put("id", it.id).put("nom", it.label) }))
            .put("trames", e.trames).put("rejetees", e.rejetees).put("excursion", e.excursion).put("serie", e.serie).put("type", e.type)
        if (obs != null) o.put("moi", JSONObject().put("lat", obs.latDeg).put("lon", obs.lonDeg))
            .put("sites", JSONArray(fr.f4ioz.satcombo.sonde.SondeSites.nearest(obs.latDeg, obs.lonDeg, max = 6).map { (s, km) ->
                JSONObject().put("nom", s.name).put("km", Math.round(km)).put("hz", s.mainHz).put("sonde", s.sonde)
                    .put("lachers", JSONArray(s.launchesUtc.map { h -> String.format(java.util.Locale.US, "%02d:%02d", h.toInt(), Math.round((h - h.toInt()) * 60).toInt()) }))
            }))
        e.derniere?.let { f ->
            val p = JSONObject().put("lat", f.lat).put("lon", f.lon).put("alt", Math.round(f.altM)).put("vitesse", Math.round(f.speedMps * 3.6))
                .put("cap", Math.round(f.headingDeg)).put("montee", Math.round(f.climbMps * 10) / 10.0).put("sats", f.sats)
                .put("heure", f.timeUtcMs).put("freq", f.freqHz).put("sure", f.trusted)
            if (obs != null) {
                val km = fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, f.lat, f.lon)
                val az = fr.f4ioz.satcombo.sonde.Geo.bearingDeg(obs.latDeg, obs.lonDeg, f.lat, f.lon)
                p.put("km", Math.round(km * 10) / 10.0).put("azimut", Math.round(az)).put("rose", fr.f4ioz.satcombo.sonde.Geo.compass(az))
            }
            fr.f4ioz.satcombo.sonde.SondeSites.likelyOrigin(f.lat, f.lon, f.type)?.let { p.put("origine", it.name) }
            o.put("derniere", p)
        }
        d.sondes.vol?.let { v ->
            val pts = v.track
            val pas = maxOf(1, pts.size / 600)
            o.put("trace", JSONArray(pts.filterIndexed { i, _ -> i % pas == 0 || i == pts.size - 1 }.map { JSONArray(listOf(Math.round(it.lat * 1e5) / 1e5, Math.round(it.lon * 1e5) / 1e5)) }))
            if (v.hasBurst) o.put("eclatement", JSONObject().put("alt", Math.round(v.burstAltM)).put("lat", v.burst!!.lat).put("lon", v.burst!!.lon))
            v.estimatedLanding()?.let { (la, lo) -> o.put("atterrissage", JSONObject().put("lat", la).put("lon", lo)) }
        }
        o.put("journaux", JSONArray(d.sondes.journaux().take(30).map { JSONObject().put("nom", it.name).put("taille", it.length()).put("date", it.lastModified()) }))
        return o
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
        val nb = sp.size
        val parBin = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / nb
        val largeur = span.coerceIn(6_000, 176_400).toDouble()
        val k = n.coerceIn(64, 2048)
        val centre = e.fineHz.toDouble()
        val v = IntArray(k) { i ->
            val a = centre - largeur / 2 + largeur * i / k
            val b = a + largeur / k
            var m = -200f
            var j0 = Math.floor(a / parBin).toInt() + nb / 2
            val j1 = Math.ceil(b / parBin).toInt() + nb / 2
            if (j1 <= j0) j0 = j1 - 1
            for (j in j0 until j1) if (j in 0 until nb) m = maxOf(m, sp[j])
            Math.round(m)
        }
        return JSONObject().put("trames", d.trames).put("centre", centre).put("span", largeur).put("pll", e.pllHz)
            .put("db", JSONArray(v.toList()))
    }

    /** QO-100: the pilot's state, the dish and the Sun; with [plan], the band plan and the presets (they never change). */
    fun qo100(st: StationPc, plan: Boolean): JSONObject {
        val Q = fr.f4ioz.satcombo.domain.Qo100
        // The tab opened: what is stored read again (the Station tab sets the same calibration).
        if (plan) st.qo100.ouvre()
        val e = st.qo100.etat.value
        val tr = fr.f4ioz.satcombo.i18n.I18n
        val seg = e.segment
        val c = e.chaineActive
        val o = JSONObject()
            .put("descente", e.descenteHz).put("montee", e.monteeHz).put("calage", e.calageHz)
            .put("transpondeur", e.transpondeur).put("sansBride", e.sansBride).put("surBalise", e.surBalise)
            .put("auPoste", e.auPoste).put("posteRx", e.posteRxHz).put("posteTx", e.posteTxHz).put("atteignable", e.posteAtteignable)
            .put("emission", e.emissionPermise).put("statut", e.statut).put("catConnecte", st.etat.value.catConnecte)
            .put("aLaCle", e.aLaCle).put("cleRx", st.versCle(e.descenteHz + e.calageHz))
            .put("cleAtteignable", st.versCle(e.descenteHz + e.calageHz) in 24_000_000L..1_766_000_000L)
            .put("tp", JSONObject().put("bas", e.tp.descenteBasHz).put("haut", e.tp.descenteHautHz).put("centre", e.tp.centreDescenteHz))
            .put("segment", seg?.let { JSONObject().put("nom", tr.t("qo100_seg_" + it.cle)).put("largeur", it.largeurMaxHz)
                .put("avert", tr.t("qo100_warn_" + it.usage.cleAvertissement)).put("emission", it.emissionPermise) }
                ?: JSONObject().put("nom", "").put("largeur", 0).put("avert", tr.t("qo100_warn_hors")).put("emission", false))
            .put("memoires", JSONArray(fr.f4ioz.satcombo.domain.MemoiresQo100.toutes(e.memoires).map { m ->
                JSONObject().put("hz", m.hz).put("nom", m.libelle { k -> tr.t(k) }).put("fixe", m.fixe) }))
            .put("chaines", JSONArray(e.chaines.map { it.nom })).put("chaine", c.nom)
            .put("olRx", c.descenteOlHz).put("olTx", c.monteeOlHz)
        st.cielQo100()?.let { k ->
            o.put("ciel", JSONObject().put("az", k.pointage.azDeg).put("el", k.pointage.elDeg).put("skew", k.pointage.skewDeg)
                .put("visible", k.pointage.visible).put("soleilAz", k.soleilAzimutMs ?: JSONObject.NULL)
                .put("ombre", fr.f4ioz.satcombo.domain.SoleilQo100.azimutDeLOmbre(k.pointage.azDeg))
                .put("transits", JSONArray(k.transits.map { t -> JSONObject().put("t", t.instantMs).put("ecart", t.ecartDeg).put("duree", t.dureeS) })))
        } ?: o.put("ciel", if (st.etat.value.observateur == null) JSONObject().put("inconnu", true) else JSONObject.NULL)
        if (plan) {
            o.put("segments", JSONArray(Q.SEGMENTS.map { g -> JSONObject().put("bas", g.basHz).put("haut", g.hautHz)
                .put("usage", g.usage.name).put("nom", tr.t("qo100_seg_" + g.cle)).put("repere", g.repereHz ?: JSONObject.NULL) }))
                .put("regletteBas", Q.REGLETTE_BAS_HZ).put("regletteHaut", Q.REGLETTE_HAUT_HZ)
                .put("transpondeurs", JSONArray(Q.TRANSPONDEURS.map { t -> JSONObject().put("cle", t.cle).put("nom", tr.t("qo100_tp_" + t.cle)) }))
                .put("presets", JSONArray(fr.f4ioz.satcombo.domain.Convertisseur.PRESETS.map { p ->
                    JSONObject().put("cle", p.cle).put("nom", tr.t("preset_" + p.cle)).put("ol", p.olHz).put("descente", p.descente) }))
        }
        return o
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
        e.position?.let { p ->
            o.put("position", JSONObject().put("az", p.azimuthDeg).put("el", p.elevationDeg)
                .put("distance", p.rangeKm).put("vitesse", p.rangeRateKmS).put("alt", p.altKm)
                .put("lat", p.latDeg).put("lon", p.lonDeg).put("eclaire", p.sunlit))
        }
        e.passage?.let { p -> o.put("passage", passage(p)) }
        o.put("arc", JSONArray(e.arc.map { JSONArray().put(it.first).put(it.second) }))
        o.put("emetteurs", JSONArray(e.emetteurs.map { t ->
            JSONObject().put("desc", t.description).put("mode", t.mode ?: "")
                .put("dlBas", t.downlinkLowHz ?: JSONObject.NULL).put("dlHaut", t.downlinkHighHz ?: JSONObject.NULL)
                .put("ulBas", t.uplinkLowHz ?: JSONObject.NULL).put("ulHaut", t.uplinkHighHz ?: JSONObject.NULL)
                .put("inverse", t.invert).put("lineaire", t.isTransponder)
        }))
        o.put("emetteur", e.emetteur)
        o.put("reglageSat", JSONObject().put("calib", e.calibHz).put("tx", e.txShiftHz)
            .put("refCalib", e.refCalibHz ?: JSONObject.NULL).put("refTx", e.refTxHz ?: JSONObject.NULL))
        o.put("passagesSuivi", JSONArray(e.passagesSuivi.map { passage(it, st) }))
        o.put("agendaSuivi", e.agendaSuivi)
        o.put("agenda", JSONArray(e.agenda.filter { it.endOrStartMs >= e.maintenant - 3_600_000L }.take(100).map { a ->
            JSONObject().put("id", a.id).put("titre", a.title).put("sat", a.satName).put("de", a.timeMs).put("a", a.endOrStartMs)
                .put("actif", a.enabled).put("type", a.kind).put("note", a.note).put("freq", a.freqHz)
                .put("enCours", a.activeAt(e.maintenant, 0L))
        }))
        o.put("repos", e.repos ?: JSONObject.NULL)
        o.put("ciel", JSONObject().put("dl", dl ?: JSONObject.NULL).put("ul", ul ?: JSONObject.NULL))
        val rx = st.convRx(); val tx = st.convTx()
        o.put("convertisseurs", JSONObject()
            .put("rxOl", if (rx.configure) rx.olHz else 0).put("rxInv", rx.inverseur)
            .put("txOl", if (tx.configure) tx.olHz else 0).put("txInv", tx.inverseur))
        o.put("cat", JSONObject().put("modele", e.catModele).put("port", st.settings.civUsbIndex).put("connecte", e.catConnecte).put("simule", e.catSimule)
            .put("statut", e.catStatut).put("maintien", e.maintienDoppler)
            .put("dl", c.descenteHz ?: JSONObject.NULL).put("ul", c.monteeHz ?: JSONObject.NULL)
            .put("pilote", c.rxPilote).put("mode", c.mode ?: "").put("modeEcart", c.modeEcart))
        o.put("rotor", JSONObject().put("connecte", r.rotorConnected).put("statut", r.rotorStatus)
            .put("suit", r.rotorEnabled).put("simule", r.rotorSim).put("lien", r.rotorLink)
            .put("az", r.rotorActualAz ?: JSONObject.NULL).put("el", r.rotorActualEl ?: JSONObject.NULL)
            .put("azCible", r.rotorTargetAz ?: JSONObject.NULL).put("elCible", r.rotorTargetEl ?: JSONObject.NULL)
            .put("port", r.rotorUsbIndex).put("baud", r.rotorBaud).put("hote", r.rotorHost).put("tcp", r.rotorPort)
            .put("attente", r.rotorPrePositioning)
            .put("reglages", JSONObject().put("maxAz", r.rotorMaxAz).put("maxEl", r.rotorMaxEl).put("butee", r.rotorAzStop)
                .put("depuisButee", r.rotorAzFromStop).put("flip", r.rotorFlip).put("azSeul", r.rotorAzOnly)
                .put("parkAz", r.rotorParkAz).put("parkEl", r.rotorParkEl).put("minEl", r.rotorMinEl)
                .put("ecart", r.rotorDeadband).put("avantAos", r.rotorPreAos))
            .put("diag", JSONArray(r.rotorDiag)))
        return o
    }

    private fun passage(p: fr.f4ioz.satcombo.data.SatPass, st: StationPc? = null) = JSONObject()
        .put("fav", st?.etat?.value?.favoris?.contains(p.catalogNumber) == true)
        .put("agenda", st?.auAgenda(p) == true)
        .put("amsat", st?.statutAmsat(p.satName)?.recent?.name ?: "")
        .put("nom", p.satName).put("catnum", p.catalogNumber).put("aos", p.aosEpochMs).put("los", p.losEpochMs)
        .put("elMax", p.maxElevationDeg).put("azAos", p.aosAzimuthDeg).put("azLos", p.losAzimuthDeg)
        // Its arc in the sky (az, el), lightened to some twenty points: the small plot beside it.
        .put("trace", JSONArray(p.track.let { t -> val pas = maxOf(1, t.size / 20); t.filterIndexed { i, _ -> i % pas == 0 || i == t.size - 1 } }
            .map { (az, el) -> JSONArray(listOf(Math.round(az), Math.round(el * 10) / 10.0)) }))

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
    fun carres(c: CarnetPc?, st: StationPc): JSONObject {
        val l = c?.contacts().orEmpty().filter { it.theirLocator.length >= 4 && fr.f4ioz.satcombo.location.Maidenhead.centre(it.theirLocator.take(4)) != null }
        val parCarre = l.groupBy { it.theirLocator.take(4).uppercase() }
        val mon = st.etat.value.locator.take(4).uppercase()
        val obs = st.etat.value.observateur
        return JSONObject().put("mon", mon).put("lat", obs?.latDeg ?: JSONObject.NULL).put("lon", obs?.lonDeg ?: JSONObject.NULL)
            .put("champs", parCarre.keys.map { it.take(2) }.distinct().size)
            .put("carres", JSONArray(parCarre.entries.sortedBy { it.key }.map { (k, q) ->
                val centre = fr.f4ioz.satcombo.location.Maidenhead.centre(k)
                JSONObject().put("c", k).put("n", q.size).put("premier", q.minOf { it.timeMs }).put("dernier", q.maxOf { it.timeMs })
                    .put("indicatifs", JSONArray(q.map { it.callsign }.distinct().take(12)))
                    .put("sats", JSONArray(q.map { it.satName }.distinct()))
                    .put("km", if (obs != null && centre != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, centre.first, centre.second)) else JSONObject.NULL)
            }))
    }

    /** The settings shown on the page; passwords and keys never come back, only whether they are set. */
    fun datv(d: DatvPc): JSONObject = Datv.json(d.etat, d.reglage, d.constellation())
        .put("noeud", d.noeud).put("nomNoeud", d.nomNoeud)
        .put("programme", d.programme() ?: JSONObject.NULL).put("chemin", d.chemin)
        .put("debits", JSONArray(Datv.DEBITS)).put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("balise", Datv.BALISE_KHZ)

    fun reglages(st: StationPc): JSONObject {
        val s = st.settings
        return JSONObject().put("c", s.callsign).put("l", s.manualLocator).put("qrzUser", s.qrzUser)
            .put("qrzMdp", s.qrzMdp.isNotEmpty()).put("carnetUrl", s.carnetUrl).put("carnetCle", s.carnetCle.isNotEmpty())
            .put("carnetProfil", s.carnetProfil).put("minEl", s.minElevDeg).put("fiche", st.ficheQrz)
            .put("carnetAuto", s.carnetAuto).put("carnetDelai", s.carnetAutoDelaiS)
            .put("delais", JSONArray(fr.f4ioz.satcombo.domain.EnvoiAuto.DELAIS_S))
    }

    /**
     * Where a station is from mine: its place (QRZ's coordinates, else its
     * square's centre), the distance and the bearing (great circle).
     */
    fun ouEstIl(o: JSONObject, st: StationPc, lat: Double?, lon: Double?, carre: String) {
        val la = lat ?: fr.f4ioz.satcombo.location.Maidenhead.centre(carre)?.first
        val lo = lon ?: fr.f4ioz.satcombo.location.Maidenhead.centre(carre)?.second
        if (la == null || lo == null) return
        o.put("lat", la).put("lon", lo)
        val moi = st.etat.value.observateur ?: return
        o.put("moi", JSONObject().put("lat", moi.latDeg).put("lon", moi.lonDeg))
            .put("km", Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(moi.latDeg, moi.lonDeg, la, lo)))
            .put("azimut", Math.round(fr.f4ioz.satcombo.sonde.Geo.bearingDeg(moi.latDeg, moi.lonDeg, la, lo)))
            .put("rose", fr.f4ioz.satcombo.sonde.Geo.compass(fr.f4ioz.satcombo.sonde.Geo.bearingDeg(moi.latDeg, moi.lonDeg, la, lo)))
    }

    fun son(s: SonStation?): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        val c = s.son.etat.value
        val e = s.etat.value
        return JSONObject().put("present", true)
            .put("cartes", JSONArray(s.son.appareils().map { JSONObject().put("nom", it.nom).put("desc", it.description) }))
            .put("carte", s.carte).put("actif", c.actif).put("rate", c.rate).put("niveau", c.niveau).put("erreur", c.erreur)
            .put("enregistrement", e.enregistrement).put("secondes", e.secondes).put("auto", e.auto)
            .put("enregAuto", s.enregAuto).put("sstv", s.sstvActif).put("aprs", s.aprsActif)
            .put("sstvMode", e.sstvMode).put("sstvLignes", e.sstvLignes).put("sstvHauteur", e.sstvHauteur)
            .put("sstvVersion", e.sstvVersion).put("sstvDerniere", e.sstvDerniere)
            .put("enregistrements", JSONArray(s.enregistrements().take(50).map {
                JSONObject().put("nom", it.name).put("taille", it.length()).put("date", it.lastModified())
            }))
            .put("images", JSONArray(s.images().take(200).map {
                JSONObject().put("nom", it.fileName).put("sat", it.satName).put("t", it.timeMs).put("mode", it.mode).put("complete", it.complete)
            }))
    }

    fun bilan(b: ImportPc.Bilan?): JSONObject = if (b == null) JSONObject().put("ok", false).put("erreur", "fichier illisible ou trop gros")
        else JSONObject().put("ok", b.message.isEmpty() || b.lus > 0).put("lus", b.lus).put("ajoutes", b.ajoutes).put("passages", b.passages)
            .put("inconnus", b.inconnus).put("erreur", b.message)

    /** FT8 heard: the decodes with what they mean here (CQ, for me, how far, already worked), the waterfall. */
    fun ft8(s: SonStation?, st: StationPc, c: CarnetPc?): JSONObject {
        val f = s?.ft8 ?: return JSONObject().put("present", false)
        val moi = st.etat.value.indicatif.uppercase()
        val obs = st.etat.value.observateur
        val faits = c?.contacts().orEmpty().map { it.callsign.uppercase().substringBefore('/') }.toHashSet()
        val k = f.cascade
        return JSONObject().put("present", true).put("enMarche", f.enMarche).put("mode", f.mode).put("avancement", f.avancement())
            .put("niveau", f.niveau).put("tranches", f.tranches).put("carte", s.son.etat.value.actif)
            .put("basse", Ft8Pc.BASSE_HZ).put("haute", Ft8Pc.HAUTE_HZ)
            .put("cascade", JSONArray(k?.lignes?.toList().orEmpty().map { java.util.Base64.getEncoder().encodeToString(it) }))
            .put("spectre", JSONArray(k?.dernierSpectre?.map { Math.round(it * 100) / 100.0 }.orEmpty()))
            .put("entendus", JSONArray(f.entendus.take(400).map { e ->
                val ll = e.locator?.let { fr.f4ioz.satcombo.location.Maidenhead.centre(it) }
                JSONObject().put("t", e.tranche).put("texte", e.texte).put("de", e.appelant ?: "").put("a", e.appele ?: "")
                    .put("loc", e.locator ?: "").put("hz", e.frequenceHz).put("db", e.rapportDb).put("dt", Math.round(e.decalageS * 10) / 10.0)
                    .put("mode", e.mode).put("cq", e.texte.startsWith("CQ"))
                    .put("moi", moi.isNotBlank() && e.appele?.uppercase() == moi)
                    .put("fait", e.appelant?.uppercase()?.substringBefore('/') in faits)
                    .put("km", if (ll != null && obs != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, ll.first, ll.second)) else JSONObject.NULL)
            }))
    }

    /** APRS heard: the frames (newest first) and what they add up to. */
    fun aprs(s: SonStation?, st: StationPc): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        val l = s.aprs.paquets
        val obs = st.etat.value.observateur
        val stations = fr.f4ioz.satcombo.aprs.AprsJeu.stations(l, obs?.latDeg, obs?.lonDeg)
        val km = stations.associate { it.indicatif to it }
        return JSONObject().put("present", true).put("actif", s.aprsActif && s.son.etat.value.actif).put("recues", s.aprs.recues)
            .put("nbStations", stations.size).put("viaIss", l.count { it.viaIss })
            .put("loin", stations.mapNotNull { it.km }.maxOrNull() ?: JSONObject.NULL)
            .put("paquets", JSONArray(l.take(400).map { p ->
                val stn = km[p.nom ?: p.source]
                JSONObject().put("t", p.quand).put("src", p.source).put("type", p.type.name).put("iss", p.viaIss)
                    .put("dest", p.destinataire ?: "").put("txt", (p.message ?: p.commentaire).take(120))
                    .put("chemin", p.trame.relais.joinToString(",") { it.toString() + if (it.repete) "*" else "" })
                    .put("carre", stn?.carre ?: "").put("km", stn?.km?.let { Math.round(it) } ?: JSONObject.NULL)
            }))
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
