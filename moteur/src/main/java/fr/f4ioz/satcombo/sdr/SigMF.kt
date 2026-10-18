/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * I/Q recordings in the SigMF layout (the open format for recorded
 * signals): the samples raw in `name.sigmf-data`, what they are in
 * `name.sigmf-meta` (JSON: rate, type, and « captures », the frequency from
 * a sample on). SatMe adds its own keys (`satme:`): the satellite, the mode,
 * the receiver's PLL and its software share of the Doppler, the converter's
 * oscillator — enough to hear a recording back exactly as it was heard.
 *
 * Plain raw files (`.cu8`, `.cs8`, `.cs16`, `.cf32`) are read too, their
 * rate given by hand.
 */
object SigMF {

    const val EXT_DATA = ".sigmf-data"
    const val EXT_META = ".sigmf-meta"

    /** A sample type: its SigMF name, bytes per I/Q pair. */
    enum class Format(val sigmf: String, val octetsParPaire: Int) {
        CU8("cu8", 2), CI8("ci8", 2), CI16("ci16_le", 4), CF32("cf32_le", 8);

        companion object {
            fun de(nom: String?): Format? = entries.firstOrNull { it.sigmf == nom?.trim()?.lowercase() }
                ?: when (nom?.trim()?.lowercase()) { "ci16" -> CI16; "cf32" -> CF32; else -> null }

            /** A raw file's type from its extension (null: not an I/Q file). */
            fun deExtension(nom: String): Format? = when (nom.substringAfterLast('.', "").lowercase()) {
                "cu8" -> CU8; "cs8", "ci8" -> CI8; "cs16", "ci16" -> CI16; "cf32", "cfile" -> CF32; else -> null
            }
        }
    }

    /** The frequency from [echantillon] on (pairs from the start): sky, receiver PLL, software share. */
    data class Capture(val echantillon: Long, val frequenceHz: Long, val pllHz: Long = frequenceHz, val fineHz: Double = 0.0)

    data class Meta(
        val format: Format,
        val debit: Double,
        /** UTC of the first sample (ms), or null when unknown. */
        val debutMs: Long? = null,
        val captures: List<Capture> = emptyList(),
        val materiel: String = "",
        val enregistreur: String = "",
        val auteur: String = "",
        val description: String = "",
        val satellite: String = "",
        val norad: Int = 0,
        val mode: String = "",
        /** The converter's (LNB's) oscillator, when one is before the receiver. */
        val oscillateurHz: Long = 0L,
        /** The pass recorded (AOS, LOS ms), when a satellite was over the horizon. */
        val aosMs: Long = 0L,
        val losMs: Long = 0L,
        /** Made by SatMe's test generator (no antenna). */
        val essai: Boolean = false,
    ) {
        /** The capture under way at [echantillon]. */
        fun captureA(echantillon: Long): Capture? {
            var c: Capture? = null
            for (x in captures) { if (x.echantillon <= echantillon) c = x else break }
            return c ?: captures.firstOrNull()
        }
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** ISO 8601 (any count of decimals, Z or an offset) to UTC ms. */
    private fun depuisIso(s: String): Long? {
        val m = Regex("""^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:?\d\d)?$""").find(s.trim()) ?: return null
        val base = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(m.groupValues[1])?.time }
            .getOrNull() ?: return null
        val ms = (m.groupValues[2] + "000").take(3).toLong()
        val z = m.groupValues[3]
        val decalage = if (z.length > 1) {
            val d = z.substring(1).replace(":", "")
            (d.take(2).toLong() * 60 + d.drop(2).toLong()) * 60_000L * (if (z[0] == '-') -1 else 1)
        } else 0L
        return base + ms - decalage
    }

    /** The meta file's JSON. */
    fun ecritMeta(m: Meta): String {
        val g = JSONObject()
            .put("core:datatype", m.format.sigmf)
            .put("core:sample_rate", m.debit)
            .put("core:version", "1.0.0")
            .put("core:num_channels", 1)
        if (m.description.isNotBlank()) g.put("core:description", m.description)
        if (m.materiel.isNotBlank()) g.put("core:hw", m.materiel)
        if (m.enregistreur.isNotBlank()) g.put("core:recorder", m.enregistreur)
        if (m.auteur.isNotBlank()) g.put("core:author", m.auteur)
        if (m.satellite.isNotBlank()) g.put("satme:satellite", m.satellite)
        if (m.norad > 0) g.put("satme:norad", m.norad)
        if (m.mode.isNotBlank()) g.put("satme:mode", m.mode)
        if (m.oscillateurHz != 0L) g.put("satme:lnb_lo_hz", m.oscillateurHz)
        if (m.aosMs > 0) g.put("satme:aos", iso(m.aosMs)).put("satme:los", iso(m.losMs))
        if (m.essai) g.put("satme:test_generator", true)
        val caps = JSONArray()
        val l = m.captures.ifEmpty { listOf(Capture(0, 0)) }
        l.forEachIndexed { i, c ->
            val o = JSONObject().put("core:sample_start", c.echantillon).put("core:frequency", c.frequenceHz)
                .put("satme:pll_hz", c.pllHz)
            if (c.fineHz != 0.0) o.put("satme:fine_hz", Math.round(c.fineHz * 10) / 10.0)
            if (i == 0 && m.debutMs != null) o.put("core:datetime", iso(m.debutMs))
            caps.put(o)
        }
        return JSONObject().put("global", g).put("captures", caps).put("annotations", JSONArray()).toString(1)
    }

    /** A meta file read back (null: not SigMF, or a type SatMe cannot read). */
    fun litMeta(texte: String): Meta? = runCatching {
        val o = JSONObject(texte)
        val g = o.getJSONObject("global")
        val fmt = Format.de(g.optString("core:datatype")) ?: return null
        val debit = g.optDouble("core:sample_rate", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: return null
        if (g.optInt("core:num_channels", 1) != 1) return null
        val ca = o.optJSONArray("captures") ?: JSONArray()
        var debut: Long? = null
        val caps = ArrayList<Capture>()
        for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            val f = c.optDouble("core:frequency", 0.0).toLong()
            caps += Capture(c.optLong("core:sample_start", 0L), f, c.optLong("satme:pll_hz", f), c.optDouble("satme:fine_hz", 0.0))
            if (debut == null) c.optString("core:datetime").takeIf { it.isNotBlank() }?.let { s -> depuisIso(s)?.let { debut = it - (caps.last().echantillon * 1000.0 / debit).toLong() } }
        }
        Meta(fmt, debit, debut, caps.sortedBy { it.echantillon }.distinctBy { it.echantillon },
            g.optString("core:hw"), g.optString("core:recorder"), g.optString("core:author"), g.optString("core:description"),
            g.optString("satme:satellite"), g.optInt("satme:norad", 0), g.optString("satme:mode"), g.optLong("satme:lnb_lo_hz", 0L),
            depuisIso(g.optString("satme:aos")) ?: 0L, depuisIso(g.optString("satme:los")) ?: 0L, g.optBoolean("satme:test_generator", false))
    }.getOrNull()

    /** The data file beside a meta file, and the other way. */
    fun data(meta: File): File = File(meta.parentFile, meta.name.removeSuffix(EXT_META) + EXT_DATA)
    fun meta(data: File): File = File(data.parentFile, data.name.removeSuffix(EXT_DATA) + EXT_META)

    /** A recording's base name: date, satellite, frequency — letters, digits, dashes only. */
    fun nomBase(debutMs: Long, satellite: String, frequenceHz: Long): String {
        val d = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(debutMs))
        val s = java.text.Normalizer.normalize(satellite.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24)
        val f = if (frequenceHz > 0) "-" + String.format(Locale.US, "%.3fMHz", frequenceHz / 1e6) else ""
        return "satme-iq-" + d + (if (s.isNotEmpty()) "-$s" else "") + f
    }

    /**
     * The captures kept for pairs [debut], [debut]+[n] of a longer stream,
     * counted from [debut]: the one under way at the start, then those inside.
     */
    fun capturesDans(l: List<Capture>, debut: Long, n: Long): List<Capture> {
        val avant = l.lastOrNull { it.echantillon <= debut }
        val dedans = l.filter { it.echantillon > debut && it.echantillon < debut + n }
        return (listOfNotNull(avant) + dedans).map { it.copy(echantillon = maxOf(0L, it.echantillon - debut)) }.distinctBy { it.echantillon }
    }
}

/**
 * Samples to little-endian bytes as SigMF stores them: unsigned bytes as
 * they come from a dongle, 16-bit pairs as the Pluto and the bladeRF give
 * them.
 */
internal object OctetsIq {
    fun de16(s: ShortArray, n: Int, dst: ByteArray) {
        var o = 0
        for (i in 0 until n) { val v = s[i].toInt(); dst[o++] = v.toByte(); dst[o++] = (v shr 8).toByte() }
    }
}
