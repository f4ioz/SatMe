/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
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
 * Asks the Play Store whether a newer SatMe is published, and stops there.
 *
 * Play Core's "flexible" in-app download was dropped: in the field it reported
 * a finished download, asked for a restart, and the restart completed nothing.
 * Now we only detect, say so, and open the Store listing; the Store downloads
 * and installs.
 *
 * On a sideloaded copy the Store API returns an error: silent for the
 * automatic check, reported when the operator asked.
 */
class PlayUpdater(private val activity: ComponentActivity, private val vm: MainViewModel) {

    companion object {
        /** The live instance, so Settings can request a check without passing
         *  it down the composable tree. */
        @Volatile var active: PlayUpdater? = null
            private set

        /**
         * Opens the SatMe Play Store listing: `market://` first (the Store
         * app, straight to "Update"), the web page on phones without it.
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
     * The factory itself can throw on phones with missing or stripped Play
     * services — and it runs in `onCreate`, before the first screen. Guard the
     * construction, not only the calls.
     */
    private val manager = runCatching { AppUpdateManagerFactory.create(activity) }.getOrNull()

    init { active = this }

    /**
     * Silent check on every return to the app: says nothing unless there is
     * an update to offer.
     */
    fun check() {
        (manager ?: return).appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
                    vm.updateFound(info.availableVersionCode())
            }
            .addOnFailureListener { /* not installed from the Store: stay quiet */ }
    }

    /**
     * Check requested from the About page. Always answers — including "up to
     * date" and "not possible here": the operator clicked.
     */
    fun recheck() {
        vm.updateForget()
        vm.updateSay(t("update_checking"))
        // No Play Core: answer anyway rather than leave "checking…" forever.
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
     * Opens the Store listing and marks this version as handled: no asking
     * again on every launch. The next published version will be announced.
     */
    fun openStore() {
        vm.updateOpened()
        if (!openListing(activity)) vm.updateSay(t("update_no_store"))
    }

    fun dispose() {
        if (active === this) active = null
    }
}
