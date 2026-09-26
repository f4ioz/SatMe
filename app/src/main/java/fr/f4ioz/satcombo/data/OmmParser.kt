/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Parses CelesTrak GP data in OMM JSON format and rebuilds classic TLE lines
 * for the SGP4 propagator (predict4java only accepts line1/line2 strings).
 *
 * WHY: CelesTrak ran out of 5-digit catalog numbers (Saramago, 2026-07-11).
 * New objects get 6-digit numbers (100000+) and are NOT published as TLE at
 * all. The OMM (JSON/XML/CSV) formats have no such limit and also fix Y2K.
 * See https://celestrak.org/NORAD/documentation/gp-data-formats.php
 *
 * The rebuilt TLE keeps the real catalog number in [TleEntry.catalogNumber]
 * (parsed from NORAD_CAT_ID as an Int, so 6+ digits are preserved), while the
 * TLE line itself uses the Alpha-5 encoding when the number exceeds 99999 —
 * SGP4 propagation only uses the orbital elements, not the catalog field.
 */
object OmmParser {

    /** Parse a CelesTrak OMM JSON array into TLE entries. */
    fun parse(json: String, freqPlan: Map<Int, Triple<Long?, Long?, String>> = emptyMap()): List<TleEntry> {
        val out = ArrayList<TleEntry>()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { toEntry(o, freqPlan) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun toEntry(o: JSONObject, freqPlan: Map<Int, Triple<Long?, Long?, String>>): TleEntry? {
        // AMSAT's GP bulletin adds AMSAT_NAME ("AO-07") next to the catalog
        // OBJECT_NAME ("OSCAR 7"). Prefer the ham designator: it's what we say
        // on the air and it matches the AMSAT status page keys.
        val name = o.optString("AMSAT_NAME").trim()
            .ifBlank { o.optString("OBJECT_NAME").trim() }
            .ifBlank { return null }
        val catnum = o.optInt("NORAD_CAT_ID", -1)
        if (catnum < 0) return null
        val (l1, l2) = buildTleLines(o) ?: return null
        val plan = freqPlan[catnum]
        return TleEntry(
            name = name, line1 = l1, line2 = l2, catalogNumber = catnum,
            uplinkHz = plan?.first, downlinkHz = plan?.second, mode = plan?.third
        )
    }

    /**
     * Encode a catalog number for the 5-char TLE field. Numbers <= 99999 are
     * plain; 100000+ use Alpha-5 (leading digit replaced by a letter, skipping
     * I and O), which is the convention 18 SPCS/CelesTrak use for legacy fields.
     */
    fun alpha5(catnum: Int): String {
        if (catnum <= 99999) return "%05d".format(catnum)
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"  // no I, no O
        val head = catnum / 10000            // 10..33 for 100000..339999
        val tail = catnum % 10000
        val idx = head - 10
        if (idx !in letters.indices) return "%05d".format(catnum % 100000)
        return "${letters[idx]}%04d".format(tail)
    }

    /** Inverse of [alpha5]: "A0123" -> 100123, "25544" -> 25544. */
    fun decodeAlpha5(field: String): Int {
        val s = field.trim()
        if (s.isEmpty()) return 0
        val c = s[0]
        if (c.isDigit()) return s.toIntOrNull() ?: 0
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val idx = letters.indexOf(c.uppercaseChar())
        if (idx < 0) return s.drop(1).toIntOrNull() ?: 0
        val tail = s.drop(1).toIntOrNull() ?: return 0
        return (idx + 10) * 10000 + tail
    }

    /** Rebuild TLE line1/line2 from OMM fields, with correct checksums. */
    fun buildTleLines(o: JSONObject): Pair<String, String>? {
        val catnum = o.optInt("NORAD_CAT_ID", -1)
        if (catnum < 0) return null
        val catField = alpha5(catnum)
        val classification = o.optString("CLASSIFICATION_TYPE", "U").take(1).ifBlank { "U" }

        // International designator: "1998-067A" -> "98067A  "
        val intlRaw = o.optString("OBJECT_ID", "")
        val intl = formatIntlDesignator(intlRaw)

        // Epoch "2026-07-16T05:13:55.837632" -> YYDDD.DDDDDDDD
        val epoch = formatEpoch(o.optString("EPOCH", "")) ?: return null

        val nDot = o.optDouble("MEAN_MOTION_DOT", 0.0)
        val nDDot = o.optDouble("MEAN_MOTION_DDOT", 0.0)
        val bstar = o.optDouble("BSTAR", 0.0)
        val ephType = o.optInt("EPHEMERIS_TYPE", 0)
        val elemSet = o.optInt("ELEMENT_SET_NO", 999)

        val incl = o.optDouble("INCLINATION", 0.0)
        val raan = o.optDouble("RA_OF_ASC_NODE", 0.0)
        val ecc = o.optDouble("ECCENTRICITY", 0.0)
        val argp = o.optDouble("ARG_OF_PERICENTER", 0.0)
        val ma = o.optDouble("MEAN_ANOMALY", 0.0)
        val mm = o.optDouble("MEAN_MOTION", 0.0)
        val rev = o.optInt("REV_AT_EPOCH", 0)

        val l = Locale.US
        // Line 1: 1 NNNNNC IIIIIII EEEEEEEEEEEEEE +.NNNNNNNN +NNNNN-N +NNNNN-N N NNNNN
        val sb1 = StringBuilder()
        sb1.append("1 ")
        sb1.append(catField).append(classification)          // 5 + 1
        sb1.append(' ')
        sb1.append(intl.padEnd(8).take(8))                   // cols 10-17
        sb1.append(' ')
        sb1.append(epoch.padStart(14))                       // cols 19-32
        sb1.append(' ')
        sb1.append(formatNDot(nDot))                         // cols 34-43
        sb1.append(' ')
        sb1.append(formatExp(nDDot))                         // cols 45-52
        sb1.append(' ')
        sb1.append(formatExp(bstar))                         // cols 54-61
        sb1.append(' ')
        sb1.append(ephType)                                  // col 63
        sb1.append(' ')
        sb1.append("%4d".format(l, elemSet))                 // cols 65-68
        var line1 = sb1.toString()
        line1 = line1.padEnd(68).take(68)
        line1 += checksum(line1)

        // Line 2: 2 NNNNN III.IIII RRR.RRRR EEEEEEE AAA.AAAA MMM.MMMM NN.NNNNNNNNRRRRR
        val sb2 = StringBuilder()
        sb2.append("2 ")
        sb2.append(catField)
        sb2.append(' ')
        sb2.append("%8.4f".format(l, incl))
        sb2.append(' ')
        sb2.append("%8.4f".format(l, raan))
        sb2.append(' ')
        sb2.append(formatEcc(ecc))                           // 7 digits, no leading "0."
        sb2.append(' ')
        sb2.append("%8.4f".format(l, argp))
        sb2.append(' ')
        sb2.append("%8.4f".format(l, ma))
        sb2.append(' ')
        sb2.append("%11.8f".format(l, mm))
        sb2.append("%5d".format(l, rev % 100000))
        var line2 = sb2.toString()
        line2 = line2.padEnd(68).take(68)
        line2 += checksum(line2)

        return line1 to line2
    }

    /** "1998-067A" -> "98067A"; empty stays empty. */
    private fun formatIntlDesignator(id: String): String {
        val m = Regex("""(\d{4})-(\d{3})([A-Z]*)""").find(id) ?: return ""
        val yy = m.groupValues[1].takeLast(2)
        return yy + m.groupValues[2] + m.groupValues[3]
    }

    /** ISO epoch -> "YYDDD.DDDDDDDD" (TLE epoch field). */
    fun formatEpoch(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching {
            // Parse without java.time desugaring concerns: manual split.
            val date = iso.substringBefore('T')
            val time = iso.substringAfter('T', "00:00:00")
            val (y, mo, d) = date.split('-').map { it.toInt() }
            val hh = time.substringBefore(':').toInt()
            val mm = time.substringAfter(':').substringBefore(':').toInt()
            val ssStr = time.substringAfterLast(':')
            val ss = ssStr.toDouble()
            val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear(); cal.set(y, mo - 1, d)
            val doy = cal.get(java.util.Calendar.DAY_OF_YEAR)
            val frac = (hh * 3600.0 + mm * 60.0 + ss) / 86400.0
            val yy = y % 100
            "%02d%03d%s".format(Locale.US, yy, doy,
                ".%08d".format(Locale.US, Math.round(frac * 1e8)))
        }.getOrNull()
    }

    /** First derivative of mean motion: " .00003540" style (10 chars). */
    private fun formatNDot(v: Double): String {
        val s = "%.8f".format(Locale.US, kotlin.math.abs(v))
        val body = s.removePrefix("0")   // ".00003540"
        val sign = if (v < 0) "-" else " "
        return (sign + body).padEnd(10).take(10)
    }

    /** TLE exponent form: 7.23e-5 -> " 72346-4"; 0 -> " 00000+0". */
    private fun formatExp(v: Double): String {
        if (v == 0.0) return " 00000+0"
        val sign = if (v < 0) "-" else " "
        var a = kotlin.math.abs(v)
        var exp = 0
        // Normalize to 0.NNNNN
        while (a >= 1.0) { a /= 10.0; exp++ }
        while (a < 0.1 && a > 0.0) { a *= 10.0; exp-- }
        val mant = Math.round(a * 100000).toInt()
        val es = if (exp < 0) "-" else "+"
        return "$sign%05d$es%d".format(Locale.US, mant, kotlin.math.abs(exp))
    }

    /** Eccentricity 0.00067484 -> "0006748" (7 digits, implied leading "0."). */
    private fun formatEcc(e: Double): String =
        "%07d".format(Locale.US, Math.round(e * 1e7))

    /** TLE modulo-10 checksum: digits summed, '-' counts as 1. */
    fun checksum(line: String): Int {
        var sum = 0
        for (c in line) {
            when {
                c.isDigit() -> sum += c - '0'
                c == '-' -> sum += 1
            }
        }
        return sum % 10
    }
}
