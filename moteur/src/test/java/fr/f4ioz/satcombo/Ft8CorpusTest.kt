/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Ft8Decodeur
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement corpus: every scenario of [Ft8Banc] decoded, the score
 * printed, and only the **floors** asserted — what the decoder already does.
 *
 * It never fails on an improvement, it fails on a regression. When the
 * decoder gets better, raise the floors here, never lower them to pass.
 *
 * False decodes (a message nobody sent) are held to the baseline, and to
 * zero on noise: a decoder that invents is worse than one that misses.
 */
class Ft8CorpusTest {

    data class Mesure(val nom: String, val trouves: Int, val attendus: Int, val faux: List<String>, val ms: Long)

    /** Decodes one scenario as the stations would: the slot's audio, what is known about it. */
    private fun mesure(s: Ft8Banc.Scenario): Mesure {
        val audio = Ft8Banc.audio(s)
        val t0 = System.nanoTime()
        val r = Ft8Decodeur.decode(audio, s.mode.cadenceHz, s.mode,
            penteHzS = if (s.penteConnue) s.penteCommuneHzS else 0.0)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lus = r.map { it.message.brut }
        val attendus = s.attendus
        return Mesure(s.nom, lus.count { it in attendus }, attendus.size, lus.filter { it !in attendus }, ms)
    }

    /** Minimum decodes per scenario: the baseline, raised as the decoder improves. */
    private val PLANCHERS = mapOf(
        "forts" to 10, "moyens" to 12, "faibles" to 7, "tres_faibles" to 0,
        "bande_chargee" to 19, "chevauchement" to 4, "fort_masque_faible" to 3,
        "doppler_connu_-1.0Hz/s" to 8, "doppler_connu_1.0Hz/s" to 7,
        "doppler_connu_-3.0Hz/s" to 8, "doppler_connu_3.0Hz/s" to 8,
        "doppler_inconnu_1Hz/s" to 0, "pentes_propres_2Hz/s" to 1, "pente_connue_et_propre" to 4,
        "indicatifs_composes" to 3, "ft4" to 4,
    )

    @Test
    fun le_corpus_ne_recule_pas() {
        // One run unmeasured: the JIT would otherwise be charged to the first scenario.
        Ft8Decodeur.decode(Ft8Banc.audio(Ft8Banc.corpus().first()), 12_800.0)
        val mesures = Ft8Banc.corpus().map { mesure(it) }
        val texte = StringBuilder("\nCorpus FT8 : scénario | décodés/attendus | faux | ms\n")
        for (m in mesures) texte.append("%-26s %3d/%-3d  faux %d  %5d ms%s\n".format(
            m.nom, m.trouves, m.attendus, m.faux.size, m.ms,
            if (m.faux.isEmpty()) "" else "  " + m.faux.joinToString(" | ")))
        texte.append("TOTAL %d/%d, faux %d, %d ms\n".format(mesures.sumOf { it.trouves },
            mesures.sumOf { it.attendus }, mesures.sumOf { it.faux.size }, mesures.sumOf { it.ms }))
        println(texte)

        for (m in mesures) {
            val plancher = PLANCHERS[m.nom] ?: 0
            assertTrue("${m.nom} : ${m.trouves} décodés, il en faut au moins $plancher", m.trouves >= plancher)
            assertTrue("${m.nom} : faux décodages ${m.faux}", m.faux.isEmpty())
        }
    }
}
