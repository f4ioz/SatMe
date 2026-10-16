/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

/** A transmission as the page sees it, whatever the transmitter. */
data class EtatEmission(
    val emet: Boolean = false,
    val debutMs: Long = 0L,
    val finMs: Long = 0L,
    val frequenceHz: Long = 0L,
    /** The gain applied (dB): the Pluto's attenuation (≤ 0), the HackRF's TX VGA (≥ 0). */
    val gainDb: Double = 0.0,
    val tampons: Long = 0L,
    val manques: Long = 0L,
    /** Why the last transmission ended. */
    val raison: String = "",
    val erreur: String = "",
)

/**
 * A DATV transmitter once started ([PlutoEmission], [HackrfEmission]): its
 * state, its stop, its gain. Each one starts with its own settings and
 * keeps its own guards.
 */
interface EmetteurDatv {
    /** Its name on the page. */
    val nom: String

    fun etatCommun(): EtatEmission

    /** Stops transmitting; [raison] kept in the state. */
    fun arrete(raison: String = "arrêt demandé")

    /** A new gain while transmitting; the gain applied, or null when it cannot change now. */
    fun regleGain(db: Double): Double?
}
