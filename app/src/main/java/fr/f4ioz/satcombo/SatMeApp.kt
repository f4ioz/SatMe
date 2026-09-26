/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.diag.PlantageGarde

/**
 * The first thing Android builds in our process, hence the only place that
 * can see a crash occurring before the first screen. Without it, a tester's
 * app once died at startup before any of our code had run to report it.
 *
 * Put nothing else here: any work done here delays the first screen, on every
 * phone, forever.
 */
class SatMeApp : Application() {

    /**
     * Not `onCreate`. Android builds content providers — including the one
     * that initialises WorkManager — BETWEEN `attachBaseContext` and
     * `onCreate`. A crash in that window escaped the guard, and that is exactly
     * where startups that depend on a vendor installer die.
     */
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        runCatching { PlantageGarde.installe(this) }
    }
}
