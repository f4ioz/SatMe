/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The HF pages' way out to the Internet: one client, a User-Agent that says
 * who asks and how to reach them, timeouts on every call, bodies read within
 * a limit, and a small bounded pool of threads for the fetches — a visitor's
 * request never waits on a data source longer than it chose to.
 */
class Amont(
    /** True: nothing asked from the Internet (tests, a preview without network). */
    val horsLigne: Boolean,
    /** Who asks: « f4ioz-site/<version> (+https://f4ioz.fr; contact) ». */
    val agent: String,
) {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS).followRedirects(true).build()
    }

    /** The fetches, four at a time; more waiting than the queue holds are refused (served from what is kept). */
    val travail: ThreadPoolExecutor = ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, ArrayBlockingQueue(64)) { r ->
        Thread(r, "hf-amont").apply { isDaemon = true }
    }.also { it.allowCoreThreadTimeOut(true) }

    fun soumet(t: () -> Unit): Future<*>? = try { travail.submit(t) } catch (e: RejectedExecutionException) { null }

    /** GET [url], its body as text (UTF-8 unless said otherwise), [max] bytes at most. */
    fun texte(url: String, max: Int = 8 * 1024 * 1024, appelS: Long = 30): String =
        lis(url, appelS) { flux -> String(litBorne(flux, max), Charsets.UTF_8) }

    /** GET [url], its body handed to [lecteur] as a stream (an archive read as it comes). */
    fun <T> lis(url: String, appelS: Long = 30, lecteur: (InputStream) -> T): T {
        if (horsLigne) throw IOException("hors ligne")
        val req = Request.Builder().url(url).header("User-Agent", agent).header("Accept-Encoding", "gzip").build()
        val appel = client.newCall(req)
        appel.timeout().timeout(appelS, TimeUnit.SECONDS)
        appel.execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val b = r.body ?: throw IOException("réponse vide")
            return lecteur(b.byteStream())
        }
    }

    companion object {
        fun litBorne(e: InputStream, max: Int): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            val b = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = e.read(b)
                if (n < 0) break
                total += n
                if (total > max) throw IOException("réponse trop grosse (> $max octets)")
                o.write(b, 0, n)
            }
            return o.toByteArray()
        }
    }
}

/**
 * Values fetched from a source and kept: fresh for [fraisMs], then served
 * as they are while one fetch (never two for the same key) brings the next;
 * a failed fetch keeps the last good value and is not retried before
 * [pauseErreurMs] (a source that says « too many » is left alone). At most
 * [maxCles] keys, the least recently asked dropped first.
 */
class Memo<V : Any>(
    private val amont: Amont,
    private val fraisMs: Long,
    private val pauseErreurMs: Long,
    private val maxCles: Int = 32,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** What a key holds: its value (null before the first success), when it came, the last error. */
    class Lu<V>(val valeur: V?, val quand: Long, val erreur: String?, val enCours: Boolean)

    private class Entree<V>(@Volatile var valeur: V? = null, @Volatile var quand: Long = 0L, @Volatile var erreur: String? = null,
                            @Volatile var essai: Long = 0L, @Volatile var tache: Future<*>? = null)

    private val entrees = object : LinkedHashMap<String, Entree<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entree<V>>?) = size > maxCles
    }

    /**
     * The value of [cle]: fresh, or the last one kept while [calcul] runs in the background; when there
     * is none yet, waits up to [attenteMs] for the first fetch.
     */
    fun lis(cle: String, attenteMs: Long = 0L, calcul: () -> V): Lu<V> {
        val t = horloge()
        val e: Entree<V>
        var tache: Future<*>? = null
        synchronized(entrees) {
            e = entrees.getOrPut(cle) { Entree() }
            val perime = e.valeur == null || t - e.quand >= fraisMs
            val enPause = e.erreur != null && t - e.essai < pauseErreurMs
            if (perime && !enPause && (e.tache == null || e.tache!!.isDone) && !amont.horsLigne) {
                e.essai = t
                tache = amont.soumet {
                    try {
                        val v = calcul()
                        e.valeur = v; e.quand = horloge(); e.erreur = null
                    } catch (x: Exception) {
                        e.erreur = (x.message ?: x.javaClass.simpleName).take(200)
                        e.essai = horloge()
                    }
                }
                e.tache = tache
            }
        }
        if (e.valeur == null && attenteMs > 0) {
            val f = e.tache
            if (f != null) runCatching { f.get(attenteMs, TimeUnit.MILLISECONDS) }
        }
        return Lu(e.valeur, e.quand, e.erreur ?: if (amont.horsLigne && e.valeur == null) "hors ligne" else null, e.tache?.isDone == false)
    }

    /** Puts a value as if fetched (tests, a value computed elsewhere). */
    fun pose(cle: String, v: V) { synchronized(entrees) { entrees.getOrPut(cle) { Entree() }.apply { valeur = v; quand = horloge(); erreur = null } } }

    fun taille(): Int = synchronized(entrees) { entrees.size }
}
