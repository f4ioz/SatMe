/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.SourcesStore
import fr.f4ioz.satcombo.data.TleCache
import fr.f4ioz.satcombo.data.TleRepository
import java.util.concurrent.TimeUnit

/** Refreshes the TLE disk cache daily so propagation stays accurate. */
class TleRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ids = SourcesStore(applicationContext).load()
        val urls = Sources.byIds(ids).map { it.url }
        if (urls.isEmpty()) return Result.success()
        val sats = runCatching { TleRepository().fetchGroups(urls) }.getOrDefault(emptyList())
        if (sats.isNotEmpty()) {
            TleCache(applicationContext).save(sats, ids)
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TleRefreshWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tle_daily_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
