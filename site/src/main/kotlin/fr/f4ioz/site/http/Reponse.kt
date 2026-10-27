/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.http

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a route answers: a status, a type, bytes or a file on disk (served
 * by parts when the browser asks, a video it seeks in), headers. [csp] is
 * the page's own Content-Security-Policy kind (see `Securite`).
 */
class Reponse(
    val statut: Int,
    val type: String,
    val corps: ByteArray = ByteArray(0),
    val fichier: File? = null,
) {
    val entetes = LinkedHashMap<String, String>()

    /** The page's CSP: [Csp.CONTENU] (nonces only), [Csp.APPLI] (SatMe's tabs, inline handlers), [Csp.AUCUN] (a sandboxed report). */
    var csp: Csp = Csp.CONTENU

    /** Frames from YouTube (the ISS live of Orbites). */
    var youtube: Boolean = false

    fun entete(k: String, v: String): Reponse { entetes[k] = v; return this }
    fun cache(s: String): Reponse = entete("Cache-Control", s)

    enum class Csp { CONTENU, APPLI, AUCUN }

    companion object {
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        const val TEXTE = "text/plain; charset=utf-8"

        fun html(s: String, statut: Int = 200) = Reponse(statut, HTML, s.toByteArray(Charsets.UTF_8))
        fun json(o: JSONObject, statut: Int = 200) = Reponse(statut, JSON, o.toString().toByteArray(Charsets.UTF_8)).cache("no-store")
        fun json(a: JSONArray, statut: Int = 200) = Reponse(statut, JSON, a.toString().toByteArray(Charsets.UTF_8)).cache("no-store")
        fun texte(s: String, statut: Int = 200, type: String = TEXTE) = Reponse(statut, type, s.toByteArray(Charsets.UTF_8))
        fun octets(b: ByteArray, type: String) = Reponse(200, type, b)
        fun fichier(f: File, type: String) = Reponse(200, type, fichier = f)

        /** A redirection; [url] is the site's own path (never taken from the request as it is). */
        fun redirige(url: String, statut: Int = 302) = Reponse(statut, TEXTE, ByteArray(0)).entete("Location", url).cache("no-store")

        /** An API's error: `{"ok": false, "erreur": …}`. */
        fun erreur(statut: Int, message: String) = json(JSONObject().put("ok", false).put("erreur", message), statut)
    }
}
