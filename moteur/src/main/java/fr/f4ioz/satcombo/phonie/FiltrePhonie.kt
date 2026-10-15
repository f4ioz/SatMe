/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import java.text.Normalizer

/**
 * What the recogniser says that was not said. On noise it writes the
 * sentences it saw most under silent video (« Merci d'avoir regardé »,
 * subtitle credits), loops on a short phrase, or answers with little
 * confidence: those segments are dropped.
 */
object FiltrePhonie {

    /** Above this, the engine thinks there was no speech. */
    const val SANS_PAROLE = 0.8
    /** Below this mean log-probability, a guess. */
    const val LOGPROB_MIN = -1.2
    /** Text that compresses this well is a loop. */
    const val COMPRESSION_MAX = 2.4

    /** Phrases that, anywhere in a segment, mean it was invented (normalised: lower case, no accents or punctuation). */
    val INVENTEES = listOf(
        "merci d avoir regarde", "merci de votre attention", "sous titres realises par", "sous titrage", "sous titres par",
        "abonnez vous", "n oubliez pas de vous abonner", "thank you for watching", "thanks for watching",
        "please subscribe", "like and subscribe", "subtitles by", "transcribed by", "amara org",
        "untertitel im auftrag", "untertitel von", "vielen dank furs zuschauen", "danke furs zuschauen",
        "subtitulos realizados por", "subtitulos por", "gracias por ver", "sottotitoli creati", "sottotitoli a cura",
        "grazie per la visione", "napisy stworzone przez", "napisy wykonane przez", "dziekuje za obejrzenie", " www ",
    )

    /** Whole segments that are nothing on their own on noise; kept only when the engine is sure. */
    val SEULES = setOf("merci", "merci beaucoup", "thank you", "thanks", "you", "bye", "bye bye", "au revoir", "danke",
        "gracias", "grazie", "dziekuje", "ok", "oui", "non", "hmm", "euh", "ah", "oh")

    /** The segments worth keeping, or an empty list. */
    fun garde(m: List<ProtocolePhonie.Morceau>): List<ProtocolePhonie.Morceau> = m.filter { garde(it) }

    fun garde(m: ProtocolePhonie.Morceau): Boolean {
        val n = normalise(m.texte)
        if (n.isBlank()) return false
        if (m.noSpeechProb > SANS_PAROLE) return false
        if (m.noSpeechProb > 0.6 && m.avgLogprob < -0.7) return false
        if (m.avgLogprob < LOGPROB_MIN) return false
        if (m.compression > COMPRESSION_MAX) return false
        if (inventee(n)) return false
        if (n in SEULES && (m.noSpeechProb > 0.2 || m.avgLogprob < -0.5)) return false
        if (boucle(n)) return false
        return true
    }

    /** One of the phrases seen under silence. */
    fun inventee(texte: String): Boolean {
        val n = " " + normalise(texte) + " "
        return INVENTEES.any { n.contains(if (it.startsWith(" ") || it.endsWith(" ")) it else " $it ") }
    }

    /** The whole segment one short phrase (1 to 4 words) said three times or more ("CQ CQ CQ" is ham talk). */
    fun boucle(texte: String): Boolean {
        val mots = normalise(texte).split(' ').filter { it.isNotEmpty() }
        if (mots.size < 3) return false
        for (l in 1..4) {
            if (mots.size % l != 0 || mots.size / l < 3) continue
            val p = mots.subList(0, l)
            if (p == listOf("cq")) continue
            if ((0 until mots.size / l).all { k -> mots.subList(k * l, k * l + l) == p }) return true
        }
        return false
    }

    /** Lower case, no accents, letters and digits only, single spaces. */
    fun normalise(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('ł', 'l').replace('ø', 'o').replace("ß", "ss")
        return d.replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
    }
}
