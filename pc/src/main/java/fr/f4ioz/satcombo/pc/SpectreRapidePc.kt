/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * « Spectre rapide » (Publication web › En direct, off by default): QO-100's narrow band sent to the owner's site
 * [cadence] lines a second (1 to 20) over **one connection held open** — a WebSocket to the site's own address
 * (`wss://<site>/ws/direct/station`, the publication token in its handshake), each line the same JSON as a
 * `direct-spectre` one ([DirectSitePc.ligneSpectre], [points] bins). The site fans it out to its visitors.
 *
 * SatMe-PC opens it, never the site; it is opened again after a cut (1 s, 2 s, 5 s, 10 s, 30 s, 1 min), not for
 * 10 minutes after a token refused, 2 minutes after a refusal. A site without the WebSocket (404, nothing listening)
 * gets the lines by HTTP instead, gathered a second at a time (`direct-spectre` with `lignes`), and the WebSocket is
 * tried again every 5 minutes. Nothing is kept: a line that cannot go is dropped.
 *
 * Only what the SDR receives goes: frequencies, levels — never an address or a setting.
 */
class SpectreRapidePc(
    private val stockage: Stockage,
    private val site: SitePc,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Opens the stream to [url] with [entetes]; throws [ClientWebSocketPc.Refus] or an IOException when it cannot. */
    private val ouvre: (String, Map<String, String>) -> Canal = { u, e -> Canal.ws(u, e, site.agentHttp) },
) {
    /** The open stream: lines out, pings, closed. */
    interface Canal {
        fun envoie(texte: String)
        fun ping()
        fun ferme()
        val ouvert: Boolean

        companion object {
            /** A real WebSocket ([ClientWebSocketPc]), its incoming messages read (and pings answered) by a thread of its own. */
            fun ws(url: String, entetes: Map<String, String>, agent: String): Canal {
                val u = URI(url)
                val tls = u.scheme == "wss"
                val port = if (u.port > 0) u.port else if (tls) 443 else 80
                val c = ClientWebSocketPc.ouvre(u.host, port, u.rawPath + (u.rawQuery?.let { "?$it" } ?: ""), "", agent, tls,
                    delaiLectureMs = 90_000, max = 64 * 1024, entetes = entetes)
                Thread({ while (c.recoit() != null) { /* the site's words (hello, counts): not needed */ } }, "satme-spectre-rapide-lecture")
                    .apply { isDaemon = true; start() }
                return object : Canal {
                    override fun envoie(texte: String) = c.envoieTexte(texte)
                    override fun ping() = c.ping()
                    override fun ferme() = c.fermePoliment()
                    override val ouvert: Boolean get() = c.ouvert
                }
            }
        }
    }

    // Set by Main.
    @Volatile var panorama: () -> DirectSitePc.Panorama? = { null }
    /** The SDR's panorama count, a new line only when it changed. */
    @Volatile var trames: () -> Long = { 0L }
    /** Keeps the SDR's panorama worked out every [Long] ms. */
    @Volatile var demandePanorama: (Long) -> Unit = {}

    var on: Boolean
        get() = stockage.getBoolean(CLE, false)
        set(v) { stockage.edit().putBoolean(CLE, v).apply(); remetAZero() }
    var cadence: Int
        get() = stockage.getInt("${CLE}_cadence", CADENCE_DEFAUT).let { c -> CADENCES.minByOrNull { Math.abs(it - c) }!! }
        set(v) { stockage.edit().putInt("${CLE}_cadence", CADENCES.minByOrNull { Math.abs(it - v) }!!).apply() }
    var points: Int
        get() = stockage.getInt("${CLE}_points", POINTS_DEFAUT).let { p -> if (p in POINTS) p else POINTS_DEFAUT }
        set(v) { if (v in POINTS) stockage.edit().putInt("${CLE}_points", v).apply() }

    // ------------------------------------------------------------------ state

    @Volatile private var canal: Canal? = null
    @Volatile private var prochainEssaiMs = 0L
    @Volatile private var essais = 0
    /** The WebSocket refused or absent: the lines go by HTTP until [wsDeNouveauMs]. */
    @Volatile private var parHttp = false
    /** The WebSocket opened at least once since switched on: a failure then is a cut, not a site without it. */
    @Volatile private var dejaOuvert = false
    @Volatile private var wsDeNouveauMs = 0L
    @Volatile var erreur = ""; private set
    @Volatile private var trameVue = -1L
    @Volatile private var prochaineLigneMs = 0L
    @Volatile private var dernierPingMs = 0L
    @Volatile private var dernierLotMs = 0L
    private val lot = ArrayList<JSONObject>()
    /** Lines sent, and their times over the last seconds (the rate shown). */
    @Volatile var envoyees = 0L; private set
    private val recents = ArrayDeque<Long>()

    private fun remetAZero() { ferme(); dejaOuvert = false; essais = 0; prochainEssaiMs = 0L; parHttp = false; wsDeNouveauMs = 0L; erreur = ""; synchronized(lot) { lot.clear() } }

    private fun ferme() { canal?.let { c -> runCatching { c.ferme() } }; canal = null }

    // ------------------------------------------------------------------ the loop

    @Volatile private var fil: Thread? = null

    fun demarre() {
        if (fil != null) return
        fil = Thread({
            while (true) {
                val attente = runCatching { tic() }.getOrDefault(500L)
                Thread.sleep(attente.coerceIn(5L, 1000L))
            }
        }, "satme-spectre-rapide").apply { isDaemon = true; start() }
    }

    /** One step: due lines sent; how long to wait before the next (ms). Nothing at all while off or the site not set. */
    fun tic(): Long {
        val t = horloge()
        if (!on || !site.fluxPossible) {
            if (canal != null) ferme()
            return 500L
        }
        val periode = periodeMs(cadence)
        runCatching { demandePanorama(periode) }
        connecte(t)
        // Every 20 s a ping, so no proxy closes a quiet connection.
        canal?.let { c -> if (t - dernierPingMs >= PING_MS) { dernierPingMs = t; runCatching { c.ping() }.onFailure { coupe(t, "coupé : " + (it.message ?: "")) } } }
        if (parHttp && t - dernierLotMs >= 1000L) { dernierLotMs = t; envoieLot(t) }
        if (t < prochaineLigneMs) return prochaineLigneMs - t
        // The next one on the beat (late ones not made up: no burst).
        prochaineLigneMs = prochaine(prochaineLigneMs, t, periode)
        val n = runCatching { trames() }.getOrDefault(0L)
        if (n == trameVue) return prochaineLigneMs - t
        val pan = runCatching { panorama() }.getOrNull() ?: return prochaineLigneMs - t
        val l = DirectSitePc.ligneSpectre(pan.db, pan.centreHz, pan.largeurHz, points) ?: return prochaineLigneMs - t
        trameVue = n
        l.put("t", t)
        val c = canal
        when {
            c != null -> runCatching { c.envoie(l.toString()); compte(t) }.onFailure { coupe(t, "coupé : " + (it.message ?: "")) }
            parHttp -> synchronized(lot) { lot += l; while (lot.size > LOT_MAX) lot.removeAt(0) }
        }
        return prochaineLigneMs - t
    }

    private fun compte(t: Long) {
        envoyees++
        synchronized(recents) { recents.addLast(t); while (recents.isNotEmpty() && t - recents.first() > 5000L) recents.removeFirst() }
        erreur = ""
    }

    /** Lines per second over the last 5 s. */
    fun debitMesure(t: Long = horloge()): Double = synchronized(recents) { recents.count { t - it <= 5000L } / 5.0 }

    private fun coupe(t: Long, raison: String) {
        ferme(); erreur = raison
        prochainEssaiMs = t + attenteMs(++essais)
    }

    /** The WebSocket opened when due; a site without it gets the lines by HTTP for a while. */
    private fun connecte(t: Long) {
        if (canal?.ouvert == true) return
        if (canal != null) coupe(t, "connexion perdue")
        if (parHttp && t < wsDeNouveauMs) return
        if (t < prochainEssaiMs) return
        val url = urlFlux(site.url) ?: run { erreur = "adresse du site non réglée"; prochainEssaiMs = t + 60_000L; return }
        try {
            canal = ouvre(url, site.entetesFlux())
            essais = 0; parHttp = false; erreur = ""; dernierPingMs = t; dejaOuvert = true
        } catch (e: ClientWebSocketPc.Refus) {
            when (e.code) {
                401 -> { erreur = "jeton refusé par le site"; prochainEssaiMs = t + 10 * 60_000L }
                403 -> { erreur = "refusé par le site (Spectre rapide coupé sur le site ?)"; prochainEssaiMs = t + 2 * 60_000L }
                // No WebSocket there (an older site, or the proxy not set for /ws/direct/): by HTTP meanwhile.
                else -> versHttp(t, "WebSocket absent (HTTP ${e.code}) : envoi par HTTP")
            }
        } catch (e: Exception) {
            essais++
            // Never opened on this site (nothing listening there): by HTTP; opened before, a cut: tried again.
            if (essais >= 3 && !dejaOuvert) versHttp(t, "WebSocket injoignable : envoi par HTTP (" + (e.message ?: e.javaClass.simpleName) + ")")
            else { erreur = "connexion impossible : " + (e.message ?: e.javaClass.simpleName); prochainEssaiMs = t + attenteMs(essais) }
        }
    }

    private fun versHttp(t: Long, raison: String) {
        parHttp = true; wsDeNouveauMs = t + WS_DE_NOUVEAU_MS; erreur = raison; essais = 0; prochainEssaiMs = 0L
    }

    /** The lines gathered this second, in one request (`direct-spectre` with `lignes`). */
    private fun envoieLot(t: Long) {
        val l = synchronized(lot) { ArrayList(lot).also { lot.clear() } }
        if (l.isEmpty()) return
        val r = runCatching { site.envoieDirect("direct-spectre", JSONObject().put("lignes", JSONArray(l))) }.getOrNull() ?: return
        if (r.first in 200..299) { l.forEach { _ -> compte(t) }; erreur = erreur.takeIf { it.startsWith("WebSocket") }.orEmpty() }
        else erreur = "HTTP ${r.first} (envoi par HTTP)"
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject = JSONObject().put("on", on).put("cadence", cadence).put("points", points)
        .put("cadences", JSONArray(CADENCES)).put("pointsPossibles", JSONArray(POINTS))
        .put("connecte", canal?.ouvert == true).put("mode", if (canal?.ouvert == true) "ws" else if (parHttp) "http" else "")
        .put("erreur", if (on) erreur else "").put("lignesS", Math.round(debitMesure() * 10) / 10.0).put("envoyees", envoyees)
        .put("debitKbps", Math.round(debitKbps(cadence, points)))

    /** `api/site/direct`: `rapide=0|1`, `rapide_cadence`, `rapide_points`. */
    fun regle(p: (String) -> String) {
        p("rapide_cadence").toIntOrNull()?.let { cadence = it }
        p("rapide_points").toIntOrNull()?.let { points = it }
        when (p("rapide")) { "1" -> on = true; "0" -> on = false; else -> if (p("rapide_cadence").isNotEmpty() || p("rapide_points").isNotEmpty()) remetAZero() }
    }

    companion object {
        const val CLE = "site_direct_rapide"
        val CADENCES = listOf(1, 2, 5, 10, 20)
        const val CADENCE_DEFAUT = 5
        val POINTS = listOf(512, 1024, 2048)
        const val POINTS_DEFAUT = 1024
        const val PING_MS = 20_000L
        /** A second's lines at most in one HTTP batch (the site takes 25). */
        const val LOT_MAX = 25
        const val WS_DE_NOUVEAU_MS = 5 * 60_000L
        /** The stream's path on the site. */
        const val CHEMIN = "/ws/direct/station"

        /** The time between two lines at [cadence] lines a second. */
        fun periodeMs(cadence: Int): Long = 1000L / cadence.coerceIn(1, 20)

        /** The next line's time: one [periode] after [prevue], or from [t] when that is already past (no catching up). */
        fun prochaine(prevue: Long, t: Long, periode: Long): Long { val n = prevue + periode; return if (prevue == 0L || n <= t) t + periode else n }

        /** The wait before the [essais]-th try again: 1 s, 2 s, 5 s, 10 s, 30 s, then a minute. */
        fun attenteMs(essais: Int): Long = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)[(essais - 1).coerceIn(0, 5)]

        /** The stream's address from the publication address (`https://h/api/publication/{type}` → `wss://h/ws/direct/station`); null when not web. */
        fun urlFlux(publication: String): String? {
            val u = runCatching { URI(publication.replace(SitePc.TYPE, "x")) }.getOrNull() ?: return null
            val schema = when (u.scheme?.lowercase()) { "https" -> "wss"; "http" -> "ws"; else -> return null }
            val h = u.host ?: return null
            val hote = if (':' in h && !h.startsWith("[")) "[$h]" else h
            return "$schema://$hote" + (if (u.port > 0) ":" + u.port else "") + CHEMIN
        }

        /** The upload's estimate: [cadence] lines of [points] bins as base64 JSON (≈ 1.37 bytes a bin, 90 bytes around), kbit/s. */
        fun debitKbps(cadence: Int, points: Int): Double = cadence * (points * 4.0 / 3.0 + 90.0) * 8.0 / 1000.0
    }
}
