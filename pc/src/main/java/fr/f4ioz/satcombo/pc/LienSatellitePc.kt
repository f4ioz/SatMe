/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.CalendrierPassages
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.JournalDoppler
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The satellite link: a local WebSocket that pushes what the station does,
 * for other programs (a logger, a dashboard, a script). Off by default; on
 * this PC only unless the page itself is open to the network ([reseau]).
 *
 * Events (JSON text frames, see docs/station-pc.md):
 * - `satellite` every second while one is followed,
 * - `passAlert` (`aosSoon` 5 min before, `aos`, `los`) for the followed
 *   satellite and the ★ favourites,
 * - `qsoLogged` when a contact goes into the log.
 * What a client sends is ignored, but ping and close.
 */
class LienSatellitePc(private val station: StationPc, private val reseau: Boolean,
                      private val horloge: () -> Long = System::currentTimeMillis) {

    private class Client(val s: Socket, val sortie: OutputStream) {
        @Synchronized fun envoie(b: ByteArray): Boolean = runCatching { sortie.write(b); sortie.flush() }.isSuccess
        fun ferme() { runCatching { s.close() } }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var serveur: ServerSocket? = null
    @Volatile private var boucle: Thread? = null

    /** On and its port, kept with the PC's settings. */
    var actif: Boolean
        get() = station.stockage.getBoolean(CLE_ACTIF, false)
        private set(v) { station.stockage.edit().putBoolean(CLE_ACTIF, v).apply() }
    var port: Int
        get() = station.stockage.getInt(CLE_PORT, PORT_DEFAUT)
        private set(v) { station.stockage.edit().putInt(CLE_PORT, v).apply() }

    /** Why it is not listening, when asked to ("" when fine). */
    @Volatile var erreur: String = ""; private set
    val ecoute: Boolean get() = serveur?.isClosed == false
    val nbClients: Int get() = clients.size
    val portReel: Int get() = serveur?.localPort ?: port

    /** Set from the page: on or off, and the port (1024–65535). */
    fun regle(on: Boolean?, nouveauPort: Int?) {
        if (nouveauPort != null && nouveauPort !in 1024..65535) { erreur = "port entre 1024 et 65535"; return }
        val autrePort = nouveauPort != null && nouveauPort != port
        nouveauPort?.let { port = it }
        on?.let { actif = it }
        when {
            !actif -> { arrete(); erreur = "" }
            autrePort || !ecoute -> { arrete(); demarre() }
        }
    }

    /** Started at launch when chosen. */
    fun demarreSiActif() { if (actif) demarre() }

    @Synchronized fun demarre() {
        if (ecoute) return
        erreur = ""
        val s = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(if (reseau) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
        }.getOrElse { erreur = "port $port : " + (it.message ?: "occupé"); return }
        serveur = s
        thread(isDaemon = true, name = "satme-lien") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "satme-lien-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
        if (boucle == null) boucle = thread(isDaemon = true, name = "satme-lien-tic") {
            while (true) {
                runCatching { tic() }
                Thread.sleep(1000)
            }
        }
    }

    @Synchronized fun arrete() {
        runCatching { serveur?.close() }
        serveur = null
        clients.forEach { it.envoie(WebSocketPc.fermeture(1001)); it.ferme() }
        clients.clear()
    }

    private fun sert(s: Socket) {
        s.soTimeout = 0
        val entree = s.getInputStream()
        val sortie = s.getOutputStream()
        val d = WebSocketPc.litDemande(entree) ?: return
        if (!d.valide) {
            sortie.write("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nSec-WebSocket-Version: 13\r\nConnection: close\r\n\r\nWebSocket attendu\n".toByteArray())
            sortie.flush(); return
        }
        sortie.write(WebSocketPc.reponse(d.cle!!)); sortie.flush()
        val c = Client(s, sortie)
        clients += c
        try {
            val e = station.etat.value
            c.envoie(WebSocketPc.texte(JSONObject().put("type", "hello").put("app", "SatMe-PC").put("version", versionPc())
                .put("callsign", e.indicatif).put("locator", e.locator).toString()))
            while (true) {
                val t = WebSocketPc.litTrame(entree) ?: break
                when (t.opcode) {
                    WebSocketPc.PING -> c.envoie(WebSocketPc.trame(WebSocketPc.PONG, t.donnees))
                    WebSocketPc.FERMETURE -> {
                        // Echo the status code, as the RFC asks, then end.
                        c.envoie(WebSocketPc.trame(WebSocketPc.FERMETURE, t.donnees.copyOf(minOf(2, t.donnees.size))))
                        break
                    }
                    else -> {}
                }
            }
        } finally { clients -= c; c.ferme() }
    }

    /** To every client; those gone are dropped. */
    fun diffuse(json: JSONObject) {
        if (clients.isEmpty()) return
        val b = WebSocketPc.texte(json.toString())
        clients.forEach { c -> if (!c.envoie(b)) { clients -= c; c.ferme() } }
    }

    // ------------------------------------------------------------- the events

    /** The alerts already sent (pass UID + kind), forgotten once old. */
    private val envoyees = HashMap<String, Long>()

    private fun tic() {
        if (!ecoute || clients.isEmpty()) return
        val maintenant = horloge()
        etatSatellite(maintenant)?.let { diffuse(it) }
        alertes(maintenant).forEach { diffuse(it) }
    }

    /** The followed satellite now: where, how far, its frequencies with Doppler and the Doppler alone. */
    fun etatSatellite(maintenant: Long): JSONObject? {
        val e = station.etat.value
        val sat = e.suivi ?: return null
        val p = e.position ?: return null
        val o = JSONObject().put("type", "satellite").put("time", maintenant).put("name", sat.name).put("catnum", sat.catalogNumber)
            .put("az", r1(p.azimuthDeg)).put("el", r1(p.elevationDeg)).put("rangeKm", r1(p.rangeKm))
            .put("rangeRateKmS", Math.round(p.rangeRateKmS * 1000) / 1000.0)
        val tx = station.emetteurChoisi()
        val (dl, ul) = station.frequencesDuCiel()
        val dlRepos = e.repos ?: tx?.downlinkLowHz?.let { lo -> (lo + (tx.downlinkHighHz ?: lo)) / 2 }
        val ulRepos = JournalDoppler.reposMontee(tx, dlRepos)
        o.put("transmitter", tx?.description ?: JSONObject.NULL)
        o.put("downlinkHz", dl ?: JSONObject.NULL).put("uplinkHz", ul ?: JSONObject.NULL)
        o.put("dopplerDownHz", dlRepos?.let { Doppler.downlink(it, p.rangeRateKmS) - it } ?: JSONObject.NULL)
        o.put("dopplerUpHz", ulRepos?.let { Doppler.uplink(it, p.rangeRateKmS) - it } ?: JSONObject.NULL)
        return o
    }

    /** The passes watched: the followed satellite's and the ★ favourites'. */
    fun surveilles(): List<SatPass> {
        val e = station.etat.value
        val suivi = e.suivi?.catalogNumber
        return (e.passagesSuivi + e.passages.filter { it.catalogNumber in e.favoris || it.catalogNumber == suivi })
            .distinctBy { CalendrierPassages.uid(it) }
    }

    /** `aosSoon` (within 5 min), `aos` and `los` (within a minute), each once per pass. */
    fun alertes(maintenant: Long, passes: List<SatPass> = surveilles()): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (p in passes) {
            val genre = when {
                maintenant in (p.aosEpochMs - AVANT_MS) until p.aosEpochMs -> "aosSoon"
                maintenant in p.aosEpochMs until minOf(p.aosEpochMs + 60_000L, p.losEpochMs) -> "aos"
                maintenant in p.losEpochMs until p.losEpochMs + 60_000L -> "los"
                else -> null
            } ?: continue
            val cle = CalendrierPassages.uid(p) + ":" + genre
            if (envoyees.containsKey(cle)) continue
            envoyees[cle] = maintenant
            out += alerte(p, genre, maintenant)
        }
        envoyees.entries.removeIf { maintenant - it.value > 3 * 3_600_000L }
        return out
    }

    private fun alerte(p: SatPass, genre: String, maintenant: Long) = JSONObject().put("type", "passAlert").put("kind", genre)
        .put("time", maintenant).put("name", p.satName).put("catnum", p.catalogNumber)
        .put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("maxEl", r1(p.maxElevationDeg))
        .put("aosAz", r1(p.aosAzimuthDeg)).put("losAz", r1(p.losAzimuthDeg))
        .also { if (genre == "aosSoon") it.put("inS", (p.aosEpochMs - maintenant) / 1000) }

    /** A contact gone into the log. */
    fun qso(c: LogEntry) {
        if (!ecoute) return
        diffuse(JSONObject().put("type", "qsoLogged").put("time", c.timeMs).put("call", c.callsign).put("grid", c.theirLocator)
            .put("satellite", c.satName).put("catnum", c.catnum).put("mode", c.mode).put("rstSent", c.rstSent).put("rstRcvd", c.rstRcvd)
            .put("downlinkMHz", c.downlinkMhz).put("uplinkMHz", c.uplinkMhz).put("az", r1(c.azimuthDeg)).put("el", r1(c.elevationDeg))
            .put("myGrid", c.myLocator))
    }

    private fun r1(x: Double) = Math.round(x * 10) / 10.0

    companion object {
        const val PORT_DEFAUT = 8095
        const val CLE_ACTIF = "pc_lien_ws"
        const val CLE_PORT = "pc_lien_ws_port"
        const val AVANT_MS = 5 * 60_000L
    }
}
