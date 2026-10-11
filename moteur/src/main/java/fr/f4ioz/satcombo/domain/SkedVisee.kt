/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * Which mutual window to show first.
 *
 * The sked search sweeps 48 hours and returns every window where the satellite
 * is visible from both stations. Opened directly, the first window (the next
 * one) is the right one.
 *
 * Opened **from a hams.at announcement**, it is not: the announcement names a
 * time, and showing tonight's window after tapping a Wednesday-morning
 * announcement forces the operator to scroll for what they already saw.
 *
 * So the announced instant selects the window containing it. If none does
 * (stale elements, the local window drifted a few minutes, or the announced
 * station is not visible from here for the whole pass), take the nearest one
 * rather than nothing: a neighbouring window is recognisable at a glance.
 */
object SkedVisee {

    /**
     * Index of the window to show in [fenetres], never out of bounds.
     * Without [visee], the first one (the list is chronological).
     */
    fun index(fenetres: List<LongRange>, visee: Long?): Int {
        if (fenetres.isEmpty() || visee == null) return 0
        val dedans = fenetres.indexOfFirst { visee in it }
        if (dedans >= 0) return dedans
        return fenetres.indices.minByOrNull {
            minOf(
                kotlin.math.abs(fenetres[it].first - visee),
                kotlin.math.abs(fenetres[it].last - visee))
        } ?: 0
    }
}
