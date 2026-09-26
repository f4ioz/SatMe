/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.diag

import android.app.Application
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crash watcher. Installed at the very first line of the application,
 * before any activity or screen, because a startup crash happens before
 * everything else.
 *
 * The previous handler is kept and called. Otherwise the system would no
 * longer show "SatMe has stopped" and Play Console would count nothing.
 *
 * Device info is collected here, at install time, not at crash time: a dying
 * process is no place to query the package manager.
 */
object PlantageGarde {

    private const val FORMAT = "yyyy-MM-dd HH:mm:ss"

    @Volatile private var pose = false

    fun installe(app: Application) {
        // Install once: a second install would chain the handler to itself
        // and write the report twice.
        if (pose) return
        pose = true

        val version = runCatching {
            val p = app.packageManager.getPackageInfo(app.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt()
                       else p.versionCode
            (p.versionName ?: "?") to code
        }.getOrDefault("?" to 0)

        val appareil = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        val androidVersion = "${Build.VERSION.RELEASE} — API ${Build.VERSION.SDK_INT}"
        val modules = runCatching {
            Plantage.modules(app.applicationInfo.splitNames)
        }.getOrDefault("?")

        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, t ->
            runCatching {
                val quand = SimpleDateFormat(FORMAT, Locale.US).format(Date())
                val texte = Plantage.redige(
                    t = t,
                    version = version.first,
                    code = version.second,
                    appareil = appareil,
                    androidVersion = androidVersion,
                    modules = modules,
                    horodatage = quand,
                    fil = fil.name)
                PlantageDisque.ecrit(app.filesDir, texte)
            }
            // Hand back to the system: it keeps the last word.
            precedent?.uncaughtException(fil, t)
        }
    }
}
