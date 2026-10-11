/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.i18n

import androidx.compose.runtime.mutableIntStateOf

/**
 * The language, seen by Compose: a screen that reads a text depends on this
 * counter, and changing the language bumps it, so the screen is redrawn —
 * live, as before the translations moved to the engine.
 */
object I18nCompose {
    private val version = mutableIntStateOf(0)

    fun installe() {
        I18n.surLecture = { version.intValue }
        I18n.surChangement = { version.intValue++ }
    }
}
