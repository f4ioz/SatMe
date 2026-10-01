/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * The rigs built from two single-band rigs, chosen side by side: what
 * receives (FT-817, IC-705 or the SDR dongle) and what transmits (FT-817 or
 * IC-705). Each combination keeps the rig model id it always had, so a choice
 * saved by an earlier version still reads the same.
 */
object Postes {

    const val FT817 = "FT817"
    const val IC705 = "IC705"
    /** Receive side only: the SDR dongle, the rig on TX alone. */
    const val SDR = "SDR"

    const val FT817_X2 = "FT817x2"
    const val FT817_TX = "FT817TX"            // FT-817 transmits, the dongle receives
    const val FT817_IC705 = "FT817_IC705"     // IC-705 receives, FT-817 transmits
    const val IC705_FT817 = "IC705_FT817"     // IC-705 transmits, FT-817 receives
    const val IC705_X2 = "IC705x2"
    const val IC705_TX = "IC705TX"            // IC-705 transmits, the dongle receives

    private val COMBINAISONS = mapOf(
        (FT817 to FT817) to FT817_X2,
        (IC705 to FT817) to FT817_IC705,
        (FT817 to IC705) to IC705_FT817,
        (IC705 to IC705) to IC705_X2,
        (SDR to FT817) to FT817_TX,
        (SDR to IC705) to IC705_TX,
    )
    private val COTES = COMBINAISONS.entries.associate { (k, v) -> v to k }

    /** Every rig model this choice covers. */
    val MODELES: Set<String> = COTES.keys

    /** The rig model for a receive side and a transmit side. */
    fun modele(rx: String, tx: String): String =
        COMBINAISONS[rx to (if (tx == SDR) FT817 else tx)] ?: FT817_X2

    /** Receive side of [modele] (FT817, IC705 or SDR), null if it is not one of these. */
    fun rx(modele: String): String? = COTES[modele]?.first
    /** Transmit side of [modele] (FT817 or IC705), null if it is not one of these. */
    fun tx(modele: String): String? = COTES[modele]?.second

    /** One rig on TX, the dongle receiving. */
    fun emetSeul(modele: String): Boolean = rx(modele) == SDR
    fun rxIc705(modele: String): Boolean = rx(modele) == IC705
    fun txIc705(modele: String): Boolean = tx(modele) == IC705
    fun avecIc705(modele: String): Boolean = rxIc705(modele) || txIc705(modele)
    fun avecFt817(modele: String): Boolean = rx(modele) == FT817 || tx(modele) == FT817

    /**
     * An FT-817 with an IC-705: the protocol tells the two cables apart. Two
     * rigs of the same make are told apart by their bands instead.
     */
    fun mixte(modele: String): Boolean = modele == FT817_IC705 || modele == IC705_FT817
}
