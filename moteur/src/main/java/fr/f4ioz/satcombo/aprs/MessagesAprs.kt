/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import org.json.JSONArray
import org.json.JSONObject

/**
 * The APRS tab's « Messages »: the messages heard that are addressed to the
 * station's callsign (any SSID: F4XYZ, F4XYZ-7 and F4XYZ-9 are all « me »)
 * and the bulletins (addressees BLN0…BLN9, BLNA…BLNZ and group bulletins
 * « BLN1WX »), newest first, those relayed by a satellite marked.
 *
 * **Receive only.** This is a reading list: no acknowledgement is ever sent
 * from here (APRS 1.01 asks the addressee to ack a numbered message; the
 * sender will simply retry, then give up).
 *
 * A message is often heard several times (the sender retries until acked,
 * digipeaters repeat it): the same sender, addressee and number (or text
 * when it has no number) is one message, its first hearing kept, the
 * repeats counted.
 */
object MessagesAprs {

    enum class Genre(val cle: String) { POUR_MOI("moi"), BULLETIN("bulletin") }

    data class Message(val t: Long, val de: String, val a: String, val texte: String, val id: String?, val genre: Genre,
                       val satellite: String?, val fois: Int = 1,
                       /** The last time it was heard (a repeat's). */
                       val vuMs: Long = t)

    /** A callsign without its SSID, upper case (« f4xyz-7 » → « F4XYZ »). */
    fun base(indicatif: String): String = indicatif.trim().uppercase().substringBefore('-').substringBefore(' ')

    /** [destinataire] is the station [moi], whatever the SSID on either side. */
    fun pourMoi(destinataire: String?, moi: String): Boolean {
        val m = base(moi)
        return m.length >= 3 && destinataire != null && base(destinataire) == m
    }

    /** A bulletin's addressee: « BLN » then a digit, a letter or a group name. */
    fun bulletin(destinataire: String?): Boolean {
        val d = destinataire?.trim()?.uppercase() ?: return false
        return d.length in 4..9 && d.startsWith("BLN") && d[3].isLetterOrDigit()
    }

    /**
     * The inbox from [paquets] (any order) for [moi]: messages to me and
     * bulletins, never what this station sent itself, newest first.
     */
    fun boite(paquets: List<Paquet>, moi: String): List<Message> {
        val vus = LinkedHashMap<String, Message>()
        val dernier = HashMap<String, String>()
        for (p in paquets.sortedBy { it.quand }) {
            if (p.emis || p.type != TypeAprs.MESSAGE) continue
            if (pourMoi(p.source, moi) && !pourMoi(p.destinataire, moi)) continue
            val genre = when {
                pourMoi(p.destinataire, moi) -> Genre.POUR_MOI
                bulletin(p.destinataire) -> Genre.BULLETIN
                else -> continue
            }
            val texte = (p.message ?: "").trim()
            val cle = p.source.uppercase() + ">" + (p.destinataire ?: "").trim().uppercase() + ":" + (p.idMessage?.takeIf { it.isNotBlank() } ?: ("#" + texte))
            val sat = if (StationsAprs.voie(p.trame) == Voie.SATELLITE) StationsAprs.nomSatellite(p) else null
            // A repeat within six hours of the last hearing is the same message; later, the same words are a new one (a daily bulletin).
            val k = dernier[cle]
            val deja = k?.let { vus[it] }
            if (k != null && deja != null && p.quand - deja.vuMs < 6 * 3_600_000L) {
                vus[k] = deja.copy(fois = deja.fois + 1, satellite = deja.satellite ?: sat, vuMs = p.quand)
                continue
            }
            val nk = cle + "@" + p.quand
            dernier[cle] = nk
            vus[nk] = Message(p.quand, p.source, (p.destinataire ?: "").trim(), texte, p.idMessage?.takeIf { it.isNotBlank() }, genre, sat, vuMs = p.quand)
        }
        return vus.values.sortedByDescending { it.t }
    }

    /** `GET /api/aprs/messages`: [moi], the messages ([max] at most). */
    fun json(l: List<Message>, moi: String, max: Int = 200): JSONObject = JSONObject().put("moi", base(moi))
        .put("pourMoi", l.count { it.genre == Genre.POUR_MOI }).put("bulletins", l.count { it.genre == Genre.BULLETIN })
        .put("messages", JSONArray(l.take(max).map { m ->
            JSONObject().put("t", m.t).put("de", m.de).put("a", m.a).put("texte", m.texte.take(200)).put("id", m.id ?: JSONObject.NULL)
                .put("genre", m.genre.cle).put("sat", m.satellite ?: JSONObject.NULL).put("fois", m.fois)
        }))
}
