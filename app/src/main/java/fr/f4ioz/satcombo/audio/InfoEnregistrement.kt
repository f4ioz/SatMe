/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import java.io.File

/**
 * What a recording does not say by itself, kept next to it ("<name>.info"):
 * where it was made, and how long the spoken header before the pass lasts.
 * A picture decoded from it later — maybe far from there — takes the place
 * and the time it was received at, not the ones of the day it is decoded.
 */
object InfoEnregistrement {

    data class Info(val locator: String = "", val annonceMs: Long = 0L)

    fun fichier(mp3: File): File = File(mp3.parentFile, mp3.name.substringBeforeLast('.') + ".info")

    fun ecrit(mp3: File, info: Info) {
        runCatching {
            fichier(mp3).writeText("locator=" + info.locator.trim() + "\nannonce=" + info.annonceMs + "\n")
        }
    }

    fun lit(mp3: File): Info? = runCatching {
        val f = fichier(mp3)
        if (!f.isFile) return null
        var i = Info()
        f.readLines().forEach { l ->
            val k = l.substringBefore('=', ""); val v = l.substringAfter('=', "").trim()
            when (k) {
                "locator" -> i = i.copy(locator = v)
                "annonce" -> i = i.copy(annonceMs = v.toLongOrNull() ?: 0L)
            }
        }
        i
    }.getOrNull()
}
