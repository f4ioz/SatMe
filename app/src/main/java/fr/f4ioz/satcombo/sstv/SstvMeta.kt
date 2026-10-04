/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What is known about a received image besides its pixels.
 *
 * The file name carries satellite, UTC time and mode: it is the only thing
 * that survives a copy to the phone gallery or a messaging app. The rest
 * (locator, callsign, source) goes in a sidecar file, one `key=value` line per
 * field. Not JSON: a missing brace would lose everything, a damaged line here
 * only loses its own field.
 *
 * No Android dependency, so it is unit-testable.
 */
object SstvMeta {

    data class SstvShot(
        /** PNG name as on disk. */
        val fileName: String,
        /** Satellite tracked at reception time, e.g. "ISS". */
        val satName: String = "",
        /**
         * Reception time, UTC ms; 0 if unreadable. For a picture decoded again
         * from a recording, still when it was received, not decoded.
         */
        val timeMs: Long = 0L,
        /** When it was decoded again from a recording, UTC ms; 0 if never. */
        val redecodeMs: Long = 0L,
        /** Its time already takes off the recording's spoken header (decoded again from 20.77 on). */
        val recale: Boolean = false,
        /** Decoded mode, e.g. PD120, Robot36. */
        val mode: String = "",
        /** False when the frame stopped early (signal loss). */
        val complete: Boolean = true,
        /** Station locator at reception time. */
        val locator: String = "",
        /** Station callsign. */
        val callsign: String = "",
        /** "live" during a pass, "file" when re-decoded from an MP3. */
        val source: String = "",
        /** Source recording, when re-decoded. */
        val recording: String = "",
        /** Free note for the operator. */
        val note: String = ""
    )

    /**
     * Image kinds SatMe archives. Same name and sidecar format for all, only
     * the tag differs: APT and SSTV images raise the same question later —
     * which satellite, when, from where.
     */
    private val KINDS = setOf("SSTV", "APT")

    /** Sidecar file name for an image. */
    fun sidecarName(pngName: String): String = pngName.removeSuffix(".png") + ".meta"

    /**
     * Builds an image file name. The satellite name is stripped of anything
     * but letters, digits and '-': "ISS (ZARYA)" travels badly across file
     * systems, and an underscore would break parsing of where the date starts.
     */
    fun fileName(
        satName: String, timeMs: Long, mode: String, complete: Boolean,
        kind: String = "SSTV", variante: Int = 0
    ): String {
        val safeKind = if (kind in KINDS) kind else "SSTV"
        val safeSat = satName.replace(Regex("[^A-Za-z0-9-]"), "-")
            .trim('-').ifBlank { "SAT" }
        val safeMode = mode.replace(Regex("[^A-Za-z0-9-]"), "").ifBlank { safeKind }
        return "SatMe_" + safeKind + "_" + safeSat + "_" + stamp(timeMs) + "_" + safeMode +
            (if (complete) "" else "_partiel") + (if (variante > 0) "_r$variante" else "") + ".png"
    }

    private fun stamp(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timeMs))

    private val DATE_RE = Regex("^\\d{8}$")
    private val TIME_RE = Regex("^\\d{6}Z$")

    /**
     * Parses a file name from the end (mode, then timestamp), so a satellite
     * name with an underscore from an older version does not shift the rest.
     */
    fun parseName(pngName: String): SstvShot {
        val base = pngName.removeSuffix(".png")
        val parts = base.split("_").toMutableList()
        if (parts.size < 5 || parts[0] != "SatMe" || parts[1] !in KINDS) {
            return SstvShot(fileName = pngName)
        }
        var complete = true
        // A second decoding of the same picture: "_r2", "_r3"…
        if (parts.size > 5 && Regex("^r\\d+$").matches(parts.last())) parts.removeAt(parts.size - 1)
        if (parts.last() == "partiel" || parts.last() == "partial") {
            complete = false
            parts.removeAt(parts.size - 1)
        }
        if (parts.size < 5) return SstvShot(fileName = pngName, complete = complete)
        val mode = parts.removeAt(parts.size - 1)
        val time = parts.removeAt(parts.size - 1)
        val date = parts.removeAt(parts.size - 1)
        if (!DATE_RE.matches(date) || !TIME_RE.matches(time)) {
            return SstvShot(fileName = pngName, complete = complete)
        }
        val sat = parts.drop(2).joinToString("_")
        return SstvShot(
            fileName = pngName,
            satName = sat,
            timeMs = parseStamp(date, time),
            mode = mode,
            complete = complete)
    }

    /**
     * When a picture was really received. A live one: its time. One decoded
     * again from a recording: the same picture received live at that moment
     * if there is one (same mode, up to 30 s before or 10 s after); else its
     * time less the recording's spoken header ([annonceMs], when the decoding
     * did not already take it off — before 20.77, or with no length kept).
     */
    fun heureOrigine(s: SstvShot, directs: List<SstvShot>, annonceMs: Long): Long {
        if (s.source != "file") return s.timeMs
        directs.filter { it.source == "live" && it.mode == s.mode && it.timeMs in (s.timeMs - 30_000L)..(s.timeMs + 10_000L) }
            .minByOrNull { kotlin.math.abs(it.timeMs - s.timeMs) }?.let { return it.timeMs }
        return if (s.recale) s.timeMs else s.timeMs - annonceMs
    }

    /**
     * The UTC start written in a recording's name ("SatMe_ISS_20261002_053005Z.mp3"),
     * or 0 when it has none.
     */
    fun debutEnregistrement(nom: String): Long {
        val m = Regex("_(\\d{8})_(\\d{6})Z").find(nom) ?: return 0L
        return parseStamp(m.groupValues[1], m.groupValues[2] + "Z")
    }

    private fun parseStamp(date: String, time: String): Long = runCatching {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            .apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            .parse(date + time.removeSuffix("Z"))!!.time
    }.getOrDefault(0L)

    // ------------------------------------------------------------ sidecar file

    /** The sidecar: one `key=value` line per known field. */
    fun encode(shot: SstvShot): String {
        val sb = StringBuilder()
        fun put(k: String, v: String) {
            if (v.isNotBlank()) sb.append(k).append('=')
                .append(v.replace('\n', ' ').replace('\r', ' ').trim()).append('\n')
        }
        put("sat", shot.satName)
        if (shot.timeMs > 0L) put("time", shot.timeMs.toString())
        if (shot.redecodeMs > 0L) put("redecode", shot.redecodeMs.toString())
        if (shot.recale) put("recale", "1")
        put("mode", shot.mode)
        put("complete", if (shot.complete) "1" else "0")
        put("locator", shot.locator)
        put("call", shot.callsign)
        put("source", shot.source)
        put("recording", shot.recording)
        put("note", shot.note)
        return sb.toString()
    }

    /**
     * Applies the sidecar over what the file name says: an image whose sidecar
     * is lost keeps satellite, time and mode, and only loses locator/callsign.
     */
    fun decode(pngName: String, text: String?): SstvShot {
        var shot = parseName(pngName)
        if (text.isNullOrBlank()) return shot
        text.split("\n").forEach { raw ->
            val line = raw.trim()
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            if (v.isBlank()) return@forEach
            shot = when (k) {
                "sat" -> shot.copy(satName = v)
                "time" -> v.toLongOrNull()?.let { shot.copy(timeMs = it) } ?: shot
                "redecode" -> v.toLongOrNull()?.let { shot.copy(redecodeMs = it) } ?: shot
                "recale" -> shot.copy(recale = v == "1")
                "mode" -> shot.copy(mode = v)
                "complete" -> shot.copy(complete = v != "0")
                "locator" -> shot.copy(locator = v)
                "call" -> shot.copy(callsign = v)
                "source" -> shot.copy(source = v)
                "recording" -> shot.copy(recording = v)
                "note" -> shot.copy(note = v)
                else -> shot
            }
        }
        return shot
    }
}
