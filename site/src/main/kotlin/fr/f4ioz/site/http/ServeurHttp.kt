/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.f4ioz.site.securite.Limiteur
import fr.f4ioz.site.securite.Securite
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The site's HTTP server: the JDK's own (`com.sun.net.httpserver`, nothing
 * to add), a bounded pool of threads, behind nginx. Every request goes the
 * same way: its path checked, the visitor's address read (X-Forwarded-For
 * from the trusted proxy only), a brake per address, the route, then the
 * security headers, the compression of text and the answer — a HEAD without
 * its body, a file by parts when asked.
 */
class ServeurHttp(
    private val ecoute: String,
    private val port: Int,
    private val routeur: Routeur,
    private val proxies: Set<String>,
    /** A page for an error (404, 405, 413, 429, 500…), the site's look. */
    private val pageErreur: (Requete, Int) -> Reponse,
    /** Requests per minute per address, all routes together. */
    limiteParMinute: Int = 300,
    private val journal: (String) -> Unit = { println(it) },
) {
    private var serveur: HttpServer? = null
    private val limiteur = Limiteur(limiteParMinute, 60_000L)
    private val aleatoire = SecureRandom()

    /** The port really listened on (0 asked: one chosen by the system). */
    val portReel: Int get() = serveur?.address?.port ?: 0

    fun demarre() {
        val s = HttpServer.create(InetSocketAddress(ecoute, port), 128)
        s.executor = ThreadPoolExecutor(4, 32, 60, TimeUnit.SECONDS, ArrayBlockingQueue(256), ThreadPoolExecutor.CallerRunsPolicy())
        s.createContext("/") { ex -> runCatching { sert(ex) }.onFailure { runCatching { ex.close() } } }
        s.start()
        serveur = s
    }

    fun arrete() { serveur?.stop(1); serveur = null }

    private fun sert(ex: HttpExchange) {
        val debut = System.nanoTime()
        val distante = ex.remoteAddress?.address?.hostAddress.orEmpty()
        val en = ex.requestHeaders
        val ip = Securite.ipClient(distante, en.getFirst("X-Forwarded-For"), proxies)
        val https = Securite.https(distante, en.getFirst("X-Forwarded-Proto"), proxies)
        val brut = ex.requestURI.rawPath ?: "/"
        val q = ex.requestURI.rawQuery.orEmpty()
        val entetes = en.entries.associate { it.key to (it.value.firstOrNull() ?: "") }
        val chemin = Requete.cheminNet(brut)
        val r = Requete(ex.requestMethod.uppercase(), chemin ?: "/", q, entetes, ip, https, ex.requestBody)
        r.nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { aleatoire.nextBytes(it) })
        val rep = try {
            when {
                chemin == null -> pageErreur(r, 400)
                r.methode !in METHODES -> pageErreur(r, 405)
                !limiteur.accepte(ip) -> pageErreur(r, 429).entete("Retry-After", "30")
                else -> {
                    val t = routeur.trouve(r)
                    when {
                        t != null -> t(r)
                        routeur.existeAutrement(r.chemin) -> pageErreur(r, 405)
                        else -> pageErreur(r, 404)
                    }
                }
            }
        } catch (e: TropGros) {
            pageErreur(r, 413)
        } catch (e: Exception) {
            journal("ERREUR ${r.methode} ${r.chemin} : ${e.javaClass.simpleName} ${e.message}")
            e.printStackTrace()
            runCatching { pageErreur(r, 500) }.getOrElse { Reponse.texte("Erreur interne", 500) }
        }
        // A body the route did not read (refused before it: 401, 413…) is read away, 4 MB at most, so the client
        // still sending it gets the answer rather than a reset connection; beyond that the connection ends with the answer.
        if (r.methode == "POST" || r.methode == "DELETE") {
            val reste = runCatching { val b = ByteArray(64 * 1024); var n = 0L
                while (n < 4L * 1024 * 1024) { val k = ex.requestBody.read(b); if (k < 0) break; n += k }
                n >= 4L * 1024 * 1024 }.getOrDefault(true)
            if (reste) rep.entete("Connection", "close")
        }
        envoie(ex, r, rep)
        val ms = (System.nanoTime() - debut) / 1_000_000
        journal("${Instant.now()} $ip ${r.methode} ${brut.take(200)} ${rep.statut} ${ms}ms")
    }

    private fun envoie(ex: HttpExchange, r: Requete, rep: Reponse) {
        // What was not read of the body is left: the connection is closed after the answer then.
        val h = ex.responseHeaders
        for ((k, v) in Securite.entetes(rep, r.nonce, r.https)) if (!rep.entetes.containsKey(k)) h.set(k, v)
        for ((k, v) in rep.entetes) h.set(k, v)
        h.set("Content-Type", rep.type)
        if (!rep.entetes.containsKey("Cache-Control")) h.set("Cache-Control", "no-cache")
        val tete = r.methode == "HEAD"
        val f = rep.fichier
        if (f != null) { envoieFichier(ex, r, rep, tete); return }
        var corps = rep.corps
        if (corps.size > 1024 && compressible(rep.type) && r.entete("accept-encoding")?.contains("gzip") == true) {
            corps = gzip(corps)
            h.set("Content-Encoding", "gzip")
            h.add("Vary", "Accept-Encoding")
        }
        if (tete || rep.statut == 204 || rep.statut == 304) {
            if (corps.isNotEmpty() && rep.statut != 204 && rep.statut != 304) h.set("Content-Length", corps.size.toString())
            ex.sendResponseHeaders(rep.statut, -1)
        } else {
            ex.sendResponseHeaders(rep.statut, if (corps.isEmpty()) -1 else corps.size.toLong())
            if (corps.isNotEmpty()) ex.responseBody.use { it.write(corps) }
        }
        ex.close()
    }

    private fun envoieFichier(ex: HttpExchange, r: Requete, rep: Reponse, tete: Boolean) {
        val f = rep.fichier!!
        val taille = f.length()
        val h = ex.responseHeaders
        h.set("Accept-Ranges", "bytes")
        val p = plage(r.entete("range"), taille)
        if (r.entete("range") != null && p == null) {
            h.set("Content-Range", "bytes */$taille"); ex.sendResponseHeaders(416, -1); ex.close(); return
        }
        val (de, a) = p ?: (0L to taille - 1)
        val n = (a - de + 1).coerceAtLeast(0)
        val statut = if (p != null) 206 else rep.statut
        if (p != null) h.set("Content-Range", "bytes $de-$a/$taille")
        if (tete || n == 0L) {
            h.set("Content-Length", n.toString()); ex.sendResponseHeaders(statut, -1); ex.close(); return
        }
        ex.sendResponseHeaders(statut, n)
        ex.responseBody.use { o -> copie(f, de, n, o) }
        ex.close()
    }

    private fun copie(f: java.io.File, de: Long, n: Long, o: OutputStream) {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(de)
            val b = ByteArray(64 * 1024)
            var reste = n
            while (reste > 0) {
                val k = raf.read(b, 0, minOf(b.size.toLong(), reste).toInt())
                if (k < 0) break
                o.write(b, 0, k); reste -= k
            }
        }
    }

    companion object {
        val METHODES = setOf("GET", "HEAD", "POST", "DELETE")

        fun compressible(type: String) = type.startsWith("text/") || type.startsWith("application/json") ||
            type.startsWith("application/javascript") || type.startsWith("image/svg") || type.contains("xml")

        fun gzip(b: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(b.size / 3 + 64)
            GZIPOutputStream(o).use { it.write(b) }
            return o.toByteArray()
        }

        /** The bytes a `Range` header asks for (SatMe's own reading of it), first and last included; null when none or not servable. */
        fun plage(entete: String?, taille: Long): Pair<Long, Long>? =
            fr.f4ioz.satcombo.demo.RequeteHttp.plage(entete, taille)?.let { it.first to it.last }
    }
}
