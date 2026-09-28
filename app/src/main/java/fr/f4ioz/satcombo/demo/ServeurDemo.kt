/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Demonstration mode: a small server letting the audience follow the pass from
 * their own phones.
 *
 * The operator's phone is the access point: nothing leaves the local network,
 * no internet, no relay server.
 *
 * Hand-written rather than built on a server library: a handful of routes,
 * where a library would bring thousands of lines for cases that never arise.
 *
 * Server-sent events rather than WebSockets: the flow is one-way, and the
 * browser reconnects on its own after a drop — which happens as soon as a
 * viewer walks away from the access point.
 */
object ServeurDemo {

    data class Etat(
        val actif: Boolean = false,
        val port: Int = 8080,
        val adresse: String = "",
        val jeton: String = "",
        /** Control desk token: it never leaves the phone. */
        val jetonCommande: String = "",
        /** The six-digit code the operator types on the PC. */
        val codeCommande: String = "",
        val commandeActive: Boolean = false,
        val spectateurs: Int = 0,
        val panne: String = "",
        /** The hotspot to join, for the first QR code. */
        val ssid: String = "",
        val motDePasse: String = ""
    ) {
        // No Wi-Fi QR payload here: `qrWifi()` builds it. Two paths to one
        // string end up disagreeing — that once drew two QR codes on screen.

        /** The control desk address, for the operator alone. */
        val urlCommande: String get() =
            if (adresse.isBlank() || !commandeActive) ""
            else "http://$adresse:$port/c/$jetonCommande/"

        /** The address to show the audience, token included. */
        val url: String get() =
            if (adresse.isBlank()) "" else "http://$adresse:$port/d/$jeton/"
    }

    /**
     * The name announced on the network: the operator's callsign, set by the
     * ViewModel. Without it every station in a room would be called "SatMe".
     */
    @Volatile var nomStation: String = "SatMe"

    /**
     * The app version, announced to API clients. Set by the ViewModel:
     * `BuildConfig` is not generated in this project.
     */
    @Volatile var versionApp: String = "?"

    private val _etat = MutableStateFlow(Etat())
    val etat = _etat.asStateFlow()

    // Hotspot credentials live in `Etat`, not in `UiState`: only the server
    // needs them, and `UiState` is already close to the JVM register limit.


    /**
     * The payload of the QR code that joins the network, in the standard
     * format both Android and iOS cameras read. `\`, `;`, `,`, `:` and `"`
     * must be escaped, or a password containing one cuts the string in half
     * and the code is unreadable — or worse, readable and wrong.
     */
    fun qrWifi(): String {
        val ssid = _etat.value.ssid
        val mdp = _etat.value.motDePasse
        if (ssid.isBlank()) return ""
        val type = if (mdp.isBlank()) "nopass" else "WPA"
        return "WIFI:T:" + type + ";S:" + echappe(ssid) + ";P:" + echappe(mdp) + ";;"
    }

    /**
     * Escapes the five characters the format reserves. Character by character
     * rather than with a regex: backslashes escaping backslashes are easy to
     * miscount, and a wrong escape fails silently in front of the audience.
     */
    private fun echappe(t: String): String {
        val b = StringBuilder(t.length + 8)
        for (c in t) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') b.append('\\')
            b.append(c)
        }
        return b.toString()
    }

    /** Current telemetry as JSON. Replaced, never accumulated. */
    @Volatile private var telemetrie: String = "{}"

    private var serveur: ServerSocket? = null
    private var fil: Thread? = null
    private val clients = CopyOnWriteArrayList<Client>()

    /**
     * A connected viewer: either the state stream (`son` false) or the raw
     * audio stream (`son` true). Each page opens one of each.
     */
    private class Client(val sortie: OutputStream, val son: Boolean) {
        @Volatile var vivant = true
    }

    // -------------------------------------------------------------- serving

    fun demarre(port: Int = 8080) {
        if (_etat.value.actif) return
        val jeton = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val s = try {
            ServerSocket(port)
        } catch (e: Exception) {
            // Another app may hold the port: say so rather than leave a
            // screen that never fills.
            _etat.value = _etat.value.copy(panne = "port_occupe", actif = false)
            return
        }
        serveur = s
        _etat.value = _etat.value.copy(actif = true, port = port,
            adresse = adresseLocale(), jeton = jeton, panne = "")
        // The station announces itself: listening phones will see it appear
        // without anyone typing an address.
        AnnonceReseau.annonce({ nomStation }, { _etat.value.url })
        fil = thread(name = "ServeurDemo", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { sert(c, jeton) }
            }
        }
    }


    fun arrete() {
        AnnonceReseau.tais()
        clients.forEach { it.vivant = false }
        clients.clear()
        runCatching { serveur?.close() }
        serveur = null
        fil = null
        _etat.value = _etat.value.copy(actif = false, adresse = "", jeton = "",
            spectateurs = 0, panne = "")
    }

    /**
     * The phone's address on the network it carries.
     *
     * As a hotspot the interface is `ap0`, `swlan0` or else depending on the
     * vendor, so none is named: take the first non-loopback IPv4 address,
     * preferring the private ranges Android gives its hotspots.
     */
    fun adresseLocale(): String {
        var repli = ""
        runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()) {
                if (!i.isUp || i.isLoopback) continue
                for (a in i.inetAddresses) {
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    val h = a.hostAddress ?: continue
                    if (h.startsWith("192.168.") || h.startsWith("172.")) return h
                    if (repli.isEmpty()) repli = h
                }
            }
        }
        return repli
    }

    /**
     * Remembers the hotspot to announce. Since Android 10 an app cannot read
     * its own hotspot password, so it has to be typed once.
     */
    fun configureWifi(ssid: String, motDePasse: String) {
        _etat.value = _etat.value.copy(ssid = ssid, motDePasse = motDePasse)
    }

    /**
     * Today's contacts, most recent first. Capped at twelve: it is the current
     * pass we came to show, not the history.
     */
    private val _contacts = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** The latest SSTV picture, already encoded. Served as is. */
    @Volatile private var image: ByteArray? = null
    @Volatile private var imageVersion: Int = 0

    /**
     * Adds a contact to the list shown to the audience. The grid square is
     * there because it means more to an audience than a callsign.
     */
    fun ajouteContact(indicatif: String, locator: String, satellite: String) {
        val h = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val propre = { v: String -> v.replace("\\", " ").replace("\"", " ").trim() }
        _contacts.addFirst(
            "{\"h\":\"" + h + "\",\"c\":\"" + propre(indicatif) +
                "\",\"l\":\"" + propre(locator) + "\",\"s\":\"" +
                propre(satellite) + "\"}")
        while (_contacts.size > 12) _contacts.pollLast()
    }

    fun contactsJson(): String = _contacts.joinToString(",")

    fun videContacts() { _contacts.clear() }

    /** Sets the SSTV picture to show. Nothing is encoded here: it arrives ready. */
    fun poseImage(jpeg: ByteArray?) {
        if (jpeg == null) return
        image = jpeg
        imageVersion++
    }

    fun versionImage(): Int = imageVersion

    /** Publishes telemetry. Called often: nothing here blocks. */
    fun publie(json: String) { telemetrie = json }

    // --------------------------------------------------------------- routes

    // --------------------------------------------------------- control desk

    /**
     * Sessions opened from a PC: a random key handed out once the code is
     * accepted. The code travels only once; afterwards the key does.
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Three hours: an afternoon of operating, no more. */
    private const val SESSION_MS = 3 * 3600_000L

    private fun sessionValide(cle: String): Boolean {
        val t = sessions[cle] ?: return false
        if (System.currentTimeMillis() - t > SESSION_MS) { sessions.remove(cle); return false }
        return true
    }

    private fun parametre(route: String, nom: String): String {
        val q = route.substringAfter('?', "")
        for (p in q.split('&')) {
            val i = p.indexOf('=')
            if (i > 0 && p.substring(0, i) == nom) {
                return java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
            }
        }
        return ""
    }

    private fun jsonCourt(sortie: OutputStream, corps: String) =
        envoie(sortie, "200 OK", "application/json; charset=utf-8",
            corps.toByteArray(Charsets.UTF_8))

    /**
     * The version of the contract exposed to clients.
     *
     * **It only moves when an existing client breaks.** Adding a route or a
     * field does not change it; removing a field or changing its meaning does
     * — and then serve both versions for a while.
     */
    const val API = 1

    private fun servCommande(sortie: OutputStream, route: String) {
        val chemin = route.substringBefore('?')

        // The page itself needs no code, the form does: serving a blank page
        // to a stranger costs nothing.
        if (chemin == "" || chemin == "/") {
            envoie(sortie, "200 OK", "text/html; charset=utf-8",
                PageCommande.HTML.toByteArray(Charsets.UTF_8))
            return
        }

        // No authentication: a client must know who it talks to before
        // presenting a code, and this reveals nothing the address did not.
        if (chemin == "/api") {
            jsonCourt(sortie, "{\"api\":$API,\"satme\":\"$versionApp\"}")
            return
        }

        if (chemin == "/entrer") {
            val donne = parametre(route, "code")
            if (donne.isNotBlank() && donne == _etat.value.codeCommande) {
                val cle = (1..24).map {
                    "abcdefghijklmnopqrstuvwxyz0123456789".random()
                }.joinToString("")
                sessions[cle] = System.currentTimeMillis()
                jsonCourt(sortie, "{\"ok\":true,\"cle\":\"$cle\",\"api\":$API}")
            } else {
                // One second of delay on a wrong code: enough to make brute
                // force pointless without annoying the operator.
                Thread.sleep(1000)
                jsonCourt(sortie, "{\"ok\":false}")
            }
            return
        }

        val cle = parametre(route, "cle")
        if (!sessionValide(cle)) {
            envoie(sortie, "403 Forbidden", "application/json",
                "{\"ok\":false,\"raison\":\"session\"}".toByteArray())
            return
        }
        if (!PontCommande.pret) {
            jsonCourt(sortie, "{\"ok\":false,\"raison\":\"pasPret\"}")
            return
        }

        when (chemin) {
            "/etat" -> jsonCourt(sortie, telemetrie)

            "/sats" -> {
                val l = PontCommande.satellites?.invoke().orEmpty()
                jsonCourt(sortie, l.joinToString(",", "[", "]") {
                    "\"" + it.replace("\"", "") + "\""
                })
            }

            "/propose" -> {
                val l = PontCommande.propose?.invoke(parametre(route, "q")).orEmpty()
                jsonCourt(sortie, l.joinToString(",", "[", "]") { p ->
                    "{\"c\":\"${p.indicatif}\",\"l\":\"${p.locator}\"," +
                        "\"n\":\"${p.nom.replace("\"", "")}\",\"q\":${p.contacts}}"
                })
            }

            "/qrz" -> {
                val f = PontCommande.chercheQrz?.invoke(parametre(route, "call"))
                if (f == null) jsonCourt(sortie, "{\"ok\":false}")
                else {
                    fun net(v: String) = v.replace("\\", " ").replace("\"", " ")
                    jsonCourt(sortie,
                        "{\"ok\":" + f.erreur.isBlank() +
                            ",\"c\":\"" + net(f.indicatif) + "\"" +
                            ",\"l\":\"" + net(f.carre) + "\"" +
                            ",\"n\":\"" + net(f.nom) + "\"" +
                            ",\"f\":\"" + net(f.prenom) + "\"" +
                            ",\"v\":\"" + net(f.qth) + "\"" +
                            ",\"p\":\"" + net(f.pays) + "\"" +
                            ",\"e\":\"" + net(f.erreur) + "\"}")
                }
            }

            "/qso" -> {
                val ok = PontCommande.ajouteQso?.invoke(
                    parametre(route, "call"), parametre(route, "loc"),
                    parametre(route, "rse").ifBlank { "59" },
                    parametre(route, "rsr").ifBlank { "59" }) ?: false
                jsonCourt(sortie, "{\"ok\":$ok}")
            }

            "/rec" -> {
                PontCommande.enregistre?.invoke(parametre(route, "on") == "1")
                jsonCourt(sortie, "{\"ok\":true}")
            }

            "/sat" -> {
                val ok = PontCommande.choisitSatellite?.invoke(parametre(route, "nom")) ?: false
                jsonCourt(sortie, "{\"ok\":$ok}")
            }

            "/journal" -> {
                val l = PontCommande.journal?.invoke().orEmpty()
                jsonCourt(sortie, l.joinToString(",", "[", "]") { q ->
                    "{\"t\":${q.timeMs},\"h\":\"${js(q.heure)}\",\"c\":\"${js(q.indicatif)}\"," +
                        "\"l\":\"${js(q.locator)}\",\"s\":\"${js(q.satellite)}\"," +
                        "\"rse\":\"${js(q.rstEnvoye)}\",\"rsr\":\"${js(q.rstRecu)}\"," +
                        "\"e\":\"${js(q.envoi)}\",\"r\":${q.resteS},\"x\":\"${js(q.refus)}\"}"
                })
            }

            "/modifie" -> {
                val ok = PontCommande.modifie?.invoke(
                    parametre(route, "t").toLongOrNull() ?: 0L,
                    parametre(route, "call"), parametre(route, "loc"),
                    parametre(route, "rse").ifBlank { "59" },
                    parametre(route, "rsr").ifBlank { "59" }) ?: false
                jsonCourt(sortie, "{\"ok\":$ok}")
            }

            "/supprime" -> {
                val ok = PontCommande.supprime?.invoke(parametre(route, "t").toLongOrNull() ?: 0L) ?: false
                jsonCourt(sortie, "{\"ok\":$ok}")
            }

            "/pause" -> {
                val ok = PontCommande.retiens?.invoke(
                    parametre(route, "t").toLongOrNull() ?: 0L, parametre(route, "on") == "1") ?: false
                jsonCourt(sortie, "{\"ok\":$ok}")
            }

            "/infos" -> {
                val i = PontCommande.infos?.invoke(parametre(route, "call"), parametre(route, "loc"))
                jsonCourt(sortie, if (i == null) "{}"
                    else "{\"carre\":\"${js(i.carre)}\",\"doublon\":\"${js(i.doublon)}\"}")
            }

            else -> envoie(sortie, "404 Not Found", "text/plain", "non".toByteArray())
        }
    }

    /** A string made safe inside JSON quotes. */
    private fun js(t: String): String = buildString {
        for (ch in t) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    /** Opens or closes the control desk, independently of the broadcast. */
    fun commande(actif: Boolean) {
        if (!actif) {
            sessions.clear()
            _etat.value = _etat.value.copy(commandeActive = false,
                jetonCommande = "", codeCommande = "")
            return
        }
        val jeton = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val code = (1..6).map { "0123456789".random() }.joinToString("")
        _etat.value = _etat.value.copy(commandeActive = true,
            jetonCommande = jeton, codeCommande = code)
    }

    private fun sert(socket: Socket, jeton: String) {
        socket.soTimeout = 0
        val entree = BufferedReader(InputStreamReader(socket.getInputStream()))
        val sortie = socket.getOutputStream()
        val ligne = try { entree.readLine() } catch (e: Exception) { null } ?: return
        val chemin = ligne.split(' ').getOrNull(1) ?: "/"

        // **Two doors, two tokens.**
        //
        // `/d/…` is the public door: read-only, its token shown as a QR code.
        // `/c/…` is the control desk: it writes to the log and drives the
        // recorder, so its token never leaves the phone and a six-digit code
        // is added. Control through the public token would hand the keys to
        // everyone who scanned the QR.
        val e = _etat.value
        if (e.commandeActive && e.jetonCommande.isNotBlank() &&
            chemin.startsWith("/c/${e.jetonCommande}")) {
            servCommande(sortie, chemin.removePrefix("/c/${e.jetonCommande}"))
            runCatching { socket.close() }
            return
        }

        // The token is not real security, just enough to keep a curious
        // neighbour off a hotspot open for the length of a demonstration.
        if (!chemin.startsWith("/d/$jeton")) {
            envoie(sortie, "404 Not Found", "text/plain", "non".toByteArray())
            runCatching { socket.close() }
            return
        }
        val route = chemin.removePrefix("/d/$jeton")

        when {
            route == "" || route == "/" ->
                envoie(sortie, "200 OK", "text/html; charset=utf-8",
                    PageDemo.HTML.toByteArray(Charsets.UTF_8))

            route.startsWith("/etat") -> {
                // Never closes; a disconnection shows up as a failed write.
                entete(sortie, "text/event-stream")
                val c = Client(sortie, false)
                clients.add(c); majSpectateurs()
                try {
                    while (c.vivant) {
                        sortie.write("data: $telemetrie\n\n".toByteArray())
                        sortie.flush()
                        Thread.sleep(500)
                    }
                } catch (e: Exception) {
                    // A viewer leaving is not a failure.
                } finally {
                    clients.remove(c); majSpectateurs(); runCatching { socket.close() }
                }
            }

            route.startsWith("/image") -> {
                val img = image
                if (img == null) envoie(sortie, "404 Not Found", "text/plain",
                    "pas d'image".toByteArray())
                else envoie(sortie, "200 OK", "image/jpeg", img)
                runCatching { socket.close() }
            }

            route.startsWith("/son") -> {
                entete(sortie, "application/octet-stream")
                val c = Client(sortie, true)
                clients.add(c); majSpectateurs()
                try {
                    while (c.vivant) Thread.sleep(200)
                } catch (e: Exception) {
                } finally {
                    clients.remove(c); majSpectateurs(); runCatching { socket.close() }
                }
            }

            else -> envoie(sortie, "404 Not Found", "text/plain", "non".toByteArray())
        }
    }

    private fun majSpectateurs() {
        clientsSon = clients.count { it.son }
        // Count pages, not connections: each viewer opens two (state, audio).
        _etat.value = _etat.value.copy(spectateurs = clients.count { !it.son })
    }

    /**
     * The header of a stream that never closes.
     *
     * **`Connection: close`, not `keep-alive`.** With neither a length nor
     * chunked encoding, `keep-alive` is invalid in HTTP/1.1 and the browser
     * waits for a size that never comes. The event stream got away with it;
     * the audio stayed silent without saying why.
     *
     * `Accept-Ranges: none`: an endless stream cannot serve byte ranges.
     */
    private fun entete(sortie: OutputStream, type: String) {
        sortie.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\n" +
                "Accept-Ranges: none\r\nConnection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
        sortie.flush()
    }

    private fun envoie(sortie: OutputStream, statut: String, type: String, corps: ByteArray) {
        runCatching {
            sortie.write(
                ("HTTP/1.1 $statut\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${corps.size}\r\nConnection: close\r\n\r\n").toByteArray())
            sortie.write(corps)
            sortie.flush()
        }
    }

    // ---------------------------------------------------------------- audio

    /**
     * Is audio flowing right now? Three seconds without a frame means no
     * recording is running, and the page says so rather than leave a silent
     * player that looks broken.
     */
    fun sonDisponible(): Boolean =
        derniereTrameSonMs > 0L &&
            System.currentTimeMillis() - derniereTrameSonMs < 3_000L

    @Volatile private var derniereTrameSonMs = 0L

    /**
     * Audio diagnostic counters: bytes produced, bytes written, to how many
     * viewers, and the last failure. Shown on the operator's screen, who has
     * no browser console at hand. Measure audio faults, do not guess them.
     */
    @Volatile var octetsEncodes = 0L; private set
    @Volatile var octetsEnvoyes = 0L; private set
    @Volatile var tramesSon = 0L; private set
    @Volatile var dernierePanneSon = ""; private set
    @Volatile var clientsSon = 0; private set

    fun videCompteurs() {
        octetsEncodes = 0; octetsEnvoyes = 0; tramesSon = 0; dernierePanneSon = ""
    }

    // **Audio comes from the recording, and from it alone.**
    //
    // A separate capture for demonstration mode used to borrow the microphone
    // and hand it back to the recorder; the handovers raced and it sometimes
    // never started. The broadcast audio is now what the recorder already
    // feeds to the monitor. With no recording, the page says so.

    /**
     * **One sample rate for the whole broadcast.**
     *
     * The stream never closes and the page plays it at a fixed rate (the
     * `CADENCE` constant in `PageDemo`): a mid-stream rate change, e.g. the
     * recorder's 48 kHz capture, used to silence the browser. So everything
     * is brought to 22 050 Hz. Nearest-sample resampling is enough for speech
     * and SSB on a phone speaker; a proper filter would cost time on the
     * capture thread, which must never wait.
     */
    private const val CADENCE_DIFFUSION = 22050
    private var reechantillon = ShortArray(4096)

    private val verrouSon = Any()

    /**
     * Pours captured audio out to the viewers.
     *
     * **Called from the capture thread: nothing here may block.** If a viewer
     * cannot keep up, their write fails and they are dropped, rather than
     * holding back the capture for everyone.
     *
     * **One thread at a time.** When the microphone changed hands, two threads
     * called in at once and corrupted the shared buffers: audio worked once,
     * then never again. The lock costs nothing in practice: a block takes a
     * few hundred microseconds, and outside a handover there is one supplier.
     */
    fun verseAudio(pcm: ShortArray, n: Int, cadence: Int = CADENCE_DIFFUSION) =
        synchronized(verrouSon) { verseAudioInterne(pcm, n, cadence) }

    private var octets = ByteArray(8192)

    private fun verseAudioInterne(pcm: ShortArray, n: Int, cadence: Int) {
        if (n <= 0 || clients.none { it.son }) return

        // Bring to the broadcast rate, if the source differs.
        var source = pcm
        var combien = n
        if (cadence != CADENCE_DIFFUSION && cadence > 0) {
            val taille = (n.toLong() * CADENCE_DIFFUSION / cadence).toInt()
            if (taille <= 0) return
            if (reechantillon.size < taille) reechantillon = ShortArray(taille * 2)
            for (i in 0 until taille) {
                val j = (i.toLong() * cadence / CADENCE_DIFFUSION).toInt()
                reechantillon[i] = pcm[if (j < n) j else n - 1]
            }
            source = reechantillon
            combien = taille
        }

        // Signed sixteen bit, little-endian: what the page expects.
        val taille = combien * 2
        if (octets.size < taille) octets = ByteArray(taille * 2)
        for (i in 0 until combien) {
            val v = source[i].toInt()
            octets[i * 2] = (v and 0xFF).toByte()
            octets[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        derniereTrameSonMs = System.currentTimeMillis()
        octetsEncodes += taille
        tramesSon++
        for (c in clients) {
            if (!c.son) continue
            try {
                c.sortie.write(octets, 0, taille)
                c.sortie.flush()
                octetsEnvoyes += taille
            } catch (e: Exception) {
                dernierePanneSon = e.javaClass.simpleName + " " + (e.message ?: "")
                c.vivant = false
            }
        }
    }
}
