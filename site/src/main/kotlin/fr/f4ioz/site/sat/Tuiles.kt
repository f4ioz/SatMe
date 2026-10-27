/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.data.AgentHttp
import fr.f4ioz.site.securite.Limiteur
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The OpenStreetMap tiles of the Sked's map, as SatMe-PC fetches them
 * (`journal/tuile`): asked by the site, kept a month on disk, so the
 * visitors' browsers ask only this site (nothing of theirs goes to OSM).
 * Zoom 12 at most, two downloads at a time, 120 a minute for the whole
 * site: a visitor cannot make the site hammer OSM's servers.
 */
class Tuiles(private val dossier: File, private val horsLigne: Boolean) {

    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
    private val enCours = Semaphore(2)
    private val debit = Limiteur(120, 60_000L)

    fun tuile(z: Int, x: Int, y: Int): File? {
        if (z !in 0..ZOOM_MAX) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val xw = ((x % n) + n) % n
        val f = File(dossier, "$z/$xw/$y.png")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) return f
        if (horsLigne || !debit.accepte("osm")) return f.takeIf { it.isFile }
        if (!enCours.tryAcquire(5, TimeUnit.SECONDS)) return f.takeIf { it.isFile }
        try {
            return runCatching {
                val req = Request.Builder().url("https://tile.openstreetmap.org/$z/$xw/$y.png").header("User-Agent", AgentHttp.USER_AGENT).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val b = r.body?.bytes() ?: return@use null
                    if (b.size > 300_000) return@use null
                    f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, f.name + ".part"); tmp.writeBytes(b); tmp.renameTo(f)
                    f
                }
            }.getOrNull() ?: f.takeIf { it.isFile }
        } finally { enCours.release() }
    }

    companion object { const val ZOOM_MAX = 12 }
}
