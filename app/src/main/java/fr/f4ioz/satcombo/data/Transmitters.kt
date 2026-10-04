/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** One SatNOGS transmitter/transceiver/transponder entry. */
data class Transmitter(
    val description: String,
    val mode: String?,
    val uplinkLowHz: Long?,
    val uplinkHighHz: Long?,
    val downlinkLowHz: Long?,
    val downlinkHighHz: Long?,
    val invert: Boolean,
    val alive: Boolean,
    val type: String
) {
    /** A linear/inverting transponder spans a passband (high != low). */
    val isTransponder: Boolean
        get() = (downlinkHighHz != null && downlinkLowHz != null && downlinkHighHz != downlinkLowHz)

    /**
     * Is the mode worth showing for this transmitter?
     *
     * SatNOGS stores **one mode per entry**. Right for a beacon (BPSK, CW),
     * meaningless for a linear transponder, which relays whatever the operator
     * chooses: the field holds whatever the first contributor typed. On QO-100
     * the narrowband segments are labelled "FM" although reserved for SSB and
     * CW — a wrong instruction, not an imprecision.
     *
     * We do not correct SatNOGS data; we just do not show it where it means
     * nothing.
     */
    val modeSignifiant: Boolean get() = !isTransponder
}

/**
 * Fetches transmitter data from the SatNOGS DB per NORAD catnum, with a disk
 * cache for offline use (Look4Sat-style frequency coverage).
 */
class TransmittersRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val dir = File(context.filesDir, "transmitters").apply { mkdirs() }
    private val memory = HashMap<Int, List<Transmitter>>()

    /** SatNOGS satellite operational status: "alive", "dead", "re-entered", "future", or null. */
    suspend fun statusOf(catnum: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://db.satnogs.org/api/satellites/?norad_cat_id=$catnum&format=json"
            val req = Request.Builder().url(url)
                .header("User-Agent", TleRepository.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val arr = org.json.JSONArray(r.body?.string().orEmpty())
                if (arr.length() == 0) null
                else arr.getJSONObject(0).optString("status").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * Satellites with every transmitter dead ([Inactifs]). Read from the cache
     * when it is less than a week old, else fetched: the whole SatNOGS list is
     * about 4 MB, so it is read as a stream and only the numbers are kept.
     * Null when there is neither a cache nor a network.
     */
    suspend fun inactifs(maintenant: Long = System.currentTimeMillis()): Set<Int>? =
        withContext(Dispatchers.IO) {
            val cache = File(dir, "inactifs.txt")
            val connu = runCatching { Inactifs.depuisTexte(cache.readText()) }.getOrNull()
            if (connu != null && maintenant - connu.first < Inactifs.VALIDITE_MS) return@withContext connu.second
            val frais = runCatching { telechargeInactifs() }.getOrNull()
            if (frais != null) {
                runCatching { cache.writeText(Inactifs.versTexte(maintenant, frais)) }
                frais
            } else connu?.second
        }

    /** The whole transmitter list, streamed: number and alive flag only. */
    private fun telechargeInactifs(): Set<Int>? {
        val req = Request.Builder().url("https://db.satnogs.org/api/transmitters/?format=json")
            .header("User-Agent", TleRepository.USER_AGENT).build()
        val lent = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        lent.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val corps = r.body ?: return null
            val paires = ArrayList<Pair<Int, Boolean>>(6000)
            android.util.JsonReader(corps.charStream()).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    var norad: Int? = null
                    var alive = false
                    j.beginObject()
                    while (j.hasNext()) {
                        when (j.nextName()) {
                            "norad_cat_id" ->
                                if (j.peek() == android.util.JsonToken.NULL) j.nextNull() else norad = j.nextInt()
                            "alive" ->
                                if (j.peek() == android.util.JsonToken.BOOLEAN) alive = j.nextBoolean() else j.skipValue()
                            else -> j.skipValue()
                        }
                    }
                    j.endObject()
                    norad?.let { paires.add(it to alive) }
                }
                j.endArray()
            }
            // An empty answer is a broken answer, not "everything is alive".
            if (paires.isEmpty()) return null
            return Inactifs.calcule(paires.asSequence())
        }
    }

    suspend fun forSatellite(catnum: Int): List<Transmitter> = withContext(Dispatchers.IO) {
        memory[catnum]?.let { return@withContext it }
        val net = runCatching { fetch(catnum) }.getOrNull()
        val result = if (net != null) {
            runCatching { File(dir, "$catnum.json").writeText(net) }
            parse(net)
        } else {
            val cached = runCatching { File(dir, "$catnum.json").readText() }.getOrNull()
            cached?.let { parse(it) } ?: emptyList()
        }
        memory[catnum] = result
        result
    }

    private fun fetch(catnum: Int): String? {
        val url = "https://db.satnogs.org/api/transmitters/?satellite__norad_cat_id=$catnum&format=json"
        val req = Request.Builder().url(url).header("User-Agent", TleRepository.USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun parse(json: String): List<Transmitter> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Transmitter(
                description = o.optString("description", "Transmitter"),
                mode = o.optString("mode").takeIf { it.isNotBlank() && it != "null" },
                uplinkLowHz = o.optLong("uplink_low", -1).takeIf { it > 0 },
                uplinkHighHz = o.optLong("uplink_high", -1).takeIf { it > 0 },
                downlinkLowHz = o.optLong("downlink_low", -1).takeIf { it > 0 },
                downlinkHighHz = o.optLong("downlink_high", -1).takeIf { it > 0 },
                invert = o.optBoolean("invert", false),
                alive = o.optBoolean("alive", false),
                type = o.optString("type", "Transmitter")
            )
        }
            // active first, then those with a downlink
            .sortedWith(compareByDescending<Transmitter> { it.alive }
                .thenByDescending { it.downlinkLowHz != null })
    }.getOrDefault(emptyList())
}
