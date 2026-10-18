/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.adsb.DemodAdsb
import fr.f4ioz.satcombo.adsb.DiffusionAvions
import fr.f4ioz.satcombo.adsb.Sbs
import fr.f4ioz.satcombo.adsb.SimulateurAdsb
import fr.f4ioz.satcombo.adsb.TableAvions
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.RtlTcp
import fr.f4ioz.satcombo.sdr.SourceIq
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * ADS-B on 1090 MHz (« Avions »): receive only, off until asked.
 *
 * **The receiver** is one of:
 * - **the SDR tab's dongle** (its rtl_tcp settings): it cannot listen to
 *   two places at once, so while the SDR tab (or QO-100, METEOR, the
 *   sondes) uses it, ADS-B waits for the owner to choose « Prendre la clé » —
 *   as METEOR pictures take it; meanwhile the SDR tab may not start on it;
 * - **a second dongle**: an rtl_tcp of its own, `-d` with its index or
 *   serial, on its own port;
 * - **the ADALM-PLUTO**: refused while it receives elsewhere (SDR, DATV) or
 *   transmits.
 *
 * All at 2.0 MS/s, the demodulator's rate: the dongle does it natively;
 * the Pluto, below its 2.083 MS/s floor, runs at 4 MS/s and decimates by 2
 * (its 2 MHz analog filter keeps the 0.5 µs pulses well enough). 2.4 MS/s
 * would need a second demodulator for little gain.
 *
 * **The BaseStation output** (SBS-1 lines on TCP 30003) is off unless
 * chosen, and bound to 127.0.0.1 only: for programs on this PC.
 */
class AdsbPc(private val station: StationPc, private val sdr: SdrPc, private val stockage: Stockage,
             private val carnet: CarnetPc? = null, private val horloge: () -> Long = System::currentTimeMillis,
             /** The BaseStation output's port (0 in the tests: any free one). */
             private val portSbs: Int = PORT_SBS) {

    val table = TableAvions(horloge)
    private val demod = DemodAdsb(connu = table::connu) { table.recoit(it) }

    // ---------------------------------------------------------------- settings
    var source: String
        get() = stockage.getString(CLE_SOURCE, CLE_SDR).takeIf { it in SOURCES } ?: CLE_SDR
        set(v) { stockage.edit().putString(CLE_SOURCE, if (v in SOURCES) v else CLE_SDR).apply() }
    /** The second dongle: its index or serial for rtl_tcp's `-d`. */
    var appareil: String
        get() = stockage.getString(CLE_APPAREIL, "1") ?: "1"
        set(v) { stockage.edit().putString(CLE_APPAREIL, v.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32).ifEmpty { "1" }).apply() }
    var portRtl: Int
        get() = stockage.getInt(CLE_PORT, 1235)
        set(v) { stockage.edit().putInt(CLE_PORT, v.coerceIn(1, 65535)).apply() }
    /** Gain in tenths of dB; −1: the tuner's automatic gain. */
    var gain: Int
        get() = stockage.getInt(CLE_GAIN, 496)
        set(v) { stockage.edit().putInt(CLE_GAIN, v.coerceIn(-1, 600)).apply(); cle?.let { c -> runCatching { c.setGain(v.takeIf { it >= 0 }) } } }
    var corrige: Boolean
        get() = stockage.getBoolean(CLE_CORRIGE, true)
        set(v) { stockage.edit().putBoolean(CLE_CORRIGE, v).apply(); demod.corrige = v }
    var sbs: Boolean
        get() = stockage.getBoolean(CLE_SBS, false)
        set(v) { stockage.edit().putBoolean(CLE_SBS, v).apply(); if (v) ouvreSbs() else fermeSbs() }

    // The scatter prediction's settings.
    var cible: String
        get() = stockage.getString(CLE_CIBLE, "") ?: ""
        private set(v) { stockage.edit().putString(CLE_CIBLE, v).apply() }
    /** The target's locator, resolved once when set (the log, else QRZ). */
    var cibleLoc: String
        get() = stockage.getString(CLE_CIBLE_LOC, "") ?: ""
        private set(v) { stockage.edit().putString(CLE_CIBLE_LOC, v).apply() }
    var bande: String
        get() = stockage.getString(CLE_BANDE, "10G").takeIf { it in DiffusionAvions.BANDES } ?: "10G"
        set(v) { stockage.edit().putString(CLE_BANDE, if (v in DiffusionAvions.BANDES) v else "10G").apply() }
    var rayonKm: Int
        get() = stockage.getInt(CLE_RAYON, 100)
        set(v) { stockage.edit().putInt(CLE_RAYON, v.coerceIn(5, 500)).apply() }
    var hauteurMoi: Int
        get() = stockage.getInt(CLE_H_MOI, 150)
        set(v) { stockage.edit().putInt(CLE_H_MOI, v.coerceIn(0, 5000)).apply() }
    var hauteurLui: Int
        get() = stockage.getInt(CLE_H_LUI, 150)
        set(v) { stockage.edit().putInt(CLE_H_LUI, v.coerceIn(0, 5000)).apply() }
    var altMin: Int
        get() = stockage.getInt(CLE_ALT_MIN, 10_000)
        set(v) { stockage.edit().putInt(CLE_ALT_MIN, v.coerceIn(0, 45_000)).apply() }
    var horizonMin: Int
        get() = stockage.getInt(CLE_HORIZON, 15)
        set(v) { stockage.edit().putInt(CLE_HORIZON, v.coerceIn(1, 60)).apply() }

    // ---------------------------------------------------------------- running
    @Volatile var actif = false; private set
    @Volatile var statut = "arrêté"; private set
    @Volatile var erreur = ""; private set
    /** What receives now: [CLE_SDR], [RTL2], [PLUTO], [ESSAI]. */
    @Volatile var tient = ""; private set
    @Volatile private var cle: SourceIq? = null
    @Volatile private var processus: Process? = null
    @Volatile private var generation = 0
    @Volatile private var octets = 0L
    @Volatile private var debitKo = 0
    @Volatile private var tuner = ""

    /** The SDR tab's dongle held here: the SDR tab may not start on it. */
    val tientCleSdr: Boolean get() = actif && tient == CLE_SDR
    val tientPluto: Boolean get() = actif && tient == PLUTO

    init { demod.corrige = corrige }

    /** AIS (Navires tab) holds the SDR tab's dongle (set at startup): never taken from it here. */
    @Volatile var aisTientCle: () -> Boolean = { false }

    /** Why [source] cannot start now without taking a receiver someone uses, or null. */
    fun occupe(s: String = source): String? = when (s) {
        CLE_SDR -> if (sdr.etat.value.actif && sdr.recepteur == SdrPc.RTL) "la clé SDR est en service (SDR, QO-100, METEOR ou sondes)"
            else if (aisTientCle()) "la clé SDR reçoit l'AIS (onglet Navires)" else null
        PLUTO -> when {
            sdr.plutoEnReception -> "l'ADALM-PLUTO reçoit (onglet SDR)"
            sdr.datvTient == SdrPc.PLUTO -> "l'ADALM-PLUTO reçoit la DATV"
            sdr.emissionPluto() -> "l'ADALM-PLUTO émet"
            else -> null
        }
        else -> null
    }

    /**
     * Starts on the chosen receiver. [prendre]: the owner chose to take the
     * SDR tab's dongle from it (never done otherwise). An error, or null.
     */
    @Synchronized fun demarre(prendre: Boolean = false): String? {
        arrete()
        val s = source
        occupe(s)?.let { o ->
            if (!(prendre && s == CLE_SDR && !aisTientCle())) return o + (if (s == CLE_SDR && !aisTientCle()) " : « Prendre la clé » pour l'ADS-B" else " : l'arrêter d'abord")
            sdr.arrete()
        }
        val gen = ++generation
        statut = "connexion…"; erreur = ""
        val r = runCatching { ouvre(s) }.getOrElse { e ->
            arreteProcessus()
            statut = "arrêté"; erreur = e.message ?: "récepteur injoignable"
            return erreur
        }
        cle = r
        runCatching {
            r.setSampleRate(DEBIT)
            r.setCenterFreq(FREQ_HZ)
            if (r is RtlTcp) { r.setAgc(false); r.setFreqCorrection(station.settings.sdrPpm) }
            r.setGain(gain.takeIf { it >= 0 })
        }
        tuner = when (r) { is RtlTcp -> "RTL-SDR " + RtlTcp.nomTuner(r.tuner); is PlutoSdr -> r.description; else -> "" }
        tient = s; actif = true; statut = "réception"
        demod.remet(); demod.corrige = corrige
        thread(isDaemon = true, name = "satme-adsb") { boucle(r, gen) }
        return null
    }

    /** Trial aircraft around the station, through the real demodulator: no receiver touched. */
    @Synchronized fun essai(): String? {
        arrete()
        val o = station.etat.value.observateur ?: return "la position de la station n'est pas connue (Réglages)"
        val gen = ++generation
        tient = ESSAI; actif = true; statut = "essai : avions simulés"; erreur = ""; tuner = "simulateur"
        demod.remet()
        thread(isDaemon = true, name = "satme-adsb-essai") {
            val sim = SimulateurAdsb(o.latDeg, o.lonDeg, (horloge() % 1000).toInt())
            val buf = FloatArray(200_000)
            while (generation == gen) {
                val n = sim.bloc(100, buf)
                demod.traite(buf, n)
                octets += n * 2L
                Thread.sleep(100)
            }
        }
        return null
    }

    @Synchronized fun arrete() {
        generation++
        cle?.let { runCatching { it.close() } }
        cle = null
        arreteProcessus()
        if (actif) statut = "arrêté"
        actif = false; tient = ""; debitKo = 0
    }

    private fun ouvre(s: String): SourceIq = when (s) {
        PLUTO -> PlutoSdr.connecte(sdr.plutoHote, sdr.plutoPort)
        RTL2 -> rtlTcp("127.0.0.1", portRtl, true, appareil)
        else -> rtlTcp(sdr.hote, sdr.port, sdr.lance, null)
    }

    /** rtl_tcp: reached, or started here at 2 MS/s ([appareil]: `-d`) when it is local and nothing answers. */
    private fun rtlTcp(hote: String, port: Int, lancer: Boolean, appareil: String?): SourceIq {
        runCatching { return RtlTcp.connecte(hote, port, 1500) }
        val local = hote == "127.0.0.1" || hote == "localhost" || hote == "::1"
        if (!lancer || !local) throw IOException("rtl_tcp ne répond pas sur $hote:$port")
        val cmd = mutableListOf(OutilsPc.trouve("rtl_tcp") ?: "rtl_tcp", "-a", "127.0.0.1", "-p", port.toString(), "-s", DEBIT.toString())
        if (appareil != null) { cmd += "-d"; cmd += appareil }
        val journal = File(System.getProperty("java.io.tmpdir"), "satme-adsb-rtl_tcp.log")
        val p = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(journal).start() }
            .getOrElse { throw IOException("rtl_tcp introuvable : " + OutilsPc.conseil("rtl_tcp")) }
        processus = p
        val fin = horloge() + 6000
        while (horloge() < fin) {
            if (!p.isAlive) throw IOException("rtl_tcp s'est arrêté : clé absente ou déjà prise (" +
                runCatching { journal.readLines().lastOrNull { it.isNotBlank() }.orEmpty() }.getOrDefault("") + ")")
            runCatching { return RtlTcp.connecte("127.0.0.1", port, 1000) }
            Thread.sleep(300)
        }
        throw IOException("rtl_tcp ne répond pas")
    }

    private fun arreteProcessus() {
        processus?.let { p -> runCatching { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }
        processus = null
    }

    private fun boucle(r: SourceIq, gen: Int) {
        val iq = ByteArray(RtlTcp.BLOC)
        val iq16 = if (r.iq16) ShortArray(RtlTcp.BLOC) else null
        val mag = FloatArray(RtlTcp.BLOC)
        var dernier = horloge(); var avant = 0L
        while (generation == gen) {
            val n = runCatching { if (iq16 != null) r.read16(iq16, 200) else r.read(iq, 200) }.getOrDefault(-1)
            if (n < 0) {
                if (generation == gen) {
                    erreur = if (r is PlutoSdr) r.erreur ?: "l'ADALM-PLUTO a coupé la liaison" else "rtl_tcp a coupé la liaison"
                    actif = false; statut = "arrêté"; tient = ""
                }
                break
            }
            if (n == 0) continue
            // The Pluto's samples come at 16-bit full scale.
            val m = if (iq16 != null) DemodAdsb.magnitudes16(iq16, n, mag, 32768f) else DemodAdsb.magnitudes8(iq, n, mag)
            demod.traite(mag, m)
            octets += n
            val t = horloge()
            if (t - dernier >= 1000) { debitKo = ((octets - avant) * 1000 / (t - dernier) / 1024).toInt(); avant = octets; dernier = t }
        }
    }

    // ---------------------------------------------------------------- SBS-1 output
    private var serveurSbs: ServerSocket? = null
    private val clientsSbs = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val fileSbs = LinkedBlockingQueue<String>(20_000)
    @Volatile var erreurSbs = ""; private set
    /** The port the output listens on now (0: closed). */
    val portSbsEcoute: Int get() = serveurSbs?.localPort ?: 0

    init {
        table.ecouteur = { m, a -> if (serveurSbs != null && clientsSbs.isNotEmpty()) Sbs.ligne(m, a, horloge())?.let { fileSbs.offer(it) } }
        if (sbs) ouvreSbs()
    }

    @Synchronized private fun ouvreSbs() {
        if (serveurSbs != null) return
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), portSbs)) } }
            .getOrElse { erreurSbs = "port $portSbs déjà pris : " + (it.message ?: ""); return }
        erreurSbs = ""
        serveurSbs = s
        thread(isDaemon = true, name = "satme-adsb-sbs") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                c.tcpNoDelay = true
                clientsSbs += c
            }
        }
        thread(isDaemon = true, name = "satme-adsb-sbs-envoi") {
            while (!s.isClosed) {
                val l = runCatching { fileSbs.poll(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: continue
                val b = (l + "\r\n").toByteArray(Charsets.US_ASCII)
                for (c in clientsSbs) if (runCatching { c.getOutputStream().write(b) }.isFailure) { clientsSbs -= c; runCatching { c.close() } }
            }
        }
    }

    @Synchronized private fun fermeSbs() {
        serveurSbs?.let { runCatching { it.close() } }
        serveurSbs = null
        clientsSbs.forEach { runCatching { it.close() } }; clientsSbs.clear(); fileSbs.clear()
    }

    // ---------------------------------------------------------------- scatter

    /** The target: a locator as typed, else a callsign's — the log's last one, then QRZ (asked once, here). */
    fun poseCible(texte: String): String? {
        val t = texte.trim().uppercase()
        if (t.isEmpty()) { cible = ""; cibleLoc = ""; return null }
        if (LOCATOR.matches(t)) { cible = t; cibleLoc = t; return null }
        val base = t.substringBefore('/')
        val duCarnet = carnet?.contacts()?.firstOrNull { it.callsign.uppercase().substringBefore('/') == base && LOCATOR.matches(it.theirLocator.trim().uppercase()) }
            ?.theirLocator?.trim()?.uppercase()
        val loc = duCarnet ?: carnet?.let { c -> runCatching { kotlinx.coroutines.runBlocking { c.ficheQrz(t) } }.getOrNull()?.carre?.trim()?.uppercase() }
            ?.takeIf { LOCATOR.matches(it) }
            ?: return "pas de locator connu pour $t (ni dans le carnet ni sur QRZ) : taper son locator"
        cible = t; cibleLoc = loc
        return null
    }

    fun reglageDiffusion(): DiffusionAvions.Reglage? {
        val o = station.etat.value.observateur ?: return null
        val (la, lo) = Maidenhead.centre(cibleLoc) ?: return null
        return DiffusionAvions.Reglage(o.latDeg, o.lonDeg, hauteurMoi.toDouble(), la, lo, hauteurLui.toDouble(), bande, rayonKm.toDouble(), altMin, horizonMin)
    }

    // ---------------------------------------------------------------- the page

    fun json(): JSONObject {
        val obs = station.etat.value.observateur
        table.station = obs?.let { it.latDeg to it.lonDeg }
        val o = JSONObject().put("ok", true).put("actif", actif).put("statut", statut).put("erreur", erreur).put("tient", tient)
            .put("tuner", tuner).put("debitKo", debitKo)
            .put("source", source).put("appareil", appareil).put("port", portRtl).put("gain", gain).put("corrige", corrige)
            .put("sbs", sbs).put("sbsPort", portSbs).put("sbsClients", clientsSbs.size).put("erreurSbs", erreurSbs)
            .put("occupe", occupe() ?: "")
            .put("preambules", demod.preambules).put("trames", demod.trames).put("corrigees", demod.corrigees).put("messages", table.total)
            .put("positions", table.positions)
            .put("avions", table.json(trace = true))
        if (obs != null) o.put("moi", JSONObject().put("lat", obs.latDeg).put("lon", obs.lonDeg))
        val d = JSONObject().put("cible", cible).put("loc", cibleLoc).put("bande", bande).put("rayonKm", rayonKm)
            .put("hMoi", hauteurMoi).put("hLui", hauteurLui).put("altMin", altMin).put("horizonMin", horizonMin)
            .put("bandes", JSONArray(DiffusionAvions.BANDES.keys.toList()))
        reglageDiffusion()?.let { r ->
            d.put("geometrie", DiffusionAvions.geometrieJson(r))
            d.put("fenetres", JSONArray(DiffusionAvions.fenetres(r, table.liste(), horloge()).map { DiffusionAvions.json(it) }))
        }
        return o.put("diffusion", d)
    }

    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val o: JSONObject = if (methode != "POST") json() else runCatching {
            when (chemin) {
                "/api/adsb/demarre" -> demarre(p("prendre") == "1")?.let { JSONObject().put("ok", false).put("erreur", it) } ?: JSONObject().put("ok", true)
                "/api/adsb/arrete" -> { arrete(); JSONObject().put("ok", true) }
                "/api/adsb/essai" -> essai()?.let { JSONObject().put("ok", false).put("erreur", it) } ?: JSONObject().put("ok", true)
                "/api/adsb/vide" -> { table.vide(); JSONObject().put("ok", true) }
                "/api/adsb/reglage" -> {
                    if (p("source").isNotEmpty()) source = p("source")
                    if (p("appareil").isNotEmpty()) appareil = p("appareil")
                    p("port").toIntOrNull()?.let { portRtl = it }
                    p("gain").toIntOrNull()?.let { gain = it }
                    if (p("corrige").isNotEmpty()) corrige = p("corrige") == "1"
                    if (p("sbs").isNotEmpty()) sbs = p("sbs") == "1"
                    if (p("bande").isNotEmpty()) bande = p("bande")
                    p("rayon").toIntOrNull()?.let { rayonKm = it }
                    p("hMoi").toIntOrNull()?.let { hauteurMoi = it }
                    p("hLui").toIntOrNull()?.let { hauteurLui = it }
                    p("altMin").toIntOrNull()?.let { altMin = it }
                    p("horizon").toIntOrNull()?.let { horizonMin = it }
                    val e = if (p("cible") != "" || p("efface") == "1") poseCible(p("cible")) else null
                    if (e != null) JSONObject().put("ok", false).put("erreur", e) else JSONObject().put("ok", true)
                }
                else -> JSONObject().put("ok", false).put("erreur", "inconnu")
            }
        }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FREQ_HZ = 1_090_000_000L
        const val DEBIT = 2_000_000
        const val PORT_SBS = 30003
        const val CLE_SDR = "cle"
        const val RTL2 = "rtl2"
        const val PLUTO = "pluto"
        const val ESSAI = "essai"
        val SOURCES = setOf(CLE_SDR, RTL2, PLUTO)
        private val LOCATOR = Regex("^[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?$")
        const val CLE_SOURCE = "pc_adsb_source"
        const val CLE_APPAREIL = "pc_adsb_appareil"
        const val CLE_PORT = "pc_adsb_port"
        const val CLE_GAIN = "pc_adsb_gain"
        const val CLE_CORRIGE = "pc_adsb_corrige"
        const val CLE_SBS = "pc_adsb_sbs"
        const val CLE_CIBLE = "pc_adsb_cible"
        const val CLE_CIBLE_LOC = "pc_adsb_cible_loc"
        const val CLE_BANDE = "pc_adsb_bande"
        const val CLE_RAYON = "pc_adsb_rayon"
        const val CLE_H_MOI = "pc_adsb_h_moi"
        const val CLE_H_LUI = "pc_adsb_h_lui"
        const val CLE_ALT_MIN = "pc_adsb_alt_min"
        const val CLE_HORIZON = "pc_adsb_horizon"
    }
}
