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
 * « Autre fréquence… »: a downlink (and optionally an uplink) typed by the
 * operator for the satellite followed, with the receiver mode wanted. It
 * joins that satellite's transmitter list as one more entry, Doppler
 * corrected like the others, kept per satellite ([SatConfigStore]).
 *
 * For a frequency SatNOGS DB lacks, or one it lists under a mode the rig
 * cannot be set from (« GENESIS FSK »): the mode here is the rig's
 * (USB, LSB, FM, CW, and USB-D / LSB-D / FM-D for the data modes).
 */
object EmetteurPerso {

    /** The entry's description: what the list shows and the choice kept per satellite. */
    const val DESCRIPTION = "Autre fréquence"
    const val TYPE = "Perso"

    /** The modes offered, as the rig takes them. */
    val MODES = listOf("USB", "USB-D", "LSB", "LSB-D", "FM", "FM-D", "CW")

    /** Plausible satellite frequencies: 20 MHz to 12 GHz. */
    private val PLAGE = 20_000_000L..12_000_000_000L

    data class Reglage(val descenteHz: Long, val monteeHz: Long?, val mode: String)

    /** [mode] cleaned to one of [MODES], or null. */
    fun mode(mode: String?): String? {
        val m = mode.orEmpty().trim().uppercase().replace(" DATA", "-D").replace("_", "-")
        return MODES.firstOrNull { it == m }
    }

    /** A frequency typed in MHz (« 436,875 », « 436.875 »), or in Hz, or null. */
    fun hz(texte: String?): Long? {
        val t = texte.orEmpty().trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull() ?: return null
        val h = if (v < 100_000.0) Math.round(v * 1e6) else Math.round(v)
        return h.takeIf { it in PLAGE }
    }

    /** The setting from what was typed; null (with why) when it is not one. */
    fun lis(descente: String?, montee: String?, mode: String?): Pair<Reglage?, String?> {
        val dl = hz(descente) ?: return null to "fréquence de descente invalide (en MHz, ex. 436,875)"
        val ul = if (montee.isNullOrBlank()) null else (hz(montee) ?: return null to "fréquence de montée invalide")
        val m = mode(mode) ?: return null to "mode inconnu (USB, USB-D, LSB, LSB-D, FM, FM-D, CW)"
        return Reglage(dl, ul, m) to null
    }

    /** The list entry for [r]. */
    fun emetteur(r: Reglage): Transmitter = Transmitter(
        description = DESCRIPTION, mode = r.mode, uplinkLowHz = r.monteeHz, uplinkHighHz = null,
        downlinkLowHz = r.descenteHz, downlinkHighHz = null, invert = false, alive = true, type = TYPE)

    fun estPerso(t: Transmitter?): Boolean = t != null && t.type == TYPE && t.description == DESCRIPTION

    /** [liste] with the satellite's own entry at its end (replacing an older one), when it has one. */
    fun ajoute(liste: List<Transmitter>, r: Reglage?): List<Transmitter> {
        val sans = liste.filterNot { estPerso(it) }
        return if (r == null) sans else sans + emetteur(r)
    }
}
