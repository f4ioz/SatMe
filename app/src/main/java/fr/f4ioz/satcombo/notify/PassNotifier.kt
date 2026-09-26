/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PassNotifier {
    const val CHANNEL_ID = "pass_alerts"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, t("pass_alert_channel"), NotificationManager.IMPORTANCE_HIGH)
            ch.description = t("pass_alert_channel_desc")
            mgr.createNotificationChannel(ch)
        }
    }

    fun notifyPass(ctx: Context, pass: SatPass) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) return

        // Follow the app-wide UTC/local preference so the AOS time in the
        // notification matches the one shown in the pass list.
        val useUtc = fr.f4ioz.satcombo.data.SettingsStore(ctx).useUtc
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val tz = if (useUtc) "UTC" else "LOC"
        val visual = if (pass.visualPass) " 👁 visible" else ""
        val text = "AOS ${fmt.format(Date(pass.aosEpochMs))} $tz · el max ${pass.maxElevationDeg.toInt()}°$visual"

        // Tapping the notification opens the app straight on this satellite.
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_catnum", pass.catalogNumber)
            putExtra("open_aos", pass.aosEpochMs)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, pass.catalogNumber, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(tf("pass_notif_title", pass.satName))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(pass.catalogNumber, notif)
    }
}
