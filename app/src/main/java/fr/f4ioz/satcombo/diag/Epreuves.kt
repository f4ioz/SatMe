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
import android.content.Context
import android.graphics.BitmapFactory
import android.view.ContextThemeWrapper
import fr.f4ioz.satcombo.R

/**
 * Le démarrage de SatMe, démonté en pièces qu'on éprouve une par une.
 *
 * L'ordre suit celui du vrai démarrage, du plus fondamental au plus construit :
 * les ressources d'abord, le thème ensuite, puis les gros fichiers embarqués,
 * les réglages, les services du téléphone, et le modèle de vue en dernier —
 * lui seul rassemble tout le reste.
 *
 * Ce découpage vaut mieux qu'une pile d'appels, parce qu'il répond à la
 * question suivante : non pas « qu'est-ce qui a levé », mais « à partir d'où
 * cet appareil-ci n'est plus d'accord ». Une seule ligne ÉCHEC au milieu d'une
 * colonne d'OK vaut deux jours de suppositions.
 *
 * Chaque épreuve rend une phrase même quand elle réussit. « OK » tout seul ne
 * se compare pas d'un appareil à l'autre ; « 3 262 504 octets lus » se compare.
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

        // Six méga-octets de JSON dorment dans le paquet. Sur un appareil
        // déclaré à faible mémoire, c'est le premier endroit où regarder.
        ModeEchec.Etape("Données géographiques embarquées") {
            val terres = ctx.resources.openRawResource(R.raw.land).use { it.readBytes().size }
            val pota = 0  // catalogue POTA retiré des ressources embarquées
            val communes = ctx.resources.openRawResource(R.raw.communes_fr).use { it.readBytes().size }
            "terres $terres o, POTA $pota o, communes $communes o"
        },

        // L'épreuve sur les images du planisphère a été retirée avec elles :
        // les côtes se tracent désormais depuis land.json, déjà éprouvé par
        // l'étape des données vectorielles juste au-dessus.

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

        // Sur un appareil sans services Google — une tablette Huawei récente
        // n'en a aucun — ces deux épreuves sont les seules à pouvoir échouer
        // sans que rien d'autre ne bouge.
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

        // Le suspect le plus sérieux de la liste. `WorkManager.getInstance`
        // lève `IllegalStateException` quand son fournisseur d'initialisation
        // n'a pas été installé — ce qui arrive chez certains installeurs de
        // constructeurs, et chez eux seulement. L'appel a lieu dans l'`init`
        // du modèle de vue, donc avant le premier écran.
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

        // En dernier, et c'est voulu : sa construction rejoue à elle seule la
        // quasi-totalité du démarrage. Si le processus meurt ici, la trace est
        // écrite par le garde-fou et le mode échec la montrera à la
        // réouverture — l'épreuve reste donc concluante même quand elle tue.
        ModeEchec.Etape("Modèle de vue (démarrage complet)") {
            val app = ctx.applicationContext as Application
            val vm = fr.f4ioz.satcombo.MainViewModel(app)
            "construit, écran d'ouverture ${vm.ui.value.screen}"
        }
    )
}
