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
 * The transport stream passed on by UDP to a player on this computer or
 * the local network: where to (checked), and the stream cut into
 * datagrams of whole 188-byte packets (7 a datagram, as players expect).
 */
object SortieUdpTs {
    const val PORT_DEFAUT = 9080
    const val DESTINATION_DEFAUT = "127.0.0.1:" + PORT_DEFAUT

    data class Destination(val ip: String, val port: Int)

    /**
     * « a.b.c.d[:port] » → a destination, or a reason in French. IPv4
     * literals only (no name looked up), and only this computer, the local
     * network or a multicast group: never an address on the internet.
     */
    fun destination(texte: String): Pair<Destination?, String?> {
        val t = texte.trim()
        if (t.isEmpty()) return null to "quelle adresse ? (par exemple " + DESTINATION_DEFAUT + ")"
        val hote = t.substringBefore(':')
        val port = if (':' in t) t.substringAfter(':').toIntOrNull() else PORT_DEFAUT
        if (port == null || port !in 1..65535) return null to "port invalide"
        val o = hote.split('.').map { it.toIntOrNull() }
        if (o.size != 4 || o.any { it == null || it !in 0..255 }) return null to "adresse IPv4 en chiffres attendue (par exemple 127.0.0.1)"
        val a = o.map { it!! }
        if (!locale(a)) return null to "seulement ce PC (127.x), le réseau local (10.x, 172.16-31.x, 192.168.x, 169.254.x) ou un groupe multicast (224-239.x)"
        return Destination(a.joinToString("."), port) to null
    }

    /** This computer, a private network, link-local, or multicast. */
    fun locale(a: List<Int>): Boolean = when {
        a[0] == 127 -> true
        a[0] == 10 -> true
        a[0] == 172 && a[1] in 16..31 -> true
        a[0] == 192 && a[1] == 168 -> true
        a[0] == 169 && a[1] == 254 -> true
        a[0] in 224..239 -> true
        else -> false
    }
}

/**
 * Cuts a byte stream back into datagrams of [parDatagramme] whole TS
 * packets, aligned on the sync byte: a block may start or end anywhere.
 */
class DecoupeTs(private val parDatagramme: Int = 7) {
    private val taille = 188 * parDatagramme
    private val tampon = ByteArray(taille)
    private var n = 0
    /** The packet being filled (bytes so far), -1 while looking for a sync byte. */
    private var dansPaquet = -1

    /** Adds [b]; [envoie] gets each full datagram (the array is reused: copied if kept). */
    fun ajoute(b: ByteArray, longueur: Int = b.size, envoie: (ByteArray, Int) -> Unit) {
        for (i in 0 until longueur) {
            val c = b[i]
            if (dansPaquet < 0) {
                if (c != SYNC) continue
                dansPaquet = 0
            }
            if (dansPaquet == 0 && c != SYNC) { dansPaquet = -1; continue }
            tampon[n++] = c
            dansPaquet++
            if (dansPaquet == 188) {
                dansPaquet = 0
                if (n == taille) { envoie(tampon, n); n = 0 }
            }
        }
    }

    /** What is held, whole packets only (at the end of a stream). */
    fun vide(envoie: (ByteArray, Int) -> Unit) {
        val entiers = n - n % 188
        if (entiers > 0) envoie(tampon, entiers)
        n = 0; dansPaquet = -1
    }

    private companion object { const val SYNC = 0x47.toByte() }
}

/**
 * The stations received lately, newest first: their name, where, at what
 * rate, first and last heard, the best MER. Kept [dureeMs] (7 days), at
 * most [max]; one line a name (a station moving keeps its line).
 */
class StationsEntendues(private val dureeMs: Long = 7 * 24 * 3600_000L, private val max: Int = 40) {
    data class Station(val nom: String, val khz: Long, val srKs: Int, val premierMs: Long, val dernierMs: Long, val merMaxDb: Double?)

    private val l = ArrayList<Station>()

    @Synchronized fun toutes(): List<Station> = l.sortedByDescending { it.dernierMs }

    /** [nom] heard now on [khz] at [srKs]; true when that is a new line (to keep it). */
    @Synchronized
    fun note(nom: String, khz: Long, srKs: Int, merDb: Double?, maintenant: Long): Boolean {
        val n = nom.trim().take(40)
        if (n.isEmpty()) return false
        val i = l.indexOfFirst { it.nom.equals(n, ignoreCase = true) }
        val avant = l.getOrNull(i)
        val mer = listOfNotNull(avant?.merMaxDb, merDb).maxOrNull()
        val s = Station(n, khz, srKs, avant?.premierMs ?: maintenant, maintenant, mer)
        if (i >= 0) l[i] = s else l += s
        l.removeAll { maintenant - it.dernierMs >= dureeMs }
        if (l.size > max) { l.sortBy { it.dernierMs }; while (l.size > max) l.removeAt(0) }
        return avant == null || avant.khz != khz || avant.srKs != srKs
    }

    @Synchronized
    fun json(): JSONArray = JSONArray(toutes().map {
        JSONObject().put("nom", it.nom).put("khz", it.khz).put("sr", it.srKs).put("premier", it.premierMs).put("dernier", it.dernierMs)
            .put("mer", it.merMaxDb ?: JSONObject.NULL)
    })

    /** As kept. */
    fun texte(): String = json().toString()

    /** Taken back from [texte], what is still fresh. */
    @Synchronized
    fun reprends(t: String?, maintenant: Long) {
        l.clear()
        val a = runCatching { JSONArray(t ?: return) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = Station(o.optString("nom"), o.optLong("khz"), o.optInt("sr"), o.optLong("premier"), o.optLong("dernier"),
                if (o.isNull("mer")) null else o.optDouble("mer"))
            if (s.nom.isNotBlank() && maintenant - s.dernierMs < dureeMs) l += s
        }
    }
}
