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

    /**
     * The spoken header of a recording made before its length was kept: the
     * file lasts the header plus the capture, and the capture ran from the
     * time in its name to when the file was last written. Seconds at most.
     */
    fun estimeAnnonce(debutNomMs: Long, finFichierMs: Long, dureeMs: Long): Long {
        if (debutNomMs <= 0 || finFichierMs <= debutNomMs || dureeMs <= 0) return 0L
        val a = dureeMs - (finFichierMs - debutNomMs)
        return if (a in 0L..20_000L) a else 0L
    }

    /** Length of a recording (ms), from its header. */
    fun dureeMs(f: File): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
        finally { runCatching { r.release() } }
    }.getOrDefault(0L)

    /** The spoken header's length: kept with the recording, else estimated. */
    fun annonceMs(mp3: File, dureeMs: Long = -1L): Long {
        lit(mp3)?.let { if (it.annonceMs > 0L) return it.annonceMs }
        val debut = fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(mp3.name)
        return estimeAnnonce(debut, mp3.lastModified(), if (dureeMs >= 0) dureeMs else dureeMs(mp3))
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
