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
 * The terrestrial rigs (TellusMe, the SatMe ecosystem's HF station), apart
 * from [ModeleIcom]: that table lists the **satellite** rigs SatMe offers,
 * and an IC-7000 there would show up in the satellite rig choice.
 *
 * [bandes]: the amateur bands the rig covers, by their ADIF names, as its
 * band buttons show them. [recoitHz]: the receive ranges it accepts a
 * frequency in (a write outside them is not sent).
 */
enum class ModeleHf(
    val id: String,
    val libelle: String,
    /** Protocol: "civ" (Icom CI-V), "hpsdr" (openHPSDR Protocol 1 over the network). */
    val protocole: String,
    /** Default CI-V address (0 when not CI-V). */
    val adresse: Int,
    /** Default speed, then the others the rig offers (empty when not serial). */
    val vitesses: List<Int>,
    val bandes: List<String>,
    val recoitHz: List<LongRange>,
) {
    /**
     * Icom IC-7000: HF + 6 m, 2 m, 70 cm. CI-V address 70h (the instruction
     * manual's frame layout, « transceiver's default address »); CI-V Baud
     * Rate on « Auto » by default, so any of 300…19 200 works — 19 200 by
     * default here, 9 600 for an old interface. Receives 0.03–199.999 MHz and
     * 400–470 MHz.
     */
    IC7000("IC7000", "Icom IC-7000", "civ", 0x70, listOf(19_200, 9_600, 4_800, 1_200, 300),
        listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm"),
        listOf(30_000L..199_999_999L, 400_000_000L..470_000_000L)),

    /** Brick2: an openHPSDR Protocol 1 board of the Hermes class, one receiver, 1.8–60 MHz. */
    BRICK2("BRICK2", "Brick2 SDR (openHPSDR)", "hpsdr", 0, emptyList(),
        listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"),
        listOf(100_000L..60_000_000L));

    fun recoit(hz: Long): Boolean = recoitHz.any { hz in it }

    companion object {
        fun de(id: String?): ModeleHf? = entries.firstOrNull { it.id == id }
    }
}
