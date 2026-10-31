/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.AdifImport
import fr.f4ioz.satcombo.data.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A contact edited **after** it went to the online log: what changed, and how
 * Wavelog can be told without a duplicate.
 *
 * What was sent is kept with the contact ([LogEntry.envoiAdif]): the ADIF
 * fields of the record as it left (station fields aside: they come from the
 * profile, not from the contact), written when the server took it. The
 * contact is « modifié depuis l'envoi » while its fields today differ from
 * those — undo the edit and the mark goes. On disk with the log, so it
 * survives restarts.
 *
 * **Never posted again.** Wavelog's `api/qso` (v1, the one SatMe uploads
 * with) cannot change a contact: it skips one with the same call, time (to
 * the minute), band, mode and station as a duplicate, and creates a new one
 * when any of those changed — the wrong contact stays beside the right one.
 * So the upload never takes an edited contact again; the change goes either
 * by hand (the fields listed) or through Wavelog's APIv2 (3.1.0 and later,
 * a `wl2_` token): the contact found by its **old** call and time, then
 * `PATCH /api/v2/qso/{id}` with the changed fields only. APIv2 cannot edit
 * VUCC_GRIDS, EMAIL or the MY_* fields: those stay « à la main ».
 */
object ModifEnvoi {

    /** Per-contact fields of the record, not the station's (STATION_CALLSIGN, OPERATOR come from the profile). */
    private val IGNORES = setOf("STATION_CALLSIGN", "OPERATOR", "ANT_AZ", "ANT_EL")

    /** The ADIF fields, in words, for the list of changes. */
    val LIBELLES = mapOf(
        "CALL" to "indicatif", "QSO_DATE" to "date", "TIME_ON" to "heure", "SAT_NAME" to "satellite",
        "PROP_MODE" to "propagation", "SAT_MODE" to "mode du satellite", "MODE" to "mode", "SUBMODE" to "sous-mode",
        "RST_SENT" to "report envoyé", "RST_RCVD" to "report reçu", "FREQ" to "fréquence d'émission",
        "BAND" to "bande d'émission", "FREQ_RX" to "fréquence de réception", "BAND_RX" to "bande de réception",
        "GRIDSQUARE" to "locator", "VUCC_GRIDS" to "ligne de locators", "MY_GRIDSQUARE" to "mon locator",
        "MY_VUCC_GRIDS" to "ma ligne de locators", "NAME" to "nom", "QTH" to "ville", "EMAIL" to "courriel",
        "COMMENT" to "commentaire")

    /** What the record of [e] carries today, field by field. */
    fun empreinte(e: LogEntry): Map<String, String> =
        AdifImport.champs(Adif.enregistrement(e)).filterKeys { it !in IGNORES }

    fun ecrit(m: Map<String, String>): String = JSONObject(m as Map<*, *>).toString()

    fun lit(s: String): Map<String, String>? = if (s.isBlank()) null else runCatching {
        val o = JSONObject(s); o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrNull()

    /** A field that differs: what Wavelog has, what SatMe has now ("" absent). */
    data class Ecart(val champ: String, val avant: String, val apres: String) {
        val libelle: String get() = LIBELLES[champ] ?: champ.lowercase()
        /** Can APIv2 change it? */
        val modifiable: Boolean get() = champ in V2
    }

    /** The changes since the upload; empty when not uploaded, nothing kept, or nothing changed. */
    fun ecarts(e: LogEntry): List<Ecart> {
        if (e.envoyeMs <= 0L) return emptyList()
        val avant = lit(e.envoiAdif) ?: return emptyList()
        val apres = empreinte(e)
        return (avant.keys + apres.keys).sortedBy { ORDRE.indexOf(it).let { i -> if (i < 0) 99 else i } }
            .mapNotNull { k -> val a = avant[k].orEmpty(); val b = apres[k].orEmpty(); if (a == b) null else Ecart(k, a, b) }
    }

    fun modifie(e: LogEntry): Boolean = ecarts(e).isNotEmpty()

    private val memo = java.util.concurrent.ConcurrentHashMap<LogEntry, List<Ecart>>()

    /** [ecarts], remembered per contact as it is (the page asks for the whole log every few seconds). */
    fun ecartsMemo(e: LogEntry): List<Ecart> {
        if (e.envoyeMs <= 0L || e.envoiAdif.isBlank()) return emptyList()
        if (memo.size > 20_000) memo.clear()
        return memo.getOrPut(e) { ecarts(e) }
    }

    private val ORDRE = listOf("CALL", "QSO_DATE", "TIME_ON", "SAT_NAME", "MODE", "SUBMODE", "RST_SENT", "RST_RCVD", "GRIDSQUARE", "VUCC_GRIDS",
        "FREQ", "FREQ_RX", "BAND", "BAND_RX", "SAT_MODE", "PROP_MODE", "NAME", "QTH", "EMAIL", "COMMENT", "MY_GRIDSQUARE", "MY_VUCC_GRIDS")

    /** « indicatif F4XYZ → F4XZY ; locator JN18 → JN19 », for the page and the messages. */
    fun texte(l: List<Ecart>): String = l.joinToString(" ; ") { it.libelle + " " + it.avant.ifBlank { "∅" } + " → " + it.apres.ifBlank { "∅" } }

    // ------------------------------------------------------------------ Wavelog APIv2

    /** The ADIF fields APIv2's PATCH changes (Qso_resource), and their key there. */
    private val V2 = mapOf(
        "CALL" to "call", "BAND" to "band", "BAND_RX" to "band_rx", "RST_SENT" to "rst_sent", "RST_RCVD" to "rst_rcvd",
        "GRIDSQUARE" to "gridsquare", "NAME" to "name", "COMMENT" to "comment", "QTH" to "qth", "PROP_MODE" to "prop_mode",
        "SAT_NAME" to "sat_name", "SAT_MODE" to "sat_mode", "MODE" to "mode", "SUBMODE" to "mode",
        "FREQ" to "freq", "FREQ_RX" to "freq_rx", "QSO_DATE" to "qso_date", "TIME_ON" to "time_on")

    /**
     * The PATCH body for [l]: only what changed and APIv2 can change. Date and
     * time always together (Wavelog wants both); frequencies in Hz; the mode
     * as its submode when there is one (Wavelog splits it again). Null when
     * nothing in [l] can go that way.
     */
    fun corpsV2(e: LogEntry, l: List<Ecart>): JSONObject? {
        val v = l.filter { it.modifiable }
        if (v.isEmpty()) return null
        val f = empreinte(e)
        val o = JSONObject()
        v.forEach { x ->
            when (x.champ) {
                "QSO_DATE", "TIME_ON" -> {
                    val d = f["QSO_DATE"].orEmpty()
                    if (d.length == 8) o.put("qso_date", d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8))
                    o.put("time_on", f["TIME_ON"].orEmpty())
                }
                "MODE", "SUBMODE" -> o.put("mode", f["SUBMODE"].orEmpty().ifBlank { f["MODE"].orEmpty() })
                "FREQ", "FREQ_RX" -> {
                    val mhz = x.apres.toDoubleOrNull()
                    o.put(V2.getValue(x.champ), if (mhz == null) JSONObject.NULL else Math.round(mhz * 1e6))
                }
                else -> o.put(V2.getValue(x.champ), x.apres.ifBlank { null } ?: JSONObject.NULL)
            }
        }
        return o
    }

    /** A contact of the online log as APIv2 lists it. */
    data class QsoV2(val id: Long, val call: String, val quandMs: Long?, val satName: String, val stationId: String)

    /** The `data` list of `GET /api/v2/qso`; empty when unreadable. */
    fun litListeV2(json: String): List<QsoV2> = runCatching {
        val a = JSONObject(json).optJSONArray("data") ?: JSONArray()
        (0 until a.length()).map { i ->
            val q = a.getJSONObject(i)
            QsoV2(q.optLong("id"), q.optString("call"), instantV2(q.optString("qso_date"), q.optString("time_on")),
                q.optString("sat_name").takeIf { it != "null" }.orEmpty(), q.optString("station_id"))
        }
    }.getOrDefault(emptyList())

    /** "2026-10-31 14:03:21" (or a date and a separate time) → ms UTC. */
    fun instantV2(date: String, heure: String = ""): Long? {
        val t = (date.trim() + (if (heure.isNotBlank() && !date.contains(':')) " " + heure.trim() else "")).replace('T', ' ')
        for (f in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd HHmmss", "yyyyMMdd HHmmss")) {
            val p = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            runCatching { return p.parse(t)!!.time }
        }
        return null
    }

    /** The day of [ms], as APIv2's filters want it. */
    fun jourV2(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /** How far apart the online time and the one sent may be (Wavelog keeps seconds; a minute for rounding). */
    const val ECART_V2_MS = 60_000L

    /**
     * The one online contact that is the contact as it was sent ([avant]: the
     * fields kept at the upload): same call, time within [ECART_V2_MS], same
     * satellite when both say one. Null when none — or **more than one**:
     * changing the wrong one is worse than changing none.
     */
    fun trouveV2(avant: Map<String, String>, liste: List<QsoV2>): QsoV2? {
        val call = avant["CALL"].orEmpty().trim().uppercase()
        val quand = AdifImport.instant(avant["QSO_DATE"].orEmpty(), avant["TIME_ON"].orEmpty()) ?: return null
        val sat = avant["SAT_NAME"].orEmpty().trim()
        val l = liste.filter { q ->
            q.call.trim().equals(call, true) && q.quandMs != null && kotlin.math.abs(q.quandMs - quand) <= ECART_V2_MS &&
                (sat.isEmpty() || q.satName.isBlank() || q.satName.equals(sat, true) || JournalPassage.memeSatellite(q.satName, sat) ||
                    // SatMe sends the space station as « ARISS »; Wavelog may keep « ISS ».
                    NomSatellite.satNameAdif(q.satName).equals(NomSatellite.satNameAdif(sat), true))
        }
        return l.singleOrNull()
    }

    /**
     * What Wavelog has after a PATCH that took [l]: the fields kept at the
     * upload with those changed — what APIv2 cannot change stays as it was,
     * so the contact stays marked for them.
     */
    fun apresV2(e: LogEntry, avant: Map<String, String>, l: List<Ecart>): Map<String, String> {
        val f = empreinte(e)
        val m = avant.toMutableMap()
        l.filter { it.modifiable }.forEach { x ->
            val ks = when (x.champ) { "QSO_DATE", "TIME_ON" -> listOf("QSO_DATE", "TIME_ON"); "MODE", "SUBMODE" -> listOf("MODE", "SUBMODE"); else -> listOf(x.champ) }
            ks.forEach { k -> val v = f[k]; if (v == null) m.remove(k) else m[k] = v }
        }
        return m
    }

    /** Wavelog's reason in a line: `{"error":{"code","message"}}`, else the text. */
    fun raisonV2(reponse: String): String = runCatching {
        val o = JSONObject(reponse.substringAfter('{', "").let { "{$it" })
        val e = o.optJSONObject("error")
        e?.optString("message")?.ifBlank { e.optString("code") }
    }.getOrNull()?.ifBlank { null } ?: reponse.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(160)
}
