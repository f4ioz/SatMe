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
 * What is pulled from the online log to feed the callsign keypad.
 *
 * The Wavelog API shapes this rule: its `band` filter takes **a single band**
 * and there is **no mode filter**. Only the satellite case can be filtered
 * server-side; everything else is downloaded whole and filtered here. That
 * first harvest is much heavier, and the screen must say so or the operator
 * will think it has hung.
 */
object FiltreMoisson {

    const val SAT = "sat"
    const val PHONIE_HF = "phonie"
    const val CW = "cw"
    const val TOUT = "tout"

    val toutes: List<String> = listOf(SAT, PHONIE_HF, CW, TOUT)

    /**
     * Band to request from the server, or `null` for everything. Only `SAT`
     * works: "HF" is a family of bands and the filter takes one.
     */
    fun bandeServeur(filtre: String): String? = if (filtre == SAT) "SAT" else null

    /** Is filtering done here rather than by the server? */
    fun triLocal(filtre: String): Boolean = filtre != SAT && filtre != TOUT

    private val BANDES_HF = setOf(
        "160m", "80m", "60m", "40m", "30m", "20m",
        "17m", "15m", "12m", "10m",
    )

    /**
     * Voice modes. The goal is to exclude digital (FT8, FT4…), so we list what
     * is voice and reject the rest: the digital list grows with every new mode
     * and an unknown one would slip through.
     */
    private val PHONIE = setOf("SSB", "USB", "LSB", "AM", "FM", "DIGITALVOICE")

    /**
     * Does this contact belong in the keypad memory?
     *
     * `propMode`, `mode` and `bande` are the raw ADIF fields. A missing field
     * is an empty string and must never make a contact pass by default: a
     * slightly short memory beats one full of what was meant to be excluded.
     */
    fun retient(filtre: String, propMode: String, mode: String, bande: String): Boolean {
        val sat = propMode.trim().equals("SAT", ignoreCase = true)
        val m = mode.trim().uppercase()
        val b = bande.trim().lowercase()
        return when (filtre) {
            SAT -> sat
            // HF voice excludes satellite, which has its own choice.
            PHONIE_HF -> !sat && b in BANDES_HF && m in PHONIE
            CW -> !sat && m == "CW"
            TOUT -> true
            else -> sat
        }
    }
}
