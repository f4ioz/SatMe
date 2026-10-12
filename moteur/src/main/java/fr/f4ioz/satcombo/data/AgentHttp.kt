/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * What SatMe says it is to the services it asks (AMSAT, SatNOGS, QRZ, the
 * online log…): app, version, platform, the project's public page — never a
 * callsign. The SatMe GP server reads "SatMe/<v>". Set at startup by the
 * phone ("Android 14") and by the PC ("Linux").
 */
object AgentHttp {
    @Volatile var version: String = "?"
    @Volatile var plateforme: String = "?"

    val USER_AGENT: String get() = "SatMe/$version ($plateforme) +https://github.com/f4ioz/SatMe"
}
