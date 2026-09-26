/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the device is willing to say about itself.
 *
 * Each line is here because it has already helped, or rules out a lead at
 * once. The modules line especially: a split APK missing one split dies before
 * its first instruction, and knowing there were none closes that lead.
 *
 * Everything is wrapped in `runCatching`: the device is suspected to be in bad
 * shape, and the inventory must not die of what it inventories.
 */
object EtatAppareil {

    private fun sansFaute(defaut: String, bloc: () -> String): String =
        runCatching(bloc).getOrDefault(defaut)

    fun entete(ctx: Context): String = buildString {
        val quand = sansFaute("?") {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        }
        appendLine("Quand : $quand")

        sansFaute("") {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            appendLine("SatMe : ${p.versionName} ($code) — ${ctx.packageName}")
            appendLine("Installé le : " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(p.firstInstallTime)) +
                ", mis à jour le " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(p.lastUpdateTime)))
            ""
        }

        appendLine("Installeur : " + sansFaute("?") {
            @Suppress("DEPRECATION")
            val nom = if (Build.VERSION.SDK_INT >= 30)
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else ctx.packageManager.getInstallerPackageName(ctx.packageName)
            nom ?: "aucun (copie installée à la main)"
        })

        appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} " +
            "(marque ${Build.BRAND}, device ${Build.DEVICE}, produit ${Build.PRODUCT})")
        appendLine("Android : ${Build.VERSION.RELEASE} — API ${Build.VERSION.SDK_INT}" +
            (if (Build.VERSION.SDK_INT >= 23) ", correctif ${Build.VERSION.SECURITY_PATCH}" else ""))
        appendLine("Empreinte : ${Build.FINGERPRINT}")
        appendLine("Jeux d'instructions : " + sansFaute("?") {
            Build.SUPPORTED_ABIS.joinToString(", ")
        })

        appendLine("Modules du paquet : " + sansFaute("?") {
            Plantage.modules(ctx.applicationInfo.splitNames)
        })
        appendLine("Fichiers du paquet : " + sansFaute("?") {
            val extras = ctx.applicationInfo.splitSourceDirs?.size ?: 0
            "1 tronc + $extras morceau(x)"
        })

        appendLine("Langue du système : " + sansFaute("?") {
            val c = ctx.resources.configuration
            @Suppress("DEPRECATION")
            val liste = if (Build.VERSION.SDK_INT >= 24) c.locales.toLanguageTags()
            else c.locale.toString()
            "$liste (défaut Java ${Locale.getDefault()})"
        })
        appendLine("Fuseau : " + sansFaute("?") { java.util.TimeZone.getDefault().id })

        appendLine("Écran : " + sansFaute("?") {
            val m = ctx.resources.displayMetrics
            val c = ctx.resources.configuration
            "${m.widthPixels}×${m.heightPixels}, ${m.densityDpi} ppp, " +
                "police ×${c.fontScale}, ${c.smallestScreenWidthDp} dp de large"
        })

        appendLine("Mémoire : " + sansFaute("?") {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            "classe ${am.memoryClass} Mo (large ${am.largeMemoryClass} Mo), " +
                "libre ${info.availMem / 1_048_576} Mo sur ${info.totalMem / 1_048_576} Mo" +
                (if (am.isLowRamDevice) ", APPAREIL DÉCLARÉ À FAIBLE MÉMOIRE" else "") +
                (if (info.lowMemory) ", système en manque de mémoire" else "")
        })

        appendLine("Disque : " + sansFaute("?") {
            val s = StatFs(Environment.getDataDirectory().path)
            "${s.availableBytes / 1_048_576} Mo libres sur ${s.totalBytes / 1_048_576} Mo"
        })

        appendLine("Dossier privé : " + sansFaute("?") { ctx.filesDir.absolutePath })
    }.trimEnd()

    /**
     * Android's process exit history. Needs Android 11; below that, callers
     * must say so rather than show an empty list that reads as "no crash".
     */
    fun sorties(ctx: Context, combien: Int = 8): List<ModeEchec.Sortie> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, combien).map { e ->
                ModeEchec.Sortie(
                    quand = fmt.format(Date(e.timestamp)),
                    raison = e.reason,
                    description = e.description,
                    importance = e.importance,
                    octets = e.pss)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The detailed trace the system keeps for ANRs and native crashes. Only
     * available for our own app and some exit reasons; when present, it is the
     * most precise document we can get.
     */
    fun traceSysteme(ctx: Context, max: Int = 20_000): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3)
                .firstOrNull { it.traceInputStream != null } ?: return null
            e.traceInputStream!!.bufferedReader().use { it.readText() }
                .let { ModeEchec.tronqueParLeDebut(it, max) }
        }.getOrNull()
    }

    /**
     * The system log, filtered by Android to our app only.
     *
     * The buffer survives process death, so a failed startup is still there
     * even if we never wrote it anywhere. Some vendors forbid `logcat` to
     * ordinary apps; we say so instead of returning nothing.
     */
    fun journal(lignes: Int = 400): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", lignes.toString())
            .redirectErrorStream(true).start()
        val texte = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        if (texte.isBlank()) "Journal vide (le système ne nous rend rien)."
        else ModeEchec.tronqueParLeDebut(texte)
    }.getOrElse { "Journal illisible : ${ModeEchec.decrit(it)}" }
}
