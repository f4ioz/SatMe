/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A WebSocket client (RFC 6455, client side): the opening handshake (TLS
 * by the JDK), the messages received, pings answered, the close echoed.
 * Nothing else is sent but what the caller passes to [envoieTexte].
 */
class ClientWebSocketPc private constructor(private val socket: Socket, private val max: Int) : Closeable {
    private val entree = BufferedInputStream(socket.getInputStream(), 16_384)
    private val sortie: OutputStream = socket.getOutputStream()
    @Volatile private var ferme = false

    /** The next data message (binary or text, fragments joined); null once closed. */
    fun recoit(): ByteArray? {
        val message = ByteArrayOutputStream()
        var enCours = false
        while (!ferme) {
            val t = WebSocketPc.litTrameServeur(entree, max) ?: run { close(); return null }
            when (t.opcode) {
                WebSocketPc.PING -> envoie(WebSocketPc.PONG, t.donnees)
                WebSocketPc.PONG -> {}
                WebSocketPc.FERMETURE -> {
                    // The close echoed with its code (section 5.5.1), then the connection dropped.
                    runCatching { envoie(WebSocketPc.FERMETURE, t.donnees.copyOf(minOf(2, t.donnees.size))) }
                    close(); return null
                }
                WebSocketPc.TEXTE, WebSocketPc.BINAIRE, WebSocketPc.CONTINUATION -> {
                    if (t.opcode == WebSocketPc.CONTINUATION && !enCours) continue
                    if (t.opcode != WebSocketPc.CONTINUATION) message.reset()
                    message.write(t.donnees)
                    if (message.size() > max) { close(); return null }
                    enCours = !t.fin
                    if (t.fin) return message.toByteArray()
                }
                else -> { close(); return null }
            }
        }
        return null
    }

    /** A text message (a chat's protocol packets, its messages). */
    fun envoieTexte(t: String) = envoie(WebSocketPc.TEXTE, t.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun envoie(opcode: Int, donnees: ByteArray) {
        if (ferme) return
        val m = ByteArray(4).also { ALEA.nextBytes(it) }
        sortie.write(WebSocketPc.trameClient(opcode, donnees, m)); sortie.flush()
    }

    /** Closed politely (a close frame, 1000), then the socket. */
    fun fermePoliment() {
        runCatching { envoie(WebSocketPc.FERMETURE, byteArrayOf(0x03, 0xE8.toByte())) }
        close()
    }

    override fun close() { ferme = true; runCatching { socket.close() } }

    companion object {
        private val ALEA = SecureRandom()

        /**
         * Opens ws(s)://[hote]:[port][chemin] asking [protocole]; [agent] in
         * User-Agent. A read waiting over [delaiLectureMs] fails (a dead
         * connection). Throws when refused.
         */
        fun ouvre(hote: String, port: Int, chemin: String, protocole: String, agent: String, tls: Boolean = true,
                  delaiMs: Int = 10_000, delaiLectureMs: Int = 30_000, max: Int = 1 shl 20): ClientWebSocketPc {
            val brut = Socket()
            brut.connect(InetSocketAddress(hote, port), delaiMs)
            val s: Socket = if (!tls) brut else {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(brut, hote, port, true) as SSLSocket
                // The certificate checked against the host's name.
                ssl.sslParameters = ssl.sslParameters.also { p: SSLParameters -> p.endpointIdentificationAlgorithm = "HTTPS" }
                ssl.soTimeout = delaiMs
                ssl.startHandshake()
                ssl
            }
            try {
                s.soTimeout = delaiMs
                val cle = Base64.getEncoder().encodeToString(ByteArray(16).also { ALEA.nextBytes(it) })
                val hoteEntete = if ((tls && port == 443) || (!tls && port == 80)) hote else "$hote:$port"
                val demande = "GET " + chemin + " HTTP/1.1\r\nHost: " + hoteEntete + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + cle + "\r\nSec-WebSocket-Version: 13\r\n" +
                    (if (protocole.isNotEmpty()) "Sec-WebSocket-Protocol: $protocole\r\n" else "") + "User-Agent: " + agent + "\r\n\r\n"
                s.getOutputStream().apply { write(demande.toByteArray(Charsets.US_ASCII)); flush() }
                val c = ClientWebSocketPc(s, max)
                // The answer's lines read as a request's: « HTTP/1.1 101 … ».
                val r = WebSocketPc.litDemande(c.entree) ?: throw IOException("pas de réponse")
                if (r.chemin != "101") throw IOException("refusé (HTTP " + r.chemin + ")")
                if (r.entetes["sec-websocket-accept"]?.trim() != WebSocketPc.cleAcceptation(cle)) throw IOException("réponse WebSocket invalide")
                val p = r.entetes["sec-websocket-protocol"]?.trim().orEmpty()
                if (protocole.isNotEmpty() && p.isNotEmpty() && p != protocole) throw IOException("autre protocole : $p")
                s.soTimeout = delaiLectureMs
                return c
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
    }
}
