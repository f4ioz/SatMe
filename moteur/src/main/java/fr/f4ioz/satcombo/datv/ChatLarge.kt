/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject

/**
 * The few Engine.IO v4 / Socket.IO v4 text packets a chat client needs,
 * over a WebSocket: the opening ("0{…}"), the pings ("2", answered "3"),
 * the namespace's connection ("40" asked, "40{sid}" answered), the events
 * ("42[name, data]").
 */
object SocketIo {

    sealed class Paquet {
        data class Ouverture(val pingIntervalleMs: Long, val pingDelaiMs: Long) : Paquet()
        object Ping : Paquet()
        object Connecte : Paquet()
        data class Refus(val pourquoi: String) : Paquet()
        object Deconnecte : Paquet()
        data class Evenement(val nom: String, val donnees: Any?) : Paquet()
        object Fermeture : Paquet()
        object Autre : Paquet()
    }

    /** The answer to a ping. */
    const val PONG = "3"
    /** Joining the main namespace. */
    const val CONNEXION = "40"

    fun lis(t: String): Paquet = runCatching {
        when {
            t.isEmpty() -> Paquet.Autre
            t[0] == '0' -> JSONObject(t.substring(1)).let { Paquet.Ouverture(it.optLong("pingInterval", 25_000L), it.optLong("pingTimeout", 20_000L)) }
            t == "2" -> Paquet.Ping
            t[0] == '1' -> Paquet.Fermeture
            t.startsWith("40") -> Paquet.Connecte
            t.startsWith("41") -> Paquet.Deconnecte
            t.startsWith("44") -> Paquet.Refus(runCatching { JSONObject(t.substring(2)).optString("message") }.getOrDefault(t.substring(2)))
            t.startsWith("42") -> {
                // An ack id may sit between the type and the array: "42" + digits + "[…]".
                val i = t.indexOf('[')
                if (i < 0) Paquet.Autre else JSONArray(t.substring(i)).let { a -> Paquet.Evenement(a.optString(0), if (a.length() > 1) a.get(1) else null) }
            }
            else -> Paquet.Autre
        }
    }.getOrDefault(Paquet.Autre)

    /** An event to send. */
    fun evenement(nom: String, donnees: JSONObject): String = "42" + JSONArray().put(nom).put(donnees).toString()
}

/**
 * The QO-100 wide band chat as received: its last messages, who is in,
 * how many watch. A message to send is checked here (length, rate, a
 * callsign as nick); sending it is the caller's, and only on the
 * operator's explicit action.
 */
class ChatLarge(private val max: Int = 200) {
    data class Message(val tempsMs: Long, val nom: String, val texte: String)

    private val messages = ArrayDeque<Message>()
    @Volatile var nicks: List<String> = emptyList(); private set
    @Volatile var spectateurs = 0; private set
    @Volatile var numero = 0L; private set

    fun liste(): List<Message> = synchronized(messages) { messages.toList() }

    /** An event received; true when it changed something. */
    fun recoit(nom: String, d: Any?): Boolean {
        val o = d as? JSONObject
        when (nom) {
            "history" -> {
                o ?: return false
                o.optJSONArray("nicks")?.let { nicks = noms(it) }
                val h = o.optJSONArray("history") ?: JSONArray()
                synchronized(messages) {
                    messages.clear()
                    for (i in 0 until h.length()) h.optJSONObject(i)?.let { m -> message(m)?.let { messages.addLast(it) } }
                    while (messages.size > max) messages.removeFirst()
                }
            }
            "message" -> {
                val m = o?.let { message(it) } ?: return false
                synchronized(messages) {
                    // Echoed once more after a reconnection: not twice.
                    if (messages.any { it == m }) return false
                    messages.addLast(m); while (messages.size > max) messages.removeFirst()
                }
            }
            "nicks" -> nicks = noms(o?.optJSONArray("nicks") ?: return false)
            "viewers" -> spectateurs = o?.optInt("num", spectateurs) ?: return false
            else -> return false
        }
        numero++
        return true
    }

    private fun noms(a: JSONArray) = (0 until a.length()).map { a.optString(it).take(40) }.filter { it.isNotBlank() }

    private fun message(m: JSONObject): Message? {
        val t = m.optString("time")
        val ms = runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull() ?: return null
        return Message(ms, m.optString("name").take(40), m.optString("message").take(MAX_TEXTE * 2))
    }

    fun json(depuis: Long): JSONObject {
        val o = JSONObject().put("n", numero).put("nicks", JSONArray(nicks)).put("spectateurs", spectateurs)
        if (depuis != numero) o.put("messages", JSONArray(liste().map { JSONObject().put("t", it.tempsMs).put("nom", it.nom).put("texte", it.texte) }))
        return o
    }

    companion object {
        const val MAX_TEXTE = 300
        const val ROOM = "eshail-wb"

        /** The nick from the station's callsign (20 characters at most), or null without one. */
        fun nick(indicatif: String): String? = indicatif.trim().uppercase().filter { it.isLetterOrDigit() || it == '/' || it == '-' }
            .take(20).takeIf { it.length >= 3 && it.any(Char::isDigit) }

        /** The text to send, cleaned, or why not. */
        fun texte(t: String): Pair<String?, String> {
            val s = t.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim()
            return when {
                s.isEmpty() -> null to "message vide"
                s.length > MAX_TEXTE -> null to "message trop long (" + MAX_TEXTE + " caractères au plus)"
                s.startsWith("/") -> null to "les commandes du salon ne passent pas par SatMe"
                else -> s to ""
            }
        }

        /** A reception report as the chat's habit writes it: callsign, MER margin and MER, rate, frequency. */
        fun rapport(indicatif: String, margeDb: Double?, merDb: Double?, srKs: Int, cielKhz: Long): String {
            fun v(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f", x)
            val qui = indicatif.trim().ifEmpty { "?" }
            val d = if (margeDb != null) " - D" + v(margeDb) else ""
            val m = if (merDb != null) " (" + v(merDb) + " dB)" else ""
            val f = String.format(java.util.Locale.FRANCE, "%.3f", cielKhz / 1000.0).trimEnd('0').trimEnd(',')
            return "SigReport: " + qui + d + m + " - " + srKs + " Ks - " + f
        }
    }
}
