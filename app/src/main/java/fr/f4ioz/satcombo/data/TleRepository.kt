/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads and parses TLE sets from Celestrak (Look4Sat-style multi-source).
 * Ham-radio frequencies are merged from a small built-in table so the
 * Doppler/frequency view works offline once TLEs are cached.
 */
class TleRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        /**
         * Says which app and version asks: the SatMe GP server's connection
         * page tells SatMe from robots with it. Up to 20.72: "SatCombo/1.0".
         * The same for every service SatMe asks (AMSAT, SatNOGS, QRZ, hams.at,
         * OpenStreetMap…), with the project's public page as contact — never
         * a callsign. The server reads "SatMe/<v>" and "(Android <n>)".
         */
        val USER_AGENT: String
            get() = "SatMe/$version (Android ${android.os.Build.VERSION.RELEASE ?: "?"}) +https://github.com/f4ioz/SatMe"

        /** Set at startup by [fr.f4ioz.satcombo.SatMeApp] (no BuildConfig here). */
        @Volatile var version: String = "?"

        /**
         * A request says which app, which version, which Android: public
         * markers only. No number, no callsign: nothing that follows one
         * phone from one day to the next.
         */
        fun requete(url: String): Request =
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        /** Satellites of the last bulletin whose elements could not be used. */
        @Volatile var ecartes: List<String> = emptyList()
            internal set

        // Celestrak GP element sets. We now request OMM JSON rather than TLE:
        // CelesTrak exhausted 5-digit catalog numbers (2026-07-11), so new
        // objects (100000+) are NOT published in TLE format at all. OMM has no
        // such limit. TLE URLs are kept as a fallback for offline/edge cases.
        // https://celestrak.org/NORAD/documentation/gp-data-formats.php
        const val GROUP_AMATEUR = "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json"
        const val GROUP_STATIONS = "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json"
        const val GROUP_VISUAL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=json"

        /**
         * AMSAT's own GP bulletin (OMM JSON): ~94 amateur satellites, curated
         * for ham use, and carrying AMSAT_NAME ("AO-07") alongside the catalog
         * name ("OSCAR 7"). Same OMM keys as CelesTrak, so it parses identically.
         */
        const val AMSAT_GP = "https://newark192.amsat.org/gpdata/current/daily-bulletin.json"

        /**
         * One satellite per catalogue number across the sources: the
         * **freshest elements** win, but the name (AMSAT's "AO-07"…) and the
         * frequencies come from the first source that had it. Keeping the
         * first source's elements put the ISS five minutes off with a
         * nine-day-old bulletin while a fresh one sat right next to it.
         */
        fun fusionne(listes: List<List<TleEntry>>, maintenant: Long = System.currentTimeMillis()): List<TleEntry> {
            val vus = LinkedHashMap<Int, TleEntry>()
            for (liste in listes) for (e in liste) {
                val avant = vus[e.catalogNumber]
                if (avant == null) { vus[e.catalogNumber] = e; continue }
                if (prefere(avant, e, maintenant) === e) {
                    vus[e.catalogNumber] = e.copy(name = avant.name,
                        uplinkHz = avant.uplinkHz ?: e.uplinkHz, downlinkHz = avant.downlinkHz ?: e.downlinkHz,
                        mode = avant.mode ?: e.mode)
                }
            }
            return vus.values.toList()
        }

        /** Elements dated more than an hour ahead are predictions for later (SupGP segments come every six hours). */
        const val AVANCE_MAX_MS = 3_600_000L

        /**
         * Of two element sets for one satellite, the one for now: the most
         * recent epoch **not in the future**. CelesTrak's SupGP gives the ISS
         * as sixty six-hour segments reaching two weeks ahead; "most recent"
         * picked the last one, valid in a fortnight, not today. When both are
         * ahead, the nearest.
         */
        fun prefere(a: TleEntry, b: TleEntry, maintenant: Long = System.currentTimeMillis()): TleEntry {
            val ea = a.epochMs ?: 0L; val eb = b.epochMs ?: 0L
            val futurA = ea > maintenant + AVANCE_MAX_MS; val futurB = eb > maintenant + AVANCE_MAX_MS
            return when {
                futurA != futurB -> if (futurA) b else a
                futurA -> if (eb < ea) b else a
                else -> if (eb > ea) b else a
            }
        }
    }

    /** Minimal frequency plan keyed by NORAD catalog number (uplink/downlink in Hz). */
    private val freqPlan: Map<Int, Triple<Long?, Long?, String>> = mapOf(
        25544 to Triple(145_990_000L, 437_800_000L, "ISS Voice/APRS/SSTV"), // ISS
        7530 to Triple(145_900_000L, 29_400_000L, "AO-7 Mode A/B"),         // AO-7
        43017 to Triple(435_250_000L, 145_960_000L, "FO-29 / AO-style V/U"),
        43700 to Triple(145_850_000L, 435_350_000L, "JO-97 (?)"),
        43137 to Triple(145_850_000L, 435_300_000L, "FO-99 / linear"),
        40967 to Triple(435_710_000L, 145_980_000L, "AO-91 FM (V/U)"),      // AO-91 Fox-1B
        43770 to Triple(145_910_000L, 435_810_000L, "PO-101 FM"),          // Diwata-2
        7531 to Triple(145_870_000L, 29_300_000L, "Mode A linear")
    )

    suspend fun fetchGroups(urls: List<String>): List<TleEntry> =
        fetchGroupesSecours(urls.map { listOf(it) })

    /**
     * One entry per group, each a list of addresses tried in order until one
     * answers with elements: the SatMe GP server first, the source itself as
     * a fallback ([ServeurGp]).
     */
    suspend fun fetchGroupesSecours(groupes: List<List<String>>): List<TleEntry> =
        withContext(Dispatchers.IO) {
            fusionne(groupes.map { adresses ->
                adresses.asSequence()
                    .map { runCatching { fetchOne(it) }.getOrDefault(emptyList()) }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
            })
        }

    /**
     * Freshest elements for one satellite, from the enabled [sources] — or
     * from the SatMe GP server at [serveur] when set, the sources themselves
     * only if it cannot be reached.
     */
    suspend fun plusRecent(catnum: Int, sources: List<TleSource>, serveur: String = "",
                           seul: Boolean = false): RafraichissementTle.Resultat =
        withContext(Dispatchers.IO) {
            if (ServeurGp.normalise(serveur).isNotEmpty()) {
                val r = RafraichissementTle.plusRecent(catnum,
                    listOf(ServeurGp.catnr(serveur, catnum)), ::fetchOne)
                // Server only: its silence is the answer, the sources are never asked.
                if (seul || r !is RafraichissementTle.Resultat.Injoignable) return@withContext r
            }
            RafraichissementTle.plusRecent(catnum,
                RafraichissementTle.adresses(catnum, sources), ::fetchOne)
        }

    /** The server's groups and satellite counts, or the reason it failed. */
    suspend fun testeServeur(base: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val req = requete(ServeurGp.index(base))
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val o = org.json.JSONObject(resp.body?.string().orEmpty())
                val g = o.getJSONArray("groupes")
                (0 until g.length()).joinToString(" · ") {
                    val x = g.getJSONObject(it); "${x.getString("id")} ${x.optInt("nombre")}"
                }.ifBlank { "aucun groupe" }
            }
        }.getOrElse { it.javaClass.simpleName }
    }

    private fun fetchOne(url: String): List<TleEntry> {
        val req = requete(url)
        client.newCall(req).execute().use { resp ->
            // An error status is a failure, not an empty answer: a blocked
            // address must not read as "no such satellite".
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return parse(body)
        }
    }

    /**
     * Parse GP data, auto-detecting the format: OMM JSON (new, supports 6+
     * digit catalog numbers) or legacy 3LE text (cached files, fallback).
     */
    /**
     * Orbital elements from a bulletin, **usable ones only**.
     *
     * A satellite whose elements predict4java cannot parse is dropped here
     * rather than later: the exception surfaces deep inside the pass
     * computation and takes every satellite down with it, not only the faulty
     * one. Seen in production on 20.47 — one bad entry, no passes at all.
     *
     * Dropping is silent on purpose. The operator can do nothing about a
     * malformed bulletin, and a satellite missing from the list is far less
     * harmful than an app that stops.
     */
    fun parse(text: String): List<TleEntry> {
        val t = text.trimStart()
        val brut = when {
            // SatNOGS DB: [{"tle0": name, "tle1": …, "tle2": …}, …]
            t.startsWith("[") && t.contains("\"tle1\"") -> parseTle(satnogsEnTle(text))
            t.startsWith("[") || t.startsWith("{") -> OmmParser.parse(text, freqPlan)
            else -> parseTle(text)
        }
        val bons = brut.filter {
            fr.f4ioz.satcombo.domain.PassPredictor.elementsUtilisables(it)
        }
        // Kept for the sources screen: otherwise a vanished satellite is a
        // mystery — you know something is missing, never what or why.
        ecartes = (brut - bons.toSet()).map {
            it.name.ifBlank { "#" + it.catalogNumber }
        }
        return bons
    }

    /** Legacy 3-line element parser (kept for cached data and fallback). */
    /** SatNOGS DB's JSON as plain 3LE text (name, line 1, line 2). */
    fun satnogsEnTle(json: String): String = runCatching {
        val a = org.json.JSONArray(json)
        buildString {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val l1 = o.optString("tle1"); val l2 = o.optString("tle2")
                if (l1.isEmpty() || l2.isEmpty()) continue
                append(o.optString("tle0").ifBlank { l1.drop(2).take(5).trim() }).append('\n')
                append(l1).append('\n').append(l2).append('\n')
            }
        }
    }.getOrDefault("")

    fun parseTle(text: String): List<TleEntry> {
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val out = ArrayList<TleEntry>()
        var i = 0
        while (i + 2 < lines.size || (i + 2 == lines.size)) {
            if (i + 2 >= lines.size) break
            val name = lines[i]
            val l1 = lines[i + 1]
            val l2 = lines[i + 2]
            if (l1.startsWith("1 ") && l2.startsWith("2 ")) {
                // Alpha-5 aware: "A0123" style encodes 6-digit catalog numbers.
                val cat = OmmParser.decodeAlpha5(l1.drop(2).take(5).trim())
                val plan = freqPlan[cat]
                out += TleEntry(
                    name = name.removePrefix("0 ").trim(),
                    line1 = l1, line2 = l2, catalogNumber = cat,
                    uplinkHz = plan?.first, downlinkHz = plan?.second, mode = plan?.third
                )
                i += 3
            } else i += 1
        }
        return out
    }
}
