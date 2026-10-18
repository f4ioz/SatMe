/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The « BaseStation » (SBS-1) text lines other programs read on TCP 30003:
 * one comma-separated line per reply, `MSG,<type>,…` with 22 fields —
 * 1 identification, 2 surface position, 3 airborne position, 4 velocity,
 * 5 altitude reply, 6 identity reply, 8 all-call. Fields the reply does
 * not carry stay empty.
 */
object Sbs {
    private val JOUR = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC)
    private val HEURE = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** The line for [m] (the aircraft [a] as it stands after it), or null for a reply with nothing to say. */
    fun ligne(m: MessageAdsb, a: TableAvions.Avion, maintenantMs: Long): String? {
        val t = Instant.ofEpochMilli(maintenantMs)
        val d = JOUR.format(t); val h = HEURE.format(t)
        val f = arrayOfNulls<String>(22)
        f[0] = "MSG"; f[2] = "1"; f[3] = "1"; f[4] = a.hex(); f[5] = "1"
        f[6] = d; f[7] = h; f[8] = d; f[9] = h
        when (m) {
            is MessageAdsb.Identification -> { f[1] = "1"; f[10] = a.indicatif }
            is MessageAdsb.Position -> {
                // Only a report that gave a fix just now.
                if (a.lat == null || a.posMs != a.dernierMs) return null
                if (m.surface) {
                    f[1] = "2"
                    f[12] = a.vitesseNoeuds?.let { Math.round(it).toString() }; f[13] = a.routeDeg?.let { Math.round(it).toString() }
                    f[21] = "-1"
                } else {
                    f[1] = "3"; f[11] = (if (m.gnss) a.altitudeGnss else a.altitudePieds)?.toString()
                    f[18] = "0"; f[19] = "0"; f[20] = "0"; f[21] = "0"
                }
                f[14] = "%.5f".format(java.util.Locale.ROOT, a.lat); f[15] = "%.5f".format(java.util.Locale.ROOT, a.lon)
            }
            is MessageAdsb.Vitesse -> {
                f[1] = "4"
                f[12] = m.vitesseNoeuds?.let { Math.round(it).toString() }; f[13] = m.routeDeg?.let { Math.round(it).toString() }
                f[16] = m.vertical?.toString()
            }
            is MessageAdsb.Altitude -> { f[1] = "5"; f[11] = m.altitudePieds?.toString() }
            is MessageAdsb.Identite -> { f[1] = "6"; f[17] = m.squawk }
            is MessageAdsb.Autre -> if (m.df == 11) f[1] = "8" else return null
        }
        return f.joinToString(",") { it ?: "" }
    }
}
