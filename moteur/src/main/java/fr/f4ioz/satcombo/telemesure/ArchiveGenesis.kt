/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a GENESIS receiver heard, kept for the journal: each telemetry frame
 * with a right CRC, and each SSDV packet with the picture it went into (a
 * packet the picture already held too: the pass heard it), one JSON line
 * each in `telemesure/genesis/trames/<yyyyMMdd>.jsonl` (UTC day):
 *
 *     {"t":…, "sat":"HADES-SA (SpinnyONE)", "norad":68446, "type":2, "hex":"…", "src":"radio", "d":"200"}
 *     {"t":…, "sat":"…", "type":10, "n":12, "ssdv":[{"cle":"<picture id>"}], "src":"radio", "d":"200"}
 *
 * (« d »: the rate it was heard at, « 200 » or « 800 » bit/s; not in the lines kept before it was.)
 *
 * The same frame (same type and bytes; the satellite's clock is in them) or
 * the same packet of the same picture within [DOUBLON_MS] is written once:
 * a recording read again over a pass already heard live adds nothing twice.
 */
class ArchiveGenesis(private val dossier: File?) {

    class Entree(val t: Long, val sat: String, val norad: Int, val type: Int, val hex: String, val numero: Int, val cle: String, val src: String,
                 /** « 200 », « 800 » (bit/s), "" when not kept. */
                 val debit: String = "")

    private val repertoire: File? get() = dossier?.let { File(it, DOSSIER) }

    /** Keys seen, by day file: key → times. */
    private val vus = HashMap<String, HashMap<String, MutableList<Long>>>()

    private fun cles(jour: String): HashMap<String, MutableList<Long>> = vus.getOrPut(jour) {
        val m = HashMap<String, MutableList<Long>>()
        lisJour(jour).forEach { e -> m.getOrPut(cle(e.type, e.hex, e.cle, e.numero)) { ArrayList() } += e.t }
        m
    }

    private fun cle(type: Int, hex: String, photo: String, numero: Int) =
        if (type == Genesis.TYPE_SSDV) "ssdv:$photo:$numero" else "$type:$hex"

    /** A frame with a right CRC, its data descrambled: true when written (not a repeat). */
    @Synchronized
    fun trame(t: Long, sat: String, norad: Int, type: Int, donnees: ByteArray, src: String, debit: String = ""): Boolean {
        val hex = AssemblageSsdv.hex(donnees)
        return ecrit(t, cle(type, hex, "", 0), JSONObject().put("t", t).put("sat", sat).put("norad", norad).put("type", type).put("hex", hex).put("src", src)
            .apply { if (debit.isNotBlank()) put("d", debit) })
    }

    /** An SSDV packet [numero] of picture [photo] heard (new or already held): true when written. */
    @Synchronized
    fun ssdv(t: Long, sat: String, norad: Int, photo: String, numero: Int, src: String, debit: String = ""): Boolean =
        ecrit(t, cle(Genesis.TYPE_SSDV, "", photo, numero), JSONObject().put("t", t).put("sat", sat).put("norad", norad)
            .put("type", Genesis.TYPE_SSDV).put("n", numero).put("ssdv", JSONArray().put(JSONObject().put("cle", photo))).put("src", src)
            .apply { if (debit.isNotBlank()) put("d", debit) })

    private fun ecrit(t: Long, k: String, o: JSONObject): Boolean {
        val d = repertoire ?: return false
        val j = jour(t)
        val l = cles(j).getOrPut(k) { ArrayList() }
        if (l.any { Math.abs(it - t) <= DOUBLON_MS }) return false
        // The day before or after (a pass across midnight UTC).
        for (x in listOf(jour(t - DOUBLON_MS), jour(t + DOUBLON_MS)).distinct()) {
            if (x != j && cles(x)[k]?.any { Math.abs(it - t) <= DOUBLON_MS } == true) return false
        }
        l += t
        return runCatching { d.mkdirs(); File(d, "$j.jsonl").appendText(o.toString() + "\n"); true }.getOrDefault(false)
    }

    private fun lisJour(j: String): List<Entree> {
        val f = File(repertoire ?: return emptyList(), "$j.jsonl")
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines().mapNotNull { lis(it) } }.getOrDefault(emptyList())
    }

    /** What was kept between [de] and [a] (ms), of [satellite] when not blank (the same satellite by name or number), in time order. */
    @Synchronized
    fun entre(de: Long, a: Long, satellite: String = "", norad: Int = 0): List<Entree> {
        val jours = generateSequence(de - de.mod(86_400_000L)) { it + 86_400_000L }.takeWhile { it <= a }.map { jour(it) }.toList()
        return jours.flatMap { lisJour(it) }.filter { it.t in de..a }
            .filter { satellite.isBlank() || (norad > 0 && it.norad == norad) || fr.f4ioz.satcombo.domain.JournalPassage.memeSatellite(it.sat, satellite) }
            .sortedBy { it.t }
    }

    companion object {
        const val DOSSIER = "telemesure/genesis/trames"
        /** The same frame or packet again within this long is a repeat (a pass lasts less). */
        const val DOUBLON_MS = 30 * 60_000L

        fun jour(ms: Long): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

        fun lis(ligne: String): Entree? {
            val o = runCatching { JSONObject(ligne) }.getOrNull() ?: return null
            val t = o.optLong("t").takeIf { it > 0 } ?: return null
            val cle = o.optJSONArray("ssdv")?.optJSONObject(0)?.optString("cle").orEmpty()
            return Entree(t, o.optString("sat"), o.optInt("norad"), o.optInt("type"), o.optString("hex"), o.optInt("n"), cle, o.optString("src"), o.optString("d"))
        }
    }
}
