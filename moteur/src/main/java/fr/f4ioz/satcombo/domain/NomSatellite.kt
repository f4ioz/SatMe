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

    /**
     * The SAT_NAME values LoTW accepts, as its help page listed them in
     * October 2026 ("What satellites are supported by LoTW?"). A snapshot:
     * LoTW adds satellites as they launch, so a name missing here is only a
     * warning, never a reason to hold a contact back.
     */
    val LOTW: Set<String> = setOf(
        "AISAT1", "AO-3", "AO-4", "AO-6", "AO-7", "AO-8", "AO-10", "AO-13", "AO-16", "AO-21", "AO-24",
        "AO-27", "AO-40", "AO-51", "AO-73", "AO-85", "AO-91", "AO-92", "AO-109", "AO-123", "ARISS",
        "BO-102", "BY70-1", "CAS-3H", "CAS-4A", "CAS-4B", "DO-64", "EO-79", "EO-88", "ESEO", "FO-12",
        "FO-20", "FO-29", "FO-99", "FS-3", "HO-68", "HO-107", "HO-113", "IO-86", "IO-117", "INSPR7",
        "JO-97", "KEDR", "LEDSAT", "LO-19", "LO-78", "LO-87", "LO-90", "MAYA-3", "MAYA-4", "MIREX",
        "MO-112", "MO-122", "NO-44", "NO-83", "NO-84", "NO-103", "NO-104", "PO-101", "QO-100",
        "RS-1", "RS-2", "RS-5", "RS-6", "RS-7", "RS-8", "RS-10", "RS-11", "RS-12", "RS-13", "RS-15",
        "RS-44", "RS95S", "SAREX", "SONATE", "SO-35", "SO-41", "SO-50", "SO-121", "SO-124", "SO-125",
        "TAURUS", "TEV2-1", "TEV2-2", "TEV2-3", "TEV2-4", "TEV2-5", "TEV2-6", "TEV2-7", "TEV2-8", "TEV2-9",
        "TEVEL1", "TEVEL2", "TEVEL3", "TEVEL4", "TEVEL5", "TEVEL6", "TEVEL7", "TEVEL8", "TO-108",
        "UKUBE1", "UO-14", "UVSQ", "VO-52", "XW-2A", "XW-2B", "XW-2C", "XW-2D", "XW-2E", "XW-2F",
    )

    /** True when LoTW knows this SAT_NAME (as written, case aside). */
    fun connuDeLotw(nom: String): Boolean = nom.trim().uppercase() in LOTW

    /**
     * The name LoTW would expect instead, when SatMe can tell: the cleaned
     * designator if LoTW knows it, ARISS for the space station. Null otherwise.
     */
    fun suggestionLotw(nom: String): String? {
        // "AO-07" is LoTW's "AO-7": no leading zero in its designators.
        val p = propre(nom).uppercase().replace(Regex("-0+(?=\\d)"), "-")
        if (p == "ISS" || p.startsWith("ISS ")) return "ARISS"
        return p.takeIf { it in LOTW && it != nom.trim().uppercase() }
    }
}
