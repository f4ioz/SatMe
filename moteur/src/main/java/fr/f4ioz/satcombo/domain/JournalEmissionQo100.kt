/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Every over on QO-100's narrow band, kept for the operator: when, how
 * long, which mode and uplink, the gain, where it went (the air, a dummy
 * load, a simulation), the highest level seen against the beacon, and why
 * it ended (released, time-out, LEILA, the level guard…). A look back at
 * one's own transmissions — nothing here ever transmits.
 */
class JournalEmissionQo100(private val max: Int = MAX) {
    data class Passage(
        val debutMs: Long,
        val dureeMs: Long,
        /** [MonteeQo100.Mode.cle]. */
        val mode: String,
        /** The uplink dial (Hz). */
        val monteeHz: Long,
        /** The gain applied at the end (dB). */
        val gainDb: Double,
        /** "air", "charge" or "simulation". */
        val destination: String,
        /** What was sent: the microphone, a sound card, CW text, a voice memory… */
        val source: String,
        /** The highest level seen against the beacon (dB), null when not measured. */
        val ecartMaxDb: Double?,
        /** Why it ended. */
        val fin: String,
    ) {
        fun json(): JSONObject = JSONObject().put("debut", debutMs).put("duree", dureeMs).put("mode", mode).put("montee", monteeHz)
            .put("gain", gainDb).put("dest", destination).put("source", source)
            .put("ecartMax", ecartMaxDb ?: JSONObject.NULL).put("fin", fin)

        companion object {
            fun lis(o: JSONObject): Passage? = runCatching {
                Passage(o.getLong("debut"), o.getLong("duree"), o.optString("mode"), o.optLong("montee"), o.optDouble("gain", 0.0),
                    o.optString("dest", "charge"), o.optString("source"), if (o.isNull("ecartMax")) null else o.optDouble("ecartMax"),
                    o.optString("fin"))
            }.getOrNull()
        }
    }

    private val passages = ArrayDeque<Passage>()

    @Synchronized fun ajoute(p: Passage) {
        passages.addFirst(p)
        while (passages.size > max) passages.removeLast()
    }

    /** The most recent first. */
    @Synchronized fun liste(): List<Passage> = passages.toList()

    @Synchronized fun efface() = passages.clear()

    /** Time on the air today (ms, [zone]'s day), dummy load and simulation left out. */
    fun surLAirAujourdhui(t: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val jour = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        return liste().filter { it.destination == "air" && Instant.ofEpochMilli(it.debutMs).atZone(zone).toLocalDate() == jour }.sumOf { it.dureeMs }
    }

    @Synchronized fun texte(): String = JSONArray(passages.map { it.json() }).toString()

    @Synchronized fun reprends(t: String?) {
        passages.clear()
        val a = runCatching { JSONArray(t ?: return) }.getOrNull() ?: return
        for (i in 0 until minOf(a.length(), max)) a.optJSONObject(i)?.let(Passage::lis)?.let { passages.addLast(it) }
    }

    /** The list as CSV (UTC times) for a spreadsheet. */
    fun csv(): String {
        val b = StringBuilder("debut_utc;duree_s;mode;montee_hz;gain_db;destination;source;ecart_max_db;fin\n")
        for (p in liste()) b.append(Instant.ofEpochMilli(p.debutMs)).append(';').append(p.dureeMs / 1000.0).append(';').append(p.mode).append(';')
            .append(p.monteeHz).append(';').append(p.gainDb).append(';').append(p.destination).append(';').append(p.source.replace(';', ',')).append(';')
            .append(p.ecartMaxDb ?: "").append(';').append(p.fin.replace(';', ',')).append('\n')
        return b.toString()
    }

    companion object {
        const val MAX = 200
    }
}
