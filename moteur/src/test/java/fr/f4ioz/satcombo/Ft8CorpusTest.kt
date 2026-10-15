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
    private fun mesure(s: Ft8Banc.Scenario, p: Ft8Decodeur.Profondeur? = PROFONDEUR): Mesure {
        val audio = Ft8Banc.audio(s)
        val t0 = System.nanoTime()
        val r = Ft8Decodeur.decode(audio, s.mode.cadenceHz, s.mode,
            penteHzS = if (s.penteConnue) s.penteCommuneHzS else 0.0, profondeur = p)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lus = r.map { it.message.brut }
        val attendus = s.attendus
        return Mesure(s.nom, lus.count { it in attendus }, attendus.size, lus.filter { it !in attendus }, ms)
    }

    /** The budget measured and held to the floors: SatMe-PC's. */
    private val PROFONDEUR = Ft8Decodeur.Profondeur.PROFONDE

    /**
     * Minimum decodes per scenario: the baseline, raised as the decoder improves.
     *
     * Lowered once, on purpose: "pente_connue_et_propre" lost a word read by
     * hard decisions with 11 of 21 sync symbols right, now refused (noise
     * candidates reach 10); see `Ft8Decodeur.synchroFranche`.
     */
    private val PLANCHERS = mapOf(
        "forts" to 10, "moyens" to 12, "faibles" to 9, "tres_faibles" to 3,
        "bande_chargee" to 22, "chevauchement" to 6, "fort_masque_faible" to 3,
        "doppler_connu_-1.0Hz/s" to 8, "doppler_connu_1.0Hz/s" to 8,
        "doppler_connu_-3.0Hz/s" to 8, "doppler_connu_3.0Hz/s" to 8,
        "doppler_inconnu_1Hz/s" to 0, "pentes_propres_2Hz/s" to 1, "pente_connue_et_propre" to 3,
        "indicatifs_composes" to 3, "ft4" to 8,
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
        // The other budgets, in short: what the phone and a busy PC get, and what it costs.
        for (p in listOf(Ft8Decodeur.Profondeur.LEGERE, Ft8Decodeur.Profondeur.NORMALE)) {
            val m = Ft8Banc.corpus().map { mesure(it, p) }
            texte.append("%-9s %d/%d, faux %d, %d ms (pire tranche %d ms)\n".format(p.name, m.sumOf { it.trouves },
                m.sumOf { it.attendus }, m.sumOf { it.faux.size }, m.sumOf { it.ms }, m.maxOf { it.ms }))
            assertTrue("$p : faux décodages", m.all { it.faux.isEmpty() })
        }
        texte.append("%-9s pire tranche %d ms\n".format(PROFONDEUR.name, mesures.maxOf { it.ms }))
        println(texte)

        for (m in mesures) {
            val plancher = PLANCHERS[m.nom] ?: 0
            assertTrue("${m.nom} : ${m.trouves} décodés, il en faut au moins $plancher", m.trouves >= plancher)
            assertTrue("${m.nom} : faux décodages ${m.faux}", m.faux.isEmpty())
        }
    }
}
