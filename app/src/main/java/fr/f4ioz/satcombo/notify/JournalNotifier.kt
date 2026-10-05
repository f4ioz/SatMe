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
 * A quiet word when a pass is kept in the journal by itself (automatic SSTV,
 * every pass under CAT, a recording with its page left). A channel of its
 * own, low: no sound, no pop-up.
 */
object JournalNotifier {
    const val CHANNEL_ID = "journal_passages"
    private var suivant = 7400
    const val EXTRA_CATNUM = "open_journal_catnum"
    const val EXTRA_DEBUT = "open_journal_debut"
    const val EXTRA_FIN = "open_journal_fin"

    /** Tapped, it opens the journal on that pass ([catnum], its start and end). */
    fun notifie(ctx: Context, titre: String, texte: String, catnum: Int, debutMs: Long, finMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, t("journal_notif_canal"), NotificationManager.IMPORTANCE_LOW)
            ch.description = t("journal_notif_canal_desc")
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val id = suivant++
        val ouvre = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CATNUM, catnum)
            putExtra(EXTRA_DEBUT, debutMs)
            putExtra(EXTRA_FIN, finMs)
        }
        // One request code per pass: each notification keeps its own pass.
        val pi = android.app.PendingIntent.getActivity(ctx, id, ouvre,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
