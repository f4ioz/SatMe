/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import java.util.Locale

/**
 * Waypoint lines a radio with built-in APRS writes on its serial port, one
 * per station it decodes — Yaesu FT3D with OUTPUT set to WAY.P:
 * `$GPWPL,4848.75,N,00227.50,E,F4IOZ-7*7F`. Also Kenwood's `$PKWDWPL` and
 * Magellan's `$PMGNWPL`. Only a callsign and a position: no path, no message.
 */
object Waypoints {

    data class Station(val nom: String, val lat: Double, val lon: Double)

    /** NMEA checksum: XOR of everything between '$' and '*'. */
    private fun sommeOk(ligne: String): Boolean {
        val etoile = ligne.lastIndexOf('*')
        if (etoile < 0) return true  // no checksum given: accepted
        val attendu = ligne.substring(etoile + 1).take(2).toIntOrNull(16) ?: return false
        return ligne.substring(1, etoile).fold(0) { a, c -> a xor c.code } == attendu
    }

    private fun angle(v: String, h: String, degres: Int): Double? {
        if (v.length < degres + 2) return null
        val d = v.substring(0, degres).toIntOrNull() ?: return null
        val m = v.substring(degres).toDoubleOrNull() ?: return null
        val a = d + m / 60
        return if (h.equals("S", true) || h.equals("W", true)) -a else a
    }

    /** One line → a station, or null when it is not a waypoint (or is damaged). */
    fun lit(ligne: String): Station? {
        val l = ligne.trim()
        if (!l.startsWith("$") || !sommeOk(l)) return null
        val f = l.substringBefore('*').split(',')
        // Where latitude starts, and where the name is, depends on the sentence.
        val (i, nom) = when (f[0].uppercase(Locale.US)) {
            "\$GPWPL", "\$PMGNWPL" -> 1 to f.getOrNull(5)
            "\$PKWDWPL" -> 3 to f.getOrNull(11)
            else -> return null
        }
        if (f.size < i + 4 || nom.isNullOrBlank()) return null
        val lat = angle(f[i], f[i + 1], 2) ?: return null
        val lon = angle(f[i + 2], f[i + 3], 3) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Station(nom.trim().uppercase(Locale.US), lat, lon)
    }

    /**
     * The station as a frame for the APRS list: its callsign as source, its
     * position, "FT3D" as comment. Not a frame that was on the air as such.
     */
    fun trame(s: Station, poste: String): Trame =
        Trame(Adresse("WPL"), Adresse.de(s.nom), emptyList(),
            AprsEmission.position(s.lat, s.lon, "/.", poste).toByteArray(Charsets.ISO_8859_1))
}
