/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.station

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The little of RFC 6455 the panadapter needs (the same as SatMe-PC's
 * WebSocketPc): the opening handshake, frames read (masked, small) and
 * written (unmasked).
 */
object Ws {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val TEXTE = 0x1; const val BINAIRE = 0x2; const val FERMETURE = 0x8; const val PING = 0x9; const val PONG = 0xA
    const val MAX_CLIENT = 4096

    fun cleAcceptation(cle: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((cle.trim() + GUID).toByteArray(Charsets.US_ASCII)))

    class Demande(val methode: String, val chemin: String, val entetes: Map<String, String>) {
        val valide: Boolean get() = methode == "GET" && entetes["upgrade"]?.lowercase()?.contains("websocket") == true &&
            entetes["connection"]?.lowercase()?.split(',')?.any { it.trim() == "upgrade" } == true &&
            !entetes["sec-websocket-key"].isNullOrBlank() && entetes["sec-websocket-version"]?.trim() == "13"
    }

    /** The request's head (8 KiB at most); null when cut short. */
    fun litDemande(e: InputStream): Demande? {
        val lignes = ArrayList<String>(); val sb = StringBuilder(); var total = 0
        while (true) {
            val b = e.read(); if (b < 0 || ++total > 8192) return null
            if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); sb.setLength(0); if (l.isEmpty()) break; lignes += l } else sb.append(b.toChar())
        }
        val p = lignes.firstOrNull()?.split(' ') ?: return null
        if (p.size < 2) return null
        val ent = lignes.drop(1).mapNotNull { l -> val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim() }.toMap()
        return Demande(p[0], p[1], ent)
    }

    fun trame(opcode: Int, d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(d.size + 10)
        o.write(0x80 or opcode)
        when {
            d.size < 126 -> o.write(d.size)
            d.size <= 0xFFFF -> { o.write(126); o.write(d.size ushr 8); o.write(d.size and 0xFF) }
            else -> { o.write(127); for (i in 7 downTo 0) o.write(((d.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        o.write(d); return o.toByteArray()
    }

    fun fermeture(code: Int) = trame(FERMETURE, byteArrayOf((code ushr 8).toByte(), (code and 0xFF).toByte()))

    class Trame(val opcode: Int, val donnees: ByteArray)

    /** A client's frame; null at the end, or on an unmasked or oversized one. */
    fun litTrame(e: InputStream): Trame? {
        val b0 = e.read(); if (b0 < 0) return null
        val b1 = e.read(); if (b1 < 0) return null
        if (b1 and 0x80 == 0) return null
        var n = (b1 and 0x7F).toLong()
        if (n == 126L) n = lit(e, 2)?.let { ((it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)).toLong() } ?: return null
        else if (n == 127L) return null
        if (n > MAX_CLIENT) return null
        val m = lit(e, 4) ?: return null
        val d = lit(e, n.toInt()) ?: return null
        for (i in d.indices) d[i] = (d[i].toInt() xor m[i % 4].toInt()).toByte()
        return Trame(b0 and 0x0F, d)
    }

    /** A client's frame (masked), for the tests. */
    fun trameClient(opcode: Int, d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); val m = byteArrayOf(1, 2, 3, 4)
        o.write(0x80 or opcode); o.write(0x80 or d.size); o.write(m)
        o.write(ByteArray(d.size) { (d[it].toInt() xor m[it % 4].toInt()).toByte() }); return o.toByteArray()
    }

    private fun lit(e: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var l = 0
        while (l < n) { val k = e.read(b, l, n - l); if (k < 0) return null; l += k }
        return b
    }
}

/**
 * The panadapter's WebSocket (`/ws/sdr/spectrum`), on its own port: the
 * JDK's HTTP server that serves the site cannot hand a connection over to
 * another protocol, so nginx sends `/ws/sdr/` here. Only for the station's
 * admin: the handshake must come from the site's own pages (Origin checked)
 * with a live admin session cookie; two clients at most. About fifteen times
 * a second the spectrum (JSON) and, when asked, the audio (16-bit PCM at
 * 12 kHz, binary frames); the client sends `{freq, rate, mode, volume, audio}`.
 */
class ServeurWs(
    private val ecoute: String,
    private val port: Int,
    private val session: HpsdrSession,
    /** True when the Cookie header carries a live admin session. */
    private val admis: (String?) -> Boolean,
    /** The origins the pages come from (« https://f4ioz.fr »); the Host's own is accepted too. */
    private val origines: Set<String>,
    private val journal: (String) -> Unit = { println(it) },
) {
    private var prise: ServerSocket? = null
    private val clients = AtomicInteger(0)

    val portReel: Int get() = prise?.localPort ?: 0

    fun demarre() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(ecoute, port), 16)
        prise = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread({ runCatching { sert(c) }; runCatching { c.close() } }, "ws-sdr").apply { isDaemon = true; start() }
            }
        }, "ws-sdr-ecoute").apply { isDaemon = true; start() }
    }

    fun arrete() { runCatching { prise?.close() } }

    /** True when [origine] is one of the site's: its public address, or the host the request was sent to. */
    fun origineSure(origine: String?, hote: String?): Boolean {
        val o = origine?.trim()?.trimEnd('/') ?: return false
        if (o in origines) return true
        val h = hote?.trim() ?: return false
        return o == "https://$h" || o == "http://$h"
    }

    private fun refuse(sortie: OutputStream, code: Int, texte: String) {
        sortie.write("HTTP/1.1 $code $texte\r\nContent-Type: text/plain\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)); sortie.flush()
    }

    private fun sert(c: Socket) {
        c.soTimeout = 5000
        c.tcpNoDelay = true
        val e = c.getInputStream().buffered(); val o = c.getOutputStream()
        val d = Ws.litDemande(e) ?: return
        if (d.chemin.substringBefore('?') != CHEMIN || !d.valide) { refuse(o, 404, "Not Found"); return }
        if (!origineSure(d.entetes["origin"], d.entetes["x-forwarded-host"] ?: d.entetes["host"])) { refuse(o, 403, "Forbidden"); return }
        o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
            Ws.cleAcceptation(d.entetes.getValue("sec-websocket-key")) + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
        // No admin session: closed at once, « policy violation ».
        if (!admis(d.entetes["cookie"])) { o.write(Ws.fermeture(1008)); o.flush(); return }
        if (clients.incrementAndGet() > 2) { clients.decrementAndGet(); envoie(o, Ws.TEXTE, JSONObject().put("error", "déjà deux clients").toString().toByteArray()); o.write(Ws.fermeture(1013)); return }
        try {
            if (!session.assure()) { envoie(o, Ws.TEXTE, JSONObject().put("error", session.erreur ?: "SDR indisponible").toString().toByteArray()); o.write(Ws.fermeture(1011)); return }
            c.soTimeout = 0
            val ouvert = java.util.concurrent.atomic.AtomicBoolean(true)
            val lecteur = Thread({
                try {
                    while (ouvert.get()) {
                        val t = Ws.litTrame(e) ?: break
                        when (t.opcode) {
                            Ws.TEXTE -> runCatching { applique(JSONObject(String(t.donnees, Charsets.UTF_8))) }
                            Ws.PING -> synchronized(o) { o.write(Ws.trame(Ws.PONG, t.donnees)); o.flush() }
                            Ws.FERMETURE -> break
                        }
                    }
                } catch (_: Exception) {}
                ouvert.set(false)
            }, "ws-sdr-lecture").apply { isDaemon = true; start() }
            journal("ws-sdr : client ouvert")
            while (ouvert.get()) {
                session.touche()
                if (!session.enMarche && !session.assure()) {
                    envoie(o, Ws.TEXTE, JSONObject().put("error", session.erreur ?: "SDR arrêté").toString().toByteArray()); break
                }
                session.instantane()?.let { envoie(o, Ws.TEXTE, it.toString().toByteArray(Charsets.UTF_8)) }
                if (session.audio) session.audioRecu().takeIf { it.isNotEmpty() }?.let { envoie(o, Ws.BINAIRE, it) }
                Thread.sleep(66)
            }
            ouvert.set(false)
            runCatching { synchronized(o) { o.write(Ws.fermeture(1000)); o.flush() } }
            lecteur.join(500)
        } finally { clients.decrementAndGet(); journal("ws-sdr : client fermé") }
    }

    private fun envoie(o: OutputStream, op: Int, d: ByteArray) = synchronized(o) { o.write(Ws.trame(op, d)); o.flush() }

    private fun applique(j: JSONObject) {
        val f = if (j.has("freq")) j.optDouble("freq", Double.NaN).takeIf { !it.isNaN() }?.toLong() else null
        val r = if (j.has("rate")) j.optInt("rate", 0) else null
        session.regle(f, r)
        session.regleAudio(if (j.has("audio")) j.optBoolean("audio") else null, if (j.has("mode")) j.optString("mode").take(4) else null,
            if (j.has("volume")) j.optDouble("volume", Double.NaN).toFloat().takeIf { !it.isNaN() } else null)
    }

    companion object { const val CHEMIN = "/ws/sdr/spectrum" }
}
