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

/** A minimal logged contact: time + satellite + pointing, no callsign (yet). */
data class LogEntry(
    val timeMs: Long,
    val satName: String,
    val catnum: Int,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val myLocator: String = "",
    /**
     * Every big square the station legitimately sits in when it operates close
     * to a grid line: "JN18,JN19", or four of them on a corner. Comma-separated
     * 4-character squares, the first one being ours. Empty when the site is
     * comfortably inside a single square.
     */
    val myGrids: String = "",
    val callsign: String = "",
    val theirLocator: String = "",
    val note: String = "",
    /**
     * What the directory tells about the other station: name, city, email.
     * From QRZ or typed; Wavelog has a field for each, otherwise they must be
     * filled in one by one on its screen.
     */
    val nom: String = "",
    val qth: String = "",
    val courriel: String = "",
    /** Mode as given by the transponder or chosen by hand: FM, USB… */
    val mode: String = "",
    /** Sent and received reports. Empty until the operator enters them. */
    val rstSent: String = "",
    val rstRcvd: String = "",
    /** Nominal downlink and uplink in MHz, for BAND and SAT_MODE. */
    val downlinkMhz: Double = 0.0,
    val uplinkMhz: Double = 0.0,
    /**
     * When this contact was uploaded to the online log, or 0.
     *
     * Without it every upload would resend the whole log, and Wavelog does not
     * deduplicate. A timestamp rather than a flag: it can say "uploaded
     * yesterday" and restore order if the remote log loses something.
     */
    val envoyeMs: Long = 0L,
    /**
     * Where [theirLocator] came from: typed, suggested from memory, or unknown.
     * Cannot be rebuilt later, and tells whether the database or the typing
     * was wrong.
     */
    val locatorOrigine: String = ""
)

/** Persists quick log entries to disk (filesDir/qso_log.json). */
class LogStore(context: Context) {
    private val file = context.filesDir.resolve("qso_log.json")

    fun load(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LogEntry(
                    timeMs = o.getLong("t"),
                    satName = o.optString("s"),
                    catnum = o.optInt("c"),
                    azimuthDeg = o.optDouble("az"),
                    elevationDeg = o.optDouble("el"),
                    myLocator = o.optString("ml"),
                    myGrids = o.optString("mg"),
                    callsign = o.optString("cs"),
                    theirLocator = o.optString("tl"),
                    note = o.optString("n"),
                    mode = o.optString("md"),
                    rstSent = o.optString("rs"),
                    rstRcvd = o.optString("rr"),
                    downlinkMhz = o.optDouble("dl", 0.0),
                    uplinkMhz = o.optDouble("ul", 0.0),
                    envoyeMs = o.optLong("ev", 0L),
                    locatorOrigine = o.optString("lo"),
                    nom = o.optString("nm"),
                    qth = o.optString("qt"),
                    courriel = o.optString("em")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timeMs); put("s", e.satName); put("c", e.catnum)
                put("az", e.azimuthDeg); put("el", e.elevationDeg); put("n", e.note)
                put("ml", e.myLocator); put("cs", e.callsign); put("tl", e.theirLocator)
                put("mg", e.myGrids)
                put("md", e.mode); put("rs", e.rstSent); put("rr", e.rstRcvd)
                put("dl", e.downlinkMhz); put("ul", e.uplinkMhz)
                put("ev", e.envoyeMs)
                put("lo", e.locatorOrigine)
                put("nm", e.nom); put("qt", e.qth); put("em", e.courriel)
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    fun add(entry: LogEntry): List<LogEntry> {
        val list = load().toMutableList()
        list.add(0, entry)   // newest first
        save(list)
        return list
    }

    fun update(
        timeMs: Long, callsign: String, theirLocator: String, note: String,
        mode: String, rstSent: String, rstRcvd: String
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                callsign = callsign, theirLocator = theirLocator, note = note,
                mode = mode, rstSent = rstSent, rstRcvd = rstRcvd) else it
        }
        save(list)
        return list
    }

    /**
     * Names a pending entry and takes it out of the queue. [origine] is written
     * only here: the only moment that information still exists.
     */
    /**
     * Changes a contact's satellite. The name is fixed at creation from the
     * selected satellite; when wrong (selection changed by mistake, contact
     * from a neighbouring pass) it skews the log, ADIF and worked squares.
     */
    fun changeSatellite(
        timeMs: Long, satName: String, catnum: Int,
        az: Double? = null, el: Double? = null, nouvelleHeure: Long? = null,
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                satName = satName, catnum = catnum,
                // Azimuth and elevation belong to satellite + time: recompute
                // them, or they mean nothing.
                azimuthDeg = az ?: it.azimuthDeg,
                elevationDeg = el ?: it.elevationDeg,
                timeMs = nouvelleHeure ?: it.timeMs)
            else it
        }
        save(list)
        return list
    }

    fun nomme(
        timeMs: Long, callsign: String, theirLocator: String, origine: String,
        rstSent: String = "", rstRcvd: String = "",
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                callsign = callsign, theirLocator = theirLocator,
                locatorOrigine = origine,
                // An empty field does not erase an existing report: naming and
                // reporting can come in either order.
                rstSent = rstSent.ifBlank { it.rstSent },
                rstRcvd = rstRcvd.ifBlank { it.rstRcvd }) else it
        }
        save(list)
        return list
    }

    /**
     * Changes an entry's time and recomputes its geometry. The time is the
     * entry's key, so the entry is rewritten; azimuth and elevation must
     * follow, or they describe another moment.
     */
    fun redate(ancienMs: Long, nouveauMs: Long, azDeg: Double, elDeg: Double): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == ancienMs)
                it.copy(timeMs = nouveauMs, azimuthDeg = azDeg, elevationDeg = elDeg)
            else it
        }.sortedByDescending { it.timeMs }
        save(list)
        return list
    }

    fun delete(timeMs: Long): List<LogEntry> {
        val list = load().filterNot { it.timeMs == timeMs }
        save(list)
        return list
    }

    /**
     * Marks a contact as uploaded. Written at once, not at the end of the
     * batch: an interruption keeps the work done instead of redoing it — and
     * creating duplicates in Wavelog, which does not deduplicate.
     */
    fun marqueEnvoye(timeMs: Long, quandMs: Long): List<LogEntry> {
        val list = load().map { if (it.timeMs == timeMs) it.copy(envoyeMs = quandMs) else it }
        save(list)
        return list
    }

    /**
     * ADIF of the whole log; [station] goes into STATION_CALLSIGN.
     *
     * **An entry without a callsign is not a contact.** The remote log rejects
     * or misfiles it, and LoTW can do nothing with it. The rule used to test the
     * "to be named" queue flag, which let anonymous compass marks through. The
     * callsign decides, and only it.
     */
    fun toAdif(station: String = ""): String =
        Adif.export(load().filter { it.callsign.isNotBlank() }, station)

    companion object {

        /**
         * ADIF of a selection of entries (whole log or activation). Built by
         * [Adif], which has no Android dependency and is tested on the JVM.
         */
        fun toAdif(entries: List<LogEntry>, station: String = ""): String =
            Adif.export(entries, station)
    }
}
