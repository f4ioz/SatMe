/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * A small MQTT 3.1.1 client, written here rather than taken from a library
 * (Eclipse Paho's EPL-2.0 is not compatible with the GPL; what Home
 * Assistant needs is little): CONNECT with a user, a password and a last
 * will, PUBLISH and SUBSCRIBE at QoS 0, PINGREQ to keep the line, TLS when
 * asked. One connection; [DomotiquePc] makes a new one when it drops.
 *
 * The password goes only in the CONNECT packet: never written in a log.
 */
class MqttPc(
    private val hote: String,
    private val port: Int,
    private val clientId: String,
    private val utilisateur: String = "",
    private val motDePasse: String = "",
    private val tls: Boolean = false,
    private val keepAliveS: Int = 60,
    /** The last will: topic, payload (retained), sent by the broker if this connection dies. */
    private val testament: Pair<String, String>? = null,
    private val delaiMs: Int = 10_000,
) {
    /** A message received on a topic subscribed to. */
    @Volatile var recu: (String, String) -> Unit = { _, _ -> }
    /** The connection lost (its reason). */
    @Volatile var perdu: (String) -> Unit = {}

    @Volatile private var socket: Socket? = null
    @Volatile private var sortie: OutputStream? = null
    @Volatile var connecte = false; private set
    private var paquetId = 1

    /** Connects, waits for CONNACK; throws with the broker's reason when refused. */
    fun connecte() {
        val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        s.connect(InetSocketAddress(hote, port), delaiMs)
        s.soTimeout = delaiMs
        val entree = DataInputStream(s.getInputStream().buffered())
        val o = s.getOutputStream()
        o.write(Codec.connect(clientId, utilisateur, motDePasse, keepAliveS, testament)); o.flush()
        val (octet, corps) = Codec.lit(entree) ?: throw IllegalStateException("le broker a fermé la connexion")
        if (octet shr 4 != Codec.CONNACK || corps.size < 2) { runCatching { s.close() }; throw IllegalStateException("réponse inattendue du broker") }
        val code = corps[1].toInt() and 0xFF
        if (code != 0) { runCatching { s.close() }; throw IllegalStateException(Codec.refus(code)) }
        // The reader waits as long as the keep-alive allows (plus half).
        s.soTimeout = keepAliveS * 1500
        socket = s; sortie = o; connecte = true
        thread(isDaemon = true, name = "satme-mqtt-lecture") { lecture(s, entree) }
        thread(isDaemon = true, name = "satme-mqtt-ping") {
            while (connecte && socket === s) {
                Thread.sleep(keepAliveS * 1000L / 2)
                if (connecte && socket === s) runCatching { ecrit(Codec.PINGREQ_PAQUET) }
            }
        }
    }

    private fun lecture(s: Socket, entree: DataInputStream) {
        var raison = "connexion fermée"
        try {
            while (socket === s) {
                val (octet, corps) = Codec.lit(entree) ?: break
                if (octet shr 4 == Codec.PUBLISH) Codec.publication(octet, corps)?.let { (topic, texte) -> runCatching { recu(topic, texte) } }
            }
        } catch (e: Exception) {
            raison = if (e is EOFException) "connexion fermée par le broker" else (e.message ?: e.toString())
        }
        if (socket === s) { ferme(); runCatching { perdu(raison) } }
    }

    @Synchronized private fun ecrit(b: ByteArray) {
        val o = sortie ?: throw IllegalStateException("non connecté")
        o.write(b); o.flush()
    }

    fun publie(topic: String, texte: String, retenu: Boolean = false) = ecrit(Codec.publish(topic, texte.toByteArray(Charsets.UTF_8), retenu))

    fun abonne(vararg filtres: String) {
        val id = synchronized(this) { paquetId = paquetId % 65_535 + 1; paquetId }
        ecrit(Codec.subscribe(id, filtres.toList()))
    }

    /** DISCONNECT (the last will not sent) and close. */
    fun deconnecte() {
        if (connecte) runCatching { ecrit(byteArrayOf(0xE0.toByte(), 0)) }
        ferme()
    }

    private fun ferme() {
        connecte = false
        val s = socket
        socket = null; sortie = null
        runCatching { s?.close() }
    }

    /** The packets, as MQTT 3.1.1 writes them. */
    object Codec {
        const val CONNACK = 2
        const val PUBLISH = 3
        const val SUBACK = 9
        const val PINGRESP = 13
        val PINGREQ_PAQUET = byteArrayOf(0xC0.toByte(), 0)

        fun longueur(n: Int): ByteArray {
            val o = ByteArrayOutputStream()
            var x = n
            do {
                var b = x % 128
                x /= 128
                if (x > 0) b = b or 0x80
                o.write(b)
            } while (x > 0)
            return o.toByteArray()
        }

        private fun chaine(o: ByteArrayOutputStream, b: ByteArray) { o.write(b.size shr 8); o.write(b.size and 0xFF); o.write(b) }
        private fun chaine(o: ByteArrayOutputStream, s: String) = chaine(o, s.toByteArray(Charsets.UTF_8))

        private fun paquet(entete: Int, corps: ByteArray): ByteArray =
            ByteArrayOutputStream().apply { write(entete); write(longueur(corps.size)); write(corps) }.toByteArray()

        fun connect(clientId: String, utilisateur: String, motDePasse: String, keepAliveS: Int, testament: Pair<String, String>?): ByteArray {
            val v = ByteArrayOutputStream()
            chaine(v, "MQTT"); v.write(4)
            var drapeaux = 0x02 // clean session
            if (testament != null) drapeaux = drapeaux or 0x04 or 0x20 // will, QoS 0, retained
            if (utilisateur.isNotEmpty()) drapeaux = drapeaux or 0x80
            if (utilisateur.isNotEmpty() && motDePasse.isNotEmpty()) drapeaux = drapeaux or 0x40
            v.write(drapeaux)
            v.write(keepAliveS shr 8); v.write(keepAliveS and 0xFF)
            chaine(v, clientId)
            testament?.let { (t, p) -> chaine(v, t); chaine(v, p) }
            if (utilisateur.isNotEmpty()) chaine(v, utilisateur)
            if (utilisateur.isNotEmpty() && motDePasse.isNotEmpty()) chaine(v, motDePasse)
            return paquet(0x10, v.toByteArray())
        }

        fun publish(topic: String, charge: ByteArray, retenu: Boolean): ByteArray {
            val v = ByteArrayOutputStream()
            chaine(v, topic); v.write(charge)
            return paquet(0x30 or (if (retenu) 1 else 0), v.toByteArray())
        }

        fun subscribe(id: Int, filtres: List<String>): ByteArray {
            val v = ByteArrayOutputStream()
            v.write(id shr 8); v.write(id and 0xFF)
            filtres.forEach { chaine(v, it); v.write(0) }
            return paquet(0x82, v.toByteArray())
        }

        /** One packet: its first byte (type and flags) and its body; null at the end of the stream. */
        fun lit(e: DataInputStream): Pair<Int, ByteArray>? {
            val b = e.read()
            if (b < 0) return null
            var n = 0; var mult = 1; var k = 0
            while (true) {
                val c = e.read()
                if (c < 0) throw EOFException()
                n += (c and 0x7F) * mult
                mult *= 128
                if (c and 0x80 == 0) break
                if (++k >= 4) throw IllegalStateException("longueur MQTT invalide")
            }
            if (n > 1_048_576) throw IllegalStateException("paquet MQTT trop grand")
            val corps = ByteArray(n)
            e.readFully(corps)
            return b to corps
        }

        /** A PUBLISH's topic and payload (QoS 0 only: a packet id is skipped when the broker sends one anyway). */
        fun publication(octet: Int, corps: ByteArray): Pair<String, String>? {
            if (corps.size < 2) return null
            val n = ((corps[0].toInt() and 0xFF) shl 8) or (corps[1].toInt() and 0xFF)
            if (2 + n > corps.size) return null
            val topic = String(corps, 2, n, Charsets.UTF_8)
            val qos = (octet shr 1) and 3
            val debut = 2 + n + if (qos > 0) 2 else 0
            if (debut > corps.size) return null
            return topic to String(corps, debut, corps.size - debut, Charsets.UTF_8)
        }

        fun refus(code: Int): String = when (code) {
            1 -> "version du protocole refusée par le broker"
            2 -> "identifiant client refusé par le broker"
            3 -> "broker indisponible"
            4 -> "utilisateur ou mot de passe refusé"
            5 -> "connexion non autorisée par le broker"
            else -> "refus du broker (code $code)"
        }
    }
}
