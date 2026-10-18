/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.ais.Aivdm
import fr.f4ioz.satcombo.ais.DemodAis
import fr.f4ioz.satcombo.ais.MessageAis
import fr.f4ioz.satcombo.ais.SimulateurAis
import fr.f4ioz.satcombo.ais.TableNavires
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.RtlTcp
import fr.f4ioz.satcombo.sdr.SourceIq
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * AIS (« Navires »): the ships' messages on 161.975 and 162.025 MHz,
 * receive only, off until asked.
 *
 * **The receiver**, as for ADS-B: the SDR tab's dongle, taken only when the
 * owner chooses « Prendre la clé » while something else uses it (the SDR
 * tab may not start on it meanwhile, nor ADS-B); or a second dongle with
 * its own rtl_tcp (`-d` index or serial, its own port). Both channels at
 * once: 288 kS/s centred on 162.000 MHz.
 *
 * **The NMEA output** (« !AIVDM » sentences by UDP to 127.0.0.1:10110, for
 * chart programs on this PC) is off unless chosen.
 */
class AisPc(private val station: StationPc, private val sdr: SdrPc, private val stockage: Stockage,
            private val horloge: () -> Long = System::currentTimeMillis,
            /** The NMEA output's port (the tests' own). */
            private val portNmea: Int = PORT_NMEA) {

    val table = TableNavires(horloge)
    private var sequence = 0
    private val demod = DemodAis { bits, canal, niveau -> recoit(bits, canal, niveau) }

    /** ADS-B holds the SDR tab's dongle (set at startup). */
    @Volatile var adsbTientCle: () -> Boolean = { false }

    // ---------------------------------------------------------------- settings
    var source: String
        get() = stockage.getString(CLE_SOURCE, CLE_SDR).takeIf { it in SOURCES } ?: CLE_SDR
        set(v) { stockage.edit().putString(CLE_SOURCE, if (v in SOURCES) v else CLE_SDR).apply() }
    var appareil: String
        get() = stockage.getString(CLE_APPAREIL, "1") ?: "1"
        set(v) { stockage.edit().putString(CLE_APPAREIL, v.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32).ifEmpty { "1" }).apply() }
    var portRtl: Int
        get() = stockage.getInt(CLE_PORT, 1236)
        set(v) { stockage.edit().putInt(CLE_PORT, v.coerceIn(1, 65535)).apply() }
    /** Gain in tenths of dB; −1: the tuner's automatic gain. */
    var gain: Int
        get() = stockage.getInt(CLE_GAIN, 400)
        set(v) { stockage.edit().putInt(CLE_GAIN, v.coerceIn(-1, 600)).apply(); cle?.let { c -> runCatching { c.setGain(v.takeIf { it >= 0 }) } } }
    /** The second dongle's error in ppm (the SDR tab's own is used for its dongle). */
    var ppm: Int
        get() = stockage.getInt(CLE_PPM, 0)
        set(v) { stockage.edit().putInt(CLE_PPM, v.coerceIn(-200, 200)).apply() }
    var nmea: Boolean
        get() = stockage.getBoolean(CLE_NMEA, false)
        set(v) { stockage.edit().putBoolean(CLE_NMEA, v).apply(); if (!v) fermeNmea() }

    // ---------------------------------------------------------------- running
    @Volatile var actif = false; private set
    @Volatile var statut = "arrêté"; private set
    @Volatile var erreur = ""; private set
    @Volatile var tient = ""; private set
    @Volatile private var cle: SourceIq? = null
    @Volatile private var processus: Process? = null
    @Volatile private var generation = 0
    @Volatile private var octets = 0L
    @Volatile private var debitKo = 0
    @Volatile private var tuner = ""
    @Volatile var phrases = 0L; private set
    private val parCanal = intArrayOf(0, 0)

    /** The SDR tab's dongle held here: the SDR tab (and ADS-B) may not start on it. */
    val tientCleSdr: Boolean get() = actif && tient == CLE_SDR

    fun occupe(s: String = source): String? = when (s) {
        CLE_SDR -> when {
            sdr.etat.value.actif && sdr.recepteur == SdrPc.RTL -> "la clé SDR est en service (SDR, QO-100, METEOR ou sondes)"
            adsbTientCle() -> "la clé SDR reçoit l'ADS-B (onglet Avions)"
            else -> null
        }
        else -> null
    }

    @Synchronized fun demarre(prendre: Boolean = false): String? {
        arrete()
        val s = source
        occupe(s)?.let { o ->
            // Taken from the SDR tab when the owner says so; never from ADS-B.
            if (!(prendre && s == CLE_SDR && !adsbTientCle())) return o + (if (s == CLE_SDR && !adsbTientCle()) " : « Prendre la clé » pour l'AIS" else " : l'arrêter d'abord")
            sdr.arrete()
        }
        val gen = ++generation
        statut = "connexion…"; erreur = ""
        val r = runCatching { if (s == RTL2) rtlTcp("127.0.0.1", portRtl, true, appareil) else rtlTcp(sdr.hote, sdr.port, sdr.lance, null) }.getOrElse { e ->
            arreteProcessus()
            statut = "arrêté"; erreur = e.message ?: "récepteur injoignable"
            return erreur
        }
        cle = r
        runCatching {
            r.setSampleRate(DemodAis.DEBIT)
            r.setCenterFreq(DemodAis.CENTRE_HZ)
            r.setAgc(false)
            r.setFreqCorrection(if (s == RTL2) ppm else station.settings.sdrPpm)
            r.setGain(gain.takeIf { it >= 0 })
        }
        tuner = (r as? RtlTcp)?.let { "RTL-SDR " + RtlTcp.nomTuner(it.tuner) } ?: ""
        tient = s; actif = true; statut = "réception"
        thread(isDaemon = true, name = "satme-ais") { boucle(r, gen) }
        return null
    }

    /** Trial vessels around the station, through the real demodulator: no receiver touched. */
    @Synchronized fun essai(): String? {
        arrete()
        val o = station.etat.value.observateur ?: return "la position de la station n'est pas connue (Réglages)"
        val gen = ++generation
        tient = ESSAI; actif = true; statut = "essai : navires simulés"; erreur = ""; tuner = "simulateur"
        thread(isDaemon = true, name = "satme-ais-essai") {
            val sim = SimulateurAis(o.latDeg, o.lonDeg, (horloge() % 1000).toInt())
            val buf = FloatArray(2 * DemodAis.DEBIT / 10)
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

    private fun rtlTcp(hote: String, port: Int, lancer: Boolean, appareil: String?): SourceIq {
        runCatching { return RtlTcp.connecte(hote, port, 1500) }
        val local = hote == "127.0.0.1" || hote == "localhost" || hote == "::1"
        if (!lancer || !local) throw IOException("rtl_tcp ne répond pas sur $hote:$port")
        val cmd = mutableListOf(OutilsPc.trouve("rtl_tcp") ?: "rtl_tcp", "-a", "127.0.0.1", "-p", port.toString(), "-s", DemodAis.DEBIT.toString(),
            "-f", DemodAis.CENTRE_HZ.toString())
        if (appareil != null) { cmd += "-d"; cmd += appareil }
        val journal = File(System.getProperty("java.io.tmpdir"), "satme-ais-rtl_tcp.log")
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
        var dernier = horloge(); var avant = 0L
        while (generation == gen) {
            val n = runCatching { r.read(iq, 200) }.getOrDefault(-1)
            if (n < 0) {
                if (generation == gen) { erreur = "rtl_tcp a coupé la liaison"; actif = false; statut = "arrêté"; tient = "" }
                break
            }
            if (n == 0) continue
            demod.traite8(iq, n / 2)
            octets += n
            val t = horloge()
            if (t - dernier >= 1000) { debitKo = ((octets - avant) * 1000 / (t - dernier) / 1024).toInt(); avant = octets; dernier = t }
        }
    }

    private fun recoit(bits: BooleanArray, canal: Char, niveau: Double) {
        parCanal[if (canal == 'A') 0 else 1]++
        MessageAis.lis(bits)?.let { table.recoit(it, canal, niveau) }
        if (nmea) envoieNmea(Aivdm.phrases(bits, canal, sequence++ % 10))
    }

    // ---------------------------------------------------------------- NMEA output
    private var socket: DatagramSocket? = null
    @Volatile var erreurNmea = ""; private set

    @Synchronized private fun envoieNmea(l: List<String>) {
        val s = socket ?: runCatching { DatagramSocket() }.getOrElse { erreurNmea = it.message ?: "UDP refusé"; return }.also { socket = it }
        for (p in l) {
            val b = (p + "\r\n").toByteArray(Charsets.US_ASCII)
            runCatching { s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), portNmea)); phrases++; erreurNmea = "" }
                .onFailure { erreurNmea = it.message ?: "envoi refusé" }
        }
    }

    @Synchronized private fun fermeNmea() { socket?.let { runCatching { it.close() } }; socket = null }

    // ---------------------------------------------------------------- the page

    fun json(): JSONObject {
        val obs = station.etat.value.observateur
        table.station = obs?.let { it.latDeg to it.lonDeg }
        val o = JSONObject().put("ok", true).put("actif", actif).put("statut", statut).put("erreur", erreur).put("tient", tient)
            .put("tuner", tuner).put("debitKo", debitKo)
            .put("source", source).put("appareil", appareil).put("port", portRtl).put("gain", gain).put("ppm", ppm)
            .put("nmea", nmea).put("nmeaPort", portNmea).put("phrases", phrases).put("erreurNmea", erreurNmea)
            .put("occupe", occupe() ?: "").put("adsbTient", adsbTientCle())
            .put("trames", demod.trames).put("canalA", parCanal[0]).put("canalB", parCanal[1]).put("messages", table.total)
            .put("navires", table.json(trace = false))
        if (obs != null) o.put("moi", JSONObject().put("lat", obs.latDeg).put("lon", obs.lonDeg))
        return o
    }

    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val o: JSONObject = if (methode != "POST") json() else runCatching {
            when (chemin) {
                "/api/ais/demarre" -> demarre(p("prendre") == "1")?.let { JSONObject().put("ok", false).put("erreur", it) } ?: JSONObject().put("ok", true)
                "/api/ais/arrete" -> { arrete(); JSONObject().put("ok", true) }
                "/api/ais/essai" -> essai()?.let { JSONObject().put("ok", false).put("erreur", it) } ?: JSONObject().put("ok", true)
                "/api/ais/vide" -> { table.vide(); JSONObject().put("ok", true) }
                "/api/ais/reglage" -> {
                    if (p("source").isNotEmpty()) source = p("source")
                    if (p("appareil").isNotEmpty()) appareil = p("appareil")
                    p("port").toIntOrNull()?.let { portRtl = it }
                    p("gain").toIntOrNull()?.let { gain = it }
                    p("ppm").toIntOrNull()?.let { ppm = it }
                    if (p("nmea").isNotEmpty()) nmea = p("nmea") == "1"
                    JSONObject().put("ok", true)
                }
                else -> JSONObject().put("ok", false).put("erreur", "inconnu")
            }
        }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PORT_NMEA = 10110
        const val CLE_SDR = "cle"
        const val RTL2 = "rtl2"
        const val ESSAI = "essai"
        val SOURCES = setOf(CLE_SDR, RTL2)
        const val CLE_SOURCE = "pc_ais_source"
        const val CLE_APPAREIL = "pc_ais_appareil"
        const val CLE_PORT = "pc_ais_port"
        const val CLE_GAIN = "pc_ais_gain"
        const val CLE_PPM = "pc_ais_ppm"
        const val CLE_NMEA = "pc_ais_nmea"
    }
}
