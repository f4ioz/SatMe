/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONObject

/**
 * The phone's settings into the PC station, from its configuration backup
 * (Settings → Backup, a `.json` file): callsign, locator, the mast's limits
 * and park, the rig's model and address — what was set once, carefully.
 *
 * **Left out: what names the phone's USB** (port numbers, adapter keys): on
 * a PC they would point at another port. And the mast arrives **not
 * following**: it moves only once the operator ticks it on the page.
 */
object ImportTelephone {

    /** Keys that name the phone's adapters or ports. */
    private val DU_TELEPHONE = setOf(
        "civ_usb_index", "civ_usb_auto", "rotor_usb_index", "rotor_usb_cle",
        "ft817_rx_serial", "ft817_tx_serial", "ts2000_cle")

    /** Writes the backup's settings into [stockage]; how many were taken, or an error. */
    fun importe(json: String, stockage: Stockage): Result<Int> = runCatching {
        val racine = JSONObject(json)
        require(racine.optString("app") == "SatMe") { "ce n'est pas une sauvegarde SatMe" }
        val reglages = racine.optJSONObject("stores")?.optJSONObject("satcombo_settings")
            ?: error("la sauvegarde n'a pas de réglages")
        val ed = stockage.edit()
        var n = 0
        for (k in reglages.keys()) {
            if (k in DU_TELEPHONE) continue
            val e = reglages.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "ss" -> {
                    val a = e.optJSONArray("v") ?: continue
                    ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
                else -> continue
            }
            n++
        }
        // The mast waits to be asked.
        ed.putBoolean("rotor_enabled", false)
        ed.apply()
        n
    }
}
