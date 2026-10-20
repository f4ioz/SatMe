/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.data.AgentHttp
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Received frames shared with SatNOGS DB by the public « SiDS » protocol:
 * one HTTP POST (form fields) per frame — NORAD number, the station's
 * callsign, the reception time (UTC), the frame in hexadecimal, the
 * position (rounded to [ARRONDI_DEG], about 10 km).
 *
 * Off unless the operator ticks it; frames made by SatMe's tests are never
 * sent. Frames wait in a small queue and are tried again when the network
 * comes back.
 */
class Sids(
    private val url: String = URL_DB,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    class Envoi(val norad: Int, val indicatif: String, val tMs: Long, val hex: String, val latDeg: Double, val lonDeg: Double)

    private val file = java.util.ArrayDeque<Envoi>()
    @Volatile var envoyees = 0; private set
    @Volatile var echecs = 0; private set
    @Volatile var derniereErreur: String? = null; private set
    @Volatile var dernierEnvoiMs = 0L; private set
    val enAttente: Int get() = synchronized(file) { file.size }

    fun ajoute(e: Envoi) = synchronized(file) {
        if (file.size >= MAX_FILE) file.pollFirst()
        file.addLast(e)
    }

    /** Sends what waits (blocking; from a worker thread). Stops at the first failure, kept for later. */
    fun vide(): Int {
        var n = 0
        while (true) {
            val e = synchronized(file) { file.peekFirst() } ?: break
            val ok = runCatching { envoie(e) }.getOrElse { derniereErreur = it.message ?: it.javaClass.simpleName; false }
            if (!ok) { echecs++; break }
            synchronized(file) { if (file.peekFirst() === e) file.pollFirst() }
            envoyees++; n++
            dernierEnvoiMs = horloge()
        }
        return n
    }

    private fun envoie(e: Envoi): Boolean {
        val f = FormBody.Builder()
        champs(e).forEach { (k, v) -> f.add(k, v) }
        val req = Request.Builder().url(url).header("User-Agent", AgentHttp.USER_AGENT).post(f.build()).build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) return true
            derniereErreur = "HTTP " + r.code
            return false
        }
    }

    companion object {
        const val URL_DB = "https://db.satnogs.org/api/telemetry/"
        const val ARRONDI_DEG = 0.1
        const val MAX_FILE = 500

        private fun arrondi(v: Double): Double = (v / ARRONDI_DEG).roundToLong() * ARRONDI_DEG

        /** « 48.9N », « 2.3E »: SiDS' way of writing a position. */
        fun latitude(deg: Double): String { val a = arrondi(deg); return String.format(java.util.Locale.ROOT, "%.1f%s", abs(a), if (a < 0) "S" else "N") }
        fun longitude(deg: Double): String { val a = arrondi(deg); return String.format(java.util.Locale.ROOT, "%.1f%s", abs(a), if (a < 0) "W" else "E") }

        fun horodatage(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

        /** The form's fields, in order. */
        fun champs(e: Envoi): List<Pair<String, String>> = listOf(
            "noradID" to e.norad.toString(),
            "source" to e.indicatif.trim().uppercase(),
            "timestamp" to horodatage(e.tMs),
            "frame" to e.hex.uppercase(),
            "locator" to "longLat",
            "longitude" to longitude(e.lonDeg),
            "latitude" to latitude(e.latDeg),
            "version" to "SatMe",
        )
    }
}
