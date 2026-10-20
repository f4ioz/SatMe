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
import fr.f4ioz.satcombo.data.LogEntry

/**
 * A logged contact given another date and time (typed late, clock wrong,
 * paper log): the time is the contact's key, so it is rewritten; what
 * belongs to the time follows — az/el where the satellite was then, the
 * log's order, the ADIF's QSO_DATE/TIME_ON (read from the time), the pass
 * it belongs to in the Journal (found by time).
 *
 * Pure logic: SatMe-PC and the phone give what they know, the answers are the same.
 */
object RedateContact {

    /** A clock a little ahead is no reason to refuse: the contact can be « now » on a slow clock. */
    const val AVANCE_MAX_MS = 5 * 60_000L

    /** The first amateur satellite's launch (12/12/1961 UTC): nothing logged before. */
    const val DEBUT_MS = -254_188_800_000L

    /** Same station, same satellite, this close: surely the same contact twice. */
    const val DOUBLON_MS = 10 * 60_000L

    /**
     * Date and time typed, in UTC: « AAAA-MM-JJ » (or « JJ/MM/AAAA ») and
     * « HH:MM », « HH:MM:SS » (or « HHMM », « HHMMSS »). Null when it is not one.
     */
    fun lis(date: String, heure: String): Long? {
        val d = date.trim()
        val (an, mois, jour) = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(d)?.groupValues?.let { Triple(it[1], it[2], it[3]) }
            ?: Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(d)?.groupValues?.let { Triple(it[3], it[2], it[1]) }
            ?: return null
        val h = heure.trim()
        val m = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(h) ?: Regex("^(\\d{2})(\\d{2})(\\d{2})?$").find(h) ?: return null
        val hh = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); val ss = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (hh > 23 || mi > 59 || ss > 59) return null
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.isLenient = false
        c.clear()
        c.set(an.toInt(), mois.toInt() - 1, jour.toInt(), hh, mi, ss)
        return runCatching { c.timeInMillis }.getOrNull()
    }

    /** Why [ms] cannot be a contact's time (French, for the page); null: it can. */
    fun pourquoiPas(ms: Long, maintenantMs: Long): String? = when {
        ms > maintenantMs + AVANCE_MAX_MS -> "Cette heure est dans le futur (l'heure est en UTC)."
        ms < DEBUT_MS -> "Date trop ancienne : avant le premier satellite amateur (1961)."
        else -> null
    }

    /**
     * Az/el belong to the satellite and the time — except where the time
     * changes nothing: QO-100 (geostationary) and a terrestrial contact.
     */
    fun geometrieFixe(e: LogEntry): Boolean =
        e.catnum == Qo100.NORAD || e.satName.equals(TraficQo100.NOM, true) ||
            (e.catnum == 0 && (e.satName.isBlank() || e.satName == Adif.DATV_TERRESTRE))

    /**
     * [e] at [nouveauMs]; [azEl] where its satellite was then (null: unknown,
     * az/el cleared rather than describing another moment).
     */
    fun applique(e: LogEntry, nouveauMs: Long, azEl: Pair<Double, Double>?): LogEntry {
        if (nouveauMs == e.timeMs) return e
        if (geometrieFixe(e)) return e.copy(timeMs = nouveauMs)
        return e.copy(timeMs = nouveauMs, azimuthDeg = azEl?.first ?: 0.0, elevationDeg = azEl?.second ?: 0.0)
    }

    /** A key no other contact has: [ms], else a millisecond after (invisible in the log and the ADIF). */
    fun cleLibre(ms: Long, prises: Set<Long>): Long {
        var t = ms
        while (t in prises) t++
        return t
    }

    /** The other contacts that look like [e] again: same callsign, same satellite, within [DOUBLON_MS]. */
    fun doublons(e: LogEntry, log: List<LogEntry>): List<LogEntry> {
        if (e.callsign.isBlank()) return emptyList()
        return log.filter {
            it.timeMs != e.timeMs && it.callsign.equals(e.callsign, true) &&
                kotlin.math.abs(it.timeMs - e.timeMs) <= DOUBLON_MS &&
                ((e.catnum != 0 && it.catnum == e.catnum) || JournalPassage.memeSatellite(it.satName, e.satName))
        }.sortedBy { kotlin.math.abs(it.timeMs - e.timeMs) }
    }

    /** What a redating did: the whole log (newest first), the contact as it is now, its look-alikes. */
    data class Resultat(val log: List<LogEntry>, val contact: LogEntry, val doublons: List<LogEntry>)

    /**
     * [ancienMs]'s contact moved to [nouveauMs] in [log]; [azEl] gives where
     * a contact's satellite was at a time (null: unknown). Null when there is
     * no such contact.
     */
    fun redate(log: List<LogEntry>, ancienMs: Long, nouveauMs: Long,
               azEl: (LogEntry, Long) -> Pair<Double, Double>?): Resultat? {
        val avant = log.firstOrNull { it.timeMs == ancienMs } ?: return null
        val autres = log.filter { it.timeMs != ancienMs }
        val t = cleLibre(nouveauMs, autres.mapTo(HashSet()) { it.timeMs })
        val apres = applique(avant, t, if (t == ancienMs || geometrieFixe(avant)) null else azEl(avant, t))
        val l = (autres + apres).sortedByDescending { it.timeMs }
        return Resultat(l, apres, doublons(apres, l))
    }

    /** The warning for look-alikes (French, for the page); null: none. */
    fun avertissement(doublons: List<LogEntry>): String? {
        val d = doublons.firstOrNull() ?: return null
        val f = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "Doublon possible : " + d.callsign + " sur " + d.satName + " déjà noté le " + f.format(java.util.Date(d.timeMs)) + " UTC" +
            (if (doublons.size > 1) " (et " + (doublons.size - 1) + " autre" + (if (doublons.size > 2) "s" else "") + ")" else "") + "."
    }
}
