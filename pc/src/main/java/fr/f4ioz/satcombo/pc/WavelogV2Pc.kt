/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.AgentHttp
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Wavelog's APIv2 (release 3.1.0 and later), for one thing only: changing a
 * contact already in the online log ([fr.f4ioz.satcombo.domain.ModifEnvoi]).
 *
 * A `wl2_` token in `Authorization: Bearer` (the v1 keys are refused there),
 * scopes `qso:read` and `qso:write`. `GET api/v2/qso` lists the contacts
 * (filters callsign, qso_since / qso_until as YYYY-MM-DD), `PATCH
 * api/v2/qso/{id}` changes the fields sent. Never a POST: creating is v1's
 * upload, and it is never used again on a contact already sent.
 *
 * java.net.http, not HttpURLConnection: the latter refuses the PATCH method.
 */
object WavelogV2Pc {

    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }

    /** A status and a body; status 0 when nothing came back. */
    data class Reponse(val code: Int, val corps: String)

    private fun envoie(base: String, chemin: String, jeton: String, methode: String, corps: String?): Reponse {
        val racine = base.trim().trimEnd('/')
        var derniere = Reponse(0, "aucune réponse")
        // index.php first: CodeIgniter's own form, whatever the URL rewriting; then the short one.
        for (f in listOf("index.php/", "")) {
            val r = runCatching {
                val b = HttpRequest.newBuilder(URI.create("$racine/$f$chemin")).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + jeton.trim()).header("Accept", "application/json")
                    .header("User-Agent", AgentHttp.USER_AGENT)
                if (corps != null) b.header("Content-Type", "application/json").method(methode, HttpRequest.BodyPublishers.ofString(corps))
                else b.method(methode, HttpRequest.BodyPublishers.noBody())
                val x = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
                Reponse(x.statusCode(), x.body() ?: "")
            }.getOrElse { Reponse(0, "échec : " + it.javaClass.simpleName) }
            if (r.code != 404 || r.corps.contains("\"error\"")) return r
            derniere = r
        }
        return derniere
    }

    /** The contacts of [call] between two days (YYYY-MM-DD), as `GET api/v2/qso` gives them. */
    fun cherche(base: String, jeton: String, call: String, depuis: String, jusque: String): Reponse {
        fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return envoie(base, "api/v2/qso?callsign=" + e(call.trim().uppercase()) + "&qso_since=" + e(depuis) + "&qso_until=" + e(jusque) + "&per_page=200",
            jeton, "GET", null)
    }

    /** `PATCH api/v2/qso/{id}` with [corps], the changed fields. */
    fun corrige(base: String, jeton: String, id: Long, corps: String): Reponse =
        envoie(base, "api/v2/qso/$id", jeton, "PATCH", corps)
}
