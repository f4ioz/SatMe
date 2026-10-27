/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque

/**
 * The Reverse Beacon Network live (telnet, port 7000: CW and RTTY skimmers),
 * for the bands map and the WSPR page's « RBN » tab. One connection for the
 * whole site, opened when a page asks and closed ten minutes after the last
 * one asked (RBN is free, a login with a callsign; one quiet connection is
 * the polite way). Lost: tried again after 5 s, then longer, five minutes at
 * most. The spots of the last 35 minutes are kept (every window the pages
 * offer, up to 30 minutes, is really there — the old site kept 12 000 spots,
 * a few minutes), 80 000 at most.
 */
class Rbn(
    private val indicatif: String,
    private val actif: Boolean,
    private val hote: String = "telnet.reversebeacon.net",
    private val port: Int = 7000,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** One spot: who heard (the skimmer), whom, where, how loud, when (ms, the site's clock). */
    class Spot(val skimmer: String, val khz: Double, val bande: String, val dx: String, val mode: String, val snr: Int, val wpm: Int,
               val type: String, val ts: Long, val rxLat: Double?, val rxLon: Double?)

    private val spots = ArrayDeque<Spot>()
    @Volatile var connecte = false; private set
    @Volatile var erreur: String? = null; private set
    @Volatile private var dernierAcces = 0L
    @Volatile private var fil: Thread? = null
    @Volatile private var prise: Socket? = null

    /** Starts the connection if needed (any API that reads the spots calls it). */
    fun assure() {
        dernierAcces = horloge()
        if (!actif || indicatif.isEmpty()) return
        synchronized(this) {
            if (fil?.isAlive == true) return
            fil = Thread({ boucle() }, "rbn-telnet").apply { isDaemon = true; start() }
        }
    }

    fun arrete() { fil = null; runCatching { prise?.close() } }

    /** Adds a spot (the reader, the tests); the old ones and the overflow dropped. */
    fun ajoute(s: Spot) {
        synchronized(spots) {
            spots.addLast(s)
            val limite = horloge() - GARDE_MS
            while (spots.isNotEmpty() && (spots.peekFirst().ts < limite || spots.size > MAX_SPOTS)) spots.pollFirst()
        }
    }

    /** The spots of the last [depuisMs] on [bande] ("" all). */
    fun recents(depuisMs: Long, bande: String = ""): List<Spot> {
        dernierAcces = horloge()
        val limite = horloge() - depuisMs
        synchronized(spots) { return spots.filter { it.ts >= limite && (bande.isEmpty() || it.bande == bande) } }
    }

    private fun boucle() {
        val moi = Thread.currentThread()
        var attente = 5_000L
        while (fil === moi) {
            if (horloge() - dernierAcces > INACTIF_MS) break
            try {
                Socket().use { s ->
                    prise = s
                    s.connect(InetSocketAddress(hote, port), 10_000)
                    s.soTimeout = 3_000
                    connecte = true; erreur = null; attente = 5_000L
                    lis(s.getInputStream(), s.getOutputStream(), moi)
                }
            } catch (e: Exception) {
                erreur = (e.message ?: e.javaClass.simpleName).take(120)
            } finally { connecte = false; prise = null }
            if (fil !== moi || horloge() - dernierAcces > INACTIF_MS) break
            try { Thread.sleep(attente) } catch (e: InterruptedException) { break }
            attente = (attente * 2).coerceAtMost(5 * 60_000L)
        }
        synchronized(this) { if (fil === moi) fil = null }
    }

    private fun lis(e: InputStream, s: java.io.OutputStream, moi: Thread) {
        var connu = false
        val ligne = StringBuilder()
        val b = ByteArray(8192)
        fun login() { if (!connu) { s.write((indicatif + "\r\n").toByteArray(Charsets.US_ASCII)); s.flush(); connu = true } }
        while (fil === moi && horloge() - dernierAcces <= INACTIF_MS) {
            val n = try { e.read(b) } catch (x: SocketTimeoutException) { login(); continue }
            if (n < 0) return
            for (i in 0 until n) {
                val c = (b[i].toInt() and 0xFF).toChar()
                if (c == '\n') {
                    val l = ligne.toString(); ligne.setLength(0)
                    analyse(l, horloge())?.let { ajoute(it) }
                } else if (ligne.length < 400) ligne.append(c)
            }
            // The login prompt comes without an end of line (« Please enter your call: »).
            if (!connu && ligne.contains("call", true) || !connu && ligne.contains("login", true)) { login(); ligne.setLength(0) }
        }
    }

    companion object {
        const val GARDE_MS = 35 * 60_000L
        const val MAX_SPOTS = 80_000
        const val INACTIF_MS = 10 * 60_000L

        /** `DX de EA5WU-#: 7020.5 F4IOZ CW 13 dB 22 WPM CQ 2137Z` — RTTY's say BPS (the old site dropped them). */
        private val LIGNE = Regex("^DX de\\s+(\\S+?):\\s+([\\d.]+)\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)\\s*dB\\s+(\\d+)\\s*(?:WPM|BPS)\\s+(\\S+(?:\\s\\S+)?)\\s+(\\d{3,4})Z")

        /** One telnet line, null when it is not a spot. The time is the site's ([ts]), the line's is the minute only. */
        fun analyse(l: String, ts: Long): Spot? {
            val m = LIGNE.find(l.trim()) ?: return null
            val g = m.groupValues
            val skimmer = g[1].uppercase().removeSuffix("-#").substringBefore('-').take(16)
            val khz = g[2].toDoubleOrNull() ?: return null
            val dx = g[3].uppercase().take(16)
            if (skimmer.isEmpty() || dx.isEmpty()) return null
            val pos = IndicatifGeo.position(skimmer)
            return Spot(skimmer, Math.round(khz * 10) / 10.0, Bandes.deKhz(khz), dx, g[4].uppercase().take(8), g[5].toInt(), g[6].toInt(),
                g[7].trim().uppercase().take(12), ts, pos?.first, pos?.second)
        }

        private fun r1(d: Double) = Math.round(d * 10) / 10.0

        /** Per band: spots, distinct skimmers and DX, mean and best SNR; in the pages' band order. */
        fun parBande(l: List<Spot>): JSONArray {
            val g = l.groupBy { it.bande }.filterKeys { it.isNotEmpty() }
            val ordre = Bandes.ORDRE_HF + Bandes.TOUTES.map { it.nom }
            return JSONArray(g.entries.sortedBy { ordre.indexOf(it.key).let { i -> if (i < 0) 999 else i } }.map { (b, s) ->
                JSONObject().put("band", b).put("spots", s.size).put("skimmers", s.map { it.skimmer }.toSet().size)
                    .put("dx", s.map { it.dx }.toSet().size).put("snr_avg", r1(s.map { it.snr }.average())).put("snr_max", s.maxOf { it.snr })
            })
        }

        /**
         * Where [indicatif] is heard: its spots by located skimmers, the best SNR per (skimmer, band), distance and
         * bearing from [home] (the station's locator when it is the site's own call, else its country's centre).
         */
        fun empreinte(indicatif: String, l: List<Spot>, home: Pair<Double, Double>?): JSONObject {
            val meilleurs = LinkedHashMap<String, Spot>()
            for (s in l) {
                if (s.dx != indicatif || s.rxLat == null || s.rxLon == null) continue
                val k = s.skimmer + "|" + s.bande
                val a = meilleurs[k]
                if (a == null || s.snr > a.snr) meilleurs[k] = s
            }
            val spots = JSONArray()
            var meilleur: JSONObject? = null
            for (s in meilleurs.values) {
                val o = JSONObject().put("band", s.bande).put("call", s.skimmer).put("loc", "").put("lat", s.rxLat).put("lon", s.rxLon)
                    .put("snr", s.snr).put("ts", s.ts / 1000)
                if (home != null) {
                    o.put("dist", Math.round(Geo.distanceKm(home.first, home.second, s.rxLat!!, s.rxLon!!)))
                        .put("az", Math.round(Geo.azimut(home.first, home.second, s.rxLat, s.rxLon!!)))
                } else o.put("dist", 0).put("az", 0)
                spots.put(o)
                if (meilleur == null || o.getLong("dist") > meilleur.getLong("dist")) meilleur = o
            }
            val o = JSONObject().put("call", indicatif).put("direction", "tx").put("spots", spots).put("count", spots.length())
                .put("reporters", meilleurs.values.map { it.skimmer }.toSet().size)
                .put("bands", JSONArray(meilleurs.values.map { it.bande }.toSet().sortedBy { Bandes.ORDRE_HF.indexOf(it) }))
                .put("best_dx", meilleur ?: JSONObject.NULL)
            if (home != null) o.put("home", JSONObject().put("lat", home.first).put("lon", home.second))
            return o
        }

        /** The propagation map's blobs: 6° cells of the skimmers (banker's rounding, as the old site), top-4 DX calls. */
        fun carte(l: List<Spot>): JSONObject {
            val cellules = LinkedHashMap<Long, MutableList<Spot>>()
            val parBande = HashMap<String, Int>()
            for (s in l) {
                if (s.bande.isNotEmpty()) parBande.merge(s.bande, 1, Int::plus)
                if (s.rxLat == null || s.rxLon == null) continue
                val k = Math.rint(s.rxLat / 6).toLong() * 1000 + Math.rint(s.rxLon / 6).toLong()
                cellules.getOrPut(k) { ArrayList() } += s
            }
            val blobs = cellules.values.sortedByDescending { it.size }.take(160).map { c ->
                JSONObject().put("lat", Math.round(c.map { it.rxLat!! }.average() * 100) / 100.0).put("lon", Math.round(c.map { it.rxLon!! }.average() * 100) / 100.0)
                    .put("count", c.size).put("snr", r1(c.map { it.snr }.average()))
                    .put("bands", JSONObject(c.groupingBy { it.bande }.eachCount().filterKeys { it.isNotEmpty() }.entries.sortedByDescending { it.value }.associate { it.key to it.value }))
                    .put("dxcc", JSONArray(c.groupingBy { it.dx }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }))
            }
            return JSONObject().put("source", "rbn").put("total", l.size)
                .put("by_band", JSONObject(parBande.entries.sortedByDescending { it.value }.associate { it.key to it.value }))
                .put("blobs", JSONArray(blobs)).put("qth_paths", JSONArray())
        }
    }
}
