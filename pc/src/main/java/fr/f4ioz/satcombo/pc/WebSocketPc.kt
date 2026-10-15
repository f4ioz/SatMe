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
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The little of RFC 6455 a server needs: the opening handshake, and frames
 * read and written. Text out; ping answered, close echoed; fragments joined.
 */
object WebSocketPc {

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    const val CONTINUATION = 0x0
    const val TEXTE = 0x1
    const val BINAIRE = 0x2
    const val FERMETURE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    /** Larger frames from a client are refused: it has nothing big to say. */
    const val MAX_CLIENT = 64 * 1024

    /** Sec-WebSocket-Accept for a client's Sec-WebSocket-Key (section 4.2.2). */
    fun cleAcceptation(cle: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((cle.trim() + GUID).toByteArray(Charsets.US_ASCII)))

    /** An opening request read: its path and headers (names in lower case). */
    class Demande(val methode: String, val chemin: String, val entetes: Map<String, String>) {
        val cle: String? get() = entetes["sec-websocket-key"]
        /** A WebSocket opening as the RFC asks: GET, Upgrade: websocket, Connection: upgrade, a key, version 13. */
        val valide: Boolean get() = methode == "GET" &&
            entetes["upgrade"]?.lowercase()?.contains("websocket") == true &&
            entetes["connection"]?.lowercase()?.split(',')?.any { it.trim() == "upgrade" } == true &&
            !cle.isNullOrBlank() && entetes["sec-websocket-version"]?.trim() == "13"
    }

    /** Reads the request lines up to the blank one (8 KiB at most); null when cut short. */
    fun litDemande(e: InputStream): Demande? {
        val lignes = ArrayList<String>()
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val b = e.read()
            if (b < 0) return null
            if (++total > 8192) return null
            if (b == '\n'.code) {
                val l = sb.toString().trimEnd('\r'); sb.setLength(0)
                if (l.isEmpty()) break
                lignes += l
            } else sb.append(b.toChar())
        }
        val premiere = lignes.firstOrNull()?.split(' ') ?: return null
        if (premiere.size < 2) return null
        val entetes = lignes.drop(1).mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return Demande(premiere[0], premiere[1], entetes)
    }

    /** The 101 answer to a valid opening. */
    fun reponse(cle: String): ByteArray = ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: " + cleAcceptation(cle) + "\r\n\r\n").toByteArray(Charsets.US_ASCII)

    /** A server frame: final, never masked (section 5.1). */
    fun trame(opcode: Int, donnees: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(donnees.size + 10)
        o.write(0x80 or (opcode and 0x0F))
        val n = donnees.size
        when {
            n < 126 -> o.write(n)
            n <= 0xFFFF -> { o.write(126); o.write(n ushr 8); o.write(n and 0xFF) }
            else -> { o.write(127); for (i in 7 downTo 0) o.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        o.write(donnees)
        return o.toByteArray()
    }

    fun texte(s: String): ByteArray = trame(TEXTE, s.toByteArray(Charsets.UTF_8))

    /** A close frame with its status code (1000: normal). */
    fun fermeture(code: Int = 1000): ByteArray = trame(FERMETURE, byteArrayOf((code ushr 8).toByte(), (code and 0xFF).toByte()))

    class Trame(val fin: Boolean, val opcode: Int, val donnees: ByteArray)

    /** Reads one frame; null at the end of the stream or on a frame the RFC forbids from a client (unmasked, too large). */
    fun litTrame(e: InputStream): Trame? {
        val b0 = e.read(); if (b0 < 0) return null
        val b1 = e.read(); if (b1 < 0) return null
        val masque = (b1 and 0x80) != 0
        var n = (b1 and 0x7F).toLong()
        if (n == 126L) n = ((lit(e, 2) ?: return null).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }).toLong()
        else if (n == 127L) n = (lit(e, 8) ?: return null).fold(0L) { a, x -> (a shl 8) or (x.toLong() and 0xFF) }
        // Clients must mask (section 5.3).
        if (!masque || n < 0 || n > MAX_CLIENT) return null
        val m = lit(e, 4) ?: return null
        val d = lit(e, n.toInt()) ?: return null
        for (i in d.indices) d[i] = (d[i].toInt() xor m[i % 4].toInt()).toByte()
        return Trame((b0 and 0x80) != 0, b0 and 0x0F, d)
    }

    /** A client frame, masked as a client must (tests, and any SatMe client). */
    fun trameClient(opcode: Int, donnees: ByteArray, masque: ByteArray = byteArrayOf(0x12, 0x34, 0x56, 0x78), fin: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write((if (fin) 0x80 else 0) or opcode)
        val n = donnees.size
        when {
            n < 126 -> o.write(0x80 or n)
            n <= 0xFFFF -> { o.write(0x80 or 126); o.write(n ushr 8); o.write(n and 0xFF) }
            else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        o.write(masque)
        o.write(ByteArray(n) { (donnees[it].toInt() xor masque[it % 4].toInt()).toByte() })
        return o.toByteArray()
    }

    private fun lit(e: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var lu = 0
        while (lu < n) { val k = e.read(b, lu, n - lu); if (k < 0) return null; lu += k }
        return b
    }
}
