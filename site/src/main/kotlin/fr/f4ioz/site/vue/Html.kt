/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.vue

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writing HTML by hand, safely: every text that comes from outside goes through [h]. */
object Html {

    /** [s] escaped for the text of an element or an attribute's value. */
    fun h(s: Any?): String {
        val t = s?.toString() ?: return ""
        val b = StringBuilder(t.length + 16)
        for (c in t) when (c) {
            '&' -> b.append("&amp;"); '<' -> b.append("&lt;"); '>' -> b.append("&gt;")
            '"' -> b.append("&quot;"); '\'' -> b.append("&#39;")
            else -> b.append(c)
        }
        return b.toString()
    }

    /** [s] for a URL's query. */
    /** A whole number as French writes it: « 12 345 » (a narrow no-break space between thousands). */
    fun nombre(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n).replace(",", "\u202F")

    fun u(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    /** A JSON string in a `<script>` (JSON-LD, data): `</` broken so the script cannot end early. */
    fun jsonScript(json: String): String = json.replace("</", "<\\/").replace("<!--", "<\\!--")

    private val FR = Locale.FRANCE

    fun date(ms: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("dd/MM/yyyy", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun dateCourte(ms: Long, z: ZoneId = java.time.ZoneOffset.UTC): String = DateTimeFormatter.ofPattern("dd/MM/yy", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun dateLongue(ms: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("d MMMM yyyy", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun heure(ms: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun dateHeure(ms: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun jourHeure(ms: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", FR).format(Instant.ofEpochMilli(ms).atZone(z))
    fun utc(ms: Long): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'", FR).format(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")))
    fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).let { Instant.ofEpochSecond(it.epochSecond) })

    /** A duration in words: « 12 min 05 », « 1 h 03 ». */
    fun duree(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = s / 60 % 60
        return if (h > 0) "$h h " + "%02d".format(m) else "$m min " + "%02d".format(s % 60)
    }

    /** A size in words: « 1,2 Mo ». */
    fun taille(o: Long): String = when {
        o >= 1_048_576 -> String.format(FR, "%.1f Mo", o / 1_048_576.0)
        o >= 1024 -> String.format(FR, "%d ko", o / 1024)
        else -> "$o o"
    }

    /** A slug: lower case ASCII, hyphens. */
    fun slug(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80).ifEmpty { "x" }
}
