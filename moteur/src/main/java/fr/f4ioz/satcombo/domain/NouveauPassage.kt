/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A pass written into the journal from nothing (a pass followed without
 * SatMe, a recording made elsewhere): its satellite and times, its predicted
 * track; then its sound, its pictures and its contacts brought to it — each
 * named and timed as the station's own, so the journal ties them to the pass
 * by time as it ties those received live. The same on the phone and SatMe-PC.
 */
object NouveauPassage {

    /** The largest file brought to a pass (a long WAV). */
    const val OCTETS_MAX = 400L * 1024 * 1024

    /** How far back a pass may be created (elements drift beyond). */
    const val JOURS_MAX = 30

    /** The pass to rebuild ([JournalPassage.reconstitue]): [sat] between [aosMs] and [losMs], seen from [locator]. */
    fun candidat(sat: TleEntry, aosMs: Long, losMs: Long, locator: String): JournalPassage.Candidat =
        JournalPassage.Candidat(sat.catalogNumber, sat.name, aosMs, losMs, emptySet(), 0, locator, emptyList())

    /** Are these the times of a pass that may be created? */
    fun heuresValables(aosMs: Long, losMs: Long, maintenant: Long): Boolean =
        losMs > aosMs && losMs - aosMs <= 3 * 3_600_000L && aosMs >= maintenant - JOURS_MAX * 86_400_000L && losMs <= maintenant + 60_000L

    private fun stamp(t: Long): String = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** A recording's name, as the station names its own: its start in it ("SatMe_ISS_20261013_093200Z.wav"). */
    fun nomEnregistrement(satName: String, debutMs: Long, extension: String): String =
        "SatMe_" + satName.replace(Regex("[^A-Za-z0-9_-]"), "-") + "_" + stamp(debutMs) + "." + extension.lowercase()

    /** The sound formats taken: the extension of the file sent, or null. */
    fun extensionSon(nom: String): String? = nom.substringAfterLast('.', "").lowercase().takeIf { it == "wav" || it == "mp3" }

    /** A picture brought to a pass: its file name and its sidecar, as a picture received then. */
    fun image(satName: String, timeMs: Long, mode: String, locator: String, indicatif: String, existe: (String) -> Boolean): Pair<String, String> {
        var v = 0
        var nom = SstvMeta.fileName(satName, timeMs, mode, true)
        while (existe(nom) && v < 99) nom = SstvMeta.fileName(satName, timeMs, mode, true, variante = ++v)
        val shot = SstvMeta.SstvShot(fileName = nom, satName = satName, timeMs = timeMs, mode = mode, complete = true,
            locator = locator, callsign = indicatif, source = "import", note = "")
        return nom to SstvMeta.encode(shot)
    }
}
