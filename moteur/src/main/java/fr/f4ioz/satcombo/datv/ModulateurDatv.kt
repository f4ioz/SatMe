/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/** What a transmitter reads from a DATV modulator (DVB-S2 or DVB-S): I/Q samples, and its counts. */
interface ModulateurDatv {
    /** [paires] I/Q pairs into [dst] (2 shorts each), always all of them. */
    fun remplis(dst: ShortArray, paires: Int)
    /** Samples clipped at the DAC's full scale. */
    val ecretages: Long
    /** Transport stream packets taken from the source. */
    val paquets: Long
    /** The share of the air time not carrying the source's packets (dummy frames, null packets added), 0 to 1. */
    fun partVide(): Double
}

/** The transmission standard: DVB-S2 (EN 302 307-1) or DVB-S (EN 300 421). */
enum class Norme(val cle: String, val libelle: String) {
    DVBS2("S2", "DVB-S2"), DVBS("S", "DVB-S");

    companion object {
        fun de(s: String?): Norme? = entries.firstOrNull {
            val t = s?.trim().orEmpty()
            it.cle.equals(t, true) || it.libelle.equals(t, true) || it.name.equals(t, true)
        }
    }
}
