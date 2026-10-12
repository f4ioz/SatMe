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
class ServeurPc(private val station: StationPc, private val port: Int, private val reseau: Boolean = false,
                private val carnet: CarnetPc? = null, private val son: SonStation? = null,
                private val journal: JournalWebPc? = null) {

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
            "/api/carres" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carres(carnet, station).toString().toByteArray())
            "/api/carnet" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.carnet(carnet).toString().toByteArray())
            "/api/reglages" -> envoie(sortie, "200 OK", "application/json; charset=utf-8", JsonPc.reglages(station).toString().toByteArray())
            "/api/qrz" -> {
                // Read only (a directory lookup), so a GET; nothing changes in the station.
                val f = carnet?.let { kotlinx.coroutines.runBlocking { it.ficheQrz(q["c"].orEmpty()) } }
                val o = if (f == null) JSONObject().put("ok", false).put("erreur", "identifiants QRZ absents ou réseau")
                    else if (f.erreur.isNotBlank()) JSONObject().put("ok", false).put("erreur", f.erreur)
                    else JSONObject().put("ok", !f.vide).put("nom", f.nom).put("prenom", f.prenom).put("qth", f.qth)
                        .put("pays", f.pays).put("carre", f.carre)
                envoie(sortie, "200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            }
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
                    else -> null
                }
                if (f != null) envoieFichier(sortie, r, f)
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
            "/api/agenda/retire" -> q["id"]?.toLongOrNull()?.let { station.retireAgenda(it) }
            "/api/agenda/active" -> q["id"]?.toLongOrNull()?.let { station.activeAgenda(it) }
            "/api/agenda/suivi" -> station.agendaSuivi(q["on"] == "1")
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
            "/api/carnet/supprime" -> q["t"]?.toLongOrNull()?.let { carnet?.supprime(it) }
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
        return JSONObject().put("carres", c?.carres() ?: 0).put("nonDeposes", l.count { it.envoyeMs == 0L })
            .put("contacts", JSONArray(l.take(500).map { e ->
                JSONObject().put("t", e.timeMs).put("c", e.callsign).put("l", e.theirLocator).put("sat", e.satName)
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
    fun reglages(st: StationPc): JSONObject {
        val s = st.settings
        return JSONObject().put("c", s.callsign).put("l", s.manualLocator).put("qrzUser", s.qrzUser)
            .put("qrzMdp", s.qrzMdp.isNotEmpty()).put("carnetUrl", s.carnetUrl).put("carnetCle", s.carnetCle.isNotEmpty())
            .put("carnetProfil", s.carnetProfil).put("minEl", s.minElevDeg)
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
