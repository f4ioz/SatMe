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
 * The AMSAT designator, from the raw name in an element set.
 *
 * A TLE sometimes names the satellite together with its rocket body:
 * "RS-44 & BREEZE-KM R/B". LoTW and Wavelog match on `SAT_NAME`, so a contact
 * logged under the long name never matches the other station's "RS-44". Both
 * forms turn up in real logs depending on the TLE source.
 *
 * The fix is never applied behind the operator's back — they may want the
 * name as is. It is offered.
 */
object NomSatellite {

    private val CONNUS = mapOf(
        "RS-44 & BREEZE-KM R/B" to "RS-44",
        "JAS-2 (FO-29)" to "FO-29",
        "FUJI-OSCAR 29" to "FO-29",
        "SAUDISAT 1C (SO-50)" to "SO-50",
        "ISS (ZARYA)" to "ISS",
        "QO-100 (ES'HAIL 2)" to "QO-100",
        "ES'HAIL 2" to "QO-100",
    )

    fun propre(nom: String): String {
        val brut = nom.trim()
        CONNUS[brut]?.let { return it }
        // "NAME (AO-XX)": the designator is in parentheses.
        if (brut.endsWith(")") && brut.contains("(")) {
            val dedans = brut.substringAfterLast("(").dropLast(1).trim()
            if (dedans.isNotEmpty()) return dedans
        }
        // "RS-44 & SOMETHING": what precedes the ampersand.
        if (brut.contains("&")) return brut.substringBefore("&").trim()
        return brut
    }

    /** True when this name would benefit from cleaning. */
    fun aNettoyer(nom: String): Boolean = propre(nom) != nom.trim()
}
