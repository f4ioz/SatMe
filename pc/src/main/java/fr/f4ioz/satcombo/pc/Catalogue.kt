/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.OmmParser
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The satellites (orbital elements) and their transmitters, for the PC.
 *
 * **AMSAT only for the elements**: its JSON bulletin (names "AO-07"…) and its
 * TLE file, which is often fresher; the transmitters from SatNOGS DB. Both
 * are kept on disk: the station starts and predicts without a network.
 * CelesTrak is never asked from here ([interdit]).
 */
class Catalogue(
    private val dossier: File,
    private val client: OkHttpClient = clientParDefaut,
    /** False: the disk only, nothing downloaded (the tests). */
    val enLigne: Boolean = true
) {

    /** Where the downloads are kept (also the AMSAT status page). */
    val dossierCache: File? get() = dossier

    private val fichierGp = File(dossier, "amsat-gp.json")
    private val fichierTle = File(dossier, "amsat-tle.txt")
    private val dossierEmetteurs = File(dossier, "emetteurs")
    private val emetteurs = java.util.concurrent.ConcurrentHashMap<Int, List<Transmitter>>()

    /** The satellites known now, from the disk (empty before the first download). */
    fun satellites(): List<TleEntry> = fusionne(
        runCatching { OmmParser.parse(fichierGp.readText()) }.getOrDefault(emptyList()),
        runCatching { lisTle(fichierTle.readText()) }.getOrDefault(emptyList()))

    /** Age of the elements on disk (ms), null when there are none. */
    fun age(maintenant: Long = System.currentTimeMillis()): Long? =
        fichierGp.takeIf { it.isFile }?.let { maintenant - it.lastModified() }

    /**
     * Downloads both AMSAT files; what was on disk stays when the network
     * fails. Returns a word for the operator.
     */
    fun rafraichis(): String {
        dossier.mkdirs()
        val gp = runCatching { telecharge(AMSAT_GP) }.getOrNull()
        val tle = runCatching { telecharge(AMSAT_TLE) }.getOrNull()
        if (gp != null && OmmParser.parse(gp).isNotEmpty()) fichierGp.writeText(gp)
        if (tle != null && lisTle(tle).isNotEmpty()) fichierTle.writeText(tle)
        return when {
            gp == null && tle == null -> "AMSAT injoignable : éléments du disque gardés"
            else -> "éléments AMSAT à jour (${satellites().size} satellites)"
        }
    }

    /** The transmitters of a satellite, from SatNOGS DB (then from disk). */
    fun emetteurs(catnum: Int): List<Transmitter> {
        emetteurs[catnum]?.let { return it }
        dossierEmetteurs.mkdirs()
        val f = File(dossierEmetteurs, "$catnum.json")
        val texte = runCatching {
            telecharge("https://db.satnogs.org/api/transmitters/?satellite__norad_cat_id=$catnum&format=json")
        }.getOrNull()?.also { runCatching { f.writeText(it) } } ?: runCatching { f.readText() }.getOrNull()
        val l = texte?.let { lisEmetteurs(it) }.orEmpty()
        if (texte != null) emetteurs[catnum] = l
        return l
    }

    private fun telecharge(url: String): String {
        require(!interdit(url)) { "source interdite : $url" }
        check(enLigne) { "hors ligne" }
        val req = Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }

    companion object {
        const val AMSAT_GP = "https://newark192.amsat.org/gpdata/current/daily-bulletin.json"
        const val AMSAT_TLE = "https://www.amsat.org/tle/current/nasabare.txt"

        /** CelesTrak refuses this station's address: never asked from the PC. */
        fun interdit(url: String): Boolean = url.contains("celestrak", ignoreCase = true)

        private val clientParDefaut by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** A TLE file: name, line 1, line 2, again and again. */
        fun lisTle(texte: String): List<TleEntry> {
            val l = texte.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<TleEntry>()
            var i = 0
            while (i + 2 < l.size) {
                if (l[i + 1].startsWith("1 ") && l[i + 2].startsWith("2 ")) {
                    runCatching { TleEntry(l[i].trim(), l[i + 1], l[i + 2]) }.getOrNull()
                        ?.takeIf { it.catalogNumber > 0 }?.let { out += it }
                    i += 3
                } else i++
            }
            return out
        }

        /**
         * One satellite per number: the name of the JSON bulletin (AMSAT's
         * "AO-07"), the freshest elements of the two.
         */
        fun fusionne(gp: List<TleEntry>, tle: List<TleEntry>): List<TleEntry> {
            val parNumero = LinkedHashMap<Int, TleEntry>()
            for (e in gp) parNumero[e.catalogNumber] = e
            for (e in tle) {
                val a = parNumero[e.catalogNumber]
                parNumero[e.catalogNumber] = when {
                    a == null -> e
                    (e.epochMs ?: 0L) > (a.epochMs ?: 0L) -> e.copy(name = a.name)
                    else -> a
                }
            }
            return parNumero.values.sortedBy { it.name.uppercase() }
        }

        /** SatNOGS transmitters: active first, those with a downlink before the others. */
        fun lisEmetteurs(json: String): List<Transmitter> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                fun hz(k: String) = o.optLong(k, -1).takeIf { it > 0 }
                Transmitter(
                    description = o.optString("description", "Transmitter"),
                    mode = o.optString("mode").takeIf { it.isNotBlank() && it != "null" },
                    uplinkLowHz = hz("uplink_low"), uplinkHighHz = hz("uplink_high"),
                    downlinkLowHz = hz("downlink_low"), downlinkHighHz = hz("downlink_high"),
                    invert = o.optBoolean("invert", false), alive = o.optBoolean("alive", false),
                    type = o.optString("type", "Transmitter"))
            }.sortedWith(compareByDescending<Transmitter> { it.alive }.thenByDescending { it.downlinkLowHz != null })
        }.getOrDefault(emptyList())
    }
}
