/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.annonces

/**
 * The sentences waiting to be said: the same one never twice within
 * [MEME_MS], [MAX] at most waiting — a burst keeps its latest, the oldest
 * dropped (stale news is worse than none). [ESSAI] goes through regardless.
 */
class FileAnnonces(private val horloge: () -> Long = System::currentTimeMillis) {

    data class Annonce(val seq: Long, val ms: Long, val sorte: Annonces.Sorte, val texte: String)

    private val file = ArrayDeque<Annonce>()
    private val dites = HashMap<String, Long>()
    private var seq = 0L
    /** Dropped for being too many, refused for being said lately: for the page. */
    var debordees = 0; private set
    var repetees = 0; private set

    /** Queued, or null when said within [MEME_MS] already. */
    @Synchronized fun propose(sorte: Annonces.Sorte, texte: String): Annonce? {
        val t = horloge()
        dites.entries.removeAll { t - it.value >= MEME_MS }
        if (sorte != Annonces.Sorte.ESSAI && dites.containsKey(texte)) { repetees++; return null }
        dites[texte] = t
        val a = Annonce(++seq, t, sorte, texte)
        file.addLast(a)
        while (file.size > MAX) { file.removeFirst(); debordees++ }
        return a
    }

    @Synchronized fun prochaine(): Annonce? = file.removeFirstOrNull()

    @Synchronized fun taille() = file.size

    @Synchronized fun vide() = file.clear()

    companion object {
        const val MEME_MS = 30_000L
        const val MAX = 3
    }
}
