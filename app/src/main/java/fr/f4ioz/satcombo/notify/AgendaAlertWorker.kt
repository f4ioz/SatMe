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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import fr.f4ioz.satcombo.data.AgendaStore
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Reminder for an agenda appointment.
 *
 * WorkManager, not AlarmManager, as for pass alerts: we warn an hour or a day
 * ahead, exact alarms need a permission the Play Store grants only to clocks
 * and calendars, and WorkManager survives a reboot without a boot receiver.
 */
class AgendaAlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val d = inputData
        val title = d.getString("title") ?: return Result.failure()
        val sat = d.getString("sat") ?: ""
        val note = d.getString("note") ?: ""
        val timeMs = d.getLong("time", 0L)
        val id = d.getLong("id", 0L)

        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        PassNotifier.ensureChannel(ctx)

        val useUtc = runCatching { SettingsStore(ctx).useUtc }.getOrDefault(true)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply {
            if (useUtc) timeZone = TimeZone.getTimeZone("UTC")
        }
        val when0 = fmt.format(Date(timeMs)) + " " + (if (useUtc) "UTC" else "LOC")
        val body = listOf(when0, sat, note).filter { it.isNotBlank() }.joinToString(" · ")

        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_agenda", true)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, id.toInt(), open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(ctx, PassNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(t("agenda_notif_title") + " · " + title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(
            (id % 100000L).toInt() + 900000, notif)
        return Result.success()
    }

    companion object {
        private const val TAG = "agenda_alert"

        /**
         * Reschedules every upcoming reminder.
         *
         * Everything is cleared and laid down again on each change: the list
         * is a few lines long, and keeping a differential state between the
         * diary and the scheduler would be the surest way to leave a reminder
         * behind for a deleted appointment.
         */
        fun reschedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            wm.cancelAllWorkByTag(TAG)
            val now = System.currentTimeMillis()
            AgendaStore.load(ctx)
                .filter { it.enabled && it.alertMs > now }
                .sortedBy { it.alertMs }
                .take(50)
                .forEach { e ->
                    val req = OneTimeWorkRequestBuilder<AgendaAlertWorker>()
                        .setInitialDelay(e.alertMs - now, TimeUnit.MILLISECONDS)
                        .addTag(TAG)
                        .setInputData(workDataOf(
                            "id" to e.id, "title" to e.title, "sat" to e.satName,
                            "note" to e.note, "time" to e.timeMs))
                        .build()
                    wm.enqueue(req)
                }
        }
    }
}
