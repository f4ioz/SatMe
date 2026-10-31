/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.ModeleHf
import fr.f4ioz.satcombo.demo.RequeteHttp
import fr.f4ioz.satcombo.pc.SonDistantPc
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * TellusMe's page and its API, on `http://localhost:8092/` by default —
 * hand-written like SatMe-PC's ([fr.f4ioz.satcombo.pc.ServeurPc]): **on this
 * PC only** unless asked ([reseau]); what changes the station is a POST,
 * from this page's own origin, never a GET.
 */
class ServeurTellus(
    private val station: StationTellus,
    private val carnet: CarnetTellus,
    private val propagation: Propagation,
    private val cluster: ClusterDx,
    private val port: Int,
    private val reseau: Boolean = false,
    private val version: String = "dev",
) {
    private var socket: ServerSocket? = null
    val portReel: Int get() = socket?.localPort ?: port

    fun demarre() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(if (reseau) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        socket = s
        thread(isDaemon = true, name = "tellus-web") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "tellus-web-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
    }

    fun arrete() { runCatching { socket?.close() } }

    private fun sert(c: Socket) {
        c.soTimeout = 15_000
        val e = c.getInputStream()
        val sortie = c.getOutputStream()
        val r = RequeteHttp.lit(e) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = HashMap(parametres(r.chemin.substringAfter('?', "")))
        if (r.methode == "POST") {
            val corps = RequeteHttp.corps(e, r, 64 * 1024) ?: return envoie(sortie, "413 Payload Too Large", TEXTE, "trop gros".toByteArray())
            if (r.entetes["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
                q.putAll(parametres(corps.toString(Charsets.UTF_8)))
            if (!SonDistantPc.origineAdmise(r.entetes["origin"], r.entetes["host"]))
                return json(sortie, JSONObject().put("ok", false).put("erreur", "origine refusée"), "403 Forbidden")
        }
        when (r.methode to chemin) {
            "GET" to "/", "GET" to "/index.html" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PageTellus.html(version).toByteArray())
            "GET" to "/api/etat" -> json(sortie, station.json())
            "GET" to "/api/plan" -> json(sortie, PlanHf.json(station.modele?.bandes ?: PlanHf.BANDES.map { it.nom }))
            "GET" to "/api/reglages" -> json(sortie, station.reglages.json().put("ports", station.ports())
                .put("vitesses", org.json.JSONArray(ModeleHf.IC7000.vitesses)))
            "POST" to "/api/reglages" -> json(sortie, regle(q))
            "POST" to "/api/poste" -> { station.choisis(q["id"].orEmpty()); json(sortie, station.json()) }
            "POST" to "/api/accorde" -> {
                val ok = q["hz"]?.toLongOrNull()?.let { station.accorde(it) } ?: false
                json(sortie, station.json().put("ok", ok))
            }
            "POST" to "/api/mode" -> json(sortie, station.json().put("ok", station.regleMode(q["m"].orEmpty())))
            "POST" to "/api/brick/cherche" -> {
                val radio = station.chercheBrick()
                json(sortie, JSONObject().put("ok", radio.enLigne).put("ip", radio.ip).put("carte", radio.carte).put("firmware", radio.firmware)
                    .put("occupee", radio.occupee).put("erreur", radio.erreur))
            }
            "POST" to "/api/brick/audio" -> {
                station.brick.regleAudio(on = q["on"]?.let { it == "1" }, volume = q["vol"]?.toFloatOrNull())
                json(sortie, JSONObject().put("ok", true).put("audio", station.brick.audio))
            }
            "POST" to "/api/brick/debit" -> {
                val d = q["d"]?.toIntOrNull()
                if (d != null && d in listOf(48_000, 96_000, 192_000)) { station.brick.regle(nouveauDebit = d); station.reglages.brickDebit = d }
                json(sortie, JSONObject().put("ok", true).put("debit", station.brick.debit))
            }
            "GET" to "/api/spectre" -> {
                if (station.modele != ModeleHf.BRICK2) return json(sortie, JSONObject().put("ok", false).put("erreur", "pas de Brick2"))
                val ok = station.brick.assure()
                val s = station.brick.instantane(q["n"]?.toIntOrNull()?.coerceIn(64, 2048) ?: 512)
                json(sortie, (s ?: JSONObject()).put("ok", ok && s != null).put("erreur", station.brick.erreur ?: ""))
            }
            "GET" to "/api/audio" -> {
                station.brick.touche()
                envoie(sortie, "200 OK", "application/octet-stream", station.brick.audioRecu())
            }
            "GET" to "/api/propagation" -> json(sortie, propagation.json())
            "GET" to "/api/cluster" -> {
                val b = PlanHf.BANDES.firstOrNull { it.nom == q["bande"] }
                json(sortie, cluster.json(b?.basHz, b?.hautHz).put("actif", station.reglages.clusterActif))
            }
            "POST" to "/api/cluster" -> {
                val on = q["on"] == "1"
                station.reglages.clusterActif = on
                if (on) cluster.demarre(station.reglages.clusterHote, station.reglages.clusterPort, station.reglages.indicatif) else cluster.arrete()
                json(sortie, cluster.json().put("actif", on))
            }
            "GET" to "/api/carnet" -> json(sortie, carnet.json())
            "POST" to "/api/carnet" -> {
                val e = carnet.ajoute(q["indicatif"].orEmpty(), q["hz"]?.toLongOrNull() ?: station.frequence, q["mode"] ?: station.mode,
                    q["rs"].orEmpty(), q["rr"].orEmpty(), q["loc"].orEmpty(), q["note"].orEmpty(), station.reglages.locator)
                json(sortie, carnet.json().put("ok", e != null).put("erreur", if (e == null) "indicatif et fréquence nécessaires" else ""))
            }
            "POST" to "/api/carnet/supprime" -> { q["t"]?.toLongOrNull()?.let { carnet.supprime(it) }; json(sortie, carnet.json().put("ok", true)) }
            "GET" to "/api/carnet/controle" -> json(sortie, carnet.controle())
            "GET" to "/api/carnet.adi" -> envoie(sortie, "200 OK", "text/plain; charset=utf-8", carnet.adif(station.reglages.indicatif).toByteArray(),
                "Content-Disposition: attachment; filename=\"tellusme.adi\"\r\n")
            else -> envoie(sortie, "404 Not Found", TEXTE, "introuvable".toByteArray())
        }
    }

    private fun regle(q: Map<String, String>): JSONObject {
        val g = station.reglages
        q["indicatif"]?.let { g.indicatif = it }
        q["locator"]?.let { g.locator = it }
        q["portCat"]?.let { g.portCat = it }
        q["vitesseCat"]?.toIntOrNull()?.takeIf { it in ModeleHf.IC7000.vitesses }?.let { g.vitesseCat = it }
        q["adresseCiv"]?.toIntOrNull(16)?.takeIf { it in 1..0xDF }?.let { g.adresseCiv = it }
        q["brickHote"]?.let { g.brickHote = it }
        q["brickPort"]?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { g.brickPort = it }
        q["clusterHote"]?.let { g.clusterHote = it }
        q["clusterPort"]?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { g.clusterPort = it }
        // The rig is reopened with what changed (port, speed, address, the Brick2's address).
        if (listOf("portCat", "vitesseCat", "adresseCiv", "brickHote", "brickPort").any { it in q }) station.choisis(g.poste)
        return g.json().put("ok", true)
    }

    private fun parametres(qs: String): Map<String, String> =
        qs.split('&').filter { '=' in it }.associate {
            runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                .getOrDefault("" to "")
        }

    private fun json(s: OutputStream, o: JSONObject, statut: String = "200 OK") =
        envoie(s, statut, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun envoie(s: OutputStream, statut: String, type: String, corps: ByteArray, entetes: String = "") {
        s.write(("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\n" + entetes +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        s.write(corps)
        s.flush()
    }

    companion object { private const val TEXTE = "text/plain; charset=utf-8" }
}
