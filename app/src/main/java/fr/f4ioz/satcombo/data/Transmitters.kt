/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
     * Le mode a-t-il un sens à afficher pour cet émetteur ?
     *
     * SatNOGS ne porte qu'**un seul mode par entrée**. Pour une balise c'est
     * exact et utile — BPSK, CW — mais un transpondeur linéaire n'a pas de
     * mode : il retransmet ce qu'on lui envoie, et c'est l'opérateur qui
     * choisit. Le champ y contient alors ce que le premier contributeur a bien
     * voulu mettre.
     *
     * Sur QO-100, les trois segments du transpondeur étroit sont ainsi
     * étiquetés « FM » alors qu'ils sont réservés à la BLU et à la
     * télégraphie. Le montrer n'est pas une imprécision : c'est une
     * instruction fausse, et un opérateur qui la suivrait passerait en FM sur
     * un segment où c'est proscrit.
     *
     * On ne corrige pas la donnée — nous ne sommes pas mieux placés que
     * SatNOGS pour savoir. On s'abstient de l'afficher là où elle ne veut rien
     * dire, ce qui est différent et défendable.
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
                .header("User-Agent", "SatCombo/4.4 amateur-radio app (F4IOZ)").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val arr = org.json.JSONArray(r.body?.string().orEmpty())
                if (arr.length() == 0) null
                else arr.getJSONObject(0).optString("status").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
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
        val req = Request.Builder().url(url).header("User-Agent", "SatCombo/1.0 (F4IOZ)").build()
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
            // actives d'abord, puis celles qui ont un downlink
            .sortedWith(compareByDescending<Transmitter> { it.alive }
                .thenByDescending { it.downlinkLowHz != null })
    }.getOrDefault(emptyList())
}
