/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Passes as an iCalendar file (RFC 5545): one VEVENT per pass, UTC times,
 * CRLF lines folded at 75 octets.
 *
 * The UID survives a new computation: the same pass exported twice updates
 * the event instead of adding a second one. It is built on the geometric
 * LOS (an in-progress pass's AOS is the scan time, not stable), to ten
 * minutes — passes of one satellite are far more apart than that.
 */
object CalendrierPassages {

    const val TYPE = "text/calendar; charset=utf-8"

    fun uid(p: SatPass): String = "satme-" + p.catalogNumber + "-" + (p.geoLosEpochMs / 600_000L) + "@f4ioz.fr"

    /** The file name offered for the download / share. */
    fun nomFichier(maintenantMs: Long): String = "satme-passages-" + format("yyyyMMdd-HHmm").format(Date(maintenantMs)) + ".ics"

    /**
     * The passes the list shows: not over, the name filter (letters and
     * digits), favourites only ([favoris] non-null), one satellite ([catnum]),
     * and whatever else [garde] keeps.
     */
    fun choisis(passes: List<SatPass>, maintenantMs: Long, texte: String = "", favoris: Set<Int>? = null,
                catnum: Int? = null, garde: (SatPass) -> Boolean = { true }): List<SatPass> {
        fun cle(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val f = cle(texte)
        return passes.filter { p ->
            p.losEpochMs >= maintenantMs && (f.isEmpty() || cle(p.satName).contains(f)) &&
                (favoris == null || p.catalogNumber in favoris) && (catnum == null || p.catalogNumber == catnum) && garde(p)
        }.sortedBy { it.aosEpochMs }
    }

    fun ics(passes: List<SatPass>, maintenantMs: Long): String {
        val horo = format("yyyyMMdd'T'HHmmss'Z'")
        val hm = format("HH:mm")
        val l = ArrayList<String>()
        l += "BEGIN:VCALENDAR"
        l += "VERSION:2.0"
        l += "PRODID:-//F4IOZ//SatMe//FR"
        l += "CALSCALE:GREGORIAN"
        l += "METHOD:PUBLISH"
        for (p in passes) {
            val el = Math.round(p.maxElevationDeg)
            var desc = tf("ics_description", el, Math.round(p.aosAzimuthDeg), Math.round(p.losAzimuthDeg))
            if (p.masque) desc += "\n" + tf("ics_sans_obstacle", hm.format(Date(p.geoAosEpochMs)), hm.format(Date(p.geoLosEpochMs)))
            l += "BEGIN:VEVENT"
            l += "UID:" + uid(p)
            l += "DTSTAMP:" + horo.format(Date(maintenantMs))
            l += "DTSTART:" + horo.format(Date(p.aosEpochMs))
            l += "DTEND:" + horo.format(Date(p.losEpochMs))
            l += "SUMMARY:" + texte(p.satName + " · " + el + "°")
            l += "DESCRIPTION:" + texte(desc)
            l += "CATEGORIES:" + texte("SatMe")
            l += "TRANSP:TRANSPARENT"
            l += "END:VEVENT"
        }
        l += "END:VCALENDAR"
        return l.joinToString("") { plie(it) + "\r\n" }
    }

    /** TEXT value escaping (RFC 5545 3.3.11). */
    fun texte(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    /** Folds a content line at 75 octets, never inside a UTF-8 character (RFC 5545 3.1). */
    fun plie(ligne: String): String {
        if (ligne.toByteArray(Charsets.UTF_8).size <= 75) return ligne
        val sb = StringBuilder()
        var octets = 0
        var i = 0
        while (i < ligne.length) {
            val cp = ligne.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            // The first line holds 75 octets; the next ones 74 after their leading space.
            if (octets + n > 75) { sb.append("\r\n "); octets = 1 }
            sb.appendCodePoint(cp)
            octets += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun format(motif: String) = SimpleDateFormat(motif, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
