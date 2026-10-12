/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.RequeteHttp
import org.json.JSONArray
import org.json.JSONObject
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
class ServeurPc(private val station: StationPc, private val port: Int, private val reseau: Boolean = false) {

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
        if (r.methode == "POST" && chemin == "/api/import") {
            // The phone's backup, sent as the request's body (a few hundred kilobytes with the log).
            val corps = RequeteHttp.corps(c.getInputStream(), r, 20L * 1024 * 1024)
            val res = if (corps == null) JSONObject().put("ok", false).put("erreur", "fichier trop gros ou coupé")
                else station.importeTelephone(String(corps, Charsets.UTF_8)).fold(
                    { JSONObject().put("ok", true).put("n", it) },
                    { JSONObject().put("ok", false).put("erreur", it.message ?: "fichier illisible") })
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        if (r.methode == "POST") {
            val res = runCatching { action(chemin, q) }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
            envoie(sortie, "200 OK", "application/json; charset=utf-8", res.toString().toByteArray())
            return
        }
        when (chemin) {
            "/", "/index.html" -> envoie(sortie, "200 OK", "text/html; charset=utf-8", PagePc.HTML.toByteArray())
            "/api/etat" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.etat(station).toString().toByteArray())
            "/api/passages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.passages(station).toString().toByteArray())
            "/api/satellites" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.satellites(station).toString().toByteArray())
            "/api/ports" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.ports(station).toString().toByteArray())
            else -> envoie(sortie, "404 Not Found", "text/plain; charset=utf-8", "introuvable".toByteArray())
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
            "/api/convertisseurs" -> {
                // MHz on the page, Hz here; an empty field leaves it as it is.
                fun hz(k: String) = q[k]?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                station.poseConvertisseurs(hz("rxOl"), q["rxInv"]?.let { it == "1" }, hz("txOl"), q["txInv"]?.let { it == "1" })
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

    private fun parametres(qs: String): Map<String, String> =
        qs.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun envoie(s: OutputStream, statut: String, type: String, corps: ByteArray) {
        s.write(("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        s.write(corps)
        s.flush()
    }
}

/** The station as the page reads it. */
object JsonPc {
    fun etat(st: StationPc): JSONObject {
        val e = st.etat.value
        val r = st.stationRotor.etat.value
        val c = st.stationCat.etat.value
        val (dl, ul) = st.frequencesDuCiel()
        val o = JSONObject()
            .put("maintenant", e.maintenant)
            .put("locator", e.locator).put("indicatif", e.indicatif)
            .put("catalogue", e.catalogue).put("nbSatellites", e.satellites.size)
            .put("calcul", e.calculEnCours)
        e.suivi?.let { s -> o.put("suivi", JSONObject().put("nom", s.name).put("catnum", s.catalogNumber)) }
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

    private fun passage(p: fr.f4ioz.satcombo.data.SatPass) = JSONObject()
        .put("nom", p.satName).put("catnum", p.catalogNumber).put("aos", p.aosEpochMs).put("los", p.losEpochMs)
        .put("elMax", p.maxElevationDeg).put("azAos", p.aosAzimuthDeg).put("azLos", p.losAzimuthDeg)

    fun passages(st: StationPc): JSONObject {
        val e = st.etat.value
        return JSONObject().put("calcul", e.calculEnCours)
            .put("liste", JSONArray(e.passages.take(300).map { passage(it) }))
    }

    fun satellites(st: StationPc) = JSONArray(st.etat.value.satellites.map {
        JSONObject().put("nom", it.name).put("catnum", it.catalogNumber)
    })

    fun ports(st: StationPc) = JSONArray(st.ports.liste().map {
        JSONObject().put("index", it.index).put("libelle", it.libelle).put("vendor", it.vendorId)
    })
}
