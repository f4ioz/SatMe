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
import fr.f4ioz.satcombo.i18n.t

/**
 * The APRS moments worth a sound in the pocket: our frame back from the ISS,
 * a contact made, a badge won. A channel of its own, so they can be silenced
 * without losing the pass alerts.
 */
object AprsNotifier {
    const val CHANNEL_ID = "aprs_joie"
    private var suivant = 7300

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, t("aprs_notif_canal"), NotificationManager.IMPORTANCE_HIGH)
            ch.description = t("aprs_notif_canal_desc")
            mgr.createNotificationChannel(ch)
        }
    }

    fun notifie(ctx: Context, titre: String, texte: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val ouvre = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = android.app.PendingIntent.getActivity(ctx, 7300, ouvre,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(suivant++, n)
    }
}
