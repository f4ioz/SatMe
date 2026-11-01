/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * What a journal pass holds, counted by kind — the list's coloured badges and
 * its filter. Each count comes from what the station already wrote beside the
 * pass (pictures' sidecars, the pass's FT8/FT4 messages, the recordings'
 * digital zones, the APRS and telemetry frames, the voices written down, the
 * log's contacts, the DATV recordings): nothing is decoded nor measured here.
 *
 * A kind is there only when its count is above zero.
 */
object ContenuPassage {
    const val SSTV = "SSTV"
    /** SSDV and Geoscan pictures, from the telemetry frames. */
    const val SSDV = "SSDV"
    const val FT8 = "FT8"
    const val FT4 = "FT4"
    /** Digital traffic not read: the timeline's violet zones no decode explains. */
    const val NUM = "NUM"
    const val APRS = "APRS"
    /** Telemetry frames (packet decoder, BPSK/FSK profiles). */
    const val TLM = "TLM"
    /** Voice: a contact logged in a voice mode, or an over written down by the voice engine. */
    const val PHONIE = "PHONIE"
    const val CW = "CW"
    const val DATV = "DATV"

    /** The kinds, in the order the list shows them. */
    val ORDRE = listOf(SSTV, SSDV, FT8, FT4, NUM, APRS, TLM, PHONIE, CW, DATV)

    private val VOIX = setOf("FM", "SSB", "USB", "LSB", "AM", "DV", "DIGITALVOICE", "FREEDV", "C4FM", "DSTAR", "D-STAR", "DMR", "M17", "NFM", "WFM", "PHONE")

    /** What a logged contact's mode says of the pass ([PHONIE], [CW], [FT8], [FT4], [APRS], [DATV], [SSTV]), or null. */
    fun genreQso(mode: String): String? {
        val m = mode.trim().uppercase()
        return when {
            m.isEmpty() -> null
            m in VOIX -> PHONIE
            m == "CW" || m.startsWith("CW-") -> CW
            m == "FT8" || m.endsWith("/FT8") -> FT8
            m == "FT4" || m.endsWith("/FT4") -> FT4
            m.contains("DATV") || m.startsWith("DVB") -> DATV
            m.contains("SSTV") -> SSTV
            m == "PKT" || m.contains("PACKET") || m.contains("APRS") || m.contains("AFSK") -> APRS
            else -> null
        }
    }

    /** What was found for one pass, before it is counted by kind. */
    data class Elements(
        /** SSTV pictures received during the pass. */
        val imagesSstv: Int = 0,
        /** SSDV / Geoscan pictures (distinct) among the telemetry frames. */
        val imagesSsdv: Int = 0,
        val messagesFt8: Int = 0,
        val messagesFt4: Int = 0,
        /** The recordings' digital zones, by their mode ([ZonesNumeriques.FT4], [ZonesNumeriques.FT8], [ZonesNumeriques.NUM]). */
        val zones: List<String> = emptyList(),
        val tramesAprs: Int = 0,
        val tramesTelemesure: Int = 0,
        /** Overs written down by the voice engine (the « phonie » files). */
        val paroles: Int = 0,
        /** The modes of the log's contacts of the pass. */
        val modesQso: List<String> = emptyList(),
        val enregistrementsDatv: Int = 0,
    )

    /** The pass's kinds and their counts, in [ORDRE], only those above zero. */
    fun resume(x: Elements): Map<String, Int> {
        val qso = x.modesQso.mapNotNull { genreQso(it) }.groupingBy { it }.eachCount()
        // A zone shaped like FT4 (FT8) with no FT4 (FT8) message read: digital, not identified yet.
        val num = x.zones.count { z ->
            when (z) {
                ZonesNumeriques.FT4 -> x.messagesFt4 == 0 && (qso[FT4] ?: 0) == 0
                ZonesNumeriques.FT8 -> x.messagesFt8 == 0 && (qso[FT8] ?: 0) == 0
                else -> true
            }
        }
        val n = mapOf(
            SSTV to x.imagesSstv,
            SSDV to x.imagesSsdv,
            // Messages kept with the pass; else its FT contacts at least.
            FT8 to (x.messagesFt8.takeIf { it > 0 } ?: qso[FT8] ?: 0),
            FT4 to (x.messagesFt4.takeIf { it > 0 } ?: qso[FT4] ?: 0),
            NUM to num,
            APRS to maxOf(x.tramesAprs, qso[APRS] ?: 0),
            TLM to x.tramesTelemesure,
            PHONIE to x.paroles + (qso[PHONIE] ?: 0),
            CW to (qso[CW] ?: 0),
            DATV to x.enregistrementsDatv + (qso[DATV] ?: 0),
        )
        return ORDRE.mapNotNull { k -> n[k]?.takeIf { it > 0 }?.let { k to it } }.toMap(LinkedHashMap())
    }

    /** The filter: no kind chosen, every pass; else a pass holding any of [choisis]. */
    fun retenu(resume: Map<String, Int>, choisis: Set<String>): Boolean =
        choisis.isEmpty() || choisis.any { (resume[it] ?: 0) > 0 }

    /** As the page reads it: `{"SSTV":3,"FT4":153}`. */
    fun json(resume: Map<String, Int>): String = resume.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
}
