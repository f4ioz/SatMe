/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue

/**
 * This SatMe-PC as a relay: the USB knob plugged in here tunes another
 * station (its address, `https://satme.example.org` behind its proxy, or
 * `http://192.168.0.65:8090` at home). Each key of the knob is sent as it
 * comes to the station's `api/molette/touches`, which applies it as its own
 * knob would (its step, its target, its learned keys).
 *
 * The station's password is asked once, sent to its login page and never
 * kept: only the session it opens (its cookie) is, in [fichier], readable by
 * this user alone. That session ended (the station's « log out everywhere »,
 * or its month gone by), the relay says so and waits for the password again.
 */
class RelaisMolettePc(
    private val fichier: File,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    @Volatile var adresse: String = ""; private set
    @Volatile private var jeton: String = ""
    /** « coupe » (no station), « connecte », « reconnexion » (the session ended), « erreur » (the station out of reach). */
    @Volatile var etat: String = "coupe"; private set
    @Volatile var message: String = ""; private set
    @Volatile var envoyes: Long = 0; private set
    @Volatile var dernierMs: Long = 0; private set

    private val file = LinkedBlockingQueue<Pair<Int, Int>>(500)

    init {
        runCatching {
            val p = Properties().apply { fichier.inputStream().use { load(it) } }
            adresse = p.getProperty("adresse", "").orEmpty(); jeton = p.getProperty("session", "").orEmpty()
            etat = if (adresse.isNotBlank() && jeton.isNotBlank()) "connecte" else "coupe"
        }
        Thread({ while (true) runCatching { envoieFile() } }, "satme-relais-molette").apply { isDaemon = true; start() }
    }

    /** Relaying: the knob's keys go to the station rather than tuning this PC. */
    val actif: Boolean get() = adresse.isNotBlank() && jeton.isNotBlank()

    /**
     * Opens a session on the station at [adr] with its password [mdp] (its login page, as a browser would).
     * Null when done, else why not (in French, for the page).
     */
    fun connecte(adr: String, mdp: String): String? {
        val a = normalise(adr) ?: return "adresse invalide : http://… ou https://…"
        if (mdp.isBlank()) return "le mot de passe de la station manque"
        val corps = "mdp=" + URLEncoder.encode(mdp, "UTF-8") + "&suite=" + URLEncoder.encode("/", "UTF-8")
        val r = try {
            client.send(HttpRequest.newBuilder(URI.create("$a/connexion")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(corps)).build(),
                HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) { return "station injoignable : " + (e.message ?: e.javaClass.simpleName) }
        val cookie = r.headers().allValues("set-cookie").firstNotNullOfOrNull { lisCookie(it) }
        return when {
            cookie != null -> {
                adresse = a; jeton = cookie; etat = "connecte"; message = ""; garde()
                // The station must have the knob's route (a recent SatMe-PC).
                if (!sonde()) "connecté, mais cette station ne reçoit pas de molette (SatMe-PC à mettre à jour)" else null
            }
            r.statusCode() == 401 -> "mot de passe refusé (ou trop d'essais : attendre un peu)"
            r.statusCode() == 403 -> "l'accès à distance n'est pas ouvert sur cette station (Réglages › Accès à distance)"
            r.statusCode() in 300..399 -> "cette station n'a pas demandé de mot de passe : est-ce bien SatMe-PC, à distance ?"
            else -> "réponse inattendue de la station (${r.statusCode()})"
        }
    }

    /** Stops relaying, and closes the session on the station. */
    fun deconnecte() {
        val a = adresse; val j = jeton
        adresse = ""; jeton = ""; etat = "coupe"; message = ""; file.clear(); garde()
        if (a.isNotBlank() && j.isNotBlank()) Thread {
            runCatching { client.send(requete("$a/deconnexion", j).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()) }
        }.apply { isDaemon = true; start() }
    }

    /** A key of the knob (Linux code, value 1 pressed, 2 repeated, 0 released): sent to the station. False when not relaying. */
    fun envoie(code: Int, valeur: Int): Boolean {
        if (!actif) return false
        if (valeur != 0) file.offer(code to valeur)
        return true
    }

    fun json(): JSONObject = JSONObject().put("adresse", adresse).put("etat", etat).put("message", message)
        .put("envoyes", envoyes).put("dernier", dernierMs)

    /** The keys waiting, sent together (a quick turn makes one request). */
    private fun envoieFile() {
        val premier = file.take()
        val l = mutableListOf(premier); file.drainTo(l, 49)
        val a = adresse; val j = jeton
        if (a.isBlank() || j.isBlank()) return
        val q = l.joinToString(",") { "${it.first}:${it.second}" }
        val r = try {
            client.send(requete("$a/api/molette/touches?l=" + URLEncoder.encode(q, "UTF-8"), j).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) { etat = "erreur"; message = "station injoignable : " + (e.message ?: e.javaClass.simpleName); return }
        when (r.statusCode()) {
            200 -> { etat = "connecte"; message = runCatching { JSONObject(r.body()).optString("geste") }.getOrDefault(""); envoyes += l.size; dernierMs = horloge() }
            401 -> { jeton = ""; etat = "reconnexion"; message = "la session sur la station a pris fin : redonner son mot de passe"; file.clear(); garde() }
            else -> { etat = "erreur"; message = "la station a répondu ${r.statusCode()}" }
        }
    }

    private fun sonde(): Boolean = runCatching {
        client.send(requete("$adresse/api/molette", jeton).GET().build(), HttpResponse.BodyHandlers.ofString()).let { r ->
            r.statusCode() == 200 && !JSONObject(r.body()).optBoolean("absent", false)
        }
    }.getOrDefault(false)

    private fun requete(url: String, j: String) = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        .header("Cookie", AccesDistantPc.COOKIE + "=" + j)

    private fun garde() {
        runCatching {
            fichier.parentFile?.mkdirs()
            val tmp = File(fichier.path + ".tmp")
            tmp.writeText("")
            SessionsPc.prive(tmp)
            Properties().apply { setProperty("adresse", adresse); setProperty("session", jeton) }
                .let { p -> tmp.outputStream().use { p.store(it, "SatMe-PC : la station que la molette accorde (sa session, jamais son mot de passe)") } }
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }

    companion object {
        /** `http(s)://host[:port]` without the trailing slash or path; null when not an address. */
        fun normalise(s: String): String? {
            val t = s.trim().trimEnd('/')
            val u = runCatching { URI(t) }.getOrNull() ?: return null
            if (u.scheme !in setOf("http", "https") || u.host.isNullOrBlank()) return null
            return u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "") + (u.path?.trimEnd('/').orEmpty())
        }

        /** The session's value in a `Set-Cookie` line of the station, else null. */
        fun lisCookie(ligne: String): String? {
            val v = ligne.substringBefore(';').trim()
            if (!v.startsWith(AccesDistantPc.COOKIE + "=")) return null
            return v.substringAfter('=').takeIf { it.isNotBlank() && it.length <= 100 }
        }
    }
}
