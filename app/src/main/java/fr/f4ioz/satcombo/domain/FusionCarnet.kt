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
 * Merging two logs without losing anything (e.g. between two phones).
 *
 * **A merge never overwrites.** The log is the only data in the app that
 * cannot be rebuilt; an import that replaces turns a wrong file or wrong
 * direction into permanent loss, while one that adds can at worst add noise.
 *
 * - **Absent: add.**
 * - **Present but incomplete: fill in.** A field empty locally and set on the
 *   other side is filled, so a name typed on one phone shows on the other.
 * - **Present and different: keep local, and report it.** Nothing in an
 *   entry says which value was corrected last; the disagreement count lets
 *   the operator go and check.
 *
 * A contact's identity is **time to the second, callsign and satellite**.
 * Time alone is not enough: two phones can log different contacts at the
 * same instant.
 */
object FusionCarnet {

    /** What a merge needs from an entry. */
    data class Fiche(
        val timeMs: Long,
        val indicatif: String,
        val catnum: Int,
        /** Fillable fields by name. Absent or empty means a gap. */
        val champs: Map<String, String> = emptyMap(),
    )

    data class Bilan(
        val fondu: List<Fiche>,
        val ajoutes: Int,
        val completes: Int,
        val identiques: Int,
        val desaccords: Int,
    )

    /** Contact identity: time to the second, callsign, satellite. */
    private fun cle(f: Fiche): Triple<Long, String, Int> =
        Triple(f.timeMs / 1000L, f.indicatif.trim().uppercase(), f.catnum)

    fun fusionne(local: List<Fiche>, entrant: List<Fiche>): Bilan {
        val parCle = LinkedHashMap<Triple<Long, String, Int>, Fiche>()
        local.forEach { parCle[cle(it)] = it }

        var ajoutes = 0
        var completes = 0
        var identiques = 0
        var desaccords = 0

        entrant.forEach { neuf ->
            val k = cle(neuf)
            val ancien = parCle[k]
            if (ancien == null) {
                parCle[k] = neuf
                ajoutes++
                return@forEach
            }
            var aComplete = false
            var aDesaccord = false
            val champs = ancien.champs.toMutableMap()
            neuf.champs.forEach { (nom, valeur) ->
                if (valeur.isBlank()) return@forEach
                val actuel = champs[nom].orEmpty()
                when {
                    actuel.isBlank() -> { champs[nom] = valeur; aComplete = true }
                    actuel != valeur -> aDesaccord = true
                }
            }
            if (aComplete) parCle[k] = ancien.copy(champs = champs)
            when {
                aComplete -> completes++
                aDesaccord -> Unit
                else -> identiques++
            }
            if (aDesaccord) desaccords++
        }

        return Bilan(parCle.values.sortedByDescending { it.timeMs },
            ajoutes, completes, identiques, desaccords)
    }
}
