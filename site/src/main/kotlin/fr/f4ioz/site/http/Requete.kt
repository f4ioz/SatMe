/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.http

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest

/** A body larger than its route allows: answered 413, the rest never read. */
class TropGros(val max: Long) : RuntimeException("corps trop gros (max $max octets)")

/**
 * One request as the site's routes see it: the method, the path (decoded,
 * checked), its parameters, its headers (names in lower case), who asks —
 * the visitor's address and whether it came over HTTPS, read from nginx's
 * X-Forwarded-* only when nginx is the one asking — and its body, read on
 * demand within a limit.
 */
class Requete(
    val methode: String,
    val chemin: String,
    /** The query string as sent (after `?`), or "". */
    val requete: String,
    entetes: Map<String, String>,
    /** The visitor's address (the proxy's X-Forwarded-For when the proxy is trusted). */
    val ip: String,
    /** Came over HTTPS (said by the trusted proxy). */
    val https: Boolean,
    private val flux: InputStream = InputStream.nullInputStream(),
) {
    val entetes: Map<String, String> = entetes.mapKeys { it.key.lowercase() }

    /** The route's variables (`/articles/{annee}/{slug}`). */
    val variables = HashMap<String, String>()

    /** The page's CSP nonce, set by the server. */
    var nonce: String = ""

    /** Anything a step before the route found (the admin session…). */
    val attributs = HashMap<String, Any>()

    val params: Map<String, List<String>> by lazy { analyse(requete) }

    fun param(k: String): String? = params[k]?.firstOrNull()
    fun entete(k: String): String? = entetes[k.lowercase()]
    fun variable(k: String): String = variables[k].orEmpty()

    /** One cookie's value. */
    fun cookie(nom: String): String? = entete("cookie")?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$nom=") }?.substringAfter('=')

    /** The announced length, -1 when none (chunked). */
    val longueur: Long get() = entete("content-length")?.trim()?.toLongOrNull() ?: -1L

    private var lu = false

    /** The whole body, [max] bytes at most ([TropGros] beyond). */
    fun corps(max: Long): ByteArray {
        check(!lu) { "corps déjà lu" }
        lu = true
        if (longueur > max) throw TropGros(max)
        val o = java.io.ByteArrayOutputStream(if (longueur in 1..max) longueur.toInt() else 8192)
        val b = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = flux.read(b)
            if (n < 0) break
            total += n
            if (total > max) throw TropGros(max)
            o.write(b, 0, n)
        }
        return o.toByteArray()
    }

    /** The body written to [f] as it comes (a video), [max] bytes at most; its SHA-256 (hex) and its size. */
    fun corpsVers(f: File, max: Long): Pair<String, Long> {
        check(!lu) { "corps déjà lu" }
        lu = true
        if (longueur > max) throw TropGros(max)
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            f.outputStream().buffered().use { o ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = flux.read(b)
                    if (n < 0) break
                    total += n
                    if (total > max) throw TropGros(max)
                    md.update(b, 0, n); o.write(b, 0, n)
                }
            }
        } catch (e: Exception) { f.delete(); throw e }
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }

    /**
     * A `multipart/form-data` body ([Multipart]), [max] bytes at most, its files written into [dossier]; null when the
     * body is not multipart (then still unread).
     */
    fun multipart(max: Long, dossier: File): Multipart? {
        val f = Multipart.frontiere(entete("content-type")) ?: return null
        check(!lu) { "corps déjà lu" }
        lu = true
        if (longueur > max) throw TropGros(max)
        return Multipart.lit(flux, f, max, dossier)
    }

    /** A form's fields (`application/x-www-form-urlencoded`), [max] bytes and [champs] fields at most. */
    fun formulaire(max: Long = 16 * 1024, champs: Int = 64): Map<String, String> =
        analyse(String(corps(max), Charsets.UTF_8), champs).mapValues { it.value.firstOrNull().orEmpty() }

    companion object {
        /** `a=1&b=x%20y` → {a: [1], b: [x y]}; at most [champs] fields (64), each name and value decoded. */
        fun analyse(q: String, champs: Int = 64): Map<String, List<String>> {
            if (q.isEmpty()) return emptyMap()
            val m = LinkedHashMap<String, MutableList<String>>()
            for (p in q.split('&').take(champs)) {
                if (p.isEmpty()) continue
                val k = decode(p.substringBefore('='))
                val v = if ('=' in p) decode(p.substringAfter('=')) else ""
                if (k.isNotEmpty()) m.getOrPut(k) { ArrayList() }.add(v)
            }
            return m
        }

        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault("")

        /**
         * The path as the routes take it: percent-decoded, one form only. Null for what no page has —
         * `..`, a backslash, control characters, a double slash, more than 512 characters.
         */
        fun cheminNet(brut: String): String? {
            if (brut.length > 1024 || !brut.startsWith("/")) return null
            val d = runCatching { URLDecoder.decode(brut.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull() ?: return null
            if (d.length > 512 || d.contains("//") || d.contains('\\') || d.any { it.code < 32 || it.code == 127 }) return null
            if (d.split('/').any { it == ".." || it == "." }) return null
            return d
        }
    }
}
