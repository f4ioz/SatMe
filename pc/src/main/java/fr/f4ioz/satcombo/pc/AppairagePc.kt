/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fr.f4ioz.satcombo.demo.RequeteHttp
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The phone joins the PC by a QR code: no address or code to type.
 *
 * The PC shows a QR code: its address on the network and a token drawn for
 * this time. SatMe on the phone scans it, opens its control desk and tells
 * the PC where the desk is and its code; the PC then joins the phone as if
 * they had been typed ([TelephonePc.connecte]).
 *
 * **A door of its own, narrow and short-lived.** The station's page stays on
 * this PC only (it drives a rig and a mast); this listener, open to the
 * network, answers one thing — the phone's announcement with the token —
 * and closes after [DUREE_MS] or once the phone has joined.
 */
class AppairagePc(
    private val telephone: TelephonePc,
    private val portVoulu: Int,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** Where the pairing stands, for the page. */
    data class Etat(val ouvert: Boolean = false, val url: String = "", val adresses: List<String> = emptyList(),
                    val joint: Boolean = false, val telephone: String = "", val erreur: String = "", val jusquA: Long = 0L)

    @Volatile var etat = Etat(); private set
    @Volatile private var jeton = ""
    private var socket: ServerSocket? = null

    /**
     * Opens the door (again: a new token) and gives what the QR code says:
     * `http://<this PC>:<port>/appairage?j=<token>`, on [adresse] when
     * given (one of [adresses]), else the likeliest.
     */
    @Synchronized
    fun ouvre(adresse: String? = null): Etat {
        val l = adresses()
        if (l.isEmpty()) { ferme(); etat = Etat(erreur = "ce PC n'est sur aucun réseau local"); return etat }
        val ip = adresse?.takeIf { it in l } ?: l.first()
        val s = socket ?: runCatching { ecoute() }.getOrElse { etat = Etat(erreur = "port ${portVoulu} occupé"); return etat }
        jeton = (1..20).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        etat = Etat(true, "http://$ip:${s.localPort}/appairage?j=$jeton", l, jusquA = horloge() + DUREE_MS)
        return etat
    }

    @Synchronized
    fun ferme() {
        runCatching { socket?.close() }
        socket = null; jeton = ""
        etat = etat.copy(ouvert = false)
    }

    /** Closes the door once its time is over (each second, from the page's polling). */
    fun verifie(): Etat {
        if (etat.ouvert && horloge() > etat.jusquA) { ferme(); etat = etat.copy(erreur = "QR code expiré : en afficher un autre") }
        return etat
    }

    private fun ecoute(): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        // The port asked for, else any: the QR code says which.
        runCatching { s.bind(InetSocketAddress(portVoulu)) }.getOrElse { s.bind(InetSocketAddress(0)) }
        socket = s
        thread(isDaemon = true, name = "satme-appairage") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
        return s
    }

    private fun sert(c: Socket) {
        c.soTimeout = 10_000
        val r = RequeteHttp.lit(c.getInputStream()) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = r.chemin.substringAfter('?', "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val sortie = c.getOutputStream()
        fun envoie(type: String, corps: String) {
            val b = corps.toByteArray(Charsets.UTF_8)
            sortie.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            sortie.write(b); sortie.flush()
        }
        when {
            // The phone's announcement: where its desk is, and its code.
            r.methode == "POST" && chemin == "/appairage/telephone" ->
                envoie("application/json; charset=utf-8", annonce(q["j"].orEmpty(), q["adresse"].orEmpty(), q["code"].orEmpty(), q["nom"].orEmpty()).toString())
            // Scanned by any camera: say what to do.
            chemin == "/appairage" -> envoie("text/html; charset=utf-8", PAGE)
            else -> envoie("text/plain; charset=utf-8", "non")
        }
    }

    /** The phone's announcement: the token checked, then the PC joins the phone. */
    @Synchronized
    fun annonce(j: String, adresse: String, code: String, nom: String): JSONObject {
        val e = verifie()
        if (!e.ouvert || jeton.isEmpty() || j != jeton) return JSONObject().put("ok", false).put("erreur", "QR code périmé : en afficher un autre sur le PC")
        val pourquoi = telephone.connecte(adresse, code)
        if (pourquoi != null) {
            etat = etat.copy(erreur = pourquoi)
            return JSONObject().put("ok", false).put("erreur", pourquoi)
        }
        // Joined: the door closes, its token spent.
        etat = etat.copy(joint = true, telephone = nom.ifBlank { telephone.adresse }, erreur = "")
        ferme()
        return JSONObject().put("ok", true).put("satme", telephone.version)
    }

    companion object {
        /** Ten minutes to scan it. */
        const val DUREE_MS = 10 * 60_000L

        /**
         * This PC's addresses on the local network (IPv4, private), the
         * likeliest first: a real card before a container's or a VPN's bridge.
         */
        fun adresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .filterNot { i -> listOf("docker", "br-", "veth", "virbr", "lxc", "lxd", "vmnet", "vboxnet", "tun", "tap", "wg", "zt", "tailscale").any { i.name.startsWith(it) } }
                .sortedBy { i -> if (i.name.startsWith("en") || i.name.startsWith("eth") || i.name.startsWith("wl")) 0 else 1 }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress } }
                .distinct()
        }.getOrDefault(emptyList())

        /** A QR code as SVG: dark squares on white, a quiet zone of four. */
        fun svg(contenu: String): String {
            val m = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
            val sb = StringBuilder()
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) sb.append("M$x ${y}h1v1h-1z")
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${m.width} ${m.height}\" shape-rendering=\"crispEdges\">" +
                "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"$sb\" fill=\"#000\"/></svg>"
        }

        private const val PAGE = """<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe-PC</title><body style="font-family:system-ui,sans-serif;background:#0B1016;color:#E8F0F7;padding:24px;line-height:1.5">
<h2>SatMe-PC</h2><p>Ce QR code se scanne depuis l'application SatMe :<br><b>Réglages → Partage et écoute → Partage → Scanner le QR code du PC</b>.</p>
<p style="color:#9AA7B4">Le téléphone et le PC doivent être sur le même réseau.</p></body></html>"""
    }
}
