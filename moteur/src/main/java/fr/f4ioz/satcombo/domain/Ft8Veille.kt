/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * What the FT8 tab watches over while it listens, the same on the PC and
 * the phone: the clock (read from the stations' DT), what is wrong with the
 * reception, the lines that call me, the stations the operator waits for.
 * Receive only: nothing here ever sends.
 */
object Ft8Veille {

    /** A station on time starts sending half a second into its slot. */
    const val DEBUT_NOMINAL_S = 0.5
    /** The clock read from the last slots heard, at most this many. */
    const val TRANCHES_HORLOGE = 8
    /** Fewer decodes than this: no reading (one station off by itself is not our clock). */
    const val DECODES_HORLOGE = 5
    /** Older than this, the reading is no longer today's. */
    const val AGE_HORLOGE_MS = 5 * 60_000L

    /** Sound blocks stop for this long: no sound. */
    const val SANS_SON_MS = 3_000L
    /** About −120 dBFS: zeros, a muted input, not a quiet band. */
    const val SILENCE = 1e-6f
    /** That many slots with sound and nothing decoded: worth saying. */
    const val TRANCHES_SANS_DECODAGE = 40

    /**
     * Our clock against the stations': the median DT, less the nominal start,
     * [dispersionS] how much the stations disagree (median distance to it).
     * Positive: our slots start early, the clock is ahead.
     */
    data class Horloge(val decalageS: Double, val dispersionS: Double, val decodes: Int, val tranches: Int)

    /** From the decodes ([tranche] start, raw DT in s), newest or not. */
    fun horloge(decodes: List<Pair<Long, Double>>, maintenant: Long): Horloge? {
        val recents = decodes.filter { maintenant - it.first <= AGE_HORLOGE_MS }
        val tranches = recents.map { it.first }.distinct().sortedDescending().take(TRANCHES_HORLOGE).toHashSet()
        val dt = recents.filter { it.first in tranches }.map { it.second }
        if (dt.size < DECODES_HORLOGE) return null
        val m = mediane(dt)
        return Horloge(m - DEBUT_NOMINAL_S, mediane(dt.map { kotlin.math.abs(it - m) }), dt.size, tranches.size)
    }

    private fun mediane(l: List<Double>): Double {
        val s = l.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** One problem with the reception, said once: [code] son, silence or rien. */
    data class Diagnostic(val code: String, val titre: String, val detail: String)

    /**
     * What stops the decoding, the worst first, or null when all is well.
     * [sonDepuisMs] since the last sound block (null: not known here),
     * [niveau] its peak (0..1), [sansDecodage] slots heard with nothing decoded,
     * [panne] what the listener itself reported.
     */
    fun diagnostic(enMarche: Boolean, carte: Boolean, sonDepuisMs: Long?, niveau: Float, sansDecodage: Int,
                   panne: String = ""): Diagnostic? {
        if (!enMarche) return null
        if (panne.isNotBlank()) return Diagnostic("son", "pas de son", panne)
        if (!carte) return Diagnostic("son", "pas de son", "la carte son est arrêtée (onglet Son)")
        if (sonDepuisMs != null && sonDepuisMs > SANS_SON_MS)
            return Diagnostic("son", "pas de son", "plus rien n’arrive de la carte son depuis " + sonDepuisMs / 1000 + " s : débranchée, ou prise par un autre logiciel ?")
        if (niveau < SILENCE)
            return Diagnostic("silence", "silence numérique", "le son arrive mais ne vaut que des zéros (sous −120 dBFS) : entrée coupée ou volume à zéro dans le mélangeur")
        if (sansDecodage >= TRANCHES_SANS_DECODAGE)
            return Diagnostic("rien", "aucun décodage depuis $sansDecodage tranches",
                "du son, mais rien de lisible : la bonne fréquence, le mode (USB), le niveau, l’horloge à la seconde ?")
        return null
    }

    /**
     * Slots with nothing decoded, from the slots counted ([tranches]) and the
     * newest decode's slot ([derniere], null when none): where the listener
     * forgets its decodes when it starts again.
     */
    fun sansDecodage(tranches: Int, derniere: Long?, maintenant: Long, dureeMs: Long): Int {
        if (derniere == null) return tranches
        val depuis = ((maintenant - derniere) / dureeMs - 1).toInt().coerceAtLeast(0)
        return minOf(tranches, depuis)
    }

    // ------------------------------------------------------------ the lines

    private fun mots(texte: String) = texte.trim().split(Regex("\\s+")).map { it.removePrefix("<").removeSuffix(">").uppercase() }

    /** A callsign without what is around it ("EA8/F4IOZ/P" → F4IOZ, the longest part). */
    fun base(c: String): String = c.uppercase().split('/').maxByOrNull { it.length } ?: c.uppercase()

    /** The line names me ([moi], my callsign), and is not mine. */
    fun appelleMoi(texte: String, appelant: String?, appele: String?, moi: String): Boolean {
        if (moi.isBlank()) return false
        val m = base(moi)
        if (appelant != null && base(appelant) == m) return false
        if (appele != null && base(appele) == m) return true
        return mots(texte).any { it.length >= 3 && base(it) == m }
    }

    /** The callsigns typed ("f4abc, K1ABC  ea8/f4xyz"): upper case, once each. */
    fun guettees(texte: String): List<String> =
        texte.split(Regex("[\\s,;]+")).map { it.trim().uppercase() }.filter { it.length in 3..12 && it.all { c -> c.isLetterOrDigit() || c == '/' } }.distinct().take(50)

    /** [appelant] is one of [liste], whatever is around it. */
    fun guettee(appelant: String?, liste: List<String>): Boolean =
        appelant != null && liste.isNotEmpty() && base(appelant) in liste.map { base(it) }

    const val CLE_GUETTEES = "ft8_guettees"

    fun lit(s: Stockage): List<String> = guettees(s.getString(CLE_GUETTEES, "") ?: "")

    fun ecrit(s: Stockage, texte: String): List<String> {
        val l = guettees(texte)
        s.edit().putString(CLE_GUETTEES, l.joinToString(" ")).apply()
        return l
    }
}
