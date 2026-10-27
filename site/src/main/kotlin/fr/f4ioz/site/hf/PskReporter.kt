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
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * PSKReporter's reception reports (`retrieve.pskreporter.info/query`): who
 * heard whom, digital modes and CW skimmers, worldwide. Not a DX cluster, but
 * what the pages call one.
 *
 * PSKReporter asks its users not to query more than once every five minutes
 * and to give an `appcontact`, and may block those who load it
 * (pskreporter.info/pskdev.html). So the site makes **one** request per mode
 * at most every five minutes, always the same shape — the last 30 minutes,
 * reception reports only — whoever asks and whatever window or band they
 * want: those are cut here from what was fetched. When PSKReporter says no
 * (it answers JSON instead of XML), the site waits ten minutes and serves the
 * last good reports meanwhile (the old site served an empty list).
 */
class PskReporter(private val amont: Amont, private val contact: String, horloge: () -> Long = System::currentTimeMillis) {

    /** One reception report, with the field names the pages read. */
    class Spot(
        val tx: String, val txLoc: String, val txDxcc: String, val txDxccCode: String, val txContinent: String,
        val rx: String, val rxLoc: String, val rxDxcc: String, val rxDxccCode: String, val rxContinent: String,
        val freqHz: Long, val bande: String, val mode: String, val snr: String, val ts: Long, val lotw: String,
    ) {
        fun json(): JSONObject = JSONObject().put("tx", tx).put("tx_loc", txLoc).put("tx_dxcc", txDxcc).put("tx_dxcc_code", txDxccCode)
            .put("tx_continent", txContinent).put("rx", rx).put("rx_loc", rxLoc).put("rx_dxcc", rxDxcc).put("rx_dxcc_code", rxDxccCode)
            .put("rx_continent", rxContinent).put("freq_hz", freqHz)
            .put("freq_khz", if (freqHz > 0) Math.round(freqHz / 1000.0 * 1000.0) / 1000.0 else 0.0)
            .put("band", bande).put("mode", mode).put("snr", snr).put("ts", ts).put("lotw", lotw)
    }

    private val memo = Memo<List<Spot>>(amont, INTERVALLE_MS, PAUSE_REFUS_MS, 4, horloge)

    /** What PSKReporter last gave for [mode] (one of [MODES], "" for all), most recent first; its age and error. */
    fun spots(mode: String, attenteMs: Long = 10_000L): Memo.Lu<List<Spot>> {
        val m = mode.uppercase().takeIf { it in MODES }.orEmpty()
        return memo.lis(m, attenteMs) {
            val t = amont.texte(url(m), 24 * 1024 * 1024, 60)
            analyse(t, MAX_SPOTS).also { println("PSKReporter ${m.ifEmpty { "tous modes" }} : ${t.length / 1024} ko, ${it.size} reports gardés") }
        }
    }

    fun pose(mode: String, l: List<Spot>) = memo.pose(mode, l)

    /** The one query shape: the last [FENETRE_S] seconds, reception reports only, no monitors, the contact. */
    fun url(mode: String): String = "https://retrieve.pskreporter.info/query?flowStartSeconds=-$FENETRE_S&rronly=1&noactive=1" +
        (if (mode.isNotEmpty()) "&mode=" + URLEncoder.encode(mode, Charsets.UTF_8) else "") +
        "&appcontact=" + URLEncoder.encode(contact, Charsets.UTF_8)

    companion object {
        /** PSKReporter's own guidance: at most one query every five minutes. */
        const val INTERVALLE_MS = 5 * 60_000L
        /** After a refusal (or a failure): ten minutes before asking again, the last good reports served. */
        const val PAUSE_REFUS_MS = 10 * 60_000L
        /** The window fetched: the widest the pages offer. */
        const val FENETRE_S = 1800
        /** The reports kept per mode (the most recent). */
        const val MAX_SPOTS = 10_000

        /** The modes a visitor may choose (each its own query shape; nothing else reaches PSKReporter). */
        val MODES = setOf("", "FT8", "FT4", "CW", "PSK31", "JT9", "JT65", "MFSK", "OLIVIA", "SSB", "RTTY", "WSPR")

        private fun attr(r: javax.xml.stream.XMLStreamReader, n: String, max: Int = 32): String =
            (r.getAttributeValue(null, n) ?: "").trim().take(max)

        /**
         * The `receptionReport`s of a retrieve answer, most recent first, [max] at most. A JSON body (or anything
         * that is not the XML expected) is PSKReporter refusing: an [IOException], so the last good list stays.
         */
        fun analyse(corps: String, max: Int = MAX_SPOTS): List<Spot> {
            val t = corps.trimStart()
            if (t.startsWith("{") || t.startsWith("[")) throw IOException("PSKReporter refuse pour l'instant (limite de requêtes)")
            if (!t.startsWith("<")) throw IOException("réponse PSKReporter illisible")
            val f = XMLInputFactory.newInstance().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
            val r = f.createXMLStreamReader(StringReader(t))
            val out = ArrayList<Spot>()
            var racine = false
            try {
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT) continue
                    when (r.localName) {
                        "receptionReports" -> racine = true
                        "receptionReport" -> {
                            val tx = attr(r, "senderCallsign", 20).uppercase()
                            val rx = attr(r, "receiverCallsign", 20).uppercase()
                            if (tx.isEmpty() || rx.isEmpty()) continue
                            val hz = attr(r, "frequency").toLongOrNull()?.takeIf { it in 1..10_000_000_000L } ?: 0L
                            out += Spot(
                                tx = tx, txLoc = attr(r, "senderLocator", 10), txDxcc = attr(r, "senderDXCC", 40), txDxccCode = attr(r, "senderDXCCCode", 8),
                                txContinent = IndicatifGeo.continent(tx),
                                rx = rx, rxLoc = attr(r, "receiverLocator", 10), rxDxcc = attr(r, "receiverDXCC", 40), rxDxccCode = attr(r, "receiverDXCCCode", 8),
                                rxContinent = IndicatifGeo.continent(rx),
                                freqHz = hz, bande = if (hz > 0) Bandes.deKhz(hz / 1000.0) else "",
                                mode = attr(r, "mode", 12).uppercase(Locale.ROOT), snr = attr(r, "sNR", 6),
                                ts = attr(r, "flowStartSeconds").toLongOrNull() ?: 0L, lotw = attr(r, "senderLotwUpload", 12),
                            )
                        }
                    }
                }
            } finally { r.close() }
            if (!racine) throw IOException("réponse PSKReporter sans receptionReports")
            out.sortByDescending { it.ts }
            return echantillon(out, max)
        }

        /**
         * At most [max] of [l] (most recent first): PSKReporter's half hour is far more than a few thousand
         * reports, so the most recent half is kept whole and the rest of the window evenly sampled — the
         * table shows the latest, the map's proportions stay those of the whole half hour.
         */
        fun echantillon(l: List<Spot>, max: Int): List<Spot> {
            if (l.size <= max) return l
            val moitie = max / 2
            val reste = l.size - moitie
            val pas = reste.toDouble() / (max - moitie)
            return l.subList(0, moitie) + (0 until max - moitie).map { l[moitie + (it * pas).toInt().coerceAtMost(reste - 1)] }
        }

        /** Reports of the last [depuisS] seconds (at [maintenantS]) on [bande] ("" all), [limite] at most. */
        fun filtre(l: List<Spot>, maintenantS: Long, depuisS: Int, bande: String, limite: Int): List<Spot> =
            l.asSequence().filter { it.ts >= maintenantS - depuisS && (bande.isEmpty() || it.bande == bande) }.take(limite).toList()

        fun json(l: List<Spot>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it.json()) } }
    }
}
