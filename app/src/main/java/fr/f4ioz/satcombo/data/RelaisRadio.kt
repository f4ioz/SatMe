/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.json.JSONObject

/**
 * SatMe as a radio in Wavelog or Cloudlog: at home, contacts are typed in the
 * online log on the PC, and choosing the radio "SatMe" there fills in the
 * satellite, its mode and both frequencies.
 *
 * The frequencies are those of the RX and TX boxes: Doppler-corrected, at
 * the satellite side of any converter — what the rig works on.
 *
 * **Sent on change, not every second**, as the API asks: at once when the
 * satellite, the transponder or a mode changes; when a frequency has moved
 * by more than [SEUIL_HZ], at most every [INTERVALLE_MS] (Doppler moves it
 * all the time); and every [VEILLE_MS] otherwise, so the log does not take
 * the radio for switched off.
 */
class RelaisRadio {

    /** What the log needs from the radio. */
    data class Etat(
        val satellite: String,
        val montantHz: Long?,
        val descendantHz: Long?,
        val modeMontant: String,
        val modeDescendant: String,
    ) {
        /** "V/U", "U/V", "L/U"…: uplink band then downlink band. */
        val modeSat: String get() = "${bande(montantHz)}/${bande(descendantHz)}"
    }

    private var dernier: Etat? = null
    private var quandMs = 0L

    /** True when [e] deserves a message now. */
    fun aEnvoyer(e: Etat, maintenantMs: Long): Boolean {
        val d = dernier ?: return true
        if (e.satellite != d.satellite || e.modeMontant != d.modeMontant ||
            e.modeDescendant != d.modeDescendant || e.modeSat != d.modeSat) return true
        val ecoule = maintenantMs - quandMs
        if (ecoule >= VEILLE_MS) return true
        val bouge = ecart(e.montantHz, d.montantHz) > SEUIL_HZ || ecart(e.descendantHz, d.descendantHz) > SEUIL_HZ
        return bouge && ecoule >= INTERVALLE_MS
    }

    /** Records [e] as sent. */
    fun envoye(e: Etat, maintenantMs: Long) { dernier = e; quandMs = maintenantMs }

    /** Forget: the next state is sent at once (settings changed, relay restarted). */
    fun oublie() { dernier = null; quandMs = 0L }

    companion object {
        const val SEUIL_HZ = 1_000L
        const val INTERVALLE_MS = 5_000L
        const val VEILLE_MS = 120_000L

        private fun ecart(a: Long?, b: Long?): Long =
            if (a == null || b == null) (if (a == b) 0L else Long.MAX_VALUE) else kotlin.math.abs(a - b)

        /** Amateur satellite band letter of a frequency; "?" outside them. */
        fun bande(hz: Long?): String = when (hz) {
            null -> "?"
            in 21_000_000L..21_450_000L -> "H"
            in 28_000_000L..29_700_000L -> "A"
            in 144_000_000L..148_000_000L -> "V"
            in 420_000_000L..450_000_000L -> "U"
            in 1_240_000_000L..1_300_000_000L -> "L"
            in 2_300_000_000L..2_450_000_000L -> "S"
            in 5_650_000_000L..5_925_000_000L -> "C"
            in 10_000_000_000L..10_500_000_000L -> "X"
            in 24_000_000_000L..24_250_000_000L -> "K"
            else -> "?"
        }

        /**
         * The body for `api/radio`. "frequency"/"mode" are the uplink, the
         * transmitting side, as the API defines them; the satellite fields
         * give both legs.
         */
        fun json(cle: String, radio: String, e: Etat): String = JSONObject().apply {
            put("key", cle)
            put("radio", radio)
            put("prop_mode", "SAT")
            put("sat_name", e.satellite)
            put("sat_mode", e.modeSat)
            e.montantHz?.let { put("frequency", it); put("uplink_freq", it) }
            put("mode", e.modeMontant)
            put("uplink_mode", e.modeMontant)
            e.descendantHz?.let { put("frequency_rx", it); put("downlink_freq", it) }
            put("mode_rx", e.modeDescendant)
            put("downlink_mode", e.modeDescendant)
        }.toString()
    }
}
