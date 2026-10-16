/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

/**
 * Which sound feeds « Phonie → texte », one at a time. The source chosen
 * ("auto": the SDR dongle while it plays, else the sound card; "carte";
 * "sdr") holds, unless the DATV subtitles are on: then only the DATV
 * sound, and the chosen source comes back when they stop (the choice
 * itself is never changed).
 */
object SourcePhonie {

    /**
     * Whether [arrivee] ("carte", "sdr", "datv") may feed now. [choix]: the
     * source chosen; [datv]: the subtitles on; [sdrRecent]: the dongle heard
     * in the last moments.
     */
    fun admise(arrivee: String, choix: String, datv: Boolean, sdrRecent: Boolean): Boolean = when {
        datv -> arrivee == "datv"
        arrivee == "datv" -> false
        arrivee == "sdr" -> choix != "carte"
        arrivee == "carte" -> choix == "carte" || (choix == "auto" && !sdrRecent)
        else -> false
    }
}
