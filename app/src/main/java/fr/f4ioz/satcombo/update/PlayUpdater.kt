/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.i18n.t

/**
 * Demande au Play Store si une version plus récente de SatMe est publiée, et
 * s'arrête là.
 *
 * La version précédente téléchargeait la mise à jour elle-même, en tâche de
 * fond, par le mécanisme « flexible » de Play Core. Sur le terrain, ça ne
 * marchait pas : l'application annonçait un téléchargement terminé et
 * réclamait un redémarrage, le redémarrage ne finissait rien, et l'opérateur
 * se retrouvait quand même à ouvrir la fiche du Store à la main. Deux chemins
 * pour la même chose, dont un qui ment.
 *
 * Il n'en reste donc qu'un : détecter, dire qu'il y a du neuf, et ouvrir la
 * fiche Play Store. C'est le Store qui télécharge et qui installe — c'est son
 * métier, il le fait bien, et il sait le dire quand ça coince. On revient dans
 * SatMe après, et il n'y a rien à redémarrer nous-mêmes.
 *
 * Rien de tout ceci ne fonctionne sur une copie installée à la main : l'API du
 * Store répond par une erreur. Silencieusement lors de la vérification
 * automatique, à voix haute quand l'opérateur a cliqué lui-même.
 */
class PlayUpdater(private val activity: ComponentActivity, private val vm: MainViewModel) {

    companion object {
        /** L'instance vivante, pour que la page Réglages puisse demander une
         *  vérification sans qu'on ait à la faire descendre dans tout l'arbre
         *  des composables. */
        @Volatile var active: PlayUpdater? = null
            private set

        /**
         * Ouvre la fiche SatMe sur le Play Store.
         *
         * On essaie d'abord `market://`, qui ouvre l'application Play Store
         * directement sur le bouton « Mettre à jour ». Sur un téléphone sans
         * Play Store — il en circule — on retombe sur la page web, qui est
         * mieux que rien.
         */
        fun openListing(context: Context): Boolean {
            val id = context.packageName
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val web = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(market); true }
                .getOrElse { runCatching { context.startActivity(web); true }.getOrDefault(false) }
        }
    }

    /**
     * La fabrique elle-même peut échouer, sur un téléphone dont les services
     * Play sont absents ou amputés. Ses *appels* étaient protégés depuis
     * toujours ; sa *construction* ne l'était pas, et elle a lieu dans
     * `onCreate`, avant le premier écran. Une application de radioamateur qui
     * ne s'ouvre pas parce qu'elle n'a pas pu demander s'il existait une mise à
     * jour d'elle-même, c'est difficile à défendre.
     */
    private val manager = runCatching { AppUpdateManagerFactory.create(activity) }.getOrNull()

    init { active = this }

    /**
     * Vérification silencieuse, à chaque retour dans l'application. Elle ne
     * coûte rien et ne dit rien quand il n'y a rien : la seule chose qu'elle
     * peut produire est la proposition d'aller au Store.
     */
    fun check() {
        (manager ?: return).appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
                    vm.updateFound(info.availableVersionCode())
            }
            .addOnFailureListener { /* pas installée par le Store : on se tait */ }
    }

    /**
     * Vérification demandée depuis la page « À propos ». Même question, mais
     * celle-ci répond toujours — y compris « rien à faire » et « impossible
     * ici ». L'opérateur a cliqué, il a droit à une réponse.
     */
    fun recheck() {
        vm.updateForget()
        vm.updateSay(t("update_checking"))
        // Pas de Play Core ici : on répond quand même. Laisser « recherche en
        // cours » à l'écran pour toujours serait la pire des trois réponses.
        val m = manager ?: run { vm.updateSay(t("update_no_store")); return }
        m.appUpdateInfo
            .addOnSuccessListener { info ->
                when (info.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE,
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        vm.updateMsgClear()
                        vm.updateFound(info.availableVersionCode())
                    }
                    else -> vm.updateSay(t("update_none"))
                }
            }
            .addOnFailureListener { vm.updateSay(t("update_no_store")) }
    }

    /**
     * Emmène l'opérateur sur la fiche du Store, et considère la version comme
     * traitée : s'il revient sans avoir mis à jour, on ne lui repose pas la
     * question à chaque fois qu'il rouvre l'application. La prochaine version
     * publiée, elle, sera bien annoncée.
     */
    fun openStore() {
        vm.updateOpened()
        if (!openListing(activity)) vm.updateSay(t("update_no_store"))
    }

    fun dispose() {
        if (active === this) active = null
    }
}
