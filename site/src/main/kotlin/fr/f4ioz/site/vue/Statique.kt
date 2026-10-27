/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.vue

import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.securite.Jetons
import java.util.concurrent.ConcurrentHashMap

/**
 * The site's own files (styles, scripts, fonts, pictures), bundled in the
 * program under `fr/f4ioz/site/static/`, plus those made at startup (the
 * SatMe tabs' styles). Each link carries a version (the file's hash): kept a
 * year by the browsers, asked again only when it changes.
 */
class Statique {

    private class Fichier(val octets: ByteArray, val type: String, val version: String)

    private val fichiers = ConcurrentHashMap<String, Fichier>()

    /** A file made by the program (`css/orbites.css`…), served like the others. */
    fun ajoute(chemin: String, octets: ByteArray, type: String = typeDe(chemin)) {
        fichiers[chemin] = Fichier(octets, type, Jetons.sha256Hex(octets).take(10))
    }

    private fun fichier(chemin: String): Fichier? {
        fichiers[chemin]?.let { return it }
        if (!SUR.matches(chemin)) return null
        val b = Statique::class.java.getResourceAsStream("/fr/f4ioz/site/static/$chemin")?.use { it.readBytes() } ?: return null
        return Fichier(b, typeDe(chemin), Jetons.sha256Hex(b).take(10)).also { fichiers[chemin] = it }
    }

    /** `/static/<chemin>?v=<version>`. */
    fun url(chemin: String): String = "/static/$chemin" + (fichier(chemin)?.let { "?v=" + it.version } ?: "")

    /** A path under /static given as `css/x.css`, or any other path of the site as it is (`/api/lib/three.js`). */
    fun urlOuTel(s: String): String = if (s.startsWith("/")) s else url(s)

    fun sert(r: Requete): Reponse? {
        val f = fichier(r.variable("reste")) ?: return null
        val rep = Reponse.octets(f.octets, f.type)
        rep.cache(if (r.param("v") == f.version) "public, max-age=31536000, immutable" else "public, max-age=3600")
        rep.entete("ETag", "\"" + f.version + "\"")
        return rep
    }

    companion object {
        private val SUR = Regex("^[a-z0-9_-]+(/[A-Za-z0-9_.-]+)*\\.[a-z0-9]+$")

        fun typeDe(chemin: String): String = when (chemin.substringAfterLast('.').lowercase()) {
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "txt" -> "text/plain; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
}
