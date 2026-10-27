/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The ISS's live video of the Orbites tab (IssDirectJs): which YouTube
 * video NASA's « Live High-Definition Views from the International Space
 * Station » is today. Its ID changes now and then; NASA's channel page
 * `@NASA/live` names the live of the moment (its canonical `watch?v=` link
 * and its title). Asked only when the window wants its source, at most once
 * an hour (a failure: again after five minutes); a video whose title does
 * not speak of the Space Station is refused and the last good one kept.
 * Nothing about the station is sent. Without the Internet (or in the
 * demonstration without it) the page falls back on the channel's live and
 * the last ID known.
 */
class IssDirectPc(
    /** The fetcher (the tests' own): the page's text, or an exception. */
    private val telecharge: (String) -> String = ::telechargeHttp,
    private val horloge: () -> Long = System::currentTimeMillis
) {
    /** The live found: its video [id], its [titre], when it was checked ([verifie], ms). */
    data class Direct(val id: String, val titre: String, val verifie: Long)

    @Volatile private var bon: Direct? = null
    @Volatile private var essai = 0L
    @Volatile private var echec = false
    private val verrou = Any()

    /** The live of the moment, asked again when the last answer is older than an hour (five minutes after a failure). */
    fun direct(): Direct? = synchronized(verrou) {
        val t = horloge()
        if (essai == 0L || t - essai >= if (echec) ECHEC_MS else CACHE_MS) {
            essai = t
            val d = runCatching { extrait(telecharge(PAGE)) }.getOrNull()
            echec = d == null
            if (d != null) bon = Direct(d.first, d.second, t)
        }
        bon
    }

    fun sert(): ReponseWeb {
        val d = direct()
        val o = JSONObject().put("ok", true).put("id", d?.id ?: JSONObject.NULL).put("titre", d?.titre ?: JSONObject.NULL)
            .put("verifie", d?.verifie ?: JSONObject.NULL).put("chaine", CHAINE).put("dernier", DERNIER)
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(), "Cache-Control: no-store\r\n")
    }

    companion object {
        const val PAGE = "https://www.youtube.com/@NASA/live"
        /** NASA's YouTube channel (its live: `embed/live_stream?channel=`). */
        const val CHAINE = "UCLA_DiR1FfKNvjuUpBHmylQ"
        /** The last ID known when this was written (October 2026). */
        const val DERNIER = "ROboUHfPUrU"
        const val CACHE_MS = 3_600_000L
        const val ECHEC_MS = 300_000L

        private val CANONIQUE = listOf(
            Regex("""<link[^>]*\brel="canonical"[^>]*\bhref="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})""""),
            Regex("""<link[^>]*\bhref="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})"[^>]*\brel="canonical""""))
        private val TITRE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        private val OG_TITRE = Regex("""<meta[^>]*\bproperty="og:title"[^>]*\bcontent="([^"]*)"""")

        /** From the channel's live page: the video's ID and title, null when it is not the Space Station's live. */
        fun extrait(html: String): Pair<String, String>? {
            val id = CANONIQUE.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return null
            val titre = listOf(TITRE, OG_TITRE).map { r -> r.find(html)?.groupValues?.get(1)?.let(::texte).orEmpty().removeSuffix(" - YouTube").trim() }
                .firstOrNull { it.contains("Space Station", ignoreCase = true) } ?: return null
            return id to titre
        }

        private fun texte(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        }

        fun telechargeHttp(url: String): String {
            require(url == PAGE) { "source interdite : " + url }
            // A browser's own words: YouTube answers a bare client with a consent page instead.
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.8").header("Cookie", "SOCS=CAI").build()
            client.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP " + r.code }
                return r.body?.string().orEmpty()
            }
        }
    }
}
