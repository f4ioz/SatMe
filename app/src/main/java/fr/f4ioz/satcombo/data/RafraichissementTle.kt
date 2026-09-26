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
 * Refreshing one satellite's elements from the sources the operator enabled.
 *
 * It used to query CelesTrak alone, whatever the settings: with the address
 * blocked by CelesTrak (it does that to addresses that fetch too often), the
 * refresh never succeeded and said nothing. Plain Kotlin, JVM-tested.
 */
object RafraichissementTle {

    sealed interface Resultat {
        data class Trouve(val entree: TleEntry) : Resultat
        /** Some source answered, none carried this satellite. */
        object Absent : Resultat
        /** No source answered at all. */
        object Injoignable : Resultat
    }

    private fun estCelestrak(s: TleSource) = s.url.contains("celestrak.org")

    /**
     * What to query for [catnum]. Non-CelesTrak sources through their
     * bulletin; CelesTrak, if any of its groups is enabled, through the
     * single-satellite query only — its group files are large, and fetching
     * them for one satellite is how an address gets blocked.
     */
    fun adresses(catnum: Int, sources: List<TleSource>): List<String> {
        val autres = sources.filterNot(::estCelestrak).map { it.url }
        val celestrak = if (sources.any(::estCelestrak))
            listOf("https://celestrak.org/NORAD/elements/gp.php?CATNR=$catnum&FORMAT=json")
        else emptyList()
        return autres + celestrak
    }

    /**
     * Queries every address and keeps the most recent epoch. A source that
     * fails does not stop the others.
     */
    fun plusRecent(catnum: Int, adresses: List<String>,
                   lire: (String) -> List<TleEntry>): Resultat {
        var repondu = false
        var meilleur: TleEntry? = null
        for (u in adresses) {
            val lus = runCatching { lire(u) }.getOrNull() ?: continue
            repondu = true
            val e = lus.firstOrNull { it.catalogNumber == catnum } ?: continue
            if (meilleur == null || (e.epochMs ?: 0L) > (meilleur.epochMs ?: 0L)) meilleur = e
        }
        return when {
            meilleur != null -> Resultat.Trouve(meilleur)
            repondu -> Resultat.Absent
            else -> Resultat.Injoignable
        }
    }
}
