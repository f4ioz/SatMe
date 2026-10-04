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
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Posts an "I heard it / I did not hear it" report to the AMSAT Live OSCAR
 * Status page — the same crowd-sourced table SatMe already reads.
 *
 * AMSAT slices the day in quarter-hours: a report belongs to the 15-minute
 * period it was made in, in UTC. Everything below is therefore computed in UTC,
 * never in local time.
 */
object AmsatSubmit {

    /** What the operator answers in the dialog. */
    enum class Report(val wire: String) {
        HEARD("Heard"),
        NOT_HEARD("Not Heard")
    }

    private const val ENDPOINT = "https://www.amsat.org/status/submit.php"

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Builds the submission URL. Kept separate from [send] so it can be checked
     * without touching the network.
     */
    fun url(
        satName: String, report: Report, callsign: String, locator: String,
        timeMs: Long = System.currentTimeMillis()
    ): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val period = c.get(Calendar.MINUTE) / 15          // 0..3, AMSAT quarter-hour slot
        return ENDPOINT +
            "?SatSubmit=yes&Confirm=yes" +
            "&SatName=" + enc(satName) +
            "&SatYear=" + c.get(Calendar.YEAR) +
            "&SatMonth=" + (c.get(Calendar.MONTH) + 1) +
            "&SatDay=" + c.get(Calendar.DAY_OF_MONTH) +
            "&SatHour=" + c.get(Calendar.HOUR_OF_DAY) +
            "&SatPeriod=" + period +
            "&SatCall=" + enc(callsign.trim().uppercase()) +
            "&SatReport=" + enc(report.wire) +
            "&SatGridSquare=" + enc(locator.trim().uppercase())
    }

    /** Sends the report. Returns true when AMSAT accepted it. */
    suspend fun send(
        satName: String, report: Report, callsign: String, locator: String,
        timeMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        if (callsign.isBlank() || satName.isBlank()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url(url(satName, report, callsign, locator, timeMs))
                .header("User-Agent", TleRepository.USER_AGENT)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
