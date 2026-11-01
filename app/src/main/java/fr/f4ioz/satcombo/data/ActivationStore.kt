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
import org.json.JSONArray
import org.json.JSONObject

/**
 * One field session ("activation"): where you were, when you started and
 * stopped, and everything worked in between. The QSOs themselves stay in
 * [LogStore]; an activation only records the window, so a contact logged during
 * the session is automatically part of it — no double bookkeeping.
 */
data class Activation(
    val startMs: Long,               // also the identifier
    val endMs: Long? = null,         // null = still running
    val name: String = "",           // site name typed by the operator
    val locator: String = "",        // 6-char Maidenhead at the start
    /**
     * The big squares the site belongs to when it sits on a grid line,
     * comma-separated and ours first ("JN18,JN19"). Empty when the site is
     * comfortably inside one square, so the sheet stays quiet in the usual case.
     */
    val grids: String = "",
    val latDeg: Double = 0.0,
    val lonDeg: Double = 0.0,
    val callsign: String = "",
    val note: String = "",
    val photoPath: String = "",      // optional QRV photo (absolute path)
    /**
     * The POTA park(s) activated ("FR-0012", or a list "FR-0012,FR-0345"):
     * written as MY_POTA_REF / MY_SIG=POTA / MY_SIG_INFO on the session's
     * contacts in its ADIF. Empty: not a POTA activation.
     */
    val potaRef: String = ""
) {
    val running: Boolean get() = endMs == null
    fun durationMs(nowMs: Long = System.currentTimeMillis()): Long =
        ((endMs ?: nowMs) - startMs).coerceAtLeast(0)
}

/** Persists the activation list to filesDir/activations.json (newest first). */
class ActivationStore(context: Context) {
    private val file = context.filesDir.resolve("activations.json")

    fun load(): List<Activation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Activation(
                    startMs = o.getLong("s"),
                    endMs = if (o.isNull("e")) null else o.getLong("e"),
                    name = o.optString("n"),
                    locator = o.optString("loc"),
                    grids = o.optString("mg"),
                    latDeg = o.optDouble("lat", 0.0),
                    lonDeg = o.optDouble("lon", 0.0),
                    callsign = o.optString("cs"),
                    note = o.optString("note"),
                    photoPath = o.optString("photo"),
                    potaRef = o.optString("pota")
                )
            }.sortedByDescending { it.startMs }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<Activation>) {
        val arr = JSONArray()
        list.sortedByDescending { it.startMs }.forEach { a ->
            arr.put(JSONObject().apply {
                put("s", a.startMs)
                if (a.endMs == null) put("e", JSONObject.NULL) else put("e", a.endMs)
                put("n", a.name); put("loc", a.locator); put("mg", a.grids)
                put("lat", a.latDeg); put("lon", a.lonDeg)
                put("cs", a.callsign); put("note", a.note); put("photo", a.photoPath)
                if (a.potaRef.isNotBlank()) put("pota", a.potaRef)
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    fun current(): Activation? = load().firstOrNull { it.running }

    /** Starts a session, closing any previous one that was left running. */
    fun start(a: Activation): List<Activation> {
        val now = a.startMs
        val list = load().map { if (it.running) it.copy(endMs = now) else it }.toMutableList()
        list.add(0, a)
        save(list)
        return load()
    }

    fun stop(startMs: Long, endMs: Long = System.currentTimeMillis()): List<Activation> {
        save(load().map { if (it.startMs == startMs && it.running) it.copy(endMs = endMs) else it })
        return load()
    }

    fun update(a: Activation): List<Activation> {
        save(load().map { if (it.startMs == a.startMs) a else it })
        return load()
    }

    fun delete(startMs: Long): List<Activation> {
        save(load().filterNot { it.startMs == startMs })
        return load()
    }

    companion object {
        /** QSOs of [a]: every log entry inside the session window. */
        fun qsosOf(a: Activation, log: List<LogEntry>): List<LogEntry> {
            val end = a.endMs ?: Long.MAX_VALUE
            return log.filter { it.timeMs in a.startMs..end }.sortedBy { it.timeMs }
        }

        /**
         * ADIF of [a]'s contacts. A POTA activation's park goes on each of
         * them (MY_POTA_REF, MY_SIG=POTA, MY_SIG_INFO), as the POTA upload wants.
         */
        fun adifOf(a: Activation, log: List<LogEntry>, station: String = ""): String =
            LogStore.toAdif(ChampsAdif.enActivationPota(qsosOf(a, log), a.potaRef), station)

        /** Distinct satellites worked during [a], in first-worked order. */
        fun satsOf(a: Activation, log: List<LogEntry>): List<String> =
            qsosOf(a, log).map { it.satName }.filter { it.isNotBlank() }.distinct()
    }
}
