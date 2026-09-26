/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context

/**
 * The operator's agenda: appointments no orbit computation can guess — a sked
 * on a given pass, an announced SSTV event — which arrive via forums or
 * mailing lists and get lost before they are needed.
 *
 * An appointment has a time (an instant or a start–end window), a title, a
 * reminder lead time, and optionally satellite, emission kind and frequency.
 * The window matters: "SSTV from QMR-KWT-2, 1 Aug 06:30 to 2 Aug 19:30 UTC"
 * means every pass in those two days, not one.
 *
 * Stored as one string, one tab-separated line per appointment. No database:
 * migrations would cost more than a handful of lines is worth, and plain text
 * can be fixed by hand. Fields are only appended at line end and [decode]
 * accepts short lines, so older agendas read back intact. [encode] and
 * [decode] are Android-free and tested on the JVM.
 */
object AgendaStore {

    data class AgendaEvent(
        /** Id = creation time; also the alarm key. */
        val id: Long,
        /** What it is: "SSTV ISS", "sked F6KMX"… */
        val title: String,
        /** Start, UTC epoch millis. */
        val timeMs: Long,
        /** Satellite, optional. */
        val satName: String = "",
        /** Reminder lead time in minutes. 0 = at the time itself. */
        val leadMin: Int = 60,
        /** Free note. */
        val note: String = "",
        /** False when the reminder is off but the appointment kept. */
        val enabled: Boolean = true,
        /**
         * Window end. 0, or anything not after the start, means no window:
         * the appointment is an instant.
         */
        val endMs: Long = 0L,
        /**
         * Expected emission kind: SSTV, NOAA, SKED, BEACON… Empty when
         * meaningless. Ham jargon, same in every language: not translated.
         */
        val kind: String = "",
        /** Announced frequency in Hz. 0 = none. */
        val freqHz: Long = 0L
    ) {
        /** When the reminder fires. */
        val alertMs: Long get() = timeMs - leadMin * 60_000L

        /** True when the appointment spans a window rather than an instant. */
        val isWindow: Boolean get() = endMs > timeMs

        /** Effective end: the one entered, else the start. */
        val endOrStartMs: Long get() = if (endMs > timeMs) endMs else timeMs

        /**
         * Does this appointment concern the pass from [aosMs] to [losMs]?
         *
         * Overlap, not inclusion: a 37-hour window holds dozens of passes, and
         * an instant noted at 14:30 applies to the pass starting at 14:32. The
         * five-minute slack on each side is because nobody notes a sked to the
         * second.
         */
        fun covers(aosMs: Long, losMs: Long, slackMs: Long = 5 * 60_000L): Boolean =
            aosMs - slackMs <= endOrStartMs && losMs + slackMs >= timeMs

        /** Is the appointment in progress at [ms]? */
        fun activeAt(ms: Long, slackMs: Long = 5 * 60_000L): Boolean =
            ms >= timeMs - slackMs && ms <= endOrStartMs + slackMs

        /** Does the window overlap [fromMs]–[toMs]? */
        fun overlaps(fromMs: Long, toMs: Long): Boolean =
            fromMs <= endOrStartMs && toMs >= timeMs

        /** Frequency in MHz, or null. */
        val freqMhz: Double? get() = if (freqHz > 0L) freqHz / 1e6 else null
    }

    private const val PREFS = "satcombo_agenda"
    private const val KEY = "events"

    /** Offered kinds. The first, empty, means "unspecified". */
    val KINDS: List<String> = listOf("", "SSTV", "NOAA", "SKED", "BEACON", "CONTEST")

    // -------------------------------------------------------------- serialising

    /** A field may contain neither tab nor newline. */
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    fun encode(list: List<AgendaEvent>): String = list.joinToString("\n") { e ->
        listOf(
            e.id.toString(), e.timeMs.toString(), e.leadMin.toString(),
            if (e.enabled) "1" else "0",
            clean(e.title), clean(e.satName), clean(e.note),
            // Fields added later. Always at line end, never elsewhere, so an
            // older version can read a newer agenda without breaking it.
            e.endMs.toString(), clean(e.kind).uppercase(), e.freqHz.toString()
        ).joinToString("\t")
    }

    /**
     * Reads the list back. A damaged line is skipped without losing the
     * others. A short line (written before windows existed) takes the
     * defaults, i.e. an instant appointment.
     */
    fun decode(text: String?): List<AgendaEvent> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<AgendaEvent>()
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            val f = line.split("\t")
            if (f.size < 5) return@forEach
            val id = f[0].toLongOrNull() ?: return@forEach
            val time = f[1].toLongOrNull() ?: return@forEach
            out += AgendaEvent(
                id = id,
                timeMs = time,
                leadMin = f[2].toIntOrNull() ?: 60,
                enabled = f[3] != "0",
                title = f[4],
                satName = f.getOrNull(5) ?: "",
                note = f.getOrNull(6) ?: "",
                endMs = f.getOrNull(7)?.toLongOrNull() ?: 0L,
                kind = (f.getOrNull(8) ?: "").uppercase(),
                freqHz = f.getOrNull(9)?.toLongOrNull() ?: 0L)
        }
        return out.sortedBy { it.timeMs }
    }

    // -------------------------------------------------------------------- disk

    fun load(ctx: Context): List<AgendaEvent> =
        decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))

    fun save(ctx: Context, list: List<AgendaEvent>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, encode(list.sortedBy { it.timeMs })).apply()
    }

    /** Adds or replaces an appointment, depending on whether its id exists. */
    fun put(ctx: Context, e: AgendaEvent): List<AgendaEvent> {
        val list = load(ctx).filter { it.id != e.id } + e
        val sorted = list.sortedBy { it.timeMs }
        save(ctx, sorted)
        return sorted
    }

    fun remove(ctx: Context, id: Long): List<AgendaEvent> {
        val list = load(ctx).filter { it.id != id }
        save(ctx, list)
        return list
    }

    /**
     * Deletes appointments that ended more than [days] days ago: keep a
     * recent trace, not an archive. Counted from the window end, so a
     * two-day event is not deleted while it is still running.
     */
    fun purge(ctx: Context, days: Int = 30, nowMs: Long = System.currentTimeMillis()):
        List<AgendaEvent> {
        val cut = nowMs - days * 86_400_000L
        val list = load(ctx)
        val kept = list.filter { it.endOrStartMs >= cut }
        if (kept.size != list.size) save(ctx, kept)
        return kept
    }
}
