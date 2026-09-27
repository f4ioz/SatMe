/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A pass handed to the phone's calendar, to plan it with the rest of the day
 * or share it with a club.
 *
 * Through the calendar's own "new event" screen: SatMe writes nothing and
 * needs no calendar permission; the operator checks, picks the calendar and
 * a reminder, and saves.
 */
object AgendaTelephone {

    /** Event title: "SO-50 pass". */
    fun titre(p: SatPass): String = tf("cal_titre", p.satName)

    /**
     * Event text: AOS and LOS in UTC with their azimuths, maximum elevation,
     * duration. UTC because a sked is agreed in UTC; the calendar itself
     * shows the event at local time.
     */
    fun description(p: SatPass, locator: String): String {
        val hm = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tf("cal_desc",
            hm.format(Date(p.aosEpochMs)), p.aosAzimuthDeg.toInt(),
            p.maxElevationDeg.toInt(),
            hm.format(Date(p.losEpochMs)), p.losAzimuthDeg.toInt(),
            p.durationSec / 60, p.durationSec % 60) +
            (if (locator.isNotBlank()) "\n" + tf("cal_qth", locator) else "")
    }

    /** Opens the calendar's new-event screen; false when the phone has none. */
    fun ouvre(ctx: Context, p: SatPass, locator: String): Boolean {
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, p.aosEpochMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, p.losEpochMs)
            .putExtra(CalendarContract.Events.TITLE, titre(p))
            .putExtra(CalendarContract.Events.DESCRIPTION, description(p, locator))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, locator)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }
}
