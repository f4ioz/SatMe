/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.pages

import fr.f4ioz.site.publication.Publication

/**
 * The SSTV wall's passes: the pictures SatMe-PC published, put back together
 * pass by pass — the same `passage` when SatMe named it, else the same
 * satellite with less than [ECART_MS] between two pictures (an ISS event
 * sends one every two minutes or so; two passes are ninety minutes apart).
 */
object MurSstv {

    const val ECART_MS = 25 * 60_000L

    /** One pass: its satellite, its first and last picture's time, its pictures (the newest first). */
    data class Passage(val satellite: String, val debutMs: Long, val finMs: Long, val images: List<Publication>)

    /** [l]'s pictures by pass, the newest pass first. */
    fun parPassage(l: List<Publication>, ecartMs: Long = ECART_MS): List<Passage> {
        val groupes = ArrayList<MutableList<Publication>>()
        for (p in l.sortedBy { it.quandMs }) {
            val g = groupes.lastOrNull()
            val d = g?.last()
            val meme = d != null && run {
                val a = d.texte("passage"); val b = p.texte("passage")
                if (a.isNotEmpty() && b.isNotEmpty()) a == b
                else d.satellite.equals(p.satellite, true) && p.quandMs - d.quandMs <= ecartMs
            }
            if (meme) g!!.add(p) else groupes.add(arrayListOf(p))
        }
        return groupes.asReversed().map { g -> Passage(g.first().satellite.ifEmpty { "Satellite inconnu" }, g.first().quandMs, g.last().quandMs, g.asReversed().toList()) }
    }
}
