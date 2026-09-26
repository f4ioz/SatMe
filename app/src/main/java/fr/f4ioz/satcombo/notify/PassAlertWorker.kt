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
import androidx.work.Worker
import androidx.work.WorkerParameters
import fr.f4ioz.satcombo.data.SatPass

/** Fires the pass notification scheduled by MainViewModel. */
class PassAlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val d = inputData
        val pass = SatPass(
            satName = d.getString("name") ?: return Result.failure(),
            catalogNumber = d.getInt("cat", 0),
            aosEpochMs = d.getLong("aos", 0),
            losEpochMs = d.getLong("los", 0),
            maxElevationDeg = d.getDouble("maxEl", 0.0),
            aosAzimuthDeg = d.getDouble("aosAz", 0.0),
            losAzimuthDeg = d.getDouble("losAz", 0.0),
            sunlit = d.getBoolean("sunlit", false),
            nightAtObserver = d.getBoolean("night", false)
        )
        PassNotifier.ensureChannel(applicationContext)
        PassNotifier.notifyPass(applicationContext, pass)
        return Result.success()
    }
}
