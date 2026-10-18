/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's SDR tab, its pieces written once: SatMe-PC's dongle
 * (through rtl_tcp) and the phone's (on its USB port) run the same chain
 * ([fr.f4ioz.satcombo.sdr.Dsp]); their spectrum is shown the same way.
 */
object ApiSdr {

    /**
     * The last spectrum [sp] (dB, [parBinHz] a bin, centred on the dongle)
     * as [n] values over [span] Hz around [centreHz] — the Doppler target,
     * so the signal stays in the middle while the satellite passes: the
     * strongest bin each value covers.
     */
    fun spectre(sp: FloatArray?, parBinHz: Double, centreHz: Double, span: Int, n: Int, trames: Long, pllHz: Long): JSONObject {
        if (sp == null || sp.isEmpty()) return JSONObject().put("trames", 0)
        val nb = sp.size
        val largeur = span.coerceIn(2_000, 176_400).toDouble()
        val k = n.coerceIn(64, 2048)
        val v = IntArray(k) { i ->
            val a = centreHz - largeur / 2 + largeur * i / k
            val b = a + largeur / k
            var m = -200f
            var j0 = Math.floor(a / parBinHz).toInt() + nb / 2
            val j1 = Math.ceil(b / parBinHz).toInt() + nb / 2
            if (j1 <= j0) j0 = j1 - 1
            for (j in j0 until j1) if (j in 0 until nb) m = maxOf(m, sp[j])
            Math.round(m)
        }
        return JSONObject().put("trames", trames).put("centre", centreHz).put("span", largeur).put("pll", pllHz)
            .put("db", JSONArray(v.toList()))
    }

    /**
     * The sound cleaning in the page's words: the settings of the mode being
     * demodulated ([mode]), what it does now (carriers notched, audio Hz;
     * share of the IQ blanked), and every mode's settings.
     */
    fun nettoyage(mode: fr.f4ioz.satcombo.sdr.RxMode, parMode: Map<fr.f4ioz.satcombo.sdr.RxMode, fr.f4ioz.satcombo.sdr.RxCleaning>,
                  notches: List<Double>, blanc: Float): JSONObject {
        val tous = JSONObject()
        parMode.forEach { (m, c) -> tous.put(m.name, c.json()) }
        return JSONObject().put("ok", true).put("mode", mode.name)
            .put("reglages", (parMode[mode] ?: fr.f4ioz.satcombo.sdr.RxCleaning.OFF).json())
            .put("notches", JSONArray(notches.map { Math.round(it) })).put("blanc", Math.round(blanc * 10_000) / 100.0)
            .put("modes", tous)
    }
}
