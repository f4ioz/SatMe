/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * Satellites sending pictures to amateurs, SSTV or SSDV — a source of their
 * own ([Sources.IMAGES]), as most are missing from AMSAT's bulletin.
 *
 * **SSDV** (Slow Scan Digital Video) cuts a JPEG into numbered packets
 * (256 bytes, each with its image and packet number and a checksum): a
 * missing packet costs a few blocks, not the picture, and pictures from
 * several stations add up. **SSTV** is the analogue picture by sound.
 *
 * The list comes from SatNOGS DB (its live transmitters marked SSDV or SSTV,
 * October 2026) and the Geoscan satellites, whose picture payload is
 * framed their own way. Their elements are asked one by one, by number: the
 * SatMe GP server's lookup (the freshest of its groups), SatNOGS DB's
 * otherwise. Their transmitters and frequencies come from SatNOGS DB as for
 * every satellite.
 */
object SatellitesImages {

    /** A picture satellite: its usual name and what it sends. */
    data class Sat(val catnum: Int, val nom: String, val images: String)

    val LISTE = listOf(
        // SSDV
        Sat(40931, "LAPAN-A2", "SSDV"),
        Sat(61781, "ASRTU-1", "SSDV"),
        Sat(68446, "HADES-SA", "SSDV"),
        Sat(64890, "Geoscan-2", "SSDV"),
        Sat(64892, "Geoscan-4", "SSDV"),
        Sat(64891, "Geoscan-5", "SSDV"),
        Sat(64881, "239Alferov", "SSDV"),
        // SSTV
        Sat(25544, "ISS", "SSTV"),
        Sat(48274, "CSS (Tianhe)", "SSTV"),
        Sat(32791, "SEEDS II", "SSTV"),
        Sat(49396, "TeikyoSat-4", "SSTV"),
        Sat(39435, "WREN", "SSTV"),
        Sat(57172, "UmKA-1", "SSTV"),
        Sat(57180, "Monitor-3", "SSTV"),
        Sat(57189, "VIZARD-meteo", "SSTV"),
        Sat(57203, "UTMN2", "SSTV"),
        Sat(57206, "AHMAT-1", "SSTV"),
        Sat(59112, "SONATE-2", "SSTV"),
        Sat(67279, "GALAPAGOS-UTE-SWSU", "SSTV"),
        Sat(67290, "SAKHACUBE-CHOLBON", "SSTV"),
        Sat(67291, "QMR-KWT 2", "SSTV"),
    )

    private val parNumero = LISTE.associateBy { it.catnum }

    fun de(catnum: Int): Sat? = parNumero[catnum]

    /** The source id of one satellite of the list: "images:64890". */
    fun id(catnum: Int) = Sources.IMAGES + ":" + catnum

    /** One source per satellite, its elements asked by number (SatNOGS DB as the direct address). */
    fun sources(): List<TleSource> = LISTE.map { TleSource(id(it.catnum), it.nom, Sources.satnogsNumero(it.catnum)) }

    /**
     * Its usual name for a satellite of the list whose elements came with
     * none (SatNOGS' "OBJECT Q" for Geoscan-2 before it was identified); a
     * real name, AMSAT's or CelesTrak's, is kept.
     */
    fun renomme(e: TleEntry): TleEntry {
        val s = parNumero[e.catalogNumber] ?: return e
        val n = e.name.trim()
        val brut = n.isEmpty() || n.startsWith("OBJECT ", ignoreCase = true) || n == e.catalogNumber.toString()
        return if (brut) e.copy(name = s.nom) else e
    }
}
