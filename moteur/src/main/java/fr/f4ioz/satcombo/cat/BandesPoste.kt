/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import java.util.Locale

/**
 * What each rig SatMe drives can be tuned to, from its maker's
 * specifications: a static table, nothing asked to the rig.
 *
 * A frequency outside these is not sent: the rig would refuse it (a bare
 * NAK on CI-V, `?;` on a Kenwood) or, worse, take it on a band it was never
 * meant to be on. The satellite drive also needs a band the rig's own
 * satellite mode works on ([Bande.satellite]): the TS-2000's HF and 6 m, the
 * IC-9100's HF, are covered by the rig but not by its satellite mode.
 *
 * Wide enough for receive (the rigs hear more than they transmit on), never
 * wider than the rig: the rig's own band edges still decide transmit.
 */
object BandesPoste {

    /** One stretch of a rig's coverage, in Hz. [option]: only with an extra module (23 cm on several). */
    data class Bande(val nom: String, val basHz: Long, val hautHz: Long, val satellite: Boolean = true, val option: Boolean = false) {
        operator fun contains(hz: Long) = hz in basHz..hautHz
    }

    private const val M = 1_000_000L

    private val HF_ICOM = Bande("HF et 6 m", 30_000L, 60 * M, satellite = false)
    private val V = Bande("2 m", 144 * M, 148 * M)
    private val U = Bande("70 cm", 430 * M, 450 * M)
    private val L = Bande("23 cm", 1_240 * M, 1_300 * M)
    private val L_OPTION = L.copy(option = true)

    /** Per model, as SatMe names them ([fr.f4ioz.satcombo.cat.ModeleIcom] ids, TS2000, THD72, FT817, IC705). */
    private val TABLE: Map<String, Pair<String, List<Bande>>> = mapOf(
        "IC9700" to ("IC-9700" to listOf(V, U, L)),
        "IC9100" to ("IC-9100" to listOf(HF_ICOM, V, U, L_OPTION)),
        "IC910" to ("IC-910H" to listOf(V, U, L_OPTION)),
        "TS2000" to ("TS-2000" to listOf(Bande("HF et 6 m", 30_000L, 60 * M, satellite = false),
            Bande("2 m", 142 * M, 152 * M), Bande("70 cm", 420 * M, 450 * M), L_OPTION)),
        "FT817" to ("FT-817" to listOf(Bande("HF et 6 m", 100_000L, 56 * M, satellite = false),
            Bande("2 m", 76 * M, 154 * M), Bande("70 cm", 420 * M, 470 * M))),
        "IC705" to ("IC-705" to listOf(Bande("HF à 2 m", 30_000L, 200 * M), Bande("70 cm", 400 * M, 470 * M))),
        "THD72" to ("TH-D72" to listOf(Bande("2 m", 136 * M, 174 * M), Bande("70 cm", 410 * M, 470 * M))),
    )

    /** The model's id as the table knows it; the simulated rigs are their models. */
    fun cle(modele: String): String = when (modele) {
        "SIM" -> "IC9700"
        "TS2000SIM" -> "TS2000"
        else -> modele
    }

    /** The model's bands, or null when it is not in the table (then nothing is refused here). */
    fun bandes(modele: String): List<Bande>? = TABLE[cle(modele)]?.second

    fun libelle(modele: String): String = TABLE[cle(modele)]?.first ?: modele

    /**
     * May [modele] be sent [hz] for satellite work? True for a model the
     * table does not know (nothing refused on a guess).
     */
    fun couvre(modele: String, hz: Long, satellite: Boolean = true): Boolean {
        val l = bandes(modele) ?: return true
        return l.any { hz in it && (!satellite || it.satellite) }
    }

    /** For a pair: the RX and TX rigs' models from the choice (an IC-705 or an FT-817 on each side). */
    fun modelePaire(ic705: Boolean): String = if (ic705) "IC705" else "FT817"

    /** « 2 m, 70 cm, 23 cm (option) »: what the model covers, for the operator. */
    fun resume(modele: String, satellite: Boolean = true): String =
        bandes(modele).orEmpty().filter { !satellite || it.satellite }
            .joinToString(", ") { it.nom + if (it.option) " (option)" else "" }

    /** Why [hz] is not sent to [modele], or null when it may be. */
    fun refus(modele: String, hz: Long, satellite: Boolean = true): String? {
        if (couvre(modele, hz, satellite)) return null
        val l = bandes(modele) ?: return null
        val f = String.format(Locale.FRANCE, "%,.3f MHz", hz / 1e6).replace(' ', ' ').replace(' ', ' ')
        val nom = libelle(modele)
        val horsSat = satellite && l.any { hz in it && !it.satellite }
        return if (horsSat) "$nom : $f est hors de son mode satellite (${resume(modele)}) — commande non envoyée"
        else "$nom : $f hors de ses bandes (${resume(modele)}) — commande non envoyée"
    }
}
