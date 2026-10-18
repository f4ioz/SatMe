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
                private val datv: DatvPc? = null, private val planche: PlancheWebPc? = null,
    /** The KISS radio on a serial port (APRS tab). */
    private val kiss: KissPc? = null,
    /** The whole station in one file, and put back at the next start. */
    private val sauvegarde: SauvegardePc? = null,
    /** The SSTV test card, played or written. */
    private val mire: MirePc? = null,
    /** The sound to the browsers that listen. */
    private val diffusion: DiffusionSon? = null,
    /** QO-100's beacon followed, the stations heard. */
    private val qo100: Qo100Pc? = null,
    /** The satellite link (local WebSocket), set in Réglages. */
    private val lien: LienSatellitePc? = null,
    /** « Phonie → texte »: the voice written out (off unless chosen). */
    private val phonie: PhoniePc? = null,
    /** DATV transmission with the ADALM-PLUTO (DATV tab). */
    private val emission: EmissionDatvPc? = null,
    /** QO-100 operated from one screen: the reception in one gesture, the session kept. */
    private val trafic: TraficQo100Pc? = null) {

    /** The DATV picture's subtitles and callsigns read in it (set before [demarre]). */
    @Volatile var textesDatv: TextesDatvPc? = null

    /** QO-100's wide band spectrum from the ground station, its signals and the « veille » (set before [demarre]). */
    @Volatile var large: SpectreLargePc? = null

    /** The DATV transmission's pictures and videos: the folder browser and the files sent (set before [demarre]). */
    @Volatile var fichiersDatv: FichiersDatvPc? = null

    /** SSTV on the air with the Pluto or the bladeRF (set before [demarre]). */
    @Volatile var emissionSstv: EmissionSstvPc? = null

    /** The transmitters' I/Q correction and its calibration (set before [demarre]). */
    @Volatile var iq: CalibrationIqPc? = null

    /** QRZ.com records of the callsigns seen in DATV, and their photos (set before [demarre]). */
    @Volatile var qrzDatv: QrzDatvPc? = null

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

    /**
     * The page kept up to date as things change (server-sent events): the
     * station, the sound, the dongle and its spectrum, each sent when it
     * changed — no more waiting for the page's next question. Topics in ?t=
     * (etat, son, sdr, spectre; span and n for the spectrum). The stream
     * ends when the page goes.
     */
    private fun flux(c: Socket, sortie: java.io.OutputStream, q: Map<String, String>) {
        c.soTimeout = 0
        val sujets = q["t"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val span = q["span"]?.toIntOrNull() ?: 48_000
        val n = q["n"]?.toIntOrNull() ?: 512
        runCatching { sortie.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nX-Accel-Buffering: no\r\n\r\nretry: 2000\n\n".toByteArray()); sortie.flush() }
            .onFailure { return }
        val envoyes = HashMap<String, String>()
        fun envoie(sujet: String, json: String): Boolean {
            if (envoyes[sujet] == json) return true
            envoyes[sujet] = json
            return runCatching { sortie.write("event: $sujet\ndata: $json\n\n".toByteArray(Charsets.UTF_8)); sortie.flush() }.isSuccess
        }
        // What the station's state is made of: built again when one of them moved, else each second.
        var refs: List<Any?> = emptyList()
        var etatMs = 0L; var sonMs = 0L; var sdrMs = 0L; var spectreTrames = -1L; var veilleMs = 0L
        var refSon: Any? = null
        var cwVersion = -1L; var cwMs = 0L
        while (true) {
            val maintenant = System.currentTimeMillis()
            if ("etat" in sujets) {
                val r = listOf(station.etat.value, station.stationCat.etat.value, station.stationRotor.etat.value)
                if (r.indices.any { r[it] !== refs.getOrNull(it) } || maintenant - etatMs >= 1000L) {
                    refs = r; etatMs = maintenant
                    if (!envoie("etat", JsonPc.etat(station, carnet).toString())) return
                }
            }
            if ("son" in sujets && son != null) {
                val r = son.etat.value to son.sstv.etat.value
                @Suppress("UNCHECKED_CAST")
                val avant = refSon as? Pair<SonStation.Etat, SstvPc.Etat>
                // A recording starting or stopping, a picture beginning: at once; the rest (level, seconds,
                // lines of a picture) at most twice a second — the sound's state moves with every block.
                val net = avant == null || r.first.enregistrement != avant.first.enregistrement || r.second.mode != avant.second.mode ||
                    r.second.derniere != avant.second.derniere
                val bouge = avant == null || r.first !== avant.first || r.second !== avant.second
                if (net || (bouge && maintenant - sonMs >= 500L) || maintenant - sonMs >= 1000L) {
                    refSon = r; sonMs = maintenant
                    if (!envoie("son", JsonPc.son(son, sdr).toString())) return
                }
            }
            // CW: each letter as it comes, the key's light a few times a second.
            if ("cw" in sujets && son != null && (son.cw.version != cwVersion || (son.cw.actif && maintenant - cwMs >= 150L) || maintenant - cwMs >= 2000L)) {
                cwVersion = son.cw.version; cwMs = maintenant
                if (!envoie("cw", JsonPc.cw(son).toString())) return
            }
            if ("sdr" in sujets && sdr != null && maintenant - sdrMs >= 200L) {
                sdrMs = maintenant
                if (!envoie("sdr", JsonPc.sdr(sdr).toString())) return
            }
            if ("spectre" in sujets && sdr != null && sdr.trames.toLong() != spectreTrames) {
                spectreTrames = sdr.trames.toLong()
                if (!envoie("spectre", JsonPc.spectre(sdr, span, n).toString())) return
            }
            // A comment now and then: a page gone is noticed.
            if (maintenant - veilleMs >= 15_000L) {
                veilleMs = maintenant
                if (runCatching { sortie.write(": \n\n".toByteArray()); sortie.flush() }.isFailure) return
            }
            Thread.sleep(40)
        }
    }

    private fun sert(c: Socket) {
        c.soTimeout = 15_000
        val r = RequeteHttp.lit(c.getInputStream()) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = parametres(r.chemin.substringAfter('?', ""))
        val sortie = c.getOutputStream()
        if (chemin == "/api/flux") { flux(c, sortie, q); return }
        if (chemin == "/api/son/flux" && diffusion != null) {
            // The dongle's or the sound card's sound, to the browser asking (the page open on another PC).
            c.soTimeout = 0
            diffusion.sert(if (q["source"] == "carte") "carte" else "sdr", sortie)
            return
        }
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
        if (r.methode == "POST" && chemin == "/api/sauvegarde/restaure") {
            // A backup, read as it comes (it may hold the recordings): laid aside for the next start.
            val s = sauvegarde
            val b = if (s == null || r.longueur <= 0 || r.longueur > SauvegardePc.MAX_OCTETS) SauvegardePc.Bilan(false, erreur = "fichier trop gros ou vide")
                else s.recoit(Borne(c.getInputStream(), r.longueur))
            envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("ok", b.ok).put("erreur", b.erreur)
                .put("fichiers", b.fichiers).put("octets", b.octets).put("date", b.date).put("version", b.version).toString().toByteArray())
            return
        }
        if (r.methode == "GET" && chemin == "/mire.wav" && mire != null) {
            val m = fr.f4ioz.satcombo.sstv.SstvMode.byName(q["mode"].orEmpty()) ?: fr.f4ioz.satcombo.sstv.SstvMode.ALL.first()
            val f = mire.wav(m, MirePc.conditions(q["bruit"]?.toIntOrNull() ?: 0, q["fading"].orEmpty(), q["coupures"] == "1", q["profil"] == "1"),
                station.settings.callsign, station.settings.manualLocator)
            envoieFichier(sortie, r, f, "audio/wav", "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            return
        }
        if (r.methode == "GET" && chemin == "/sauvegarde.zip" && sauvegarde != null) {
            val f = sauvegarde.fabrique(q["medias"] == "1")
            envoieFichier(sortie, r, f, "application/zip", "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            return
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
        if (phonie != null && (chemin.startsWith("/api/phonie") || chemin.startsWith("/phonie/"))) { phonie(r, chemin, q, sortie, phonie); return }
        textesDatv?.takeIf { chemin == "/api/datv/textes" }?.let { t ->
            val o = if (r.methode == "POST") runCatching { t.action(q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") } else t.json()
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/datv/large" || chemin.startsWith("/api/datv/large/")) {
            val l = large
            val o = when {
                l == null -> JSONObject().put("ok", false)
                r.methode == "POST" -> runCatching { l.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> l.json(q["depuis"]?.toLongOrNull() ?: -1L)
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin.startsWith("/api/datv/sources/") || chemin.startsWith("/api/datv/emission/apercu") || chemin == "/api/datv/emission/incrustation") {
            sourcesDatv(r, c, chemin, q, sortie); return
        }
        if (r.methode == "POST") {
            val res = runCatching { action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        when (chemin) {
            "/", "/index.html", "/pc" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PagePc.HTML.toByteArray())
            "/api/police-drapeaux" -> PagePc.POLICE_DRAPEAUX?.let { envoie(sortie, "200 OK", "font/woff2", it) } ?: envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            // Every tab here; the phone offers some of them.
            "/api/capacites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                JSONObject().put("onglets", JSONArray(PagePc.TOUS)).put("serveur", "pc").toString().toByteArray())
            "/api/etat" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.etat(station, carnet).toString().toByteArray())
            // The satellite link: on or not, its port, who listens.
            "/api/lien" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (lien?.let { l ->
                JSONObject().put("on", l.actif).put("port", l.port).put("ecoute", l.ecoute).put("clients", l.nbClients)
                    .put("erreur", l.erreur).put("reseau", reseau)
            } ?: JSONObject().put("absent", true)).toString().toByteArray())
            // The Doppler/CAT log: on or not, the last files (downloaded from the journal's route).
            "/api/doppler" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("on", station.settings.journalDoppler)
                .put("fichiers", JSONArray(station.journauxDoppler().take(8).map { JSONObject().put("nom", it.name).put("taille", it.length()) }))
                .toString().toByteArray())
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
                val o = ApiQo100.panorama(actif, d?.panorama, d?.axeQo100(), q["n"]?.toIntOrNull() ?: 512, d?.panoramaTrames ?: 0L, if (actif) d!!.mesureBalise() else null,
                    ApiQo100.fenetre(q["bas"], q["haut"]))
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            // The signals on the transponder, each judged (BLU, CW, FT8…): asking is what keeps the analysis on.
            "/api/qo100/signaux" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (qo100?.signaux?.let { it.demande(); it.json() } ?: JSONObject().put("actif", false).put("absent", true)).toString().toByteArray())
            "/api/qo100/trafic" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (trafic?.json() ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/qo100/qualite" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (qo100?.json() ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/qo100" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.qo100(station, q["plan"] == "1", sdr?.gammeHz).toString().toByteArray())
            "/api/sdr" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (sdr?.let { JsonPc.sdr(it) } ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/sdr/spectre" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (sdr?.let { JsonPc.spectre(it, q["span"]?.toIntOrNull() ?: 48_000, q["n"]?.toIntOrNull() ?: 512, q["autour"] == "ecoute") } ?: JSONObject()).toString().toByteArray())
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
                val o = if (d == null) JSONObject().put("ok", false) else JsonPc.datv(d).put("carte", carteDatv(d)).put("verrouDepuis", d.verrouDepuisMs).also { o ->
                    qrzDatv?.let { z ->
                        // The callsigns confirmed: the card's, the service name's, those the picture's reading confirmed.
                        val t = textesDatv
                        val carteActuelle = d.carte.carte?.takeIf { d.carte.carteMs >= d.verrouDepuisMs }?.indicatif.orEmpty()
                        val l = fr.f4ioz.satcombo.datv.QrzDatv.candidats(carteActuelle, d.etat.service,
                            if (t != null && t.ocr) t.confirmes().filter { it.dernierMs >= d.verrouDepuisMs }.map { it.indicatif } else emptyList())
                        z.suit(l)
                        o.put("qrz", z.json(l, station.etat.value.observateur)
                            .put("service", fr.f4ioz.satcombo.datv.QrzDatv.candidats("", d.etat.service, emptyList()).firstOrNull().orEmpty()))
                    }
                }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/iq" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (iq?.json()?.put("ok", true) ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/sstv/emission" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (emissionSstv?.json()?.put("ok", true) ?: JSONObject().put("ok", false)).toString().toByteArray())
            // The picture a start would send now, the callsign over it (nothing transmitted).
            "/api/sstv/emission/apercu.png" -> {
                val m = fr.f4ioz.satcombo.sstv.SstvMode.byName(q["mode"].orEmpty())
                val b = emissionSstv?.let { e -> e.apercuPng(m ?: e.mode) }
                if (b == null) envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "pas d'image".toByteArray())
                else envoie(sortie, "200 OK", "image/png", b)
            }
            "/api/datv/emission" -> {
                val o = emission?.json()?.put("ok", true)?.put("coupeTx", sdr?.coupeTxEnReception == true)
                    ?.put("plutoHote", sdr?.plutoHote.orEmpty())?.put("plutoPort", sdr?.plutoPort ?: 0) ?: JSONObject().put("ok", false)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/datv/qrz/photo" -> {
                val p = qrzDatv?.photo(q["c"].orEmpty())
                if (p == null) envoie(sortie, "404 Not Found", "text/plain", "pas de photo".toByteArray())
                else envoie(sortie, "200 OK", p.second, p.first)
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
            "/api/carnet/records" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiStation.records(fr.f4ioz.satcombo.domain.RecordsCarnet.calcule(carnet?.contacts().orEmpty(), station.settings.manualLocator))
                    .toString().toByteArray())
            "/api/carnet" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carnet(carnet).toString().toByteArray())
            "/api/reglages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.reglages(station, son).toString().toByteArray())
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
            "/api/thd72" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", fr.f4ioz.satcombo.demo.ApiStation.thd72(station.thd72.etat.value,
                station.etat.value.catModele == "THD72" && station.etat.value.catConnecte, station.thd72.bandeTx()).toString().toByteArray())
            "/api/kiss" -> {
                val k = kiss
                val o = if (k == null) JSONObject().put("absent", true) else {
                    val l = station.ports.liste()
                    val choisi = l.firstOrNull { it.cle == station.settings.aprsKissCle } ?: l.firstOrNull { it.cle == station.cleThd72 }
                    fr.f4ioz.satcombo.demo.ApiRadio.kiss(k.lien.etat.value, l.map { it.index to station.nomPort(it) },
                        choisi?.index, station.settings.aprsTravail, station.settings.aprsKissDoppler,
                        k.lignesPour(choisi?.cle), lignesAuChoix = true, voulue = k.frequenceVoulue()).let { o ->
                        val st = station.settings
                        fr.f4ioz.satcombo.demo.ApiRadio.kissEmission(o, fr.f4ioz.satcombo.aprs.EmissionKiss.source(st), k.chemin(""), k.envoi,
                            st.aprsBaliseIss, st.aprsPositionFloue, st.aprsSsid)
                    }
                }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/cw" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.cw(son).toString().toByteArray())
            "/api/aprs/trophees" -> {
                val a = son?.aprs
                val o = if (a == null) JSONObject().put("present", false)
                    else fr.f4ioz.satcombo.demo.ApiRadio.trophees(a.trophees, a.paquets, station.settings.callsign, station.etat.value.observateur,
                        a.evenements, System.currentTimeMillis()).put("present", true)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/ft8/cascade" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiRadio.ft8Cascade(son?.ft8?.cascade, q["depuis"]?.toLongOrNull() ?: 0L, son?.ft8?.derniereLigneMs ?: 0L).toString().toByteArray())
            "/api/aprs" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.aprs(son, station).toString().toByteArray())
            "/api/son" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.son(son, sdr).toString().toByteArray())
            "/api/veille" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.veille(station, son).toString().toByteArray())
            "/api/mire" -> {
                val m = mire
                val e = m?.etat?.value
                envoie(sortie, "200 OK", "application/json; charset=utf-8", (if (m == null || e == null) JSONObject().put("present", false) else JSONObject().put("present", true)
                    .put("modes", JSONArray(fr.f4ioz.satcombo.sstv.SstvMode.ALL.map { JSONObject().put("nom", it.name).put("s", Math.round(fr.f4ioz.satcombo.sstv.SstvEncoder.seconds(it))).put("w", it.width).put("h", it.height) }))
                    .put("sorties", JSONArray(m.sorties().map { JSONObject().put("nom", it.first).put("desc", it.second) }))
                    .put("joue", e.joue).put("mode", e.mode).put("progres", e.progres).put("sortie", e.sortie).put("erreur", e.erreur)).toString().toByteArray())
            }
            "/api/mire/image.png" -> {
                val m = fr.f4ioz.satcombo.sstv.SstvMode.byName(q["mode"].orEmpty()) ?: fr.f4ioz.satcombo.sstv.SstvMode.ALL.first()
                val im = mire?.image(m, station.settings.callsign, station.settings.manualLocator)
                if (im == null) envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
                else envoie(sortie, "200 OK", "image/png", java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(im, "png", it) }.toByteArray())
            }
            "/api/sstv/encours.png" -> son?.imageEnCours()?.let { envoie(sortie, "200 OK", "image/png", it) }
                ?: envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            "/api/sauvegarde" -> {
                val s = sauvegarde
                val (t, tm) = s?.taille() ?: (0L to 0L)
                val a = s?.enAttente()
                envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("present", s != null).put("taille", t).put("tailleMedias", tm)
                    .put("attente", a?.let { JSONObject().put("date", it.date).put("version", it.version) } ?: JSONObject.NULL).toString().toByteArray())
            }
            "/carnet.adi", "/api/carnet/adif" -> {
                // The contacts ticked (their times, ?t=…), else the whole log.
                val choisis = q["t"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                val nom = (if (choisis.isEmpty()) "SatMe_carnet_" else "SatMe_selection_") +
                    java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".adi"
                envoie(sortie, "200 OK", "text/plain; charset=utf-8", (carnet?.adif(choisis) ?: "").toByteArray(),
                    "Content-Disposition: attachment; filename=\"$nom\"\r\n")
            }
            // The passes listed, as a calendar: the list's filters (?f=, fav=1, actifs=1) or one satellite (?catnum=).
            "/api/passages.ics" -> {
                val e = station.etat.value
                val maintenant = System.currentTimeMillis()
                val n = q["catnum"]?.toIntOrNull()
                val l = fr.f4ioz.satcombo.domain.CalendrierPassages.choisis(if (n != null) e.passagesSuivi + e.passages else e.passages,
                    maintenant, q["f"].orEmpty(), e.favoris.takeIf { q["fav"] == "1" }, n) {
                    q["actifs"] != "1" || station.statutAmsat(it.satName)?.recent?.name == "ACTIVE"
                }.distinctBy { fr.f4ioz.satcombo.domain.CalendrierPassages.uid(it) }
                envoie(sortie, "200 OK", fr.f4ioz.satcombo.domain.CalendrierPassages.TYPE,
                    fr.f4ioz.satcombo.domain.CalendrierPassages.ics(l, maintenant).toByteArray(),
                    "Content-Disposition: attachment; filename=\"" + fr.f4ioz.satcombo.domain.CalendrierPassages.nomFichier(maintenant) + "\"\r\n")
            }
            "/api/carnet/lot" -> {
                val choisis = q["t"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                val nom = "satme-contacts-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
                envoie(sortie, "200 OK", "application/json; charset=utf-8", (carnet?.lot(choisis) ?: "").toByteArray(),
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

    private fun origine(proposee: Boolean) =
        if (proposee) fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE else fr.f4ioz.satcombo.domain.GardeQo100.Origine.MAIN

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
            "/api/skeds/reglages" -> station.regleSkeds(q["on"]?.let { it == "1" }, q["cle"])
            "/api/sauvegarde/annule" -> sauvegarde?.annule()
            "/api/mire/joue" -> {
                val m = fr.f4ioz.satcombo.sstv.SstvMode.byName(q["mode"].orEmpty()) ?: return JSONObject().put("ok", false).put("erreur", "mode inconnu")
                mire?.joue(m, MirePc.conditions(q["bruit"]?.toIntOrNull() ?: 0, q["fading"].orEmpty(), q["coupures"] == "1", q["profil"] == "1"),
                    q["sortie"].orEmpty(), station.settings.callsign, station.settings.manualLocator)
            }
            "/api/mire/stop" -> mire?.arrete()
            "/api/cw" -> {
                val w = son?.cw ?: return JSONObject().put("ok", false)
                q["on"]?.let { w.actif = it == "1" }
                q["ton"]?.toIntOrNull()?.let { w.tonChoisi = it }
                if (q["efface"] == "1") w.efface()
            }
            "/api/veille" -> fr.f4ioz.satcombo.demo.ApiVeille.action({ q[it] }, station.settings, station.etat.value.favoris, System.currentTimeMillis())
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
            "/api/sdr/arreter" -> { trafic?.oublie(); sdr?.arrete() }
            "/api/sdr/decalage" -> q["hz"]?.toIntOrNull()?.let { sdr?.decalageHz = it }
            "/api/sdr/pic" -> sdr?.calePic()
            "/api/sdr/voix" -> if (sdr?.caleVoix() != true) return JSONObject().put("ok", false).put("erreur", "le calage sur la voix sert en BLU (USB / LSB)")
            // The vernier: the listened frequency pushed by [hz] (the dongle's software offset, ±80 kHz).
            "/api/sdr/pas" -> q["hz"]?.toIntOrNull()?.let { h -> sdr?.let { it.decalageHz = it.decalageHz + h } }
            "/api/sdr/accord" -> {
                val st = station.settings
                q["loupe"]?.let { st.sdrLoupe = it == "1" }
                q["loupeSpan"]?.toIntOrNull()?.takeIf { it in fr.f4ioz.satcombo.domain.AccordFin.LOUPES }?.let { st.sdrLoupeSpanHz = it }
                q["vernier"]?.let { st.sdrVernier = it == "1" }
                q["ratio"]?.toIntOrNull()?.takeIf { it in fr.f4ioz.satcombo.domain.AccordFin.RAPPORTS }?.let { st.sdrVernierHzParCm = it }
                q["voix"]?.let { st.sdrCalageVoix = it == "1" }
            }
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
                q["recepteur"]?.let { d.recepteur = it }
                q["plutoHote"]?.let { d.plutoHote = it }
                q["plutoPort"]?.toIntOrNull()?.let { d.plutoPort = it }
                q["bladerfOutil"]?.let { d.bladerfOutil = it }
                q["bias"]?.let { d.biasTee = it == "1" }
            }
            // bladeRF-cli -e info only (it opens the board, prints what it is, sends nothing).
            "/api/sdr/bladerf" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val i = d.detecteBladerf()
                return ok.put("message", i.message).put("libelle", i.carte?.libelle ?: "")
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
                if (!d.demarre(pourImages = true)) return JSONObject().put("ok", false).put("erreur", d.etat.value.erreur)
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
                q["recepteur"]?.takeIf { it in Datv.RECEPTEURS }?.let { d.recepteur = it }
                large?.veilleArrete()
                d.demarre(r)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/datv/arrete" -> { large?.veilleArrete("arrêtée : réception arrêtée"); datv?.arrete() }
            // Transmission: the parameters (kept), the start (confirmed on the page), the stop, the page's heartbeat, the gain.
            "/api/datv/emission/reglage" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                // A symbol rate or a MODCOD not offered: refused, nothing changed (never kept silently at the old one).
                // The standard first: the MODCOD given is read in it (DVB-S: its code rates). Not while transmitting.
                val norme = q["norme"]?.let { fr.f4ioz.satcombo.datv.Norme.de(it) ?: return JSONObject().put("ok", false).put("erreur", "norme inconnue : $it") }
                if (norme != null && norme != e.norme && e.etat.emet) return JSONObject().put("ok", false).put("erreur", "norme : arrêter l'émission d'abord")
                val nrm = norme ?: e.norme
                EmissionDatvPc.refusReglage(q["sr"], q["modcod"], q["courtes"]?.let { it == "1" } ?: e.courtes, nrm)
                    ?.let { return JSONObject().put("ok", false).put("erreur", it) }
                norme?.let { e.norme = it }
                q["modcod"]?.let { if (nrm == fr.f4ioz.satcombo.datv.Norme.DVBS) e.tauxDvbsTexte = it else e.modcodTexte = it }
                entier("sr")?.let { e.srKs = it }
                q["ro"]?.replace(',', '.')?.toDoubleOrNull()?.let { a -> fr.f4ioz.satcombo.datv.s2.Rolloff.de(a)?.let { e.rolloff = it } }
                q["courtes"]?.let { e.courtes = it == "1" }
                q["pilotes"]?.let { e.pilotes = it == "1" }
                q["mhz"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.frequenceKhz = Math.round(it * 1000.0) }
                q["gain"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.regleGain(it) }
                entier("duree")?.let { e.dureeS = it * 60 }
                q["source"]?.let { e.source = it }
                q["image"]?.let { e.image = it }
                q["video"]?.let { e.video = it }
                q["camera"]?.let { if (it.isEmpty() || it in EmissionDatvPc.cameras()) e.camera = it }
                q["hevc"]?.let { e.hevc = it == "1" }
                q["son"]?.let { e.son = it == "1" }
                q["gainMax"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.gainMaxDb = it }
                q["coupeTx"]?.let { sdr?.coupeTxEnReception = it == "1" }
                // The transmitter (not while transmitting), the HackRF's gain and tools, the indoor test.
                q["emetteur"]?.let { if (!e.etat.emet) e.emetteur = fr.f4ioz.satcombo.datv.Emetteur.de(it) }
                q["vga"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.vgaHackrf = fr.f4ioz.satcombo.datv.ReglesEmission.vgaHackrf(it) }
                q["hackrfOutil"]?.let { e.hackrfOutil = it }
                q["hackrfDecalage"]?.let { if (!e.etat.emet) e.hackrfDecalage = it == "1" }
                // The MODCOD fitted to our reception: on, its range, its margin.
                q["adapt"]?.let { e.adaptOn = it == "1" }
                q["adaptMin"]?.let { e.adaptMin = it }
                q["adaptMax"]?.let { e.adaptMax = it }
                q["adaptMarge"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.adaptMarge = it }
                q["intMhz"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.frequenceInterieurKhz = Math.round(it * 1000.0) }
                entier("intDuree")?.let { e.dureeInterieurS = it * 60 }
                // The station card sent in the stream.
                q["carte"]?.let { e.carteOn = it == "1" }
                q["qth"]?.let { e.qth = it }
                q["puissance"]?.let { e.puissance = it }
                q["antenne"]?.let { e.antenne = it }
                q["message"]?.let { e.message = it }
                // Talkback (MHz on the page): empty clears it; a narrow band frequency, never the emergency one.
                q["retourMode"]?.let { e.retourMode = it }
                q["retour"]?.let { m ->
                    val hz = ApiQo100.mhz(m) ?: 0L
                    if (hz != 0L && hz !in fr.f4ioz.satcombo.domain.Qo100.NB.descenteBasHz..fr.f4ioz.satcombo.domain.Qo100.NB.descenteHautHz)
                        return JSONObject().put("ok", false).put("erreur", "fréquence de retour : une descente du transpondeur étroit, 10 489,505 à 10 489,997 MHz")
                    if (hz != 0L && fr.f4ioz.satcombo.domain.GardeQo100.urgence(hz))
                        return JSONObject().put("ok", false).put("erreur", "fréquence d'urgence : réservée au trafic d'urgence, pas un retour")
                    e.retourHz = hz
                }
            }
            "/api/datv/emission/demarre" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                e.demarre(q["confirme"] == "1", q["charge"] == "1", q["interieur"] == "1")?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            // hackrf_info only (it lists the boards, sends nothing).
            "/api/datv/emission/hackrf" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                val i = e.detecteHackrf()
                return ok.put("message", i.message).put("cartes", JSONArray(i.cartes.map { it.libelle }))
            }
            // bladeRF-cli -e info only (it opens the board, prints what it is, sends nothing).
            "/api/datv/emission/bladerf" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                val i = e.detecteBladerf()
                return ok.put("message", i.message).put("libelle", i.carte?.libelle ?: "")
            }
            "/api/datv/emission/arrete" -> emission?.arrete()
            // SSTV on the air: the parameters (kept; « vers un satellite » never), the confirmed start, the stop, the page's heartbeat.
            "/api/sstv/emission/reglage" -> {
                val e = emissionSstv ?: return JSONObject().put("ok", false)
                if (e.etat.emet && (q.keys - setOf("gain")).isNotEmpty()) return JSONObject().put("ok", false).put("erreur", "en émission : seul le gain change")
                q["emetteur"]?.let { e.emetteur = fr.f4ioz.satcombo.datv.Emetteur.de(it) }
                q["modulation"]?.let { m -> e.modulation = fr.f4ioz.satcombo.sdr.ModulationAudio.de(m) ?: return JSONObject().put("ok", false).put("erreur", "modulation inconnue") }
                q["dev"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.deviationHz = it }
                q["preacc"]?.let { e.preaccentuation = it == "1" }
                q["mhz"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.frequenceKhz = Math.round(it * 1000.0) }
                q["intMhz"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.frequenceInterieurKhz = Math.round(it * 1000.0) }
                q["mode"]?.let { m -> e.mode = fr.f4ioz.satcombo.sstv.SstvMode.byName(m) ?: return JSONObject().put("ok", false).put("erreur", "mode inconnu") }
                q["source"]?.let { if (it in EmissionSstvPc.SOURCES) e.source = it }
                q["planche"]?.let { e.plancheId = it }
                q["fichier"]?.let { e.fichier = it }
                entier("rep")?.let { e.repetitions = it }
                q["cw"]?.let { e.cwId = it == "1" }
                q["gain"]?.replace(',', '.')?.toDoubleOrNull()?.let { e.regleGain(it) }
            }
            "/api/sstv/emission/demarre" -> {
                val e = emissionSstv ?: return JSONObject().put("ok", false)
                e.demarre(q["confirme"] == "1", q["interieur"] == "1", q["charge"] == "1", q["satellite"] == "1")
                    ?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/sstv/emission/arrete" -> emissionSstv?.arrete()
            // The transmitters' I/Q correction: the sliders (kept, live), the test tone and the calibration (indoors, confirmed), the stop.
            "/api/iq/regle" -> {
                val c = iq ?: return JSONObject().put("ok", false)
                val e = fr.f4ioz.satcombo.datv.Emetteur.de(q["app"])
                fun d(k: String) = q[k]?.replace(',', '.')?.toDoubleOrNull()
                q["gainTx"]?.let { d("gainTx")?.let { g -> c.gainTxDb = g } }
                if (q.keys.any { it in setOf("dcI", "dcQ", "gain", "phase", "ppm", "raz") }) {
                    val p0 = c.correction(e, true)?.parametres ?: fr.f4ioz.satcombo.sdr.ParametresIq.NEUTRE
                    val p = if (q["raz"] == "1") fr.f4ioz.satcombo.sdr.ParametresIq.NEUTRE
                        else fr.f4ioz.satcombo.sdr.ParametresIq(d("dcI") ?: p0.dcI, d("dcQ") ?: p0.dcQ, d("gain") ?: p0.gain, d("phase") ?: p0.phaseDeg, d("ppm") ?: p0.ppm)
                    c.regle(e, p)?.let { return JSONObject().put("ok", false).put("erreur", it) }
                }
            }
            "/api/iq/demarre" -> {
                val c = iq ?: return JSONObject().put("ok", false)
                c.demarre(fr.f4ioz.satcombo.datv.Emetteur.de(q["app"]), q["auto"] == "1", q["mesure"] == "1", q["confirme"] == "1")
                    ?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/iq/arrete" -> iq?.arrete()
            "/api/sstv/emission/vie" -> emissionSstv?.vie()
            // A chat line in the stream being sent (refused when nothing is being sent: typing never transmits).
            "/api/datv/emission/chat" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                e.dis(q["texte"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/datv/qso" -> {
                // The station whose card is received, into the log: QO-100 or terrestrial DATV, the MER we measure as our report.
                val c = carnet ?: return JSONObject().put("ok", false)
                // What the card leaves blank, from QRZ (its square never for a /P or /M: QRZ gives the home address).
                val f = qrzDatv?.fiche(q["c"].orEmpty())
                val l = q["l"].orEmpty().ifBlank { if (f != null && !q["c"].orEmpty().contains('/')) f.carre else "" }
                val e = c.ajouteDatv(q["c"].orEmpty(), l, q["rs"].orEmpty(), q["n"].orEmpty(),
                    q["qo100"] == "1", q["khz"]?.toLongOrNull() ?: 0L, q["nm"].orEmpty().ifBlank { f?.nom.orEmpty() },
                    q["qth"].orEmpty().ifBlank { f?.qth.orEmpty() })
                    ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif")
                return ok.put("t", e.timeMs)
            }
            "/api/datv/emission/vie" -> emission?.vie()
            "/api/datv/enregistre" -> return ok.put("nom", datv?.enregistre(q["on"] == "1").orEmpty())
            "/api/datv/udp" -> datv?.regleUdp(q["on"]?.let { it == "1" }, q["dest"])?.let { return JSONObject().put("ok", false).put("erreur", it) }
            "/api/datv/son" -> datv?.coupeSon(q["on"] == "1")
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
                large?.veilleArrete()
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
            // auto=1: a frequency SatMe proposed (a memory), not one tuned by hand.
            "/api/qo100/frequence" -> q["hz"]?.toLongOrNull()?.let { station.qo100.descente(it, origine(q["auto"] == "1")) }
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
            "/api/qo100/cle" -> { if (q["on"] != "1") trafic?.oublie(); station.qo100.aLaCle(q["on"] == "1") }
            // The cockpit: one gesture to receive, a signal tuned in its mode, the session taken back or not.
            "/api/qo100/demarrer" -> {
                val t = trafic ?: return JSONObject().put("ok", false).put("erreur", "pas de récepteur ici")
                t.demarre()?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/qo100/arreter" -> trafic?.arrete()
            "/api/qo100/accorde" -> {
                val hz = q["hz"]?.toLongOrNull() ?: return JSONObject().put("ok", false)
                val a = trafic?.accorde(hz, q["classe"]) ?: run { station.qo100.descente(hz, origine(true)); null }
                a?.let { return ok.put("cadran", it.cadranHz).put("mode", it.modeCarnet).put("decodeur", it.decodeur).put("ft8Mode", it.ft8Mode) }
            }
            "/api/qo100/mode" -> trafic?.mode(q["m"].orEmpty())
            "/api/qo100/reprise" -> {
                val t = trafic ?: return JSONObject().put("ok", false)
                if (q["on"] == "1") t.reprend()?.let { return JSONObject().put("ok", false).put("erreur", it) } else t.oublie()
            }
            "/api/qo100/reprise-auto" -> station.settings.qo100RepriseAuto = q["on"] == "1"
            "/api/qo100/caler-mesure" -> {
                // No measurement: nothing done — certainly not a calibration back to zero.
                val m = sdr?.mesureBalise() ?: return JSONObject().put("ok", false).put("erreur", "La balise n'est pas vue par la clé.")
                station.qo100.calage(station.qo100.etat.value.calageHz + m.ecartArrondiHz)
            }
            "/api/qo100/calage-zero" -> station.qo100.annuleCalage()
            "/api/qo100/auto" -> qo100?.auto = q["on"] == "1"
            "/api/convertisseurs" -> {
                // MHz on the page, Hz here; an empty field leaves it as it is.
                fun hz(k: String) = q[k]?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                station.poseConvertisseurs(hz("rxOl"), q["rxInv"]?.let { it == "1" }, hz("txOl"), q["txInv"]?.let { it == "1" })
            }
            "/api/carnet/ajoute" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                // From the DATV tab, the record already fetched there first (no second QRZ request).
                val fiche = q["c"]?.takeIf { it.isNotBlank() }?.let { cs ->
                    q["datv"]?.let { qrzDatv?.fiche(cs) } ?: kotlinx.coroutines.runBlocking { c.ficheQrz(cs) } }
                    ?.takeIf { it.erreur.isBlank() && !it.vide }
                // Afterwards: the satellite and the time given (a past pass), not the followed one now.
                val t = q["t"]?.toLongOrNull()
                // From the DATV tab: mode DATV, on QO-100 or terrestrial by the frequency received.
                q["datv"]?.toLongOrNull()?.let { k ->
                    val d = c.ajouteDatv(q["c"].orEmpty(), q["l"].orEmpty(), q["rs"].orEmpty(), q["rr"].orEmpty(), q["n"].orEmpty(), k, fiche)
                        ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et la fréquence reçue")
                    return ok.put("t", d.timeMs)
                }
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
                q["pskReporter"]?.let { v -> son?.psk?.let { it.actif = v == "1" } ?: run { s.pskReporter = v == "1" } }
            }
            "/api/doppler" -> station.journalDoppler(q["on"] == "1")
            "/api/lien" -> {
                lien?.regle(q["on"]?.let { it == "1" }, q["port"]?.trim()?.toIntOrNull())
                if (lien != null && lien.erreur.isNotEmpty()) return JSONObject().put("ok", false).put("erreur", lien.erreur)
            }
            "/api/horizon" -> station.poseMasque(fr.f4ioz.satcombo.domain.MasqueHorizon.lis(q["m"]))
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
            // The TH-D72 panel: each change the operator's own; reading never changes the rig.
            "/api/thd72/lire" -> station.thd72.lire()
            "/api/thd72/frequence" -> {
                val b = entier("b")?.coerceIn(0, 1) ?: return JSONObject().put("ok", false)
                val hz = fr.f4ioz.satcombo.demo.ApiQo100.mhz(q["mhz"]) ?: return JSONObject().put("ok", false).put("erreur", "une fréquence en MHz")
                station.thd72.frequence(b, hz)
            }
            "/api/thd72/pas" -> station.thd72.pas(entier("b")?.coerceIn(0, 1) ?: 0, if (q["sens"] == "-1") -1 else 1)
            "/api/thd72/puissance" -> station.thd72.puissance(entier("b")?.coerceIn(0, 1) ?: 0, (entier("p") ?: 0).coerceIn(0, 2))
            "/api/thd72/bandetx" -> station.thd72.setBandeTx((entier("b") ?: 0).coerceIn(0, 1))
            "/api/kiss/connecter" -> {
                val k = kiss ?: return JSONObject().put("ok", false)
                kotlinx.coroutines.runBlocking { k.connecte(entier("port") ?: -1, entier("vitesse") ?: 9600, q["lignes"] == "1") }
                    ?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/kiss/deconnecter" -> kiss?.deconnecte()
            "/api/kiss/reglage" -> {
                val st = station.settings
                q["balise"]?.let { st.aprsBaliseIss = it == "1" }
                q["floue"]?.let { st.aprsPositionFloue = it == "1" }
                entier("ssid")?.let { st.aprsSsid = it.coerceIn(0, 15) }
                kiss?.let { k -> if (q["travail"] != null || q["doppler"] != null) kotlinx.coroutines.runBlocking { k.regle(q["travail"], q["doppler"]?.let { it == "1" }) } }
            }
            // A frame through the KISS radio: only when the operator asks, every check of the phone's first.
            "/api/kiss/emet" -> {
                val k = kiss ?: return JSONObject().put("ok", false)
                kotlinx.coroutines.runBlocking { k.emet(q["type"].orEmpty(), q["dest"].orEmpty(), q["texte"].orEmpty(), q["numero"] == "1",
                    q["chemin"].orEmpty(), q["confirme"] == "1") }?.let { return JSONObject().put("ok", false).put("erreur", it) }
                return ok.put("message", k.envoi)
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
                // The stations waited for: settings, kept even without a sound card.
                q["guettees"]?.let { return JSONObject().put("ok", true).put("guettees", JSONArray(fr.f4ioz.satcombo.domain.Ft8Veille.ecrit(station.stockage, it))) }
                val f = son?.ft8 ?: return JSONObject().put("ok", false)
                when {
                    q["vide"] == "1" -> f.vide()
                    q["tranches"] != null -> f.enregistreTranches = q["tranches"] == "1"
                    q["on"] == "1" -> { if (son.son.etat.value.actif) f.demarre(q["mode"] ?: f.mode) else return JSONObject().put("ok", false).put("erreur", "démarre d'abord la carte son (onglet Son)") }
                    q["on"] == "0" -> f.arrete()
                    q["mode"] != null -> if (f.enMarche) f.demarre(q["mode"]!!) else f.demarre(q["mode"]!!).also { f.arrete() }
                }
            }
            "/api/son/options" -> {
                val s = son ?: return JSONObject().put("ok", false)
                q["auto"]?.let { s.enregAuto = it == "1" }
                q["coupe"]?.let { s.coupeLos = it == "1" }
                q["sstv"]?.let { s.sstvActif = it == "1" }
                q["aprs"]?.let { s.aprsActif = it == "1" }
            }
            // SSTV, as the phone's controls: source, forced mode, continuous, cleaning; start by hand, end.
            "/api/sstv/reglage" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                q["source"]?.let { if (it in SstvPc.SOURCES) x.source = it else return JSONObject().put("ok", false).put("erreur", "source inconnue") }
                q["force"]?.let { x.force = fr.f4ioz.satcombo.sstv.SstvMode.byName(it) }
                q["continu"]?.let { x.continu = it == "1" }
                q["nettoyage"]?.let { x.nettoyage = it == "1" }
                q["afc"]?.let { x.afc = it == "1" }
                q["actif"]?.let { son.sstvActif = it == "1" }
            }
            "/api/sstv/afc/zero" -> son?.sstv?.remiseAZeroAfc() ?: return JSONObject().put("ok", false)
            "/api/sstv/caler" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                x.cale(q["mode"]?.let { fr.f4ioz.satcombo.sstv.SstvMode.byName(it) })?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/sstv/abandon" -> son?.sstv?.abandonne() ?: return JSONObject().put("ok", false)
            "/api/sstv/redecode" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                // A recording by its name, or a picture's own sound (decoded again with what its sidecar says).
                val image = q["image"]?.let { x.fichier(it) }
                val f = if (image != null) fr.f4ioz.satcombo.sstv.SstvSon.fichier(image) else son.fichierEnregistrement(q["nom"].orEmpty())
                    ?: return JSONObject().put("ok", false).put("erreur", "enregistrement introuvable")
                val origine = image?.let { im -> x.images().firstOrNull { it.fileName == im.name } }
                x.redecode(f, origine)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/sstv/annule" -> son?.sstv?.annuleRedecodage() ?: return JSONObject().put("ok", false)
            "/api/sstv/supprime" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                if (!x.supprime(q["nom"].orEmpty())) return JSONObject().put("ok", false).put("erreur", "image introuvable")
            }
            "/api/sstv/gif" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                x.gif(q["nom"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
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

    /**
     * DATV transmission's sources: the folder browser (read only), the files
     * sent from the page, the overlay's settings and the preview (one frame
     * made by ffmpeg, nothing transmitted).
     */
    private fun sourcesDatv(r: RequeteHttp.Requete, c: Socket, chemin: String, q: Map<String, String>, sortie: OutputStream) {
        val json = "application/json; charset=utf-8"
        fun repond(o: JSONObject) = envoie(sortie, "200 OK", json, o.toString().toByteArray())
        val absent = JSONObject().put("ok", false).put("erreur", "émission DATV indisponible")
        val f = fichiersDatv
        val e = emission
        val post = r.methode == "POST"
        when {
            chemin == "/api/datv/sources/liste" -> repond(f?.liste(q["d"].orEmpty(), q["type"].orEmpty()) ?: absent)
            chemin == "/api/datv/sources/envois" -> repond(f?.envoisJson() ?: absent)
            chemin == "/api/datv/sources/vignette.jpg" -> {
                val b = f?.vignette(q["p"].orEmpty())
                if (b == null) envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "pas de vignette".toByteArray())
                else envoie(sortie, "200 OK", "image/jpeg", b)
            }
            post && chemin == "/api/datv/sources/envoi" -> {
                if (f == null) return repond(absent)
                val res = f.recoit(q["nom"].orEmpty(), r.longueur, Borne(c.getInputStream(), r.longueur))
                repond(res.fold({ JSONObject().put("ok", true).put("chemin", it.path).put("nom", it.name) },
                    { JSONObject().put("ok", false).put("erreur", it.message ?: "envoi refusé") }))
            }
            post && chemin == "/api/datv/sources/supprime" -> repond(if (f != null && f.supprime(q["nom"].orEmpty())) JSONObject().put("ok", true)
                else JSONObject().put("ok", false).put("erreur", "fichier introuvable"))
            chemin == "/api/datv/emission/incrustation" -> {
                val i = e?.incrustation ?: return repond(absent)
                if (post) i.regle(q)
                repond(i.json())
            }
            post && chemin == "/api/datv/emission/apercu" -> {
                if (e == null) return repond(absent)
                val err = e.apercu()
                repond(if (err == null) JSONObject().put("ok", true).put("t", System.currentTimeMillis()) else JSONObject().put("ok", false).put("erreur", err))
            }
            chemin == "/api/datv/emission/apercu.jpg" -> {
                val a = e?.fichierApercu
                if (a == null || !a.isFile) envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "pas d'aperçu".toByteArray())
                else envoie(sortie, "200 OK", "image/jpeg", a.readBytes())
            }
            else -> envoie(sortie, "404 Not Found", json, "{\"ok\":false}".toByteArray())
        }
    }

    /** A file as it is on disk, by parts when the browser asks (a sound it seeks in). */
    /** « Phonie → texte »: its state and overs, its settings, the day's transcript to download. */
    /** The station card received on DATV, with where it is from us and whether it is in the log. */
    private fun carteDatv(d: DatvPc): JSONObject = d.carte.json(System.currentTimeMillis(), station.etat.value.observateur,
        { c -> carnet?.historique(c)?.size ?: 0 }, d.reglage.cielKhz, d.verrouDepuisMs)

    private fun phonie(r: RequeteHttp.Requete, chemin: String, q: Map<String, String>, sortie: OutputStream, p: PhoniePc) {
        val json = "application/json; charset=utf-8"
        if (r.methode == "POST") {
            val res = runCatching { p.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", json, res.toString().toByteArray()); return
        }
        when (chemin) {
            "/api/phonie" -> envoie(sortie, "200 OK", json, p.json(q["depuis"]?.toLongOrNull() ?: 0L).toString().toByteArray())
            "/api/phonie/moteur" -> envoie(sortie, "200 OK", json, p.jsonMoteur().toString().toByteArray())
            "/api/phonie/jours" -> envoie(sortie, "200 OK", json, JSONObject().put("jours", JSONArray(p.jours())).toString().toByteArray())
            "/phonie/journal" -> {
                val rien = "rien de transcrit ce jour".toByteArray()
                val jour = q["jour"].orEmpty().takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: p.jours().firstOrNull()
                    ?: return envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", rien)
                if (q["format"] == "jsonl") {
                    val f = java.io.File(p.fichierJour(0L).parentFile, "$jour.jsonl")
                    if (!f.isFile) return envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", rien)
                    envoieFichier(sortie, r, f, "application/x-ndjson", "Content-Disposition: attachment; filename=\"SatMe_phonie_$jour.jsonl\"\r\n")
                } else {
                    val t = p.texteJour(jour) ?: return envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", rien)
                    envoie(sortie, "200 OK", "text/plain; charset=utf-8", t.toByteArray(Charsets.UTF_8), "Content-Disposition: attachment; filename=\"SatMe_phonie_$jour.txt\"\r\n")
                }
            }
            else -> envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
        }
    }

    private fun envoieFichier(s: OutputStream, r: RequeteHttp.Requete, f: java.io.File, typeDonne: String? = null, entetes: String = "") {
        val type = typeDonne ?: when {
            f.name.endsWith(".png") -> "image/png"
            f.name.endsWith(".wav") -> "audio/wav"
            f.name.endsWith(".mp3") -> "audio/mpeg"
            f.name.endsWith(".gif") -> "image/gif"
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
            .put("satellite", e.satellite).put("datvTient", d.datvTient ?: "")
            .put("gain", st.sdrGainTenthDb).put("agc", st.sdrAgc).put("ppm", st.sdrPpm).put("mode", d.modeChoisi).put("bw", st.sdrBandwidthHz)
            .put("squelch", st.sdrSquelchDb).put("son", st.sdrAudio).put("deemph", st.sdrDeemph).put("doppler", st.sdrDopplerTrack)
            .put("suit", d.suitSatellite).put("manuelle", d.manuelleHz).put("hote", d.hote).put("port", d.port).put("lance", d.lance).put("bias", d.biasTee)
            .put("recepteur", d.recepteur).put("plutoHote", d.plutoHote).put("plutoPort", d.plutoPort)
            .put("bladerfOutil", d.bladerfOutil).put("bladerfProgramme", d.programmeBladerf() ?: "").put("bladerfConseil", d.conseilBladerf())
            .put("bladerf", d.infoBladerf.let { i -> JSONObject().put("lu", i.lu).put("message", i.message).put("libelle", i.carte?.libelle ?: "") })
            // Fine tuning, the phone's settings: the magnifier, the vernier, the voice netting.
            .put("modeEff", d.modeEffectif()).put("loupe", st.sdrLoupe).put("loupeSpan", st.sdrLoupeSpanHz)
            .put("vernier", st.sdrVernier).put("ratio", st.sdrVernierHzParCm).put("calageVoix", st.sdrCalageVoix)
    }

    /**
     * The last spectrum, [n] values (dB, the strongest of the bins each one
     * covers) over [span] Hz around the Doppler target — the signal stays in
     * the middle while the satellite passes. Null values before the first.
     */
    fun spectre(d: SdrPc, span: Int, n: Int, autourEcoute: Boolean = false): JSONObject {
        val sp = d.spectre ?: return JSONObject().put("trames", 0)
        val e = d.etat.value
        // The magnifier: around where we listen (the offset in), not around the Doppler target.
        val centre = e.fineHz.toDouble() + if (autourEcoute) e.decalageHz else 0
        return fr.f4ioz.satcombo.demo.ApiSdr.spectre(sp, Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size, centre, span, n, d.trames.toLong(), e.pllHz)
    }

    /** QO-100: the pilot's state, the dish and the Sun; with [plan], the band plan and the presets (they never change). */
    fun qo100(st: StationPc, plan: Boolean, gammeCle: LongRange? = null): JSONObject {
        // The tab opened: what is stored read again (the Station tab sets the same calibration).
        if (plan) st.qo100.ouvre()
        val e = st.qo100.etat.value
        return ApiQo100.etat(e, st.etat.value.catConnecte, st.versCle(e.descenteHz + e.calageHz),
            st.cielQo100()?.let { k -> ApiQo100.Ciel(k.pointage, k.soleilAzimutMs, k.transits) }, st.etat.value.observateur != null, plan,
            gammeCle ?: ApiQo100.GAMME_CLE)
    }

    fun etat(st: StationPc, carnet: CarnetPc? = null): JSONObject {
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
        o.put("amsatAuto", st.settings.amsatAuto).put("amsatEtat", st.pushAmsat.etat)
        o.put("convertisseurs", JSONObject()
            .put("rxOl", if (rx.configure) rx.olHz else 0).put("rxInv", rx.inverseur)
            .put("txOl", if (tx.configure) tx.olHz else 0).put("txInv", tx.inverseur))
        o.put("cat", fr.f4ioz.satcombo.demo.ApiStation.cat(c, e.catModele, st.settings.civUsbIndex, e.catConnecte, e.catSimule, e.catStatut, e.maintienDoppler))
        o.put("rotor", fr.f4ioz.satcombo.demo.ApiStation.rotor(r))
        // The log's pass: the skeds announced for the pass under way or next, the contacts of the one under way
        // (or of the one just over, an hour after it).
        val s = e.suivi
        val p = e.passage
        if (s != null && p != null) {
            val maintenant = e.maintenant.takeIf { it > 0L } ?: System.currentTimeMillis()
            val c = if (p.aosEpochMs <= maintenant) p else e.dernierPassage?.takeIf { it.losEpochMs > maintenant - 3_600_000L } ?: p
            val contacts = fr.f4ioz.satcombo.domain.SkedsPassage.contacts(carnet?.contacts().orEmpty(), s.catalogNumber, s.name, c.aosEpochMs, c.losEpochMs)
            o.put("passageCarnet", fr.f4ioz.satcombo.demo.ApiStation.passageCarnet(p.aosEpochMs, p.losEpochMs, st.skedsDu(p),
                c.aosEpochMs, c.losEpochMs, contacts))
        }
        o.put("skedsOn", st.settings.skedsEnabled)
        return o
    }

    private fun passage(p: fr.f4ioz.satcombo.data.SatPass, st: StationPc? = null) = fr.f4ioz.satcombo.demo.ApiStation.passage(p,
        fav = st?.etat?.value?.favoris?.contains(p.catalogNumber) == true, agenda = st?.auAgenda(p) == true,
        amsat = st?.statutAmsat(p.satName)?.recent?.name ?: "", skeds = st?.skedsDu(p)?.size ?: 0)

    /** The automatic mode: its choices, its plan for 24 h, and what it waits for to work. */
    fun veille(st: StationPc, s: SonStation?): JSONObject {
        val e = st.etat.value
        val maintenant = e.maintenant.takeIf { it > 0L } ?: System.currentTimeMillis()
        val r = st.stationRotor.etat.value
        return fr.f4ioz.satcombo.demo.ApiVeille.json(st.settings, { n -> e.satellites.firstOrNull { it.catalogNumber == n }?.name },
            st.planVeille(maintenant), maintenant,
            fr.f4ioz.satcombo.demo.ApiVeille.Pret(s?.son?.etat?.value?.actif == true, e.catConnecte, r.rotorConnected, r.rotorEnabled, e.calculEnCours))
    }

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
    fun datv(d: DatvPc): JSONObject = Datv.json(d.etat, d.reglage, d.constellation()).put("qualite", d.qualite() ?: JSONObject.NULL)
        .put("noeud", d.noeud).put("nomNoeud", d.nomNoeud)
        .put("programme", d.programme() ?: JSONObject.NULL).put("chemin", d.chemin).put("windows", OutilsPc.windows)
        .put("debits", JSONArray(Datv.DEBITS)).put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("balise", Datv.BALISE_KHZ)
        .put("udp", JSONObject().put("on", d.udpOn).put("dest", d.udpDestination).put("datagrammes", d.udpDatagrammes).put("erreur", d.udpErreur))
        .put("recepteur", d.recepteur).put("sdrTenu", d.sdrTenu ?: "").put("s2", d.logiciel?.let { l ->
            val e = l.etatS2()
            JSONObject().put("fs", l.fs).put("trames", e.tramesDonnees).put("vides", e.tramesVides).put("echecs", e.ldpcEchecs)
                .put("sautees", e.tramesSautees).put("iterations", Math.round(e.iterationsMoyennes * 10) / 10.0).put("paquets", e.paquets)
                .put("paquetsFaux", e.paquetsFaux).put("decalage", Math.round(e.decalageHz)).put("verrouillages", e.verrouillages)
        } ?: JSONObject.NULL)

    fun reglages(st: StationPc, son: SonStation? = null): JSONObject {
        val s = st.settings
        return JSONObject().put("c", s.callsign).put("l", s.manualLocator).put("qrzUser", s.qrzUser)
            .put("qrzMdp", s.qrzMdp.isNotEmpty()).put("carnetUrl", s.carnetUrl).put("carnetCle", s.carnetCle.isNotEmpty())
            .put("carnetProfil", s.carnetProfil).put("minEl", s.minElevDeg).put("fiche", st.ficheQrz)
            .put("carnetAuto", s.carnetAuto).put("carnetDelai", s.carnetAutoDelaiS)
            .put("amsatAuto", s.amsatAuto).put("amsatEtat", st.pushAmsat.etat)
            .put("pskReporter", s.pskReporter).put("pskEtat", son?.psk?.etat().orEmpty())
            .put("delais", JSONArray(fr.f4ioz.satcombo.domain.EnvoiAuto.DELAIS_S))
            .put("horizon", JSONArray(s.masqueHorizon.elevationsDeg))
    }

    /**
     * Where a station is from mine: its place (QRZ's coordinates, else its
     * square's centre), the distance and the bearing (great circle).
     */
    fun son(s: SonStation?, sdr: SdrPc? = null): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        val c = s.son.etat.value
        val e = s.etat.value
        val v = s.sstv.etat.value
        return ApiSon.json(ApiSon.Etat(s.son.appareils().map { it.nom to it.description }, s.carte, c.actif, c.rate, c.niveau, c.erreur,
            e.enregistrement, e.secondes, e.auto, s.enregAuto, s.sstvActif, s.aprsActif,
            v.mode, v.lignes, v.hauteur, v.version, v.derniere, s.enregistrements(), s.images(), s.coupeLos,
            detail = sstvDetail(s, sdr), dossierImages = s.dossierSstv))
            // What actually feeds the decoders (the dongle when it took the card's place), not only the card chosen.
            .put("appareil", c.appareil)
    }

    /** The SSTV decoder in detail: its source, what it hears, its settings, the work under way. */
    fun sstvDetail(s: SonStation, sdr: SdrPc?): ApiSon.Sstv {
        val x = s.sstv
        val v = x.etat.value
        val e = x.ecoute()
        val sdrActive = sdr?.etat?.value?.actif == true
        val carte = s.son.etat.value
        val src = when (x.sourceActive) {
            SstvPc.SDR -> "clé SDR"
            SstvPc.CARTE -> if (carte.appareil == SdrPc.NOM_SOURCE) "clé SDR (par l'onglet Son)" else "carte son" + (carte.appareil.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            else -> ""
        }
        return ApiSon.Sstv(source = x.source, sourceActive = if (e.depuisMs in 0..SstvPc.SON_VU_MS) src else "", depuisMs = e.depuisMs,
            niveau = e.niveau, ecartHz = e.ecartHz, ecartSyncHz = e.ecartSyncHz, spectre = e.spectre,
            spectreBasHz = fr.f4ioz.satcombo.sstv.SstvAccord.BASSE_HZ, spectrePasHz = fr.f4ioz.satcombo.sstv.SstvAccord.PAS_HZ,
            force = x.force?.name.orEmpty(), continu = x.continu, nettoyage = x.nettoyage, decodage = e.decodage,
            progres = e.progres, gardees = v.gardees, erreur = v.erreur,
            conseil = x.conseil(carte.actif, sdrActive).orEmpty(),
            redecode = v.redecode, redecodeProgres = v.redecodeProgres, redecodeImages = v.redecodeImages, gif = v.gif,
            calage = calage(s, sdr))
    }

    /** The SSTV automatic tuning for the page: what it moves (or why it does not), its numbers. */
    private fun calage(s: SonStation, sdr: SdrPc?): ApiSon.Calage {
        val x = s.sstv
        val parLaCle = x.sourceActive == SstvPc.SDR || s.son.etat.value.appareil == SdrPc.NOM_SOURCE
        val catConnecte = s.catConnecte()
        val c = x.calage("")
        val voie = when {
            x.sourceActive.isEmpty() -> ""
            parLaCle -> if (c.sens == 0) "clé SDR : en " + (sdr?.modeEffectif() ?: "FM") + " l'accord ne déplace pas les tons, rien à caler"
                else "décalage fin de la clé SDR"
            !catConnecte -> "poste sans CAT : conseil seulement"
            c.sens == 0 -> "poste : transpondeur hors BLU, rien à caler"
            !c.applique -> "poste par CAT : en attente (Doppler en maintien ou bouton tourné), conseil seulement"
            else -> "poste par CAT, avec les écritures du Doppler"
        }
        return ApiSon.Calage(c.actif, c.mesureHz, c.totalHz, c.actif && c.applique, voie, c.visHz, c.corrections, c.conseil)
    }

    /** The CW decoder: on or not, its text, the speed, the tone, the key's light. */
    fun cw(s: SonStation?): JSONObject {
        val w = s?.cw ?: return JSONObject().put("present", false)
        return JSONObject().put("present", true).put("on", w.actif).put("texte", w.texte()).put("wpm", w.wpm).put("ton", w.tonHz)
            .put("auto", w.tonChoisi == 0).put("marque", w.marque).put("ecart", Math.round(w.ecartDb)).put("version", w.version)
            .put("carte", s.son.etat.value.actif).put("suivi", w.suivi)
            .put("mots", JSONArray(w.mots().map { m -> JSONObject().put("m", m.texte).put("g", m.genre.name).put("s", m.score).put("c", m.coherent) }))
    }

    fun bilan(b: ImportPc.Bilan?): JSONObject = if (b == null) JSONObject().put("ok", false).put("erreur", "fichier illisible ou trop gros")
        else JSONObject().put("ok", b.message.isEmpty() || b.lus > 0).put("lus", b.lus).put("ajoutes", b.ajoutes).put("passages", b.passages)
            .put("inconnus", b.inconnus).put("erreur", b.message)

    /** FT8 heard: the decodes with what they mean here (CQ, for me, how far, already worked), the waterfall. */
    fun ft8(s: SonStation?, st: StationPc, c: CarnetPc?): JSONObject {
        val f = s?.ft8 ?: return JSONObject().put("present", false)
        val contacts = c?.contacts().orEmpty()
        val faits = contacts.map { it.callsign.uppercase().substringBefore('/') }.toHashSet()
        val paysFaits = contacts.mapNotNull { fr.f4ioz.satcombo.domain.Dxcc.entite(it.callsign)?.nom }.toHashSet()
        val carresFaits = contacts.mapNotNull { it.theirLocator.trim().uppercase().take(4).takeIf { l -> l.length == 4 } }.toHashSet()
        val k = f.cascade
        return fr.f4ioz.satcombo.demo.ApiRadio.ft8(f.enMarche, f.mode, f.avancement(), f.niveau, f.tranches, s.son.etat.value.actif,
            Ft8Pc.BASSE_HZ, Ft8Pc.HAUTE_HZ, emptyList(), k?.dernierSpectre ?: FloatArray(0),
            f.entendus.map { e -> fr.f4ioz.satcombo.demo.ApiRadio.Ligne(e.tranche, e.texte, e.appelant, e.appele, e.locator,
                e.frequenceHz, e.rapportDb, e.decalageS, e.mode) },
            st.etat.value.indicatif.uppercase(), st.etat.value.observateur, faits, paysFaits, carresFaits,
            guettees = fr.f4ioz.satcombo.domain.Ft8Veille.lit(st.stockage), sonDepuisMs = f.sonDepuisMs(), sansDecodage = f.sansDecodage)
            .put("enregistreTranches", f.enregistreTranches)
    }

    /** APRS heard: the frames (newest first) and what they add up to. */
    fun aprs(s: SonStation?, st: StationPc): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        return fr.f4ioz.satcombo.demo.ApiRadio.aprs(s.aprs.paquets, s.aprsActif && s.son.etat.value.actif, s.aprs.recues, st.etat.value.observateur)
    }

    fun ports(st: StationPc) = JSONArray(st.ports.liste().map {
        JSONObject().put("index", it.index).put("libelle", st.nomPort(it)).put("vendor", it.vendorId)
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
