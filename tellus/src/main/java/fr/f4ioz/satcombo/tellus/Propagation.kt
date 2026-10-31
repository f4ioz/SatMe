/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Propagation »: the solar flux, the K and A indices and HamQSL's band
 * conditions, from its public XML (https://www.hamqsl.com/solarxml.php).
 * Fetched **at most once an hour** (HamQSL updates every three hours or so),
 * only while the page asks, kept in [cache] so a restart does not fetch again.
 */
class Propagation(
    private val cache: File,
    private val telecharge: () -> String? = { Http.texte(URL) },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    data class Etat(val flux: Int?, val a: Int?, val k: Int?, val taches: Int?, val rayonsX: String, val misAJour: String,
                    /** "80m-40m" → (day, night). */ val bandes: Map<String, Pair<String, String>>)

    @Volatile private var dernierEssai = 0L
    @Volatile var telechargements = 0; private set
    @Volatile private var enCours = false

    /** What is known now; asks HamQSL in the background when the cache is older than an hour. */
    fun etat(): Etat? {
        val age = if (cache.isFile) horloge() - cache.lastModified() else Long.MAX_VALUE
        if (age >= HEURE && horloge() - dernierEssai >= HEURE && !enCours) {
            dernierEssai = horloge(); enCours = true
            Thread({ try { rafraichis() } finally { enCours = false } }, "tellus-propagation").apply { isDaemon = true; start() }
        }
        return if (cache.isFile) runCatching { lis(cache.readText()) }.getOrNull() else null
    }

    /** Fetches now (the background thread; tests call it directly). */
    fun rafraichis(): Boolean {
        val t = runCatching { telecharge() }.getOrNull() ?: return false
        telechargements++
        if (lis(t).flux == null) return false
        cache.parentFile?.mkdirs()
        cache.writeText(t)
        cache.setLastModified(horloge())
        return true
    }

    fun json(): JSONObject {
        val e = etat() ?: return JSONObject().put("ok", false).put("message", "en attente de HamQSL")
        return JSONObject().put("ok", true).put("flux", e.flux ?: JSONObject.NULL).put("a", e.a ?: JSONObject.NULL)
            .put("k", e.k ?: JSONObject.NULL).put("taches", e.taches ?: JSONObject.NULL).put("rayonsX", e.rayonsX)
            .put("misAJour", e.misAJour)
            .put("bandes", JSONArray(e.bandes.map { (b, j) -> JSONObject().put("b", b).put("jour", j.first).put("nuit", j.second) }))
    }

    companion object {
        const val URL = "https://www.hamqsl.com/solarxml.php"
        const val HEURE = 3_600_000L

        private fun champ(x: String, nom: String): String? =
            Regex("<$nom>\\s*([^<]*?)\\s*</$nom>").find(x)?.groupValues?.get(1)

        fun lis(x: String): Etat {
            val bandes = LinkedHashMap<String, Pair<String, String>>()
            val sect = x.substringAfter("<calculatedconditions>", "").substringBefore("</calculatedconditions>")
            for (m in Regex("<band name=\"([^\"]+)\" time=\"(day|night)\">\\s*([^<]*?)\\s*</band>").findAll(sect)) {
                val (b, quand, v) = m.destructured
                val p = bandes[b] ?: ("" to "")
                bandes[b] = if (quand == "day") v to p.second else p.first to v
            }
            return Etat(champ(x, "solarflux")?.trim()?.toIntOrNull(), champ(x, "aindex")?.trim()?.toIntOrNull(),
                champ(x, "kindex")?.trim()?.toIntOrNull(), champ(x, "sunspots")?.trim()?.toIntOrNull(),
                champ(x, "xray").orEmpty(), champ(x, "updated").orEmpty(), bandes)
        }
    }
}

/** The few plain GETs TellusMe makes (HamQSL), through the engine's OkHttp. */
object Http {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(15)).build()
    }

    fun texte(url: String): String? = runCatching {
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "TellusMe (SatMe)").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
}
