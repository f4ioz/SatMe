/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.diag

import android.app.Application
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le guetteur. Posé à la toute première instruction de l'application — avant
 * la moindre activité, avant le moindre écran — parce qu'un plantage au
 * démarrage arrive justement avant tout le reste.
 *
 * On garde le gestionnaire précédent et on le rappelle. Sans cela le système
 * n'afficherait plus « SatMe s'est arrêté », le Play Console ne compterait
 * plus rien, et l'on aurait remplacé une cécité par une autre.
 *
 * Le renseignement sur l'appareil est récolté ici, à l'installation, et non au
 * moment de la chute : un processus qui meurt n'est pas l'endroit où
 * interroger le gestionnaire de paquets.
 */
object PlantageGarde {

    private const val FORMAT = "yyyy-MM-dd HH:mm:ss"

    @Volatile private var pose = false

    fun installe(app: Application) {
        // Posé une fois. Deux poses enchaîneraient le guetteur sur lui-même,
        // et le rapport partirait en double.
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
            // Et l'on rend la main : le système garde le dernier mot.
            precedent?.uncaughtException(fil, t)
        }
    }
}
