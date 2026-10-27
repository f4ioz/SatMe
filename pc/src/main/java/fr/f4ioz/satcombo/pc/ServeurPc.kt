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
import fr.f4ioz.satcombo.demo.FonctionsNonSat
import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.demo.PagePc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.demo.RequeteHttp
import org.json.JSONArray
import org.json.JSONObject
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.TableConversion
import fr.f4ioz.satcombo.domain.UsageConversion
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

    /** The IC-9700 by the network: settings, status, scope (set before [demarre]). */
    @Volatile var icomReseau: IcomReseauPc? = null

    /** QO-100's wide band spectrum from the ground station, its signals and the « veille » (set before [demarre]). */
    @Volatile var large: SpectreLargePc? = null
    /** The wide band from our own SDR (DATV tab), off unless chosen. */
    @Volatile var spectreSdr: SpectreSdrDatvPc? = null
    /** The wide band chat (off unless chosen). */
    @Volatile var chatLarge: ChatLargePc? = null
    /** Tune requests by UDP (off unless chosen). */
    @Volatile var accordUdp: AccordUdpPc? = null

    /** The DATV transmission's pictures and videos: the folder browser and the files sent (set before [demarre]). */
    @Volatile var fichiersDatv: FichiersDatvPc? = null

    /** SSTV on the air with the Pluto or the bladeRF (set before [demarre]). */
    @Volatile var emissionSstv: EmissionSstvPc? = null
    /** The SatMe-Node joined, beyond DATV: its SDR, its transmitter. */
    @Volatile var noeudDistant: NoeudDistantPc? = null

    /** The transmitters' I/Q correction and its calibration (set before [demarre]). */
    @Volatile var iq: CalibrationIqPc? = null

    /** The station's frequency for logging programs, rigctld protocol (set before [demarre]). */
    @Volatile var rigctld: RigctldPc? = null

    /** The USB volume knob as a VFO, Linux (set before [demarre]). */
    @Volatile var molette: MoletteUsbPc? = null
    /** The antenna polarisation switch (module « Polarisation »). */
    @Volatile var polarisation: PolarisationPc? = null
    /** This PC relaying its knob to another station. */
    @Volatile var relaisMolette: RelaisMolettePc? = null

    /** The IC-9700 transmitting: PTT, microphone, power (set before [demarre]). */
    @Volatile var emissionPoste: EmissionPostePc? = null
    /** FT8/FT4 on the air from the FT8 tab (through [emissionPoste]'s PTT). */
    @Volatile var ft8Tx: Ft8EmissionPc? = null
    /** QO-100's narrow band by the Pluto: SSB, CW, digital; the uplink profiles (set before [demarre]). */
    @Volatile var emissionBe: EmissionBandeEtroitePc? = null

    /** HTTPS for the remote page: its certificate (set before [demarre]); on only when chosen, on this PC. */
    @Volatile var certificat: CertificatPc? = null
    private var socketHttps: ServerSocket? = null
    @Volatile var erreurHttps = ""; private set
    var httpsActif: Boolean
        get() = station.stockage.getBoolean("pc_https", false)
        set(v) { station.stockage.edit().putBoolean("pc_https", v).apply() }
    var httpsPort: Int
        get() = station.stockage.getInt("pc_https_port", 8443).coerceIn(1, 65535)
        set(v) { station.stockage.edit().putInt("pc_https_port", v.coerceIn(1, 65535)).apply() }
    val portHttpsReel: Int get() = socketHttps?.takeIf { !it.isClosed }?.localPort ?: 0

    /** Spoken announcements (set before [demarre]); their sentences ride the live stream. */
    @Volatile var annonces: AnnoncesPc? = null
    /** « Traduction parlée » (off unless chosen). */
    @Volatile var parole: ParolePc? = null

    /** The « Montage » page: the station's chain, its drawing lit by what happens (set before [demarre]). */
    @Volatile var montage: MontagePc? = null
    @Volatile var memoiresSdr: MemoiresSdrPc? = null
    /** The SDR tab's scan and band stack (set before [demarre]). */
    @Volatile var balayage: BalayagePc? = null
    /** « Niveau dans le temps » (set before [demarre]). */
    @Volatile var niveaux: NiveauxPc? = null
    /** The Sun's noise and the raster around it (set before [demarre]). */
    @Volatile var soleil: SoleilPc? = null
    /** « Décodeurs multiples » in the QO-100 cockpit (set before [demarre]). */
    @Volatile var canaux: CanauxPc? = null
    @Volatile var telemesure: TelemesurePc? = null
    /** ADS-B (Avions tab) and the shared map (set before [demarre]). */
    @Volatile var adsb: AdsbPc? = null
    @Volatile var ais: AisPc? = null
    @Volatile var espace: MeteoSpatialePc? = null
    /** The satellites' cards: a photo and a summary (Wikipedia, Commons, kept on disk), SatMe's facts. */
    @Volatile var fiches: FichesSatPc? = null
    /** The ISS's live video: NASA's live of the moment (Orbites › ISS en direct). */
    @Volatile var issDirect: IssDirectPc? = null
    /** « Journal détaillé » (spectra and decodes per pass, for the report). */
    @Volatile var spectreJournal: SpectreJournalPc? = null
    @Volatile var carte: CartePc? = null
    /** The SDR's raw I/Q kept and played back (set before [demarre]). */
    @Volatile var iqSdr: IqPc? = null
    /** « Agenda › SSTV »: the campaigns and their auto-pilot (set before [demarre]). */
    @Volatile var autopilote: AutoPiloteSstvPc? = null
    @Volatile var activites: ActivitesPc? = null
    /** The SSTV gallery sorted by slide, the series' timetable (set before [demarre]). */
    @Volatile var classement: ClassementSstvPc? = null
    /** The SSTV options (missing slide's notice, retention, QSL, ARISS pack). */
    @Volatile var optionsSstv: OptionsSstvPc? = null
    /** « Agenda › Diplômes »: the awards, matched with the gallery and the log. */
    @Volatile var diplomes: DiplomesPc? = null
    /** « Réglages › Site web »: what is published on the operator's web site (set before [demarre]). */
    @Volatile var site: SitePc? = null

    /** What the « Montage » page lights: the same state the other tabs read. */
    fun lecturesMontage(): fr.f4ioz.satcombo.station.ActiviteMontage.Lectures = fr.f4ioz.satcombo.station.ActiviteMontage.Lectures(
        etat = runCatching { JsonPc.etat(station, carnet) }.getOrNull(),
        sdr = sdr?.let { s -> runCatching { JsonPc.sdr(s) }.getOrNull() },
        datv = datv?.let { d -> runCatching { JsonPc.datv(d) }.getOrNull() },
        emissionDatv = emission?.let { e -> runCatching { e.json().put("ok", true) }.getOrNull() },
        emissionSstv = emissionSstv?.let { e -> runCatching { e.json().put("ok", true) }.getOrNull() },
        iq = iq?.let { i -> runCatching { i.json().put("ok", true) }.getOrNull() },
        qo100 = runCatching { JsonPc.qo100(station, false, sdr?.gammeHz) }.getOrNull(),
        lien = lien?.let { l -> JSONObject().put("on", l.actif).put("ecoute", l.ecoute).put("clients", l.nbClients) },
        boites = runCatching { boitesMontage() }.getOrNull(),
        icom = icomReseau?.let { i -> runCatching { i.json() }.getOrNull() },
        molette = molette?.let { m -> runCatching { m.json() }.getOrNull() })

    /** What the montage's functions add to the parts' state: the tabs not about satellites, ADS-B, AIS, QO-100's channels, the scan, the listeners. */
    private fun extrasMontage(): JSONObject = JSONObject()
        .put("nonSat", JSONArray(FonctionsNonSat.choisis(station.stockage).orEmpty().toList()))
        .put("adsb", adsb?.let { a -> runCatching { a.json() }.getOrNull()?.let { j -> JSONObject().put("actif", j.optBoolean("actif")).put("source", j.optString("source")) } } ?: JSONObject.NULL)
        .put("ais", ais?.let { a -> runCatching { a.json() }.getOrNull()?.let { j -> JSONObject().put("actif", j.optBoolean("actif")).put("source", j.optString("source")) } } ?: JSONObject.NULL)
        .put("canaux", canaux?.let { k -> runCatching { k.json().optJSONArray("canaux") }.getOrNull() } ?: JSONArray())
        .put("balayage", balayage?.let { b -> runCatching { b.json().optBoolean("actif") }.getOrNull() } ?: false)
        .put("auditeurs", diffusion?.nombre() ?: 0)

    /** The programs and services beside the computer, as this PC knows them (settings, services on): nothing asked of them. */
    private fun boitesMontage(): JSONObject {
        val s = station.settings
        val o = JSONObject()
            .put("wavelog", JSONObject().put("url", s.carnetUrl.isNotBlank()).put("cle", s.carnetCle.isNotBlank()))
            .put("qrz", JSONObject().put("configure", s.qrzUser.isNotBlank() && s.qrzMdp.isNotBlank()))
            .put("psk", JSONObject().put("on", son?.psk?.actif ?: s.pskReporter).put("etat", son?.psk?.etat().orEmpty()))
            .put("telephone", JSONObject().put("connecte", telephone?.connecte == true).put("adresse", telephone?.adresse.orEmpty()))
            .put("ecoute", JSONObject().put("on", diffusion?.ecoute("sdr") == true || diffusion?.ecoute("datv") == true).put("clients", diffusion?.nombre() ?: 0))
        chatLarge?.let { c -> o.put("chat", JSONObject().put("on", c.on).put("etat", c.etat)) }
        rigctld?.let { r -> runCatching { r.json() }.getOrNull()?.let { j -> o.put("carnets", JSONObject().put("on", j.optBoolean("on")).put("clients", j.optInt("clients")).put("port", j.optInt("port"))) } }
        lien?.let { l -> o.put("lien", JSONObject().put("on", l.actif).put("clients", l.nbClients).put("port", l.port)) }
        datv?.let { d -> o.put("noeud", JSONObject().put("nom", d.nomNoeud.ifBlank { d.noeud }).put("actif", d.noeud.isNotBlank() && d.etat.actif)) }
        espace?.let { e -> runCatching { e.json() }.getOrNull()?.let { j ->
            o.put("espace", JSONObject().put("on", j.optBoolean("actif")).put("erreur", if ((j.optJSONObject("erreurs")?.length() ?: 0) > 0) "lecture en erreur" else "")
                .put("texte", j.optJSONObject("kp")?.optString("texte")?.takeIf { it.isNotBlank() }?.let { "Kp " + it } ?: ""))
        } }
        return o
    }

    /** The « Montage » page's functions: their state, and its receive gestures through the page's own routes. */
    val fonctionsMontage: FonctionsPc by lazy { FonctionsPc(station, sdr, son, datv, phonie, diffusion, { c, q -> action(c, q) }, { lecturesMontage() }, { extrasMontage() }) }

    /** The password asked of other machines when the page is open to the network (set before [demarre]). */
    @Volatile var acces: AccesDistantPc? = null

    /** This PC asking (free, as before the password); another machine needs a session. */
    @Volatile internal var estLocal: (InetAddress) -> Boolean = { it.isLoopbackAddress }

    /** « Configuration rapide », « Quoi de neuf », Réglages › Démarrage, the update check, « Déploiement » (set before [demarre]). */
    @Volatile var demarrage: DemarragePc? = null
    /** The demonstration's « Remettre à neuf » (set by Main with `--demo`): what it did. */
    @Volatile var demoReset: (() -> JSONObject)? = null

    /** QRZ.com records of the callsigns seen in DATV, and their photos (set before [demarre]). */
    @Volatile var qrzDatv: QrzDatvPc? = null

    private val sonDistant: SonDistantPc? by lazy { diffusion?.let { SonDistantPc(it) } }

    private var socket: ServerSocket? = null
    val portReel: Int get() = socket?.localPort ?: port

    /** Who drives the transmissions now, for every client ([fr.f4ioz.satcombo.datv.ControleEmission]). */
    val controle = fr.f4ioz.satcombo.datv.ControleEmission().also { it.chienLocal = station.txChienLocal }

    private val ROUTES_TX = setOf("/api/datv/emission/demarre", "/api/datv/emission/vie", "/api/datv/emission/arrete",
        "/api/sstv/emission/demarre", "/api/sstv/emission/vie", "/api/sstv/emission/arrete",
        "/api/poste/emission/demarre", "/api/poste/emission/vie", "/api/poste/emission/arrete",
        "/api/be/emission/demarre", "/api/be/emission/vie", "/api/be/emission/arrete")

    private fun emetQuelqueChose() = emission?.etat?.emet == true || emissionSstv?.etat?.emet == true || emissionPoste?.poste?.etat?.emet == true ||
        emissionBe?.emet == true || ft8Tx?.arme == true

    /** The Pluto's narrow-band PTT routes: hold (demarre, ?confirme=1 on the air), still holding (vie), release (arrete). */
    private fun actionBe(chemin: String, q: Map<String, String>, cl: fr.f4ioz.satcombo.datv.ControleEmission.Client): JSONObject {
        val b = emissionBe ?: return JSONObject().put("ok", false).put("absent", true)
        return when {
            chemin.endsWith("/demarre") -> b.appuie(cl.id, cl.distant, cl.adresse, q["confirme"] == "1")?.let { JSONObject().put("ok", false).put("erreur", it) }
                ?: b.json(cl.id).put("local", !cl.distant)
            chemin.endsWith("/vie") -> if (b.vie(cl.id)) b.json(cl.id).put("local", !cl.distant) else JSONObject().put("ok", false).put("erreur", "PTT non tenu par ce client")
            else -> { b.relache("relâché"); b.json(cl.id).put("local", !cl.distant) }
        }
    }

    /** The rig's PTT routes: hold (demarre), still holding (vie), release (arrete). */
    private fun actionPoste(chemin: String, cl: fr.f4ioz.satcombo.datv.ControleEmission.Client): JSONObject {
        val p = emissionPoste ?: return JSONObject().put("ok", false).put("absent", true)
        return when {
            chemin.endsWith("/demarre") -> p.appuie(cl.id, cl.distant, cl.adresse)?.let { JSONObject().put("ok", false).put("erreur", it) } ?: p.json(cl.id)
            chemin.endsWith("/vie") -> if (p.vie(cl.id)) p.json(cl.id) else JSONObject().put("ok", false).put("erreur", "PTT non tenu par ce client")
            else -> { p.relache("relâché"); p.json(cl.id) }
        }
    }

    /** A transmission route through the controller's rules, then as before. */
    private fun controleTx(chemin: String, methode: String, q: Map<String, String>, cl: fr.f4ioz.satcombo.datv.ControleEmission.Client): JSONObject {
        fun etat() = controle.json(cl).put("ok", true)
            .put("datv", emission?.etat?.let { e -> JSONObject().put("emet", e.emet).put("khz", e.frequenceKhz).put("parNoeud", e.parNoeud).put("emetteur", e.emetteur.libelle) } ?: JSONObject.NULL)
            .put("sstv", emissionSstv?.etat?.let { e -> JSONObject().put("emet", e.emet).put("hz", e.frequenceHz).put("parNoeud", e.parNoeud).put("emetteur", e.emetteur.libelle) } ?: JSONObject.NULL)
        if (chemin == "/api/emission/controle") {
            if (methode == "POST" && q["confirme"] == "1") controle.confirme(cl)
            return etat()
        }
        val poste = chemin.startsWith("/api/poste/")
        val be = chemin.startsWith("/api/be/")
        val quoi = if (poste) "IC-9700" else if (be) "BLU Pluto" else if (chemin.startsWith("/api/datv/")) "DATV" else "SSTV"
        fun faire() = if (poste) actionPoste(chemin, cl) else if (be) actionBe(chemin, q, cl) else action(chemin, q)
        return when {
            chemin.endsWith("/demarre") -> {
                controle.refus(cl, emetQuelqueChose())?.let { return JSONObject().put("ok", false).put("erreur", it).put("controle", controle.json(cl)) }
                val r = runCatching { faire() }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                if (r.optBoolean("ok", true)) controle.prend(cl, quoi)
                r
            }
            // Only the controller's « still there » keeps the transmission.
            chemin.endsWith("/vie") -> if (controle.vie(cl)) faire() else JSONObject().put("ok", false).put("erreur", "un autre client pilote l'émission")
            // STOP: always, from anyone.
            else -> faire().also { if (!emetQuelqueChose()) controle.libere() }
        }
    }

    /** The decoded line the page answers (double-click): its text, callsigns, grid, SNR, frequency and slot. */
    private fun recuFt8(q: Map<String, String>, mode: String): fr.f4ioz.satcombo.domain.Ft8Qso.Recu {
        val t = q["t"]?.toLongOrNull() ?: error("créneau de la ligne manquant")
        val ft4 = mode == "FT4"
        return fr.f4ioz.satcombo.domain.Ft8Qso.Recu(q["repond"].orEmpty(), q["de"]?.ifBlank { null }, q["a"]?.ifBlank { null },
            q["loc"]?.ifBlank { null }, q["db"]?.toIntOrNull() ?: -10, q["hz"]?.toIntOrNull() ?: 0, t,
            fr.f4ioz.satcombo.domain.Ft8Qso.estPaire(fr.f4ioz.satcombo.domain.Ft8Qso.tranche(t, ft4)))
    }

    /**
     * A measurement on our own downlink: the Pluto's error ([quoi] "pluto"),
     * or the IC-9700 chain's uplink oscillator ("poste": the rig keyed in CW
     * by SatMe, its carrier heard) — the latter stored in the chain.
     */
    private fun mesureBe(b: EmissionBandeEtroitePc, quoi: String): JSONObject {
        if (quoi != "poste") return b.mesureCorrection()
        val p = emissionPoste
        if (p == null || !p.poste.etat.emet) return JSONObject().put("ok", false).put("erreur", "mettre l'IC-9700 en émission (CW, porteuse) par SatMe d'abord")
        val q = station.qo100.etat.value
        val f = station.stationCat.etat.value.monteeHz?.let { station.posteTxCat(it) }
            ?: return JSONObject().put("ok", false).put("erreur", "fréquence d'émission du poste inconnue")
        val (ol, msg) = b.mesureOlPoste(f, q.descenteHz)
        if (ol == null) return JSONObject().put("ok", false).put("erreur", msg)
        station.qo100.poseOl(false, ol)
        return JSONObject().put("ok", true).put("ol", ol).put("message", "$msg — gardé dans la chaîne « " + q.chaine + " »")
    }

    fun demarre() {
        // A remote controller gone silent: the transmissions stopped within its watchdog; nobody holds once all stopped.
        thread(isDaemon = true, name = "satme-controle-tx") {
            while (socket?.isClosed != true) {
                runCatching {
                    if (!emetQuelqueChose()) controle.libere()
                    else controle.perdu()?.let { r -> ft8Tx?.arrete(r); emission?.arrete(r); emissionSstv?.arrete(r); emissionPoste?.relache(r); emissionBe?.relache(r); controle.libere() }
                }
                Thread.sleep(200)
            }
        }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(if (reseau) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        socket = s
        ecoute(s, "satme-web")
        relanceHttps()
    }

    private fun ecoute(s: ServerSocket, nom: String) {
        thread(isDaemon = true, name = nom) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "$nom-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
    }

    /**
     * The HTTPS port opened, closed or moved as the settings say: the same
     * page and routes, the same password, over TLS with this PC's
     * self-signed certificate. Plain http stays as it was.
     */
    @Synchronized fun relanceHttps() {
        runCatching { socketHttps?.close() }
        socketHttps = null
        erreurHttps = ""
        val cert = certificat ?: return
        if (!httpsActif || socket?.isClosed != false) return
        try {
            val ss = cert.contexte().serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
            ss.reuseAddress = true
            ss.bind(if (reseau) InetSocketAddress(httpsPort) else InetSocketAddress(InetAddress.getLoopbackAddress(), httpsPort))
            socketHttps = ss
            ecoute(ss, "satme-https")
        } catch (e: Exception) {
            erreurHttps = "port " + httpsPort + " : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Réglages › Accès à distance › HTTPS: its state; [local] sees the settings it may change. */
    private fun jsonHttps(local: Boolean, r: RequeteHttp.Requete): JSONObject {
        val c = certificat ?: return JSONObject().put("ok", false).put("absent", true)
        val o = JSONObject().put("ok", true).put("on", httpsActif).put("port", httpsPort).put("ecoute", portHttpsReel).put("reseau", reseau)
            .put("erreur", erreurHttps).put("local", local).put("ici", r.entetes["host"].orEmpty())
        if (httpsActif || c.fichier.exists()) runCatching { val x = c.certificat()
            o.put("empreinte", CertificatPc.empreinte(x)).put("jusqua", x.notAfter.time)
                .put("noms", JSONArray(runCatching { x.subjectAlternativeNames.map { it[1].toString() } }.getOrDefault(emptyList()))) }
        return o
    }

    private fun reglageHttps(q: Map<String, String>): String? {
        q["port"]?.let { t ->
            val v = t.toIntOrNull() ?: return "port non reconnu"
            if (v !in 1024..65535 || v == portReel) return "port 1024 à 65535, autre que celui de la page"
            httpsPort = v
        }
        q["on"]?.let { httpsActif = it == "1" }
        relanceHttps()
        return null
    }

    fun arrete() { runCatching { socket?.close() }; runCatching { socketHttps?.close() } }

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
        val dx = q["dx"]?.toIntOrNull() ?: 0
        // « Mode distant »: the spectrum at most [fps] times a second, its levels in one byte each (q=8).
        val spectreMs = q["fps"]?.toIntOrNull()?.takeIf { it in 1..60 }?.let { 1000L / it } ?: 0L
        val octets = q["q"] == "8"
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
        var etatMs = 0L; var sonMs = 0L; var sdrMs = 0L; var spectreTrames = -1L; var veilleMs = 0L; var spectreVu = 0L
        var refSon: Any? = null
        var cwVersion = -1L; var cwMs = 0L
        // The page reconnecting (a tab changed) says the last one it had: none lost between two streams.
        var annonceVu = q["an"]?.toLongOrNull()?.takeIf { it >= 0 } ?: annonces?.dernierSeq ?: -1L
        var paroleVu = q["pa"]?.toLongOrNull()?.takeIf { it >= 0 } ?: parole?.dernierSeq ?: -1L
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
                    if (!envoie("son", JsonPc.son(son, sdr, phonie?.actif == true).toString())) return
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
            if ("spectre" in sujets && sdr != null && sdr.trames.toLong() != spectreTrames && maintenant - spectreVu >= spectreMs) {
                spectreTrames = sdr.trames.toLong(); spectreVu = maintenant
                val j = JsonPc.spectre(sdr, span, n, dx = dx)
                if (!envoie("spectre", (if (octets) fr.f4ioz.satcombo.demo.ApiSdr.octets(j) else j).toString())) return
            }
            // Spoken announcements: each sentence once, those handed out since this page joined.
            val an = annonces
            if ("annonce" in sujets && an != null && an.dernierSeq != annonceVu) {
                if (annonceVu < 0) annonceVu = an.dernierSeq
                for (a in an.depuis(annonceVu)) {
                    annonceVu = a.getLong("seq")
                    // Old news is not said.
                    if (maintenant - a.getLong("ms") > 20_000L) continue
                    if (!envoie("annonce", a.toString())) return
                }
            }
            // « Traduction parlée »: each over said once, for the pages that play it.
            val pa = parole
            if ("parole" in sujets && pa != null && pa.dernierSeq != paroleVu) {
                for (a in pa.depuis(paroleVu)) {
                    paroleVu = a.getLong("seq")
                    if (maintenant - a.getLong("ms") > ParolePc.PERIME_MS) continue
                    if (!envoie("parole", a.toString())) return
                }
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
        // Another machine: a session first, whatever it asks (page, API, streams, files).
        val local = estLocal(c.inetAddress)
        if (garde(c, r, chemin, q, sortie, local)) return
        if (chemin == "/api/acces" || chemin.startsWith("/api/acces/")) { acces(c, r, chemin, sortie, local); return }
        // The demonstration made new (its band's button): from this page's own origin only.
        if (chemin == "/api/demo/reset" && r.methode == "POST") {
            val o = when {
                !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]) -> JSONObject().put("ok", false).put("erreur", "origine refusée")
                else -> demoReset?.invoke() ?: JSONObject().put("ok", false).put("erreur", "pas une démonstration")
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/flux") { flux(c, sortie, q); return }
        if (chemin == "/api/son/ws" && sonDistant != null) {
            // « Mode distant »: the low-latency sound, a WebSocket from this page's own origin.
            val d = WebSocketPc.Demande(r.methode, r.chemin, r.entetes)
            when {
                !d.valide -> SonDistantPc.refusHttp(sortie, "400 Bad Request", "WebSocket attendu")
                !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]) -> SonDistantPc.refusHttp(sortie, "403 Forbidden", "origine refusée")
                else -> {
                    sortie.write(WebSocketPc.reponse(d.cle!!)); sortie.flush()
                    sonDistant!!.sert(c, fr.f4ioz.satcombo.demo.SonDistant.Config.de(q["source"], q["rate"], q["codec"]))
                }
            }
            return
        }
        if (chemin == "/api/son/flux" && diffusion != null) {
            // The dongle's or the sound card's sound, to the browser asking (the page open on another PC).
            c.soTimeout = 0
            diffusion.sert(when (q["source"]) { "carte" -> "carte"; "datv" -> "datv"; else -> "sdr" }, sortie)
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
        if (chemin == "/api/datv/emission/vue.mjpeg" && emission != null) {
            // The picture being transmitted, decoded here from a copy of the stream (display only), while transmitting.
            c.soTimeout = 0
            sortie.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=satme\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            var vu: ByteArray? = null
            var calme = 0
            while (true) {
                val img = emission.vue.demande()
                if (img != null && img !== vu) {
                    vu = img; calme = 0
                    val ok = runCatching {
                        sortie.write(("--satme\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n").toByteArray())
                        sortie.write(img); sortie.write("\r\n".toByteArray()); sortie.flush()
                    }.isSuccess
                    if (!ok) return
                } else {
                    // The transmission over (2 s), or nothing new for 30 s: the page asks again when it wants.
                    calme++
                    if ((!emission.etat.emet && calme > 50) || calme > 750) return
                    Thread.sleep(40)
                }
            }
        }
        if (chemin == "/api/datv/emission/vue.jpg" && emission != null) {
            val img = emission.vue.demande()
            if (img == null) envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "pas d'image émise".toByteArray())
            else envoie(sortie, "200 OK", "image/jpeg", img)
            return
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
        // Start-up options, update check, deployment: their own guard (this PC or a session) and their own origin check.
        demarrage?.let { d ->
            val rep = d.sert(chemin, r.methode, q, local, acces?.session(r.cookie(AccesDistantPc.COOKIE)) == true,
                r.entetes["origin"], r.entetes["host"]) { max -> RequeteHttp.corps(c.getInputStream(), r, max) }
            if (rep != null) { envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return }
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
        if (r.methode == "POST" && chemin == "/api/molette/relais") {
            // The other station's password: in the body, never in an address, never kept.
            val f = RequeteHttp.corps(c.getInputStream(), r, 4096)?.let { b -> runCatching { parametres(String(b, Charsets.UTF_8)) }.getOrNull() }.orEmpty()
            val rl = relaisMolette
            val err = if (rl == null) "absent" else rl.connecte(f["adresse"].orEmpty(), f["mdp"].orEmpty())
            envoie(sortie, "200 OK", "application/json; charset=utf-8", (if (err == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", err))
                .put("relais", rl?.json()).toString().toByteArray())
            return
        }
        if (r.methode == "POST" && chemin == "/api/sked/video/navigateur") {
            // The sked's video the page recorded itself (« Globe animé »): from this page's own origin, laid on disk as it comes, made an MP4 here.
            val v = videoSked
            val err = when {
                v == null -> "absent"
                !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]) -> "origine refusée"
                else -> v.recoitNavigateur(r.entetes["content-type"], r.longueur, VideoSked.navigateur { q[it].orEmpty() }) { f ->
                    RequeteHttp.versFichier(c.getInputStream(), r, VideoSked.MAX_NAVIGATEUR, f) }
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (if (err == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", err)).toString().toByteArray())
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
            val rep = journal.sert(chemin.removePrefix("/journal"), { q[it].orEmpty() }, r.methode) { f ->
                // A pass's recording or picture, written to disk as it comes.
                r.methode == "POST" && RequeteHttp.versFichier(c.getInputStream(), r, fr.f4ioz.satcombo.domain.NouveauPassage.OCTETS_MAX, f)
            }
            val f = rep.fichier
            if (f != null) envoieFichier(sortie, r, f, rep.type, rep.entetes)
            else envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes)
            return
        }
        if (phonie != null && (chemin.startsWith("/api/phonie") || chemin.startsWith("/phonie/"))) { phonie(r, chemin, q, sortie, phonie); return }
        // The owner's capture of a login: read into memory, learnt from, wiped; never written to disk.
        if (r.methode == "POST" && chemin == "/api/icomreseau/trace") {
            val ir = icomReseau
            val o = when {
                ir == null -> JSONObject().put("ok", false).put("absent", true)
                r.longueur <= 0 -> JSONObject().put("ok", false).put("erreur", "fichier vide")
                else -> RequeteHttp.corps(c.getInputStream(), r, IcomReseauPc.TRACE_MAX)?.let { ir.apprendsTrace(it) }
                    ?: JSONObject().put("ok", false).put("erreur", "fichier trop gros (50 Mo au plus) ou coupé")
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/icomreseau" || chemin.startsWith("/api/icomreseau/")) {
            val ir = icomReseau
            val o = when {
                ir == null -> JSONObject().put("ok", false).put("absent", true)
                chemin == "/api/icomreseau/spectre" -> ir.spectre()
                chemin == "/api/icomreseau/cartes" -> ir.cartes()
                r.methode == "POST" -> runCatching { ir.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> ir.json()
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // The SatMe-Node joined: its devices and roles; its SDR's settings, « émettre par le node » (off by default).
        if (chemin == "/api/noeud" || chemin == "/api/noeud/reglage") {
            val n = noeudDistant
            val o = when {
                n == null -> JSONObject().put("ok", false).put("absent", true)
                r.methode == "POST" -> runCatching { n.action(q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> n.jsonPage()
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        textesDatv?.takeIf { chemin == "/api/datv/textes" }?.let { t ->
            val o = if (r.methode == "POST") runCatching { t.action(q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") } else t.json()
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/datv/large" || chemin.startsWith("/api/datv/large/")) {
            val l = large
            val o = when {
                l == null -> JSONObject().put("ok", false)
                r.methode == "POST" -> runCatching { l.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> l.json(q["depuis"]?.toLongOrNull() ?: -1L, q["vu"] != "0")
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/datv/sdr" || chemin == "/api/datv/sdr/reglage") {
            val l = spectreSdr
            val o = when {
                l == null -> JSONObject().put("ok", false)
                r.methode == "POST" -> runCatching { l.action(q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> l.json(q["depuis"]?.toLongOrNull() ?: -1L, q["vu"] != "0")
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/datv/chat" || chemin.startsWith("/api/datv/chat/")) {
            val l = chatLarge
            val o = when {
                l == null -> JSONObject().put("ok", false)
                r.methode == "POST" -> runCatching { l.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> l.json(q["depuis"]?.toLongOrNull() ?: -1L).put("rapport", rapportDatv())
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin.startsWith("/api/datv/sources/") || chemin.startsWith("/api/datv/emission/apercu") || chemin == "/api/datv/emission/incrustation") {
            sourcesDatv(r, c, chemin, q, sortie); return
        }
        // PSK31 / RTTY on the main sound (receive only): its state and text, its settings, the text as a file.
        if (chemin == "/api/clavier" || chemin == "/api/clavier/texte") {
            val k = son?.clavier
            if (chemin == "/api/clavier/texte") {
                val nom = "SatMe_" + (k?.mode ?: "PSK31") + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss'Z'", java.util.Locale.ROOT)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()) + ".txt"
                envoie(sortie, "200 OK", "text/plain; charset=utf-8", (k?.journal?.texte() ?: "").toByteArray(),
                    "Content-Disposition: attachment; filename=\"$nom\"\r\n"); return
            }
            val o = when {
                k == null -> JSONObject().put("present", false)
                r.methode == "POST" -> k.action(q)
                else -> k.json(station.settings.callsign).put("carte", son?.son?.etat?.value?.actif == true)
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // « Décodeurs multiples »: the channels taken from the receiver's IQ besides the main one (receive only).
        if (chemin == "/api/canaux" || chemin.startsWith("/api/canaux/")) {
            val k = canaux
            val o = when {
                k == null -> JSONObject().put("absent", true)
                r.methode == "POST" -> runCatching { k.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> k.json()
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // BPSK telemetry: the QO-100 beacon's panel, the LEO decoder, the test mode (receive only).
        // An SSDV picture as it stands (missing blocks grey), or a saved one.
        if (chemin == "/api/telemesure/ssdv.jpg" || chemin == "/api/telemesure/ssdv.png") {
            val png = chemin.endsWith(".png")
            val t = telemesure
            val j = if (png) t?.pngSsdv(q) else t?.jpegSsdv(q)
            // dl=1: « Exporter », as a file.
            val dl = if (q["dl"] == "1" && t != null) "Content-Disposition: attachment; filename=\"" + t.nomSsdv(q, if (png) "png" else "jpg") + "\"\r\n" else ""
            if (j == null) envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "absente".toByteArray())
            else envoie(sortie, "200 OK", if (png) "image/png" else "image/jpeg", j, dl)
            return
        }
        // SSDV packets from a file (raw packets, KISS, SatMe's frame log): put with the pictures.
        if (chemin == "/api/telemesure/ssdv/importe" && r.methode == "POST") {
            val t = telemesure
            val b = RequeteHttp.corps(c.getInputStream(), r, 32 * 1024 * 1024)
            val o = when {
                t == null -> JSONObject().put("ok", false).put("erreur", "absent")
                b == null -> JSONObject().put("ok", false).put("erreur", "fichier trop gros ou illisible")
                else -> runCatching { t.importeSsdv(b, q["nom"].orEmpty()) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/telemesure" || chemin.startsWith("/api/telemesure/")) {
            val k = telemesure
            val o = when {
                k == null -> JSONObject().put("absent", true)
                r.methode == "POST" -> runCatching { k.action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                else -> k.json()
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // The SDR's raw I/Q: « Remonter le temps », the recordings, their replay, the test generator (receive only).
        if (chemin == "/api/sdr-iq" || chemin.startsWith("/api/sdr-iq/")) {
            val rep = iqSdr?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The receiver's memories and the band plans (display and receive only).
        if (chemin == "/api/plan-bandes" || chemin == "/api/memoires-sdr" || chemin.startsWith("/api/memoires-sdr/")) {
            val m = memoiresSdr
            val rep = m?.sert(chemin, r.methode, { q[it].orEmpty() }) { max -> RequeteHttp.corps(c.getInputStream(), r, max) }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The scan and the band stack (receive only).
        if (chemin == "/api/balayage" || chemin.startsWith("/api/balayage/") || chemin == "/api/pile-bandes" || chemin.startsWith("/api/pile-bandes/")) {
            val rep = balayage?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // Levels over time and the Sun's noise (receive only; the raster moves the rotor only once confirmed).
        if (chemin == "/api/niveaux" || chemin == "/api/niveaux.csv" || chemin.startsWith("/api/niveaux/")) {
            val rep = niveaux?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        if (chemin == "/api/soleil" || chemin.startsWith("/api/soleil/")) {
            val rep = soleil?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The rotor calibration assistant: a GET reads; a POST acts (the controller's own calibration only once confirmed).
        if (fr.f4ioz.satcombo.demo.ApiEtalonnageRotor.pourMoi(chemin)) {
            val o = if (r.methode == "POST") runCatching { fr.f4ioz.satcombo.demo.ApiEtalonnageRotor.action(station.stationRotor, chemin) { q[it].orEmpty() } }
                .getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            else fr.f4ioz.satcombo.demo.ApiEtalonnageRotor.etat(station.stationRotor)
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // A module switched off in Réglages › Modules: nothing of it starts.
        if (r.methode == "POST") moduleRefuse(chemin)?.let { id ->
            val o = JSONObject().put("ok", false).put("module", id).put("erreur", "Module « ${ModulesStation.module(id)?.nom ?: id} » désactivé — l'activer dans Réglages › Modules")
            if (id in FonctionsNonSat.ONGLETS) o.put("nonSat", id)
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // The antenna polarisation switch: a GET reads, a POST acts (relays only, never a transmission).
        if (chemin == "/api/polar" || chemin.startsWith("/api/polar/")) {
            val o = polarisation?.let { p -> runCatching { p.sert(chemin, r.methode, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") } }
                ?: JSONObject().put("ok", false).put("absent", true)
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // ADS-B (receive only) and the shared map's layers.
        if (chemin == "/api/adsb" || chemin.startsWith("/api/adsb/")) {
            val rep = adsb?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // AIS (receive only) and space weather (fetched only once opted in).
        if (chemin == "/api/ais" || chemin.startsWith("/api/ais/")) {
            val rep = ais?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        if ((chemin == "/api/satfiche" || chemin == "/api/satfiche/photo") && r.methode != "POST") {
            val rep = fiches?.sert(chemin) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        if (chemin == "/api/iss-direct/source" && r.methode != "POST") {
            val rep = issDirect?.sert()
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // A QR code of a short text (the Vitrine's link to the club), drawn here: nothing asked elsewhere.
        if (chemin == "/api/qr" && r.methode != "POST") {
            val t = q["t"].orEmpty().take(300)
            if (t.isBlank()) envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            else envoie(sortie, "200 OK", "image/svg+xml", AppairagePc.svg(t).toByteArray())
            return
        }
        if (chemin == "/api/espace" || chemin.startsWith("/api/espace/")) {
            val rep = espace?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        if (chemin.startsWith("/api/carte/") && r.methode != "POST") {
            val rep = carte?.sert(chemin)
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // « Agenda › SSTV »: the campaigns, their auto-pilot, a flyer read (its picture in a POST's body).
        if (chemin == "/api/autopilote" || chemin.startsWith("/api/autopilote/")) {
            val rep = autopilote?.sert(chemin, r.methode, { q[it].orEmpty() }) { max -> RequeteHttp.corps(c.getInputStream(), r, max) }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // « Réglages › Site web »: its settings and its queue; a POST from this page's own origin only, its fields in the body
        // (the token never in an address).
        if (chemin == "/api/site" || chemin.startsWith("/api/site/")) {
            val rep = site?.let { st ->
                if (r.methode == "POST" && !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]))
                    fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"erreur\":\"origine refusée\"}".toByteArray())
                else {
                    val f = if (r.methode == "POST") RequeteHttp.corps(c.getInputStream(), r, 8192)?.let { b -> runCatching { parametres(String(b, Charsets.UTF_8)) }.getOrNull() }.orEmpty() else emptyMap()
                    st.sert(chemin, r.methode) { k -> f[k] ?: q[k].orEmpty() }
                }
            } ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The SSTV options: the settings, the live quality, a picture's QSL, the ARISS award pack (downloads only).
        if (chemin == "/api/sstv-options" || chemin.startsWith("/api/sstv-options/")) {
            val rep = optionsSstv?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // « Agenda › Diplômes »: the awards, what the station has for each, the request prepared (nothing sent).
        if (chemin == "/api/diplomes" || chemin.startsWith("/api/diplomes/")) {
            val rep = diplomes?.sert(chemin, r.methode, { q[it].orEmpty() }) { max -> RequeteHttp.corps(c.getInputStream(), r, max) }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // « Agenda › Activités à venir »: what the online sources announce, the passes during it, the notices.
        if (chemin == "/api/activites" || chemin.startsWith("/api/activites/")) {
            val rep = activites?.sert(chemin, r.methode) { q[it].orEmpty() }
                ?: fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8", "{\"ok\":false,\"absent\":true}".toByteArray())
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The « Montage » page: its chain (a POST saves it, a drawing only), its photos.
        if (chemin == "/api/montage" || chemin.startsWith("/api/montage/")) {
            val m = montage
            val rep = if (m == null) ReponseMontageAbsente else m.sert(chemin.removePrefix("/api/montage"), r.methode, { q[it].orEmpty() }) { max ->
                RequeteHttp.corps(c.getInputStream(), r, max)
            }
            envoie(sortie, rep.statut, rep.type, rep.corps, rep.entetes); return
        }
        // The IC-9700's microphone from the page: its own WebSocket, the PTT holder's frames only to the rig.
        if (chemin == "/api/poste/micro/ws") {
            val p = emissionPoste
            val d = WebSocketPc.Demande(r.methode, r.chemin, r.entetes)
            when {
                p == null -> SonDistantPc.refusHttp(sortie, "404 Not Found", "absent")
                !d.valide -> SonDistantPc.refusHttp(sortie, "400 Bad Request", "WebSocket attendu")
                !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]) -> SonDistantPc.refusHttp(sortie, "403 Forbidden", "origine refusée")
                else -> {
                    sortie.write(WebSocketPc.reponse(d.cle!!)); sortie.flush()
                    p.sertMicro(c, q["client"].orEmpty().take(40).ifBlank { "ip:" + c.inetAddress?.hostAddress })
                }
            }
            return
        }
        // The Pluto's narrow-band microphone: its own WebSocket, the PTT holder's frames only to the transmitter.
        if (chemin == "/api/be/micro/ws") {
            val b = emissionBe
            val d = WebSocketPc.Demande(r.methode, r.chemin, r.entetes)
            when {
                b == null -> SonDistantPc.refusHttp(sortie, "404 Not Found", "absent")
                !d.valide -> SonDistantPc.refusHttp(sortie, "400 Bad Request", "WebSocket attendu")
                !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]) -> SonDistantPc.refusHttp(sortie, "403 Forbidden", "origine refusée")
                else -> {
                    sortie.write(WebSocketPc.reponse(d.cle!!)); sortie.flush()
                    b.sertMicro(c, q["client"].orEmpty().take(40).ifBlank { "ip:" + c.inetAddress?.hostAddress })
                }
            }
            return
        }
        // The Pluto's narrow-band panel: its state, its settings (POST, the guards from this PC only), CW, the measurements.
        if (chemin == "/api/be/emission" || chemin == "/api/be/emission/reglage" || chemin == "/api/be/cw" || chemin == "/api/be/mesure") {
            val b = emissionBe
            val client = q["client"].orEmpty().take(40)
            val o = when {
                b == null -> JSONObject().put("ok", false).put("absent", true)
                r.methode != "POST" -> b.json(client).put("local", local)
                chemin == "/api/be/cw" -> b.cw(client, q)
                chemin == "/api/be/mesure" -> mesureBe(b, q["quoi"].orEmpty())
                else -> b.reglage(q, local).let { res -> if (res.optBoolean("ok")) b.json(client).put("local", local) else res }
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // The IC-9700's TX panel: its state, its settings and power (POST), a TX capture to learn from (this PC only).
        if (chemin == "/api/poste/emission" || chemin == "/api/poste/emission/reglage" || chemin == "/api/poste/emission/trace") {
            val p = emissionPoste
            val o = when {
                p == null -> JSONObject().put("ok", false).put("absent", true)
                chemin == "/api/poste/emission/trace" && r.methode == "POST" ->
                    if (!local) JSONObject().put("ok", false).put("erreur", "seulement depuis le PC de la station")
                    else RequeteHttp.corps(c.getInputStream(), r, IcomReseauPc.TRACE_MAX)?.let { p.apprendsTrace(it) }
                        ?: JSONObject().put("ok", false).put("erreur", "fichier trop gros (50 Mo au plus) ou coupé")
                r.methode == "POST" -> p.reglage(q, local).let { res -> if (res.optBoolean("ok")) p.json(q["client"]).put("local", local) else res }
                else -> p.json(q["client"]).put("local", local)
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        if (chemin == "/api/https" || chemin == "/api/https/reglage" || chemin == "/api/https/regenere") {
            val o = when {
                certificat == null -> JSONObject().put("ok", false).put("absent", true)
                r.methode != "POST" -> jsonHttps(local, r)
                !local -> JSONObject().put("ok", false).put("erreur", "seulement depuis le PC de la station")
                chemin == "/api/https/regenere" -> { runCatching { certificat?.regenere() }; relanceHttps(); jsonHttps(local, r) }
                else -> reglageHttps(q)?.let { JSONObject().put("ok", false).put("erreur", it) } ?: jsonHttps(local, r)
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // FT8/FT4 on the air: its state (GET; the page's « still there » with vie=1), its settings and actions (POST).
        // A contact started (CQ, an answer, enable) goes through the controller's rules; STOP (arret) from anyone.
        if (chemin == "/api/ft8/tx") {
            val f = ft8Tx
            val cl = fr.f4ioz.satcombo.datv.ControleEmission.Client(q["client"].orEmpty().take(40).ifBlank { "ip:" + c.inetAddress?.hostAddress },
                !local, c.inetAddress?.hostAddress.orEmpty())
            val o = when {
                f == null -> JSONObject().put("ok", false).put("absent", true)
                r.methode != "POST" -> { if (q["vie"] == "1" && f.arme && controle.vie(cl)) f.vie(); f.json().put("controle", controle.json(cl)) }
                q["arret"] == "1" -> { f.arrete(if (cl.distant) "arrêt demandé depuis un autre appareil" else "arrêt demandé"); f.json() }
                q["actif"] == "0" -> { f.active(false); f.json() }
                q["cq"] == "1" || q["repond"] != null || q["actif"] == "1" -> controle.refus(cl, emetQuelqueChose())
                    ?.let { JSONObject().put("ok", false).put("erreur", it).put("controle", controle.json(cl)) } ?: run {
                    val err = runCatching {
                        when {
                            q["cq"] == "1" -> f.appelleCq()
                            q["actif"] == "1" -> f.active(true)
                            else -> f.repond(recuFt8(q, son?.ft8?.mode ?: "FT8"))
                        }
                    }.getOrElse { it.message ?: "erreur" }
                    if (err == null) controle.prend(cl, "FT8/FT4")
                    f.json().also { if (err != null) it.put("ok", false).put("erreur", err) }
                }
                else -> f.reglage(q)
            }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray()); return
        }
        // The transmissions: one controller at a time, its heartbeat, STOP from anyone ([ControleEmission]).
        if (chemin == "/api/emission/controle" || (r.methode == "POST" && chemin in ROUTES_TX)) {
            val cl = fr.f4ioz.satcombo.datv.ControleEmission.Client(q["client"].orEmpty().take(40).ifBlank { "ip:" + c.inetAddress?.hostAddress },
                !local, c.inetAddress?.hostAddress.orEmpty())
            envoie(sortie, "200 OK", "application/json; charset=utf-8", controleTx(chemin, r.methode, q, cl).toString().toByteArray()); return
        }
        // The modules switched from another site's page open in the same browser: refused, as Démarrage's settings are.
        if (r.methode == "POST" && (chemin == "/api/modules" || chemin == "/api/nonsat") && !SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"])) {
            envoie(sortie, "403 Forbidden", "application/json; charset=utf-8", JSONObject().put("ok", false).put("erreur", "origine refusée").toString().toByteArray()); return
        }
        if (r.methode == "POST") {
            val res = runCatching { action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        when (chemin) {
            "/", "/index.html", "/pc" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PagePc.HTML.toByteArray())
            "/api/police-drapeaux" -> PagePc.POLICE_DRAPEAUX?.let { envoie(sortie, "200 OK", "font/woff2", it) } ?: envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            "/api/lib/three.js", "/api/lib/satellite.js", "/api/lib/relief.png" -> PagePc.bibliotheque(chemin.substringAfterLast('/'))
                ?.let { envoie(sortie, "200 OK", if (chemin.endsWith(".png")) "image/png" else "text/javascript; charset=utf-8", it) }
                ?: envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            // The built-in manual (F1): the guide's chapters, and its full-text search.
            "/api/manuel" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", fr.f4ioz.satcombo.demo.Manuel.REPONSE)
            "/api/manuel/cherche" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.Manuel.chercheJson(q["q"].orEmpty().take(200)).toString().toByteArray())
            // Every tab here but the functions not about satellites left unticked; the phone offers some of them.
            "/api/capacites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", capacites().toString().toByteArray())
            "/api/etat" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.etat(station, carnet).toString().toByteArray())
            // The satellite link: on or not, its port, who listens.
            "/api/lien" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (lien?.let { l ->
                JSONObject().put("on", l.actif).put("port", l.port).put("ecoute", l.ecoute).put("clients", l.nbClients)
                    .put("erreur", l.erreur).put("reseau", reseau)
            } ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/rigctld" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (rigctld?.json() ?: JSONObject().put("absent", true)).toString().toByteArray())
            // The USB knob: its state; the page's tab told at each reading (the « Auto » target follows it).
            "/api/annonces" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (annonces?.json() ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/molette" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (molette?.let { m -> m.contextePage(q["ctx"]); m.json() } ?: JSONObject().put("absent", true)).toString().toByteArray())
            // The Doppler/CAT log: on or not, the last files (downloaded from the journal's route).
            "/api/doppler" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("on", station.settings.journalDoppler)
                .put("detaille", spectreJournal?.actif ?: false)
                .put("fichiers", JSONArray(station.journauxDoppler().take(8).map { JSONObject().put("nom", it.name).put("taille", it.length()) }))
                .toString().toByteArray())
            "/api/passages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.passages(station).toString().toByteArray())
            // The mast's plan for the pass followed or next (a preview without a mast), and a contact's pass as the mast went.
            "/api/rotor/plan" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiRotorPlan.plan(station.stationRotor.apercuPlan(), station.stationRotor.etat.value).toString().toByteArray())
            "/api/carnet/rotor" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                fr.f4ioz.satcombo.demo.ApiRotorPlan.journal(journal?.passages() ?: emptyList(), q["t"]?.toLongOrNull() ?: 0L, q["sat"].orEmpty(),
                    fr.f4ioz.satcombo.demo.ApiRotorPlan.limites(station.stationRotor.etat.value),
                    station.stationRotor.etat.value.rotorMaxError.toDouble()).toString().toByteArray())
            "/api/satellites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.satellites(station).toString().toByteArray())
            "/api/orbites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.orbites(station).toString().toByteArray())
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
            "/api/sdr/nettoyage" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (sdr?.let { JsonPc.nettoyage(it) } ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/sdr" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (sdr?.let { JsonPc.sdr(it) } ?: JSONObject().put("absent", true)).toString().toByteArray())
            // The waterfall's scale: the satellites' downlinks in the receiver's view, Doppler applied.
            "/api/sdr/echelle" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (sdr?.let { fr.f4ioz.satcombo.domain.EchelleSdr.json(it.echelle(), System.currentTimeMillis()) } ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/conversion" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.tableConversion(station, sdr).toString().toByteArray())
            "/api/calibration" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", calibration.references().toString().toByteArray())
            "/api/calibration/fcch" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", calibration.fcch(q["arfcn"]?.trim()?.toIntOrNull() ?: -1).toString().toByteArray())
            "/api/calibration/mesure" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", (q["hz"]?.toLongOrNull()?.let {
                calibration.mesure(it, q["fenetre"]?.toDoubleOrNull() ?: 20_000.0, q["maintien"] == "1", q["pic"]?.toLongOrNull(), q["lobes"] == "1")
            } ?: JSONObject().put("ok", false).put("erreur", "fréquence ?")).toString().toByteArray())
            "/api/sdr/spectre" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (sdr?.let { JsonPc.spectre(it, q["span"]?.toIntOrNull() ?: 48_000, q["n"]?.toIntOrNull() ?: 512, q["autour"] == "ecoute", q["dx"]?.toIntOrNull() ?: 0) } ?: JSONObject()).toString().toByteArray())
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
                d?.retrouveDeTempsEnTemps()
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
                // The band plan's channels free or busy on the ground station's spectrum (« Créneaux »).
                emission?.let { e ->
                    val l = large
                    val notre = if (e.etat.emet && !e.etat.chargeFictive && !e.etat.interieur) e.etat.frequenceKhz + fr.f4ioz.satcombo.datv.PlanQo100.DECALAGE_KHZ else null
                    val cr = l?.creneaux(e.srKs, e.rolloffEffectif.alpha, notre, station.settings.callsign)
                        ?: fr.f4ioz.satcombo.datv.CreneauxDatv.calcule(e.srKs, e.rolloffEffectif.alpha, null, notreDescenteKhz = notre, indicatif = station.settings.callsign)
                    o.put("creneaux", fr.f4ioz.satcombo.datv.CreneauxDatv.json(cr)).put("creneauxSpectre", l != null && l.on && l.frais())
                }
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
            "/api/datv/qrz/photo" -> {
                val p = qrzDatv?.photo(q["c"].orEmpty())
                if (p == null) envoie(sortie, "404 Not Found", "text/plain", "pas de photo".toByteArray())
                else envoie(sortie, "200 OK", p.second, p.first)
            }
            "/api/datv/pointage" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (datv?.pointageJson() ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/datv/historique" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (datv?.historique?.json(System.currentTimeMillis(), (q["fenetre"]?.toLongOrNull() ?: 3_600_000L).coerceIn(60_000L, 86_400_000L))
                    ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/datv/son/niveaux" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (datv?.sonJson()?.put("ok", true) ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/datv/accord-udp" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (accordUdp?.json()?.put("ok", true) ?: JSONObject().put("ok", false)).toString().toByteArray())
            "/api/datv/image.jpg" -> {
                val img = datv?.image
                if (img == null) envoie(sortie, "404 Not Found", "text/plain", "pas d'image".toByteArray())
                else envoie(sortie, "200 OK", "image/jpeg", img)
            }
            "/api/frise" -> {
                val (debut, lignes) = kotlinx.coroutines.runBlocking { station.frise((q["h"]?.toIntOrNull() ?: 12).coerceIn(2, 48), autopilote?.catnums().orEmpty()) }
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
                fr.f4ioz.satcombo.demo.ApiStation.lieuSaisi(o, saisie)
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
            "/api/son" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.son(son, sdr, phonie?.actif == true).toString().toByteArray())
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
            "/api/sstv/classement" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (classement?.json() ?: JSONObject().put("absent", true)).toString().toByteArray())
            "/api/sstv/correction" -> envoie(sortie, "200 OK", "application/json; charset=utf-8",
                (classement?.corrections?.api?.etat(q["nom"].orEmpty()) ?: JSONObject().put("absent", true)).toString().toByteArray())
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
                    // A corrected picture (kept apart from those received).
                    chemin.startsWith("/sstv/corrigees/") -> classement?.corrections?.api?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/sstv/corrigees/"), "UTF-8"))
                    chemin.startsWith("/sstv/") -> son?.fichierImage(java.net.URLDecoder.decode(chemin.removePrefix("/sstv/"), "UTF-8"))
                    // A sonde's log or track: only a plain name, inside its folder.
                    chemin.startsWith("/sked-video/") -> videoSked?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/sked-video/"), "UTF-8"))
                    chemin.startsWith("/meteor/") -> sdr?.meteor?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/meteor/"), "UTF-8"))
                    chemin.startsWith("/sdr-iq/") -> iqSdr?.fichier(java.net.URLDecoder.decode(chemin.removePrefix("/sdr-iq/"), "UTF-8"))
                    chemin.startsWith("/sondes/") -> java.net.URLDecoder.decode(chemin.removePrefix("/sondes/"), "UTF-8")
                        .takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }?.let { n -> sdr?.sondes?.journaux()?.firstOrNull { it.name == n } }
                    else -> null
                }
                if (f != null) envoieFichier(sortie, r, f, typeDonne = if (f.name.endsWith(".mp4")) "video/mp4" else null,
                    entetes = if (chemin.startsWith("/sondes/") || chemin.startsWith("/sked-video/") || chemin.startsWith("/sdr-iq/")) "Content-Disposition: attachment; filename=\"${f.name}\"\r\n" else "")
                else envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "introuvable".toByteArray())
            }
        }
    }

    /**
     * The door for other machines: the application's files (no secret) and
     * the login page for anyone; everything else for this PC or a session
     * only. True when the request was answered here.
     */
    private fun garde(c: Socket, r: RequeteHttp.Requete, chemin: String, q: Map<String, String>, sortie: OutputStream, local: Boolean): Boolean {
        when (chemin) {
            "/manifest.webmanifest" -> { envoie(sortie, "200 OK", "application/manifest+json; charset=utf-8", PagesAccesPc.MANIFESTE.toByteArray()); return true }
            "/sw.js" -> { envoie(sortie, "200 OK", "text/javascript; charset=utf-8", PagesAccesPc.SERVICE_WORKER.toByteArray()); return true }
            "/icone-192.png", "/icone-512.png" -> {
                val b = PagesAccesPc.icone(if (chemin == "/icone-192.png") 192 else 512)
                if (b == null) envoie(sortie, "404 Not Found", "text/plain", ByteArray(0)) else envoie(sortie, "200 OK", "image/png", b)
                return true
            }
        }
        val a = acces
        val jeton = r.cookie(AccesDistantPc.COOKIE)
        val ouverte = local || (a != null && a.session(jeton))
        val html = "text/html; charset=utf-8"
        if (chemin == "/connexion") {
            when {
                ouverte -> redirige(sortie, AccesDistantPc.suite(q["suite"]))
                a == null || !a.defini -> envoie(sortie, "403 Forbidden", html, PagesAccesPc.connexion("", "/", false).toByteArray())
                r.methode == "POST" -> {
                    val f = RequeteHttp.corps(c.getInputStream(), r, 4096)?.let { b -> runCatching { parametres(String(b, Charsets.UTF_8)) }.getOrNull() }.orEmpty()
                    val suite = AccesDistantPc.suite(f["suite"])
                    when (val res = a.connecte(c.inetAddress.hostAddress, f["mdp"].orEmpty())) {
                        is AccesDistantPc.Connexion.Ouverte -> redirige(sortie, suite, AccesDistantPc.cookie(res.jeton))
                        is AccesDistantPc.Connexion.Refusee -> envoie(sortie, "401 Unauthorized", html, PagesAccesPc.connexion(res.raison, suite, true).toByteArray())
                    }
                }
                else -> envoie(sortie, "200 OK", html, PagesAccesPc.connexion("", AccesDistantPc.suite(q["suite"]), true).toByteArray())
            }
            return true
        }
        if (chemin == "/deconnexion") {
            if (r.methode != "POST") { envoie(sortie, "405 Method Not Allowed", "text/plain", ByteArray(0)); return true }
            a?.sessions?.retire(jeton)
            envoie(sortie, "200 OK", "application/json; charset=utf-8", JSONObject().put("ok", true).toString().toByteArray(), AccesDistantPc.COOKIE_EFFACE)
            return true
        }
        if (ouverte) return false
        // A page asked by a browser: the login page, then back here; anything else: refused, said so.
        if (r.methode == "GET" && chemin in PAGES_NAVIGATEUR) {
            val ici = r.chemin.takeIf { it.length < 400 } ?: chemin
            redirige(sortie, "/connexion?suite=" + java.net.URLEncoder.encode(ici, "UTF-8"))
        } else envoie(sortie, "401 Unauthorized", "application/json; charset=utf-8",
            JSONObject().put("ok", false).put("erreur", "connexion requise").put("connexion", true).toString().toByteArray(), "X-SatMe-Connexion: 1\r\n")
        return true
    }

    /** Réglages › Accès à distance: the password (set from this PC or a session), the sessions. */
    private fun acces(c: Socket, r: RequeteHttp.Requete, chemin: String, sortie: OutputStream, local: Boolean) {
        val a = acces
        val jeton = r.cookie(AccesDistantPc.COOKIE)
        val o = when {
            a == null -> JSONObject().put("ok", false).put("present", false)
            r.methode != "POST" -> JSONObject().put("ok", true).put("present", true).put("reseau", reseau).put("local", local)
                .put("defini", a.defini).put("depuis", a.depuis).put("sessions", a.sessions.nombre)
                .put("connecte", !local && a.session(jeton)).put("initial", if (local) a.initial ?: "" else "")
            chemin == "/api/acces/mdp" -> {
                // In the body, not the address: a password never in a URL.
                val f = RequeteHttp.corps(c.getInputStream(), r, 4096)?.let { b -> runCatching { parametres(String(b, Charsets.UTF_8)) }.getOrNull() }.orEmpty()
                val err = a.regle(f["mdp"].orEmpty(), if (local) null else jeton)
                if (err == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", err)
            }
            chemin == "/api/acces/retire" ->
                if (!local) JSONObject().put("ok", false).put("erreur", "seulement depuis le PC lui-même")
                else if (reseau && ModeDemo.actif) JSONObject().put("ok", false).put("erreur", ModeDemo.refus("le retrait du mot de passe (démonstration ouverte au réseau)"))
                else { a.retire(); JSONObject().put("ok", true) }
            chemin == "/api/acces/sessions" -> { a.sessions.retireTout(if (local) null else jeton); JSONObject().put("ok", true) }
            else -> JSONObject().put("ok", false).put("erreur", "inconnu")
        }
        envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
    }

    private fun redirige(s: OutputStream, ou: String, entetes: String = "") {
        s.write(("HTTP/1.1 303 See Other\r\nLocation: $ou\r\n" + entetes + "Content-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        s.flush()
    }

    private fun origine(proposee: Boolean) =
        if (proposee) fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE else fr.f4ioz.satcombo.domain.GardeQo100.Origine.MAIN

    /** What a POST asks; the answer says whether it was done. */
    /**
     * The tabs offered (those of the modules switched off left out), the
     * modules and the profiles, what the page hides; [FonctionsNonSat]'s
     * choice as before (the phone's page and the montage read it).
     */
    private fun capacites(): JSONObject {
        val c = ModulesStation.coupes(station.stockage)
        val modules = JSONArray()
        ModulesStation.MODULES.forEach { m ->
            modules.put(JSONObject().put("id", m.id).put("nom", m.nom).put("quoi", m.quoi).put("avis", m.avis)
                .put("onglets", JSONArray(m.onglets)).put("utilise", JSONArray(m.utilise)).put("on", m.id !in c))
        }
        val profils = JSONArray()
        ModulesStation.PROFILS.forEach { p -> profils.put(JSONObject().put("id", p.id).put("nom", p.nom).put("quoi", p.quoi).put("modules", JSONArray(p.modules.toList()))) }
        val eteints = JSONObject()
        ModulesStation.MODULES.filter { it.id in c }.forEach { m -> m.onglets.forEach { eteints.put(it, m.nom) } }
        return JSONObject().put("onglets", JSONArray(ModulesStation.onglets(PagePc.TOUS, c))).put("serveur", "pc")
            .put("nonSat", JSONObject(FonctionsNonSat.ONGLETS.keys.associateWith { it !in c }))
            .put("modules", modules).put("profils", profils).put("profil", ModulesStation.profilDe(c) ?: JSONObject.NULL)
            .put("coeur", ModulesStation.COEUR).put("eteints", eteints).put("masques", JSONArray(ModulesStation.masques(c)))
    }

    /** Whether the module [id] holds a receiver or a transmitter now (what switching it off would stop). */
    private fun moduleTourne(id: String): Boolean = when (id) {
        "avions" -> adsb?.actif == true
        "ais" -> ais?.actif == true
        "sondes" -> sdr?.let { it.pourSonde && it.etat.value.actif } == true
        "meteor" -> sdr?.let { it.pourMeteor && it.etat.value.actif } == true
        "datv" -> datv?.etat?.actif == true || emission?.etat?.emet == true
        else -> false
    }

    private fun moduleArrete(id: String) {
        when (id) {
            "avions" -> adsb?.arrete()
            "ais" -> ais?.arrete()
            "sondes", "meteor" -> sdr?.arrete()
            "datv" -> { emission?.arrete(); datv?.arrete() }
        }
    }

    /** The module switched off that a POST to [chemin] would start, else null. */
    private fun moduleRefuse(chemin: String): String? = when (chemin) {
        "/api/adsb/demarre", "/api/adsb/essai" -> "avions"
        "/api/ais/demarre", "/api/ais/essai" -> "ais"
        "/api/sondes/demarrer" -> "sondes"
        "/api/meteor/demarrer" -> "meteor"
        "/api/datv/demarre", "/api/datv/emission/demarre" -> "datv"
        // Its settings stay open (to tick it again is elsewhere); every gesture on the box is refused.
        "/api/polar/pol", "/api/polar/test", "/api/polar/sim", "/api/polar/sonde" -> "polar"
        else -> null
    }?.takeIf { !ModulesStation.actif(station.stockage, it) }

    /**
     * [coupes] stored. Modules being switched off that receive or transmit
     * now: nothing changed, their names answered (enMarche), unless [force]
     * — then they are stopped first (the receiver given back).
     */
    private fun posModules(coupes: Set<String>, force: Boolean): JSONObject {
        val avant = ModulesStation.coupes(station.stockage)
        val tournent = (coupes - avant).filter { moduleTourne(it) }
        if (tournent.isNotEmpty() && !force) return JSONObject().put("ok", false).put("enMarche", true)
            .put("nom", tournent.joinToString(", ") { ModulesStation.module(it)?.nom ?: it })
        tournent.forEach { moduleArrete(it) }
        ModulesStation.pose(station.stockage, coupes)
        return capacites().put("ok", true)
    }

    /**
     * The decoders' source in one gesture: a card (started, receive only), the
     * receiver ([fr.f4ioz.satcombo.domain.SourceAudio.SDR]: given the input back
     * if it runs, else started), or none (blank: stopped, the recording closed).
     * Remembered for the satellite followed. Null when done, else why not.
     */
    internal fun prendSource(s: SonStation, nom: String): String? {
        if (nom.isBlank()) { s.arreteEnregistrement(); s.son.arrete(); return null }
        if (nom == fr.f4ioz.satcombo.domain.SourceAudio.SDR) {
            val d = sdr ?: return "pas de récepteur SDR ici"
            if (d.pourSonde || d.pourMeteor) return "le récepteur sert aux radiosondes ou à METEOR"
            if (d.etat.value.actif) s.son.prendPour(SdrPc.NOM_SOURCE, fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE)
            else if (!d.demarre()) return d.etat.value.erreur.ifBlank { "le récepteur n'a pas démarré" }
            s.memoriseSourceSatellite(nom)
            return null
        }
        if (s.son.appareils().none { it.nom == nom }) return "carte son introuvable : $nom"
        s.prendCarte(nom)?.let { return it }
        s.memoriseSourceSatellite(nom)
        return null
    }

    /**
     * The followed satellite's pass made ready, receive side only: the source
     * that hears it, the decoders its transmitter calls for, the recording
     * (now if it is up, else at AOS, stopped 30 s after LOS). Never transmits.
     */
    private fun preparePassage(s: SonStation, enregistrer: Boolean): JSONObject {
        val e = station.etat.value
        val sat = e.suivi ?: return JSONObject().put("ok", false).put("erreur", "aucun satellite suivi : en choisir un (Suivi)")
        val tx = station.emetteurChoisi()
        val l = s.son.appareils()
        val p = fr.f4ioz.satcombo.domain.SourceAudio.plan(tx?.mode.orEmpty(), tx?.description.orEmpty(), l.map { it.nom },
            s.sourcePourSatellite(sat.catalogNumber), s.derniereCarte, s.sourceDuPoste(l), e.catConnecte && !e.catSimule,
            sdr?.etat?.value?.actif == true, enregistrer)
        val fait = mutableListOf<String>()
        val noms = s.nomsCartes(l)
        val src = p.source
        val sdrNom = fr.f4ioz.satcombo.domain.SourceAudio.SDR
        if (src != null && !(s.son.etat.value.actif && s.son.etat.value.appareil == (if (src == sdrNom) SdrPc.NOM_SOURCE else src)))
            prendSource(s, src)?.let { return JSONObject().put("ok", false).put("erreur", it) }
        else if (!s.son.etat.value.actif) s.assureSource()?.let { return JSONObject().put("ok", false).put("erreur", it) }
        // Said as it stands now: the source, the decoders on, the recording.
        val a = s.son.etat.value.appareil
        fait += "son : " + if (a == SdrPc.NOM_SOURCE) "récepteur SDR" else (noms[a]?.libelle ?: a)
        for (d in p.decodeurs) when (d) {
            "SSTV" -> s.sstvActif = true
            "APRS" -> s.aprsActif = true
            "CW" -> s.cw.actif = true
            "FT8" -> if (!s.ft8.enMarche) s.ft8.demarre(s.ft8.mode)
        }
        if (p.decodeurs.isNotEmpty()) fait += fr.f4ioz.satcombo.domain.SourceAudio.liste(p.decodeurs)
        if (p.enregistrer && s.etat.value.enregistrement.isBlank()) {
            val leve = (e.position?.elevationDeg ?: -1.0) >= 0
            if (leve) fait += s.demarreEnregistrement(auto = true)?.let { "pas d'enregistrement : $it" } ?: "enregistré jusqu'à la fin du passage"
            else { s.enregistrePassage = sat.catalogNumber; fait += "enregistrement armé pour l'AOS" }
        } else if (s.etat.value.enregistrement.isNotBlank()) fait += "enregistrement en cours"
        return JSONObject().put("ok", true).put("satellite", sat.name).put("fait", JSONArray(fait))
            .put("decodeurs", JSONArray(p.decodeurs))
    }

    /** The SDR calibration assistant (receive only). */
    private val calibration by lazy { CalibrationSdrPc(station) { sdr } }

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
                if (q["on"] == "1") son.assureSource()
                q["ton"]?.toIntOrNull()?.let { w.tonChoisi = it }
                if (q["efface"] == "1") w.efface()
            }
            "/api/veille" -> fr.f4ioz.satcombo.demo.ApiVeille.action({ q[it] }, station.settings, station.etat.value.favoris, System.currentTimeMillis())
            "/api/amsat/rapport" -> {
                // Heard or not, on amsat.org's public page: the satellite followed, my callsign and square, now.
                ModeDemo.refus("le rapport à l'AMSAT (indicatif fictif)")?.let { return JSONObject().put("ok", false).put("erreur", it) }
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
            // The rig tuned even below the horizon: asked with « on », read without.
            "/api/emission/chien-local" -> {
                q["on"]?.let { station.txChienLocal = it == "1"; controle.chienLocal = it == "1" }
                return JSONObject().put("ok", true).put("on", controle.chienLocal)
            }
            "/api/cat/sous-horizon" -> {
                q["on"]?.let { station.catSousHorizon = it == "1" }
                return JSONObject().put("ok", true).put("on", station.catSousHorizon)
            }
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
            // A tuning by hand stops the scan (as a dial turned on a scanner).
            "/api/sdr/decalage" -> q["hz"]?.toIntOrNull()?.let { balayage?.arrete("accord à la main", main = true); sdr?.decalageHz = it }
            // A click on the scale: a transmitter (n, tx), else what lies at hz (a transponder, or a plain frequency; tol: the click's reach).
            "/api/sdr/echelle/accorde" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val hz = q["hz"]?.toLongOrNull() ?: return JSONObject().put("ok", false).put("erreur", "quelle fréquence ?")
                val n = q["n"]?.toIntOrNull(); val tx = q["tx"]
                val a = if (n != null && !tx.isNullOrEmpty()) fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Emetteur(n, tx)
                    else fr.f4ioz.satcombo.domain.EchelleSdr.clic(hz, d.echelle(), (q["tol"]?.toLongOrNull() ?: 0L).coerceIn(0L, 20_000L))
                balayage?.arrete("accord à la main", main = true)
                d.accordeEchelle(a, hz)?.let { return JSONObject().put("ok", false).put("erreur", it) }
                return JSONObject().put("ok", true).put("accord", when (a) {
                    is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Emetteur -> "emetteur"
                    is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Bande -> "bande"
                    is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Terrestre -> "terrestre"
                })
            }
            // A satellite's « correction manuelle » from the scale (Ctrl + wheel or drag): moved by dhz, or set to hz.
            "/api/sdr/echelle/correction" -> {
                val n = q["n"]?.toIntOrNull() ?: return JSONObject().put("ok", false).put("erreur", "quel satellite ?")
                val v = station.correctionSat(n, q["dhz"]?.toLongOrNull(), q["hz"]?.toLongOrNull())
                return JSONObject().put("ok", true).put("corr", v)
            }
            "/api/sdr/pic" -> sdr?.calePic()
            "/api/sdr/voix" -> if (sdr?.caleVoix() != true) return JSONObject().put("ok", false).put("erreur", "le calage sur la voix sert en BLU (USB / LSB)")
            // The vernier: the listened frequency pushed by [hz] (the dongle's software offset, ±80 kHz).
            "/api/sdr/pas" -> q["hz"]?.toIntOrNull()?.let { h -> balayage?.arrete("accord à la main", main = true); sdr?.let { it.decalageHz = it.decalageHz + h } }
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
                // The tone squelch: a standard CTCSS tone (Hz), anything else turns it off.
                q["tonSq"]?.replace(',', '.')?.toDoubleOrNull()?.let { st.sdrSilencieuxTon = Math.round(fr.f4ioz.satcombo.sdr.DetecteurSousTon.normalise(it) * 10).toInt() }
                q["son"]?.let { st.sdrAudio = it == "1" }
                q["deemph"]?.let { st.sdrDeemph = it == "1" }
                q["doppler"]?.let { st.sdrDopplerTrack = it == "1" }
                if (q["suit"] == "1" || q["mhz"] != null) balayage?.arrete("accord à la main", main = true)
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
                q["fiRecentre"]?.let { d.recentrePoste = it == "1" }
            }
            // The sound cleaning of the mode demodulated (or of [mode]), kept per mode; receive only.
            "/api/sdr/nettoyage" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val m = q["mode"]?.let { k -> fr.f4ioz.satcombo.sdr.RxMode.values().firstOrNull { it.name == k } }
                d.regleNettoyage(q, m)
                return JsonPc.nettoyage(d)
            }
            // bladeRF-cli -e info only (it opens the board, prints what it is, sends nothing).
            "/api/sdr/bladerf" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val i = d.detecteBladerf()
                return ok.put("message", i.message).put("libelle", i.carte?.libelle ?: "")
            }
            // A function not about satellites ticked or not; unticked while it runs: stopped once confirmed (the receiver given back).
            "/api/nonsat" -> {
                val id = q["o"].orEmpty().takeIf { it in FonctionsNonSat.ONGLETS } ?: return JSONObject().put("ok", false).put("erreur", "fonction inconnue")
                return posModules(ModulesStation.bascule(ModulesStation.coupes(station.stockage), id, q["on"] == "1"), q["force"] == "1")
            }
            // Réglages › Modules: one module on or off (m, on), or a profile (profil); switching off what runs: stopped once confirmed.
            "/api/modules" -> {
                val c = ModulesStation.coupes(station.stockage)
                val apres = q["profil"]?.let { ModulesStation.profil(it) ?: return JSONObject().put("ok", false).put("erreur", "profil inconnu") }
                    ?: q["m"]?.takeIf { ModulesStation.module(it) != null }?.let { ModulesStation.bascule(c, it, q["on"] == "1") }
                    ?: return JSONObject().put("ok", false).put("erreur", "module inconnu")
                return posModules(apres, q["force"] == "1")
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
                // The map or the globe as the page shows it (its look, camera, settings), 16:9 or a phone's 9:16.
                val rendu = VideoSked.rendu { q[it].orEmpty() }
                if (!v.lance(VideoSked.Sked(tr, l("de"), l("a"), sat, station.settings.callsign.ifBlank { station.etat.value.locator }.uppercase(), loc,
                        locMoi = station.etat.value.locator), rendu))
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
                    son = q["son"]?.let { it == "1" } ?: r0.son, balayage = q["balayage"]?.let { Datv.balayageDe(it) } ?: r0.balayage,
                    demiLargeur = q["demi"]?.let { Datv.demiLargeurDe(it) } ?: r0.demiLargeur, attenteMs = q["attente"]?.let { Datv.attenteDe(it) } ?: r0.attenteMs)
                q["relance"]?.let { d.relanceAuto = it == "1" }
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
            // The sound cleaning of the mode demodulated (or of [mode]), kept per mode; receive only.
            "/api/sdr/nettoyage" -> {
                val d = sdr ?: return JSONObject().put("ok", false)
                val m = q["mode"]?.let { k -> fr.f4ioz.satcombo.sdr.RxMode.values().firstOrNull { it.name == k } }
                d.regleNettoyage(q, m)
                return JsonPc.nettoyage(d)
            }
            // bladeRF-cli -e info only (it opens the board, prints what it is, sends nothing).
            "/api/datv/emission/bladerf" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                val i = e.detecteBladerf()
                return ok.put("message", i.message).put("libelle", i.carte?.libelle ?: "")
            }
            "/api/datv/emission/arrete" -> emission?.arrete()
            // « Tester l'encodeur »: ffmpeg to nothing, never while transmitting.
            "/api/datv/emission/banc" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                e.lanceBanc()?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            // The gain stepped while transmitting (the same clamps as a gain typed in).
            "/api/datv/emission/rampe" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                e.lanceRampe(q["sens"].orEmpty(), q["gain"]?.replace(',', '.')?.toDoubleOrNull())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            // The gain remembered per symbol rate: taken back (a setting, or a ramp while transmitting), or forgotten.
            "/api/datv/emission/calibration" -> {
                val e = emission ?: return JSONObject().put("ok", false)
                if (q["oublie"] == "1") e.oublieCalibration()
                else e.appliqueCalibration()?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
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
            "/api/datv/relance" -> datv?.let { it.relanceAuto = q["on"] == "1" }
            // The pointing aid: its best forgotten, a mark in the history (a touch-up on the mast).
            "/api/datv/pointage/raz" -> datv?.pointage?.raz()
            "/api/datv/pointage/marque" -> datv?.let { d -> d.historique.marque(System.currentTimeMillis(), q["texte"].orEmpty().trim().ifEmpty { "retouche" }, "retouche") }
            // The options: the beacon's calibration, tolerant decoding (at once), LongMynd's -S and -r (kept, at the next start).
            "/api/datv/options" -> {
                val d = datv ?: return JSONObject().put("ok", false)
                q["caleAuto"]?.let { d.caleAuto = it == "1" }
                q["tolerant"]?.let { d.decodageTolerant = it == "1" }
                if (q.containsKey("demi") || q.containsKey("attente"))
                    d.regleLongMynd(q["demi"]?.let { Datv.demiLargeurDe(it) }, q["attente"]?.let { Datv.attenteDe(it) })
            }
            "/api/datv/son/local" -> datv?.regleSonLocal(q["reglable"]?.let { it == "1" }, q["volume"]?.trim()?.toIntOrNull())
            "/api/datv/accord-udp" -> {
                val a = accordUdp ?: return JSONObject().put("ok", false).put("erreur", "indisponible")
                val port = q["port"]?.let { it.trim().toIntOrNull() ?: return JSONObject().put("ok", false).put("erreur", "port : un nombre") }
                a.regle(q["on"]?.let { it == "1" }, port, q["reseau"]?.let { it == "1" })?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
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
            "/api/qo100/verrou" -> qo100?.verrouBalise = q["on"] == "1"
            // The converter table, sent whole from the page (JSON lines); « Rétablir les préréglages ».
            "/api/conversion/table" -> {
                val a = runCatching { JSONArray(q["t"].orEmpty()) }.getOrNull() ?: return JSONObject().put("ok", false).put("erreur", "table illisible")
                val t = (0 until minOf(a.length(), TableConversion.MAX_LIGNES)).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                    fr.f4ioz.satcombo.domain.BandeConversion(o.optString("nom").trim().take(80).ifEmpty { "Bande " + (i + 1) },
                        o.optLong("bas"), o.optLong("haut"), o.optLong("ol"), o.optBoolean("inv"),
                        o.optBoolean("sdr"), o.optBoolean("catRx"), o.optBoolean("catTx"), o.optBoolean("actif", true),
                        o.optString("chaine").takeIf { it == TableConversion.CHAINE_RX || it == TableConversion.CHAINE_TX }.orEmpty()) } }
                station.poseBandes(t)
            }
            "/api/conversion/retablir" -> station.retablitBandes()
            // The calibration assistant: tune the SDR (receive only), save what was measured.
            "/api/calibration/accorde" -> return q["hz"]?.toLongOrNull()?.let { calibration.accorde(it) } ?: JSONObject().put("ok", false).put("erreur", "fréquence ?")
            "/api/calibration/ppm" -> return q["v"]?.trim()?.toIntOrNull()?.let { calibration.posePpm(it) } ?: JSONObject().put("ok", false).put("erreur", "ppm ?")
            "/api/calibration/ol" -> return calibration.poseOl(entier("i") ?: -1, q["nom"].orEmpty(), q["hz"]?.toLongOrNull() ?: 0L)
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
                } else c.ajoute(q["c"].orEmpty(), q["l"].orEmpty(), q["rs"].orEmpty(), q["rr"].orEmpty(), q["n"].orEmpty(), fiche,
                    // The satellite chosen in the form; else the rig's, QO-100 from the cockpit's form.
                    fr.f4ioz.satcombo.domain.ChoixCarnet.Demande.de(q["sat"]), q["cockpit"] == "1")
                    ?: return JSONObject().put("ok", false).put("erreur", "il faut un indicatif et un satellite suivi")
                return ok.put("t", e.timeMs)
            }
            "/api/carnet/modifie" -> {
                val c = carnet ?: return JSONObject().put("ok", false)
                val t = q["t"]?.toLongOrNull() ?: return JSONObject().put("ok", false)
                val avant = c.contacts().firstOrNull { it.timeMs == t } ?: return JSONObject().put("ok", false)
                // Its date and time (UTC), when changed: checked before anything is written.
                val R = fr.f4ioz.satcombo.domain.RedateContact
                val nouveau = if (q["date"] != null || q["heure"] != null)
                    R.lis(q["date"].orEmpty(), q["heure"].orEmpty()) ?: return JSONObject().put("ok", false).put("erreur", "date ou heure illisible")
                else null
                nouveau?.let { R.pourquoiPas(it, System.currentTimeMillis()) }?.let { return JSONObject().put("ok", false).put("erreur", it) }
                // Its satellite (a name; its number when one of the list): what belongs to it follows.
                q["sat"]?.let { c.changeSatellite(t, it, q["satnum"]?.toIntOrNull() ?: 0) }
                c.modifie(t, avant.copy(callsign = q["c"] ?: avant.callsign, theirLocator = q["l"] ?: avant.theirLocator,
                    rstSent = q["rs"] ?: avant.rstSent, rstRcvd = q["rr"] ?: avant.rstRcvd, note = q["n"] ?: avant.note,
                    mode = q["md"] ?: avant.mode, nom = q["nm"] ?: avant.nom, qth = q["qth"] ?: avant.qth,
                    locatorOrigine = q["lo"] ?: avant.locatorOrigine))
                // The time last: it changes the key; az/el and the order follow.
                val r = if (nouveau != null && nouveau != t) c.redate(t, nouveau) else null
                val apres = r?.contact ?: c.contacts().firstOrNull { it.timeMs == t }
                return ok.put("t", apres?.timeMs ?: t).put("doublon", (r?.doublons ?: apres?.let { R.doublons(it, c.contacts()) }.orEmpty())
                    .let { R.avertissement(it) } ?: "")
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
            "/api/doppler" -> q["detaille"]?.let { d -> spectreJournal?.actif = d == "1" } ?: station.journalDoppler(q["on"] == "1")
            "/api/lien" -> {
                lien?.regle(q["on"]?.let { it == "1" }, q["port"]?.trim()?.toIntOrNull())
                if (lien != null && lien.erreur.isNotEmpty()) return JSONObject().put("ok", false).put("erreur", lien.erreur)
            }
            "/api/molette" -> return (molette ?: return JSONObject().put("ok", false).put("erreur", "absent")).regle(q).put("ok", true)
            // The page's wheel or drag on a frequency: the knob's steps, its settings.
            // Another SatMe-PC's knob, relayed here (its keys, as this station's own knob would send them).
            "/api/molette/touches" -> {
                val m = molette ?: return JSONObject().put("ok", false).put("erreur", "absent")
                var g = ""
                q["l"].orEmpty().split(',').take(50).forEach { k ->
                    val (c, v) = k.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return@forEach) to (it.getOrNull(1)?.toIntOrNull() ?: return@forEach) }
                    if (c in 1..0x2ff && v in 0..2) { m.recoit(c, v, distant = true); g = m.dernierGeste() }
                }
                return JSONObject().put("ok", true).put("geste", g)
            }
            "/api/molette/relais/coupe" -> { relaisMolette?.deconnecte(); return JSONObject().put("ok", true) }
            "/api/molette/tourne" -> {
                val m = molette ?: return JSONObject().put("ok", false).put("erreur", "absent")
                m.contextePage(q["ctx"])
                return JSONObject().put("ok", true).put("geste", m.tourne(q["n"]?.toIntOrNull() ?: 0, q["poste"] == "1"))
            }
            "/api/annonces" -> return (annonces ?: return JSONObject().put("ok", false).put("erreur", "absent")).regle(q)
            "/api/annonces/essai" -> return (annonces ?: return JSONObject().put("ok", false).put("erreur", "absent")).essai()
            "/api/rigctld" -> {
                val r = rigctld ?: return JSONObject().put("ok", false).put("erreur", "absent")
                fun b(k: String) = q[k]?.let { it == "1" }
                r.regle(b("on"), q["port"]?.trim()?.toIntOrNull(), b("reseau"), b("accord"), b("montee"))
                if (r.erreur.isNotEmpty()) return JSONObject().put("ok", false).put("erreur", r.erreur)
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
                else if (s.son.demarre(nom)) { s.carte = nom; s.memoriseSourceSatellite(nom) }
                else return JSONObject().put("ok", false).put("erreur", s.son.etat.value.erreur)
            }
            // The header's « Son »: what feeds the decoders, in one gesture (receive only).
            "/api/son/source" -> {
                val s = son ?: return JSONObject().put("ok", false)
                prendSource(s, q["nom"].orEmpty())?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/son/enregistrer" -> {
                val s = son ?: return JSONObject().put("ok", false)
                // Asked with nothing feeding: the card it needs started first (receive only).
                if (q["on"] == "1") (s.assureSource() ?: s.demarreEnregistrement())?.let { return JSONObject().put("ok", false).put("erreur", it) }
                else s.arreteEnregistrement()
            }
            "/api/passage/preparer" -> {
                val s = son ?: return JSONObject().put("ok", false)
                return preparePassage(s, q["enregistrer"] != "0")
            }
            "/api/ft8" -> {
                // The stations waited for: settings, kept even without a sound card.
                q["guettees"]?.let { return JSONObject().put("ok", true).put("guettees", JSONArray(fr.f4ioz.satcombo.domain.Ft8Veille.ecrit(station.stockage, it))) }
                val f = son?.ft8 ?: return JSONObject().put("ok", false)
                when {
                    q["vide"] == "1" -> f.vide()
                    q["tranches"] != null -> f.enregistreTranches = q["tranches"] == "1"
                    // Nothing feeding: the card it needs started first (receive only), else why not.
                    q["on"] == "1" -> { son.assureSource()?.let { return JSONObject().put("ok", false).put("erreur", it) }; f.demarre(q["mode"] ?: f.mode) }
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
                // A decoder switched on: its sound started when a card is known (else its card says why).
                if (q["sstv"] == "1" || q["aprs"] == "1") s.assureSource()
            }
            // SSTV, as the phone's controls: source, forced mode, continuous, cleaning; start by hand, end.
            "/api/sstv/reglage" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                q["source"]?.let { if (it in SstvPc.SOURCES) x.source = it else return JSONObject().put("ok", false).put("erreur", "source inconnue") }
                // A card chosen right here: the Sound tab's card, started (receive only).
                q["carte"]?.takeIf { it.isNotBlank() }?.let { nom ->
                    if (son.son.appareils().none { it.nom == nom }) return JSONObject().put("ok", false).put("erreur", "carte son introuvable : $nom")
                    son.prendCarte(nom)?.let { return JSONObject().put("ok", false).put("erreur", it) }
                }
                // « Auto » with nothing feeding: the rig's own card, when the rig is in CAT.
                if (q["source"] == SstvPc.AUTO && !son.son.etat.value.actif && son.catConnecte())
                    son.carteDuPoste()?.let { son.prendCarte(it) }
                q["force"]?.let { x.force = fr.f4ioz.satcombo.sstv.SstvMode.byName(it) }
                q["continu"]?.let { x.continu = it == "1" }
                q["nettoyage"]?.let { x.nettoyage = it == "1" }
                q["afc"]?.let { x.afc = it == "1" }
                q["actif"]?.let { son.sstvActif = it == "1" }
                if (q["actif"] == "1" && x.source != SstvPc.SDR) son.assureSource()
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
                // Its « à garder » mark goes with it (a picture of the same name received later is not kept for it).
                optionsSstv?.oublieGarde(q["nom"].orEmpty())
            }
            "/api/sstv/diapo", "/api/sstv/groupe", "/api/sstv/relance" ->
                return classement?.action(chemin) { q[it].orEmpty() } ?: JSONObject().put("ok", false).put("erreur", "absent")
            "/api/sstv/corriger", "/api/sstv/fusionner", "/api/sstv/correction/oublier" ->
                return classement?.corrections?.api?.action(chemin) { q[it].orEmpty() } ?: JSONObject().put("ok", false).put("erreur", "absent")
            "/api/sstv/satellite" -> {
                val x = son?.sstv ?: return JSONObject().put("ok", false)
                if (!x.renommeSatellite(q["nom"].orEmpty(), q["sat"].orEmpty())) return JSONObject().put("ok", false).put("erreur", "image ou nom de satellite refusé")
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
                q["zones"]?.let { rotor.setRotorZones(it) }
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
    /** The reception report the chat's habit writes, for what is received now ("" when nothing is locked). */
    private fun rapportDatv(): String {
        val d = datv ?: return ""
        val e = d.etat
        if (!e.actif || !e.verrouille) return ""
        val marge = d.qualite()?.opt("marge")?.let { (it as? Number)?.toDouble() }
        return fr.f4ioz.satcombo.datv.ChatLarge.rapport(large?.nomRecu() ?: e.service, marge, e.merDb, d.reglage.debitAccroche(e.srTrouveKs), d.reglage.cielKhz)
    }

    private fun carteDatv(d: DatvPc): JSONObject = d.carte.json(System.currentTimeMillis(), station.etat.value.observateur,
        { c -> carnet?.historique(c)?.size ?: 0 }, d.reglage.cielKhz, d.verrouDepuisMs)

    private fun phonie(r: RequeteHttp.Requete, chemin: String, q: Map<String, String>, sortie: OutputStream, p: PhoniePc) {
        val json = "application/json; charset=utf-8"
        if (chemin == "/api/phonie/parole" || chemin.startsWith("/api/phonie/parole/")) {
            val pa = parole ?: return envoie(sortie, "200 OK", json, JSONObject().put("present", false).toString().toByteArray())
            if (r.methode == "POST") {
                val res = runCatching { pa.action(q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
                return envoie(sortie, "200 OK", json, res.toString().toByteArray())
            }
            when (chemin) {
                "/api/phonie/parole" -> envoie(sortie, "200 OK", json, pa.json().toString().toByteArray())
                "/api/phonie/parole/installation" -> envoie(sortie, "200 OK", json, pa.jsonInstall().toString().toByteArray())
                "/api/phonie/parole/son" -> {
                    val w = q["seq"]?.toLongOrNull()?.let { pa.son(it) } ?: return envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
                    envoie(sortie, "200 OK", "audio/wav", w)
                }
                else -> envoie(sortie, "404 Not Found", "text/plain", ByteArray(0))
            }
            return
        }
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

    private fun envoie(s: OutputStream, statut: String, type: String, corps0: ByteArray, entetes: String = "") {
        // The demonstration's band on every page.
        val corps = if (ModeDemo.actif && type.startsWith("text/html")) ModeDemo.bandeau(corps0) else corps0
        s.write(("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\n" + entetes +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        s.write(corps)
        s.flush()
    }
}

/** The station as the page reads it. */
object JsonPc {
    /** The joined SatMe-Node's boards and roles as last read (set by Main), for the Montage's proposals. */
    @Volatile var noeudMateriel: () -> JSONObject? = { null }
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

    /** The dongle's sound cleaning. */
    fun nettoyage(d: SdrPc): JSONObject = fr.f4ioz.satcombo.demo.ApiSdr.nettoyage(d.modeNettoyage(), d.nettoyages, d.notchesVus, d.tauxBlanc)

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
            // CTCSS: the tone heard in NFM (receive only), and the tone squelch chosen (0: off).
            .put("ctcss", JSONObject().put("hz", e.ctcssHz).put("confiance", Math.round(e.ctcssConfiance * 100) / 100.0).put("dev", e.ctcssDevHz))
            .put("tonSq", st.sdrSilencieuxTon / 10.0).put("tonFerme", e.tonFerme)
            .put("suit", d.suitSatellite).put("manuelle", d.manuelleHz).put("hote", d.hote).put("port", d.port).put("lance", d.lance).put("bias", d.biasTee)
            .put("recepteur", d.recepteur).put("plutoHote", d.plutoHote).put("plutoPort", d.plutoPort)
            // The rig's IF (« IC-9700 FI »): its transport and the half-span usable; « recentrer le poste ».
            .put("fiRecentre", d.recentrePoste)
            .put("fi", d.fiPoste?.let { f -> JSONObject().put("transport", f.transport).put("demiHz", Math.round(f.plan.demiUtileHz)) } ?: JSONObject.NULL)
            .put("bladerfOutil", d.bladerfOutil).put("bladerfProgramme", d.programmeBladerf() ?: "").put("bladerfConseil", d.conseilBladerf())
            .put("bladerf", d.infoBladerf.let { i -> JSONObject().put("lu", i.lu).put("message", i.message).put("libelle", i.carte?.libelle ?: "") })
            // Fine tuning, the phone's settings: the magnifier, the vernier, the voice netting.
            .put("modeEff", d.modeEffectif()).put("loupe", st.sdrLoupe).put("loupeSpan", st.sdrLoupeSpanHz)
            .put("vernier", st.sdrVernier).put("ratio", st.sdrVernierHzParCm).put("calageVoix", st.sdrCalageVoix)
            // No hardware: the recording played (its transport), the generator's signals and where to hear each.
            .put("fichierIq", java.io.File(d.fichierIq).name).put("fictif", d.fictif)
            .put("lecture", d.lecture?.let { l ->
                JSONObject().put("nom", l.donnees.name).put("pos", Math.round(l.positionS * 10) / 10.0).put("duree", Math.round(l.dureeS * 10) / 10.0)
                    .put("vitesse", l.vitesse).put("pause", l.enPause).put("boucle", l.boucle).put("cale", l.caleTranches)
                    .put("moment", l.momentMs() ?: 0L).put("debit", l.debit).put("essai", l.meta.essai)
                    .put("attente", if (l.attenteJusqua > 0) maxOf(0L, l.attenteJusqua - System.currentTimeMillis()) / 1000.0 else 0.0)
            } ?: JSONObject.NULL)
            .put("generateur", d.reglagesGenerateur.let { g ->
                g.plat().put("ecoutes", JSONArray(fr.f4ioz.satcombo.sdr.GenerateurIq.ecoutes(g).map {
                    JSONObject().put("cle", it.cle).put("nom", it.nom).put("mode", it.mode).put("hz", it.decalageHz).put("bw", it.largeurHz) }))
                    .put("modesSstv", JSONArray(fr.f4ioz.satcombo.sstv.SstvMode.ALL.map { it.name }))
            })
    }

    /**
     * The last spectrum, [n] values (dB, the strongest of the bins each one
     * covers) over [span] Hz around the Doppler target — the signal stays in
     * the middle while the satellite passes. Null values before the first.
     */
    fun spectre(d: SdrPc, span: Int, n: Int, autourEcoute: Boolean = false, dx: Int = 0): JSONObject {
        val sp = d.spectre ?: return JSONObject().put("trames", 0)
        val e = d.etat.value
        // The magnifier: around where we listen (the offset in), not around the Doppler target;
        // [dx]: the view panned, within what the receiver sees.
        val k = if (autourEcoute) 0 else fr.f4ioz.satcombo.demo.ApiSdr.decalageVue(span, dx)
        val centre = e.fineHz.toDouble() + (if (autourEcoute) e.decalageHz else 0) + k
        return fr.f4ioz.satcombo.demo.ApiSdr.spectre(sp, Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size, centre, span, n, d.trames.toLong(), e.pllHz).put("dx", k)
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

    /**
     * What the rig is sent through the converter table: for each side the
     * sky, the IF actually sent and its line, or why nothing is sent ([avert]
     * when the CAT is connected: the status bar's warning).
     */
    fun conversionCat(st: StationPc, dl: Long?, ul: Long?, catDl: Long?, catUl: Long?, connecte: Boolean): JSONObject {
        val q = st.qo100.etat.value
        val cielDl = if (q.auPoste) q.descenteHz + q.calageHz else catDl ?: dl
        val cielUl = if (q.auPoste) q.monteeHz else catUl ?: ul
        val o = JSONObject()
        val refus = mutableListOf<String>()
        fun cote(k: String, ciel: Long?, u: UsageConversion) {
            if (ciel == null || ciel <= 0L) return
            val f = st.fiCat(ciel, u)
            o.put(k, JSONObject().put("ciel", f.cielHz).put("fi", f.fiHz).put("bande", f.bande ?: JSONObject.NULL).put("refus", f.refus ?: JSONObject.NULL))
            f.refus?.let { refus += it }
        }
        cote("dl", cielDl, UsageConversion.CAT_RX)
        cote("ul", cielUl, UsageConversion.CAT_TX)
        if (connecte && refus.isNotEmpty()) o.put("avert", refus.joinToString(" · "))
        return o
    }

    /** The converter table for the page: its lines, each one's IF window and problem, and what each device gets now. */
    fun tableConversion(st: StationPc, sdr: SdrPc?): JSONObject {
        val t = st.bandes()
        val a = JSONArray(t.map { b ->
            JSONObject().put("nom", b.nom).put("bas", b.basHz).put("haut", b.hautHz).put("ol", b.olHz).put("inv", b.inverse)
                .put("sdr", b.sdr).put("catRx", b.catRx).put("catTx", b.catTx).put("actif", b.actif).put("chaine", b.chaine)
                .put("probleme", b.probleme() ?: JSONObject.NULL)
                .put("fiBas", b.fenetreFi().first).put("fiHaut", b.fenetreFi().last)
        })
        val o = JSONObject().put("bandes", a).put("max", TableConversion.MAX_LIGNES)
            .put("presets", JSONArray(TableConversion.PRESETS.map { JSONObject().put("nom", it.nom) }))
        sdr?.let { d -> val e = d.etat.value
            if (e.actif && e.pllHz > 0) o.put("cle", JSONObject().put("ciel", d.cielDePll(e.pllHz)).put("fi", e.pllHz)) }
        return o
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
        o.put("amsatAuto", st.settings.amsatAuto).put("amsatEtat", st.pushAmsat.etat)
        // QO-100's converters as the station chain reads them (the Montage tab): the table's lines for its beacon and uplink.
        val bandes = st.bandes()
        val rx = TableConversion.bande(bandes, Convertisseur.BALISE_MEDIANE_HZ, UsageConversion.SDR)
            ?: TableConversion.bande(bandes, Convertisseur.BALISE_MEDIANE_HZ, UsageConversion.CAT_RX)
        val tx = TableConversion.bande(bandes, TableConversion.QO100_UL_BAS + 50_000L, UsageConversion.CAT_TX)
        o.put("convertisseurs", JSONObject()
            .put("rxOl", rx?.olHz ?: 0).put("rxInv", rx?.inverse == true)
            .put("txOl", tx?.olHz ?: 0).put("txInv", tx?.inverse == true))
        o.put("conversion", conversionCat(st, dl, ul, c.descenteHz, c.monteeHz, e.catConnecte))
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

    /** The Orbites tab and the Suivi globe: the station, the satellite followed, the favourites, every satellite's elements (worked out in the page). */
    fun orbites(st: StationPc): JSONObject {
        val e = st.etat.value
        val o = JSONObject().put("indicatif", e.indicatif).put("locator", e.locator).put("suivi", e.suivi?.catalogNumber ?: 0).put("favoris", JSONArray(e.favoris))
        e.observateur?.let { o.put("obs", JSONObject().put("lat", it.latDeg).put("lon", it.lonDeg).put("alt", it.altMeters)) }
        return o.put("sats", JSONArray(e.satellites.map { s ->
            JSONObject().put("nom", s.name).put("catnum", s.catalogNumber).put("l1", s.line1).put("l2", s.line2)
                .also { j -> s.downlinkHz?.let { j.put("dl", it / 1e6) } }
        }))
    }

    /** Each callsign's flag, worked out once (the log is sent every few seconds). */
    private val drapeaux = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

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
                    .also { o -> drapeaux.getOrPut(e.callsign) { fr.f4ioz.satcombo.demo.ApiStation.drapeau(JSONObject(), e.callsign) }
                        .let { d -> d.optString("dr").takeIf { it.isNotEmpty() }?.let { o.put("dr", it) }; d.optString("py").takeIf { it.isNotEmpty() }?.let { o.put("py", it) } } }
            }))
    }

    /**
     * The squares worked (4 characters), from the log: how many contacts,
     * the first one, who, on which satellites; and mine with its neighbours.
     */
    /** The settings shown on the page; passwords and keys never come back, only whether they are set. */
    fun datv(d: DatvPc): JSONObject = Datv.json(d.etat, d.reglage, d.constellation()).put("qualite", d.qualite() ?: JSONObject.NULL)
        .put("noeud", d.noeud).put("nomNoeud", d.nomNoeud).put("noeudMateriel", noeudMateriel()?.takeIf { d.noeud.isNotBlank() } ?: JSONObject.NULL)
        .put("programme", d.programme() ?: JSONObject.NULL).put("chemin", d.chemin).put("windows", OutilsPc.windows)
        .put("debits", JSONArray(Datv.DEBITS)).put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("balise", Datv.BALISE_KHZ)
        .put("sonReglable", d.sonReglable).put("volume", d.volume).put("relance", d.relanceAuto).put("relancesImage", d.relancesImage)
        .put("niveau", d.niveauDb() ?: JSONObject.NULL).put("caleAuto", d.caleAuto).put("tolerant", d.decodageTolerant)
        .put("calage", d.calage.json(d.reglage.olKhz, System.currentTimeMillis()))
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
    fun son(s: SonStation?, sdr: SdrPc? = null, phonieActif: Boolean = false): JSONObject {
        if (s == null) return JSONObject().put("present", false)
        val c = s.son.etat.value
        val e = s.etat.value
        val v = s.sstv.etat.value
        val appareils = s.son.appareils()
        val noms = s.nomsCartes(appareils)
        return ApiSon.json(ApiSon.Etat(appareils.map { it.nom to it.description }, s.carte, c.actif, c.rate, c.niveau, c.erreur,
            e.enregistrement, e.secondes, e.auto, s.enregAuto, s.sstvActif, s.aprsActif,
            v.mode, v.lignes, v.hauteur, v.version, v.derniere, s.enregistrements(), s.images(), s.coupeLos,
            detail = sstvDetail(s, sdr, noms), dossierImages = s.dossierSstv,
            libelles = noms.mapValues { it.value.libelle }, carteDuPoste = noms.entries.firstOrNull { it.value.poste.isNotBlank() }?.key.orEmpty()))
            // What actually feeds the decoders (the dongle when it took the card's place), not only the card chosen.
            .put("appareil", c.appareil)
            .also { resumeSource(it, s, sdr, phonieActif, appareils, noms) }
    }

    /**
     * The header's « Son »: who listens, the source for the satellite
     * followed, and what to do when they hear nothing (one line, one button).
     */
    private fun resumeSource(o: JSONObject, s: SonStation, sdr: SdrPc?, phonieActif: Boolean,
                             appareils: List<SonPc.Appareil>, noms: Map<String, CartesSonLinux.Libelle>) {
        val c = s.son.etat.value
        val ecoutent = buildList {
            if (s.sstvActif) add("SSTV")
            if (s.ft8.enMarche) add(s.ft8.mode.ifBlank { "FT8" })
            if (s.cw.actif) add("CW")
            if (s.aprsActif) add("APRS")
            if (phonieActif) add("Phonie")
        }
        val sat = s.sourcePourSatellite(s.suiviCatnum())
        val duPoste = s.sourceDuPoste(appareils)
        val carte = fr.f4ioz.satcombo.domain.SourceAudio.carteAPrendre(appareils.map { it.nom }, sat, s.derniereCarte, duPoste)
        val lib = { n: String -> if (n == SdrPc.NOM_SOURCE) "la clé SDR" else noms[n]?.libelle ?: n }
        val silence = s.silenceDepuis.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L
        val k = fr.f4ioz.satcombo.domain.SourceAudio.conseil(ecoutent.filter { it != "Phonie" }, c.actif, c.niveau.toDouble(), silence,
            carte, carte?.let(lib).orEmpty(), sdr != null, lib(c.appareil))
        o.put("ecoutent", JSONArray(ecoutent)).put("duPoste", duPoste.orEmpty()).put("pourSatellite", sat.orEmpty()).put("aPrendre", carte.orEmpty())
            .put("sdrPresent", sdr != null).put("sdrActif", sdr?.etat?.value?.actif == true)
            .put("armePassage", s.enregistrePassage != 0)
            .put("conseil", k?.let { JSONObject().put("texte", it.texte).put("action", it.action).put("bouton", it.bouton) } ?: JSONObject.NULL)
    }

    /** The SSTV decoder in detail: its source, what it hears, its settings, the work under way. */
    fun sstvDetail(s: SonStation, sdr: SdrPc?, noms: Map<String, CartesSonLinux.Libelle> = emptyMap()): ApiSon.Sstv {
        val x = s.sstv
        val v = x.etat.value
        val e = x.ecoute()
        val sdrActive = sdr?.etat?.value?.actif == true
        val carte = s.son.etat.value
        val src = when (x.sourceActive) {
            SstvPc.SDR -> "clé SDR"
            SstvPc.CARTE -> if (carte.appareil == SdrPc.NOM_SOURCE) "clé SDR (par l'onglet Son)" else "carte son" + (carte.appareil.takeIf { it.isNotBlank() }?.let { " (" + (noms[it]?.libelle ?: it) + ")" } ?: "")
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

/** The pages a browser opens by their address: without a session, sent to the login page (else a 401). */
private val PAGES_NAVIGATEUR = setOf("/", "/index.html", "/pc", "/journal", "/journal/", "/planche", "/planche/")

private val ReponseMontageAbsente =fr.f4ioz.satcombo.demo.ReponseWeb("200 OK", "application/json; charset=utf-8",
    "{\"ok\":false,\"erreur\":\"pas de montage ici\"}".toByteArray())

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
