/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * An audio file from outside SatMe, for the SSTV and NOAA decoders: a WAV
 * the rig recorded on its SD card (IC-9700…), read on the phone with a USB
 * card reader, or any MP3.
 *
 * Copied into the app's cache first: the decoders read a file, and a
 * document from a card reader may vanish when the reader is pulled out.
 * Named "SatMe_IMPORT_…" so the pictures are labelled IMPORT, not after
 * the first word of the rig's file name. Copies older than a day go.
 */
object ImportAudio {

    fun copie(ctx: Context, uri: Uri): File? = runCatching {
        val dossier = File(ctx.cacheDir, "imports").apply { mkdirs() }
        val veille = System.currentTimeMillis() - 86_400_000L
        dossier.listFiles()?.forEach { if (it.lastModified() < veille) it.delete() }
        val nom = (nomDe(ctx, uri) ?: "audio.wav").replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val f = File(dossier, "SatMe_IMPORT_$nom")
        ctx.contentResolver.openInputStream(uri)!!.use { entree ->
            f.outputStream().use { entree.copyTo(it) }
        }
        f.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun nomDe(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
