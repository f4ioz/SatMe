/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.fazecast.jSerialComm.SerialPort
import fr.f4ioz.satcombo.datv.SequenceurPtt
import fr.f4ioz.satcombo.datv.SortiePtt
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * The DATV transmission's PTT/sequencer output (Émission › Sortie PTT) and
 * its settings: **none by default**; a serial port's RTS or DTR line (a
 * USB-serial adapter driving a relay through a transistor); or an HTTP GET
 * to a box on the network (the antenna and preamplifier box to come, an
 * ESP32: one address to go active, one to go back at rest).
 *
 * The order around the RF is [SequenceurPtt]'s: the output active, the
 * delay before, the RF; the RF off, the delay after, the output at rest.
 * Nothing here starts a transmission; [teste] pulses the output alone
 * (one second, no RF), never while transmitting.
 */
class PttDatvPc(
    private val stockage: Stockage,
    /** The output for a setting (the tests give theirs). */
    private val fabrique: (Reglage) -> SortiePtt = { cree(it) },
    attente: (Long) -> Unit = { Thread.sleep(it) },
    differe: ((() -> Unit) -> Unit)? = null,
) {
    enum class Type(val cle: String, val libelle: String) {
        AUCUNE("aucune", "aucune"), SERIE("serie", "ligne d’un port série"), HTTP("http", "requête HTTP (boîtier réseau)");
        companion object { fun de(c: String?) = entries.firstOrNull { it.cle == c } ?: AUCUNE }
    }

    data class Reglage(
        val type: Type = Type.AUCUNE,
        /** The port's path: /dev/ttyUSB0, COM3. */
        val port: String = "",
        /** "rts" or "dtr". */
        val ligne: String = "rts",
        /** Active low (the line raised at rest). */
        val inverse: Boolean = false,
        val urlActive: String = "",
        val urlRepos: String = "",
        val avantMs: Long = DELAI_DEFAUT_MS,
        val apresMs: Long = DELAI_DEFAUT_MS,
    )

    var reglage: Reglage
        get() = Reglage(Type.de(stockage.getString("datv_ptt_type", "aucune")), stockage.getString("datv_ptt_port", "") ?: "",
            if (stockage.getString("datv_ptt_ligne", "rts") == "dtr") "dtr" else "rts", stockage.getBoolean("datv_ptt_inverse", false),
            stockage.getString("datv_ptt_url_on", "") ?: "", stockage.getString("datv_ptt_url_off", "") ?: "",
            SequenceurPtt.borne(stockage.getLong("datv_ptt_avant_ms", DELAI_DEFAUT_MS)), SequenceurPtt.borne(stockage.getLong("datv_ptt_apres_ms", DELAI_DEFAUT_MS)))
        private set(r) {
            stockage.edit().putString("datv_ptt_type", r.type.cle).putString("datv_ptt_port", r.port.trim()).putString("datv_ptt_ligne", r.ligne)
                .putBoolean("datv_ptt_inverse", r.inverse).putString("datv_ptt_url_on", r.urlActive.trim()).putString("datv_ptt_url_off", r.urlRepos.trim())
                .putLong("datv_ptt_avant_ms", SequenceurPtt.borne(r.avantMs)).putLong("datv_ptt_apres_ms", SequenceurPtt.borne(r.apresMs)).apply()
        }

    /** The output for the setting now, made again when the setting changes (the old one let go). */
    private var sortie: Pair<Reglage, SortiePtt>? = null

    @Synchronized
    private fun sortieActuelle(): SortiePtt {
        val r = reglage
        sortie?.let { (rr, s) -> if (rr == r) return s; runCatching { s.ferme() } }
        val s = fabrique(r)
        sortie = r to s
        return s
    }

    val sequenceur = SequenceurPtt({ sortieActuelle() }, { reglage.avantMs }, { reglage.apresMs }, attente,
        differe ?: { f -> kotlin.concurrent.thread(isDaemon = true, name = "satme-ptt-repos") { f() }; Unit })

    /**
     * The page's settings ([q]: what it sent, the rest kept); refused while
     * the output is active or [emet]. Null once kept, else why not.
     */
    fun regle(q: Map<String, String>, emet: Boolean): String? {
        if (emet || sequenceur.actif) return "sortie PTT : arrêter l’émission d’abord"
        val r = reglage
        val n = r.copy(type = q["type"]?.let { Type.de(it) } ?: r.type, port = q["port"] ?: r.port,
            ligne = q["ligne"]?.let { if (it == "dtr") "dtr" else "rts" } ?: r.ligne, inverse = q["inverse"]?.let { it == "1" } ?: r.inverse,
            urlActive = q["urlActive"] ?: r.urlActive, urlRepos = q["urlRepos"] ?: r.urlRepos,
            avantMs = q["avant"]?.trim()?.toLongOrNull() ?: r.avantMs, apresMs = q["apres"]?.trim()?.toLongOrNull() ?: r.apresMs)
        refus(n)?.let { return it }
        reglage = n
        return null
    }

    /** One second active, then at rest: the wiring checked, no RF. Null once done, else why not. */
    fun teste(emet: Boolean): String? {
        ModeDemo.refus("la sortie PTT")?.let { return it }
        if (emet || sequenceur.actif) return "pas pendant une émission"
        val s = sortieActuelle()
        if (s === SortiePtt.Aucune) return "aucune sortie choisie"
        s.pose(true)?.let { runCatching { s.pose(false) }; return it }
        Thread.sleep(1000)
        return s.pose(false)
    }

    /** SatMe-PC closing: at rest, the port let go. */
    @Synchronized
    fun ferme() {
        sequenceur.relacheMaintenant()
        sortie?.second?.let { runCatching { it.ferme() } }
        sortie = null
    }

    fun json(emet: Boolean): JSONObject {
        val r = reglage
        return JSONObject().put("ok", true).put("type", r.type.cle).put("port", r.port).put("ligne", r.ligne).put("inverse", r.inverse)
            .put("urlActive", r.urlActive).put("urlRepos", r.urlRepos).put("avant", r.avantMs).put("apres", r.apresMs)
            .put("delaiMax", SequenceurPtt.DELAI_MAX_MS).put("actif", sequenceur.actif).put("erreur", sequenceur.erreur)
            .put("modifiable", !emet && !sequenceur.actif)
            .put("types", JSONArray(Type.entries.map { JSONObject().put("cle", it.cle).put("libelle", it.libelle) }))
            .put("ports", JSONArray(if (ModeDemo.actif) emptyList() else runCatching { SerialPort.getCommPorts().map { it.systemPortPath }.sorted() }.getOrDefault(emptyList())))
    }

    companion object {
        const val DELAI_DEFAUT_MS = 200L

        /** Why [r] cannot be kept, or null. */
        fun refus(r: Reglage): String? = when (r.type) {
            Type.AUCUNE -> null
            Type.SERIE -> if (r.port.isBlank()) "sortie PTT : choisir le port série" else null
            Type.HTTP -> listOf(r.urlActive, r.urlRepos).firstNotNullOfOrNull { u ->
                if (u.isBlank()) "sortie PTT : donner les deux adresses (active, repos)"
                else if (!u.trim().let { it.startsWith("http://") || it.startsWith("https://") }) "sortie PTT : une adresse http:// ou https:// ($u)"
                else null
            }
        }

        fun cree(r: Reglage): SortiePtt = when (r.type) {
            Type.AUCUNE -> SortiePtt.Aucune
            Type.SERIE -> SortiePttSerie(r.port, r.ligne == "dtr", r.inverse)
            Type.HTTP -> SortiePttHttp(r.urlActive, r.urlRepos)
        }
    }
}

/**
 * RTS or DTR of a serial port held high while active ([inverse]: low).
 * The port opens at the first use and stays open (opening raises the lines
 * briefly on Linux: never at random, only once chosen); closed, its lines
 * drop. Not in an instance started without serial ports (tests, demos).
 */
class SortiePttSerie(private val chemin: String, private val dtr: Boolean, private val inverse: Boolean) : SortiePtt {
    override val nom = chemin + " " + (if (dtr) "DTR" else "RTS") + (if (inverse) " (active à 0)" else "")
    private var port: SerialPort? = null

    @Synchronized
    override fun pose(actif: Boolean): String? {
        if (PortsSeriePc.sansSerie() || ModeDemo.actif) return "ports série désactivés pour cette instance"
        val p = port ?: run {
            val q = runCatching { SerialPort.getCommPort(chemin) }.getOrNull() ?: return "port introuvable : $chemin"
            // Before opening: jSerialComm applies them as the port opens (at rest).
            val repos = inverse
            if (repos) { q.setRTS(); q.setDTR() } else { q.clearRTS(); q.clearDTR() }
            if (!q.openPort()) return "port $chemin : ouverture refusée (droits : groupe dialout ?)"
            q.also { port = it }
        }
        val haut = actif != inverse
        val ok = if (dtr) (if (haut) p.setDTR() else p.clearDTR()) else (if (haut) p.setRTS() else p.clearRTS())
        return if (ok) null else "port $chemin : ligne non commandée"
    }

    @Synchronized
    override fun ferme() {
        val p = port ?: return
        runCatching { pose(false) }
        runCatching { p.closePort() }
        port = null
    }
}

/**
 * An HTTP GET to [urlActive] or [urlRepos] (2 s at most), a 2xx answer
 * expected: the network box's own way. An MQTT box later takes its own
 * [SortiePtt] (a topic, « on » / « off »), the sequencer unchanged.
 */
class SortiePttHttp(private val urlActive: String, private val urlRepos: String, private val delaiMs: Int = 2000) : SortiePtt {
    override val nom = "HTTP " + runCatching { URI(urlActive.trim()).host }.getOrNull().orEmpty()

    override fun pose(actif: Boolean): String? = runCatching {
        val u = URI((if (actif) urlActive else urlRepos).trim()).toURL()
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = delaiMs; c.readTimeout = delaiMs; c.requestMethod = "GET"; c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            runCatching { c.inputStream.use { it.readNBytes(4096) } }
            if (code in 200..299) null else "réponse $code"
        } finally { c.disconnect() }
    }.getOrElse { "injoignable (" + (it.message ?: it.javaClass.simpleName) + ")" }
}
