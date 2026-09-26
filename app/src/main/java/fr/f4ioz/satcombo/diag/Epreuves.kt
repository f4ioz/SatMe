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
import android.content.Context
import android.graphics.BitmapFactory
import android.view.ContextThemeWrapper
import fr.f4ioz.satcombo.R

/**
 * SatMe's startup, taken apart and tested piece by piece.
 *
 * The order follows the real startup, from most basic to most built: resources,
 * theme, large bundled files, settings, phone services, and the ViewModel last,
 * since it pulls in everything else.
 *
 * This beats a stack trace because it answers "from which point does this
 * device stop agreeing", not just "what threw". One FAIL line in a column of
 * OKs saves days of guessing.
 *
 * Each step returns a sentence even on success: "OK" alone cannot be compared
 * across devices, "3 262 504 bytes read" can.
 */
object Epreuves {

    fun liste(ctx: Context): List<ModeEchec.Etape> = listOf(

        ModeEchec.Etape("Ressources de l'application") {
            val nom = ctx.resources.getString(R.string.app_name)
            val d = ctx.resources.displayMetrics.densityDpi
            "libellé « $nom » lu, densité $d ppp"
        },

        ModeEchec.Etape("Thème de l'application") {
            val habille = ContextThemeWrapper(ctx, R.style.Theme_SatCombo)
            val a = habille.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val fond = a.getColor(0, 0)
            a.recycle()
            "thème appliqué, fond 0x%08X".format(fond)
        },

        ModeEchec.Etape("Textes embarqués (i18n)") {
            fr.f4ioz.satcombo.i18n.I18n.apply("auto", java.util.Locale.getDefault().language)
            val cfr = fr.f4ioz.satcombo.i18n.FR.size
            val cen = fr.f4ioz.satcombo.i18n.EN.size
            "$cfr clés FR, $cen clés EN"
        },

        // Megabytes of JSON sit in the APK: first place to look on a
        // low-RAM device.
        ModeEchec.Etape("Données géographiques embarquées") {
            val terres = ctx.resources.openRawResource(R.raw.land).use { it.readBytes().size }
            val pota = 0  // POTA catalogue removed from bundled resources
            val communes = ctx.resources.openRawResource(R.raw.communes_fr).use { it.readBytes().size }
            "terres $terres o, POTA $pota o, communes $communes o"
        },

        // No world-map image step any more: coastlines are drawn from
        // land.json, already tested by the step above.

        ModeEchec.Etape("Réglages enregistrés") {
            val noms = listOf("satcombo_settings", "satcombo_favorites", "satcombo_satconfig",
                "satcombo_sources", "satcombo_tle_cache", "satcombo_pota")
            noms.joinToString(", ") { n ->
                val p = ctx.getSharedPreferences(n, Context.MODE_PRIVATE)
                "$n ${p.all.size}"
            }
        },

        ModeEchec.Etape("Cache des éléments orbitaux") {
            val f = java.io.File(ctx.filesDir, "tle_cache.txt")
            if (!f.isFile) "aucun cache"
            else "${f.length()} o, ${f.readLines().size} lignes"
        },

        ModeEchec.Etape("Canal de notification") {
            fr.f4ioz.satcombo.notify.PassNotifier.ensureChannel(ctx)
            "canal en place"
        },

        // On a device without Google services (e.g. a recent Huawei tablet),
        // these steps can fail while everything else passes.
        ModeEchec.Etape("Services Google Play") {
            val code = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(ctx)
            val mot = when (code) {
                0 -> "disponibles"
                1 -> "ABSENTS"
                2 -> "à mettre à jour"
                3 -> "désactivés"
                9 -> "version non officielle"
                18 -> "en cours de mise à jour"
                else -> "code $code"
            }
            "$mot (code $code)"
        },

        ModeEchec.Etape("Localisation Google (fused)") {
            com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(ctx)
            "client de localisation construit"
        },

        ModeEchec.Etape("Mise à jour depuis le Play Store") {
            com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(ctx)
            "fabrique construite"
        },

        // Prime suspect. `WorkManager.getInstance` throws
        // `IllegalStateException` when its initialization provider was not
        // installed, which happens with some vendor installers only. It is
        // called in the ViewModel `init`, hence before the first screen.
        ModeEchec.Etape("Planificateur de tâches (WorkManager)") {
            val wm = androidx.work.WorkManager.getInstance(ctx)
            "instance ${wm.javaClass.simpleName}"
        },

        ModeEchec.Etape("Bibliothèque MP3 native (LAME)") {
            System.loadLibrary("androidlame")
            "libandroidlame.so chargée"
        },

        ModeEchec.Etape("Compose") {
            val v = androidx.compose.ui.platform.ComposeView(ctx)
            "vue ${v.javaClass.simpleName} construite"
        },

        // Last on purpose: constructing it replays nearly the whole startup.
        // If the process dies here, the crash handler writes the trace and
        // failure mode shows it on reopen, so the step is conclusive even
        // when it kills.
        ModeEchec.Etape("Modèle de vue (démarrage complet)") {
            val app = ctx.applicationContext as Application
            val vm = fr.f4ioz.satcombo.MainViewModel(app)
            "construit, écran d'ouverture ${vm.ui.value.screen}"
        }
    )
}
