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
 * Which station profile an uploaded contact belongs to.
 *
 * **Wavelog files a contact under its station profile and ignores the file's
 * `MY_GRIDSQUARE`.** The costliest upload trap, and silent: an operator who
 * activates several grid squares but uploads to one profile sees every
 * portable outing reclassified under the home square. The profile square is
 * what counts for awards, so the loss only shows when claiming VUCC, months
 * later.
 */
object ProfilsStation {

    /** A station location as Wavelog declares it. */
    data class Profil(
        val id: String,
        val carre: String,
        val indicatif: String,
        val nom: String = "",
    )

    /**
     * Grid square truncated to the wanted precision. Four characters is the
     * VUCC unit ("JN06", not "JN06XJ"); six separates nearby locations.
     */
    fun carreCourt(carre: String, precision: Int = 4): String =
        carre.trim().uppercase().take(precision)

    /**
     * Grid squares covered by a profile. The field may hold **several,
     * comma-separated**: that is how Wavelog declares a station on a grid
     * line (its import writes such a field to `MY_VUCC_GRIDS`, not
     * `MY_GRIDSQUARE`).
     */
    fun carresDuProfil(p: Profil, precision: Int = 4): Set<String> =
        p.carre.split(",").mapNotNull { g ->
            carreCourt(g, precision).ifBlank { null }
        }.toSet()

    /**
     * The location a contact was made from, as a set of grid squares.
     *
     * On a grid line the operator is **in both squares at once** and each
     * contact counts for both, so these contacts cannot share a profile with
     * those made in one square only (that would grant or remove a double
     * claim wrongly). `{JN16}` and `{JN16, JN06}` are distinct locations;
     * order does not matter.
     */
    fun emplacement(monCarre: String, mesCarres: String, precision: Int = 4): Set<String> {
        val revendiques = mesCarres.split(",").mapNotNull { g ->
            carreCourt(g, precision).ifBlank { null }
        }
        return if (revendiques.size >= 2) revendiques.toSet()
        else setOf(carreCourt(monCarre, precision))
    }

    /** A set of grid squares, written for reading: "JN06,JN16". */
    fun nomEmplacement(cle: Set<String>): String =
        cle.filter { it.isNotBlank() }.sorted().joinToString(",")
            .ifBlank { "—" }

    /**
     * For each location, the profile covering it **exactly**. Set equality,
     * not membership: a "JN16,JN06" profile does not fit a contact made in
     * JN16 alone, it would give it a double claim.
     */
    fun apparieEmplacements(
        cles: List<Set<String>>,
        profils: List<Profil>,
        indicatif: String,
        precision: Int = 4,
    ): Map<Set<String>, String?> {
        val ind = indicatif.trim().uppercase()
        return cles.associateWith { cle ->
            profils.firstOrNull { p ->
                carresDuProfil(p, precision) == cle &&
                    (ind.isEmpty() || p.indicatif.trim().uppercase() == ind)
            }?.id
        }
    }

    /**
     * Profile to upload a contact to, grid lines included. Without a fetched
     * table, falls back to the single profile from settings.
     */
    fun profilPourEmplacement(
        monCarre: String,
        mesCarres: String,
        table: Map<Set<String>, String?>,
        defaut: String,
        precision: Int = 4,
    ): String = table[emplacement(monCarre, mesCarres, precision)] ?: defaut

    // The old single-square rules (`apparie`, `profilPour`, `sansProfil`)
    // were removed rather than kept alongside: two rules answering the same
    // question end up diverging, and callers pick the wrong one silently.
}
