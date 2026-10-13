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
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * **SatMe-Node**: SatMe on a small computer next to the antenna (a Raspberry
 * Pi), the MiniTiouner plugged into it, driven from SatMe-PC or the phone
 * over the network (`satme --noeud`).
 *
 * The node only receives: LongMynd there ([DatvPc] without players), its
 * status and its transport stream sent to the clients. Nothing is decoded on
 * it, which is why a Raspberry Pi 3 is enough.
 *
 * **A code, then a key.** At its first start the node draws a six-digit
 * code, said in its log (`journalctl -u satme-noeud`) and kept. A client
 * gives it once and gets a key, kept on both sides: the node can restart,
 * the client stays joined. Without a key, the node only says what it is.
 */
class NoeudPc(
    private val datv: DatvPc,
    private val stockage: Stockage,
    private val port: Int,
    val nom: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("satme-noeud"),
) {
    private var socket: ServerSocket? = null
    val portReel: Int get() = socket?.localPort ?: port

    /** The code to give a client, drawn once and kept. */
    val code: String = stockage.getString(CLE_CODE, null)?.takeIf { it.length == 6 }
        ?: (1..6).map { "0123456789".random() }.joinToString("").also { stockage.edit().putString(CLE_CODE, it).apply() }

    private fun cles(): Set<String> = stockage.getStringSet(CLE_CLES, emptySet()).orEmpty()

    fun demarre() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        thread(isDaemon = true, name = "satme-noeud") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "satme-noeud-client") { runCatching { sert(c) }; runCatching { c.close() } }
            }
        }
    }

    fun arrete() { runCatching { socket?.close() } }

    private fun sert(c: Socket) {
        c.soTimeout = 15_000
        val r = RequeteHttp.lit(c.getInputStream()) ?: return
        val chemin = r.chemin.substringBefore('?')
        val q = r.chemin.substringAfter('?', "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val o = c.getOutputStream()
        when (chemin) {
            // What it is, for anyone: nothing more.
            "/noeud/api" -> json(o, JSONObject().put("noeud", true).put("nom", nom).put("services", org.json.JSONArray(listOf("datv")))
                .put("longmynd", datv.programme() != null))
            "/noeud/entrer" -> {
                if (q["code"]?.trim() == code) {
                    val cle = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                    stockage.edit().putStringSet(CLE_CLES, (cles() + cle).toList().takeLast(20).toSet()).apply()
                    json(o, JSONObject().put("ok", true).put("cle", cle))
                } else {
                    // A second per wrong code: guessing a million of them is not worth it.
                    Thread.sleep(1000)
                    json(o, JSONObject().put("ok", false))
                }
            }
            "/" -> page(o)
            else -> {
                if (q["cle"].isNullOrBlank() || q["cle"] !in cles()) { envoie(o, "403 Forbidden", "text/plain", "clé".toByteArray()); return }
                route(chemin, q, r.methode, o, c)
            }
        }
    }

    private fun route(chemin: String, q: Map<String, String>, methode: String, o: OutputStream, c: Socket) {
        when (chemin) {
            "/noeud/datv" -> json(o, JsonPc.datv(datv))
            "/noeud/datv/demarre" -> {
                if (methode != "POST") { envoie(o, "405 Method Not Allowed", "text/plain", ByteArray(0)); return }
                fun khz(k: String) = q[k]?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 1000.0) }
                val r0 = datv.reglage
                val r = r0.copy(cielKhz = khz("ciel") ?: r0.cielKhz, srKs = q["sr"]?.toIntOrNull() ?: r0.srKs, olKhz = khz("ol") ?: r0.olKhz,
                    entreeB = q["entree"]?.let { it == "b" } ?: r0.entreeB, lnb = q["lnb"]?.takeIf { it in setOf("", "v", "h") } ?: "", son = false)
                val e = datv.demarre(r)
                json(o, if (e == null) JSONObject().put("ok", true) else JSONObject().put("ok", false).put("erreur", e))
            }
            "/noeud/datv/arrete" -> { datv.arrete(); json(o, JSONObject().put("ok", true)) }
            "/noeud/datv/ts" -> {
                // The stream as it comes, for as long as the client reads it.
                c.soTimeout = 0
                o.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                o.flush()
                val file = datv.abonne()
                try {
                    while (true) {
                        val bloc = file.poll(2, TimeUnit.SECONDS) ?: run { if (!datv.etat.actif) return; null } ?: continue
                        o.write(bloc)
                        if (file.isEmpty()) o.flush()
                    }
                } catch (_: Exception) {
                } finally { datv.desabonne(file) }
            }
            else -> envoie(o, "404 Not Found", "text/plain", "non".toByteArray())
        }
    }

    /** A page for whoever opens the node in a browser: what it is and how it receives; never its code. */
    private fun page(o: OutputStream) {
        val e = datv.etat
        val h = """<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe-Node</title><body style="font-family:system-ui,sans-serif;background:#0B1016;color:#E8F0F7;padding:24px;line-height:1.6">
<h2>SatMe-Node · ${esc(nom)}</h2>
<p>MiniTiouner : ${if (e.actif) esc(e.libelle) + (e.merDb?.let { " · MER %.1f dB".format(it) } ?: "") else "à l'arrêt"}${if (datv.programme() == null) " — <b>LongMynd introuvable</b>" else ""}</p>
<p style="color:#9AA7B4">Se pilote depuis SatMe-PC (onglet DATV, « SatMe-Node ») ou le téléphone, avec le code donné au démarrage du node
(<code>journalctl -u satme-noeud</code>).</p></body></html>"""
        envoie(o, "200 OK", "text/html; charset=utf-8", h.toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    private fun json(o: OutputStream, j: JSONObject) = envoie(o, "200 OK", "application/json; charset=utf-8", j.toString().toByteArray())

    private fun envoie(o: OutputStream, statut: String, type: String, corps: ByteArray) {
        o.write("HTTP/1.1 $statut\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(corps); o.flush()
    }

    companion object {
        private const val CLE_CODE = "noeud_code"
        private const val CLE_CLES = "noeud_cles"
    }
}
