/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fr.f4ioz.satcombo.demo.ApiCorrectionSstv
import java.io.File

/**
 * The phone's corrected SSTV pictures ([CorrectionsGalerie], kept apart in
 * `sstv/corrigees/`): the viewer's « Corrigée », the page's (as SatMe-PC's),
 * the sheet's « Versions corrigées ». One worker for all of them.
 */
object CorrectionsTelephone {

    @Volatile private var api: ApiCorrectionSstv? = null

    private fun charge(f: File): ClassementGalerie.Pixels? = runCatching {
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        ClassementGalerie.Pixels(px, b.width, b.height).also { b.recycle() }
    }.getOrNull()

    private fun ecrit(f: File, px: IntArray, w: Int, h: Int): Boolean = runCatching {
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val t = File(f.parentFile, f.name + ".tmp")
        val ok = t.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        ok && (t.renameTo(f) || run { f.delete(); t.renameTo(f) })
    }.getOrDefault(false)

    fun api(ctx: Context): ApiCorrectionSstv = api ?: synchronized(this) {
        api ?: run {
            val app = ctx.applicationContext
            ApiCorrectionSstv(CorrectionsGalerie(::charge, ::ecrit), { SstvHub.dir(app) },
                { ClassementTelephone.shots(app) }, { ClassementTelephone.etat(app).groupes }).also { api = it }
        }
    }

    @Volatile private var auto: RedecodageAuto? = null

    /**
     * « Re-décoder chaque image à la fin »: one background-class thread, the
     * option on and the battery saver off (asked again when a picture's turn
     * comes).
     */
    fun auto(ctx: Context): RedecodageAuto = auto ?: synchronized(this) {
        auto ?: run {
            val app = ctx.applicationContext
            val energie = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            RedecodageAuto(api(app).corrections,
                { OptionsSstvTelephone.options(app).redecodeFin && energie?.isPowerSaveMode != true },
                RedecodageAuto.filBas { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }).also { auto = it }
        }
    }

    /** A picture just saved live with its sound: decoded again in the background (returns at once). */
    fun apresImage(ctx: Context, png: File) {
        runCatching {
            if (!SstvSon.fichier(png).isFile) return
            val s = SstvMeta.decode(png.name, File(png.parentFile, SstvMeta.sidecarName(png.name)).readText(Charsets.UTF_8))
            auto(ctx).demande(png.parentFile ?: return, s)
        }
    }

    /** A picture deleted: its corrected version with it. */
    fun oublie(png: File) {
        val d = CorrectionsGalerie.dossier(png.parentFile ?: return)
        val c = File(d, CorrectionsGalerie.nomCorrigee(png.name))
        runCatching { File(d, c.name.removeSuffix(".png") + ".meta").delete(); c.delete() }
    }
}
