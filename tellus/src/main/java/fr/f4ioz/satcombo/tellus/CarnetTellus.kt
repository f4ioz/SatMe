/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.ControleAdif
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TellusMe's logbook: the engine's ([LogStore], [Adif], [ControleAdif]), in
 * TellusMe's own folder — never SatMe's. A terrestrial contact has no
 * satellite: the same frequency on both sides (FREQ = FREQ_RX), no SAT_NAME,
 * no PROP_MODE.
 */
class CarnetTellus(dossier: File) {
    private val store = LogStore(File(dossier, "qso_log.json"))

    fun contacts(): List<LogEntry> = store.load()

    fun ajoute(indicatif: String, hz: Long, mode: String, rstEnvoye: String, rstRecu: String, locator: String,
               note: String, monLocator: String, tMs: Long = System.currentTimeMillis()): LogEntry? {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty() || hz <= 0) return null
        val mhz = hz / 1e6
        val e = LogEntry(timeMs = tMs, satName = "", catnum = 0, azimuthDeg = 0.0, elevationDeg = -1.0,
            myLocator = monLocator.trim().uppercase(), callsign = call, theirLocator = locator.trim().uppercase(),
            note = note.trim(), mode = mode.trim().uppercase(), rstSent = rstEnvoye.trim(), rstRcvd = rstRecu.trim(),
            downlinkMhz = mhz, uplinkMhz = mhz)
        store.add(e)
        return e
    }

    fun supprime(tMs: Long) { store.delete(tMs) }

    fun adif(station: String): String = Adif.export(contacts(), station, "TellusMe")

    fun controle(): JSONObject { val l = contacts(); return ControleAdif.json(ControleAdif.controle(l), l.size) }

    fun json(n: Int = 50): JSONObject {
        val l = contacts()
        return JSONObject().put("total", l.size).put("contacts", JSONArray(l.take(n).map {
            JSONObject().put("t", it.timeMs).put("c", it.callsign).put("mhz", it.downlinkMhz).put("bande", Adif.band(it.downlinkMhz))
                .put("mode", it.mode).put("rs", it.rstSent).put("rr", it.rstRcvd).put("loc", it.theirLocator).put("n", it.note)
        }))
    }
}
