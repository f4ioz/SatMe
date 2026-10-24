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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fr.f4ioz.satcombo.data.ActivitesTelephone
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * « Activités à venir » in the background, every 30 minutes with a network:
 * the sources due read (each at its own pace, see [fr.f4ioz.satcombo.data.ActivitesEnLigne]),
 * then the notices — a new activity (once), « ça émet maintenant », and one
 * before each useful pass during an activity, laid down as one-off work at
 * AOS minus the chosen lead (WorkManager, as the agenda's reminders).
 */
class ActivitesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = SettingsStore(ctx)
        // The SSTV recordings kept as long as chosen (off by default: nothing deleted).
        runCatching { fr.f4ioz.satcombo.sstv.OptionsSstvTelephone.appliqueRetention(ctx, "réglage") }
        if (!s.activitesOn) { WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG_PASSAGE); WorkManager.getInstance(ctx).cancelAllWorkByTag(fr.f4ioz.satcombo.sstv.OptionsSstvTelephone.TAG); return Result.success() }
        val en = ActivitesTelephone.enLigne(ctx)
        val sats = ActivitesTelephone.satellites(ctx)
        runCatching { en.rafraichit(s.reglagesActivites, sats.map { it.name to it.catalogNumber }) }
        verifie(ctx)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "activites_a_venir"
        const val TAG_PASSAGE = "activites_passage"
        const val CHANNEL_ID = "activites_a_venir"
        const val EXTRA_OUVRE = "open_activites"

        /** Every 30 min with a network (AMSAT's pace while an SSTV is on; the others slower, by themselves). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ActivitesWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * What to say from the list as it is now (after a refresh, in the
         * background or on screen): new activities, « ça émet », the pass
         * notices laid down again.
         */
        suspend fun verifie(ctx: Context) {
            val s = SettingsStore(ctx)
            val t = System.currentTimeMillis()
            val l = ActivitesTelephone.enLigne(ctx).liste(s.reglagesActivites)
            val sats = ActivitesTelephone.satellites(ctx)
            val obs = ActivitesTelephone.observateur(ctx)
            val passes = ActivitesTelephone.passages(l, sats, obs, s.minElevDeg.toDouble(), t)
            val utiles: (ActivitesAVenir.Activite) -> List<fr.f4ioz.satcombo.data.SatPass> = { passes[it.id].orEmpty() }
            var m = ActivitesTelephone.memoire(ctx)
            if (s.activitesNotifNouvelle) ActivitesAVenir.aSignaler(m, l, t, utiles).take(4).forEach { a ->
                notifie(ctx, tf("act_notif_nouvelle", a.satNom.ifBlank { "?" } + " " + t("act_type_" + a.type.name.lowercase())), resume(ctx, a, passes[a.id].orEmpty()), a.id.hashCode())
            }
            m = ActivitesAVenir.retiens(m, l, t)
            val emet = ActivitesAVenir.emetMaintenant(m, l, t)
            if (s.activitesNotifEmet) emet.forEach { a ->
                notifie(ctx, tf("act_notif_emet", a.satNom + " " + t("act_type_" + a.type.name.lowercase())), tf("act_notif_emet_texte", a.ecoutes) +
                    (passes[a.id]?.firstOrNull()?.let { " · " + tf("act_prochain", heure(ctx, it.aosEpochMs), it.maxElevationDeg.toInt()) } ?: ""), a.id.hashCode() + 1)
            }
            m = m.copy(emises = m.emises + emet.map(ActivitesAVenir::cle))
            ActivitesTelephone.garde(ctx, m)
            planifiePassages(ctx, ActivitesAVenir.alertesPassages(l, utiles, t, s.activitesAvantMin).filter { it.cle !in m.passages }, s.activitesNotifPassage)
            // « Diapo manquante attendue »: the series' passes sending a slide still wanted.
            runCatching { fr.f4ioz.satcombo.sstv.OptionsSstvTelephone.planifieAlertes(ctx) }
        }

        /** The pass notices laid down again (all cleared first: the list may have moved). */
        private fun planifiePassages(ctx: Context, l: List<ActivitesAVenir.AlertePassage>, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelAllWorkByTag(TAG_PASSAGE)
            if (!on) return
            val t = System.currentTimeMillis()
            l.take(30).forEach { a ->
                val p = a.passage
                wm.enqueue(OneTimeWorkRequestBuilder<ActivitesPassageWorker>()
                    .setInitialDelay((a.quandMs - t).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .addTag(TAG_PASSAGE)
                    .setInputData(workDataOf("cle" to a.cle, "sat" to a.activite.satNom, "type" to t("act_type_" + a.activite.type.name.lowercase()),
                        "titre" to a.activite.titre, "aos" to p.aosEpochMs, "los" to p.losEpochMs, "el" to p.maxElevationDeg,
                        "freq" to a.activite.freqHz, "mode" to a.activite.mode))
                    .build())
            }
        }

        fun heure(ctx: Context, ms: Long): String {
            val utc = runCatching { SettingsStore(ctx).useUtc }.getOrDefault(false)
            return SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { if (utc) timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms)) + if (utc) " UTC" else ""
        }

        private fun resume(ctx: Context, a: ActivitesAVenir.Activite, p: List<fr.f4ioz.satcombo.data.SatPass>): String =
            listOfNotNull(a.titre,
                heure(ctx, a.debutMs) + " → " + heure(ctx, a.finMs),
                a.freqHz.takeIf { it > 0 }?.let { "%.3f MHz".format(Locale.US, it / 1e6) + if (a.mode.isNotBlank()) " " + a.mode else "" },
                p.firstOrNull()?.let { tf("act_prochain", heure(ctx, it.aosEpochMs), it.maxElevationDeg.toInt()) }).joinToString(" · ")

        /** One notice on the activities' own channel; tapped, it opens « Activités à venir ». */
        fun notifie(ctx: Context, titre: String, texte: String, id: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, t("act_canal"), NotificationManager.IMPORTANCE_HIGH)
                ch.description = t("act_canal_desc")
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val ouvre = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OUVRE, true)
            }
            val pi = android.app.PendingIntent.getActivity(ctx, 0x5A7E, ouvre,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(0x5A7E0000 + (id and 0xFFFF), n)
        }
    }
}

/** A useful pass during an activity, [ActivitesWorker.verifie]'s notice: said once. */
class ActivitesPassageWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val d = inputData
        val cle = d.getString("cle") ?: return Result.failure()
        val s = SettingsStore(ctx)
        if (!s.activitesOn || !s.activitesNotifPassage) return Result.success()
        val m = ActivitesTelephone.memoire(ctx)
        if (cle in m.passages) return Result.success()
        ActivitesTelephone.garde(ctx, m.copy(passages = m.passages + cle))
        val aos = d.getLong("aos", 0L)
        val minutes = ((aos - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0L)
        val freq = d.getLong("freq", 0L)
        val texte = listOf(d.getString("titre").orEmpty(),
            tf("act_passage_aos", ActivitesWorker.heure(ctx, aos), d.getDouble("el", 0.0).toInt()),
            if (freq > 0) "%.3f MHz".format(Locale.US, freq / 1e6) + d.getString("mode").orEmpty().let { if (it.isNotBlank()) " $it" else "" } else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
        ActivitesWorker.notifie(ctx, tf("act_notif_passage", d.getString("sat").orEmpty(), minutes, d.getString("type").orEmpty()), texte, cle.hashCode())
        return Result.success()
    }
}
