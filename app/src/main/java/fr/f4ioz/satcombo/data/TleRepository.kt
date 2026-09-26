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
        withContext(Dispatchers.IO) {
            val seen = LinkedHashMap<Int, TleEntry>()
            for (url in urls) {
                runCatching { fetchOne(url) }.getOrDefault(emptyList()).forEach { e ->
                    seen.putIfAbsent(e.catalogNumber, e)
                }
            }
            seen.values.toList()
        }

    /** Freshest elements for one satellite, from the enabled [sources]. */
    suspend fun plusRecent(catnum: Int, sources: List<TleSource>): RafraichissementTle.Resultat =
        withContext(Dispatchers.IO) {
            RafraichissementTle.plusRecent(catnum,
                RafraichissementTle.adresses(catnum, sources), ::fetchOne)
        }

    private fun fetchOne(url: String): List<TleEntry> {
        val req = Request.Builder().url(url).header("User-Agent", "SatCombo/1.0 (F4IOZ)").build()
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
        val brut = if (t.startsWith("[") || t.startsWith("{"))
            OmmParser.parse(text, freqPlan) else parseTle(text)
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
