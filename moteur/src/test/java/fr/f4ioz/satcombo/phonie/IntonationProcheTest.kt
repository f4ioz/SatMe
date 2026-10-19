/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import fr.f4ioz.satcombo.phonie.HauteurVoixTest.Companion.mediane
import fr.f4ioz.satcombo.phonie.HauteurVoixTest.Companion.voisee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IntonationProcheTest {

    @Test fun `ajustement d une montee et d une descente`() {
        val t = (0..40).map { it / 40.0 }
        val monte = IntonationProche.ajuste(t, t.map { -2 + 4 * it })
        assertEquals(-2.0, monte.en(0.0), 0.3); assertEquals(2.0, monte.en(1.0), 0.3)
        // A rise at the end (a question): the curve holds it.
        val question = IntonationProche.ajuste(t, t.map { if (it < 0.7) 0.0 else (it - 0.7) * 15 })
        assertTrue(question.en(1.0) - question.en(0.5) > 2.0)
        assertTrue(abs(question.en(0.3) - question.en(0.0)) < 1.5)
        assertEquals(IntonationProche.Mouvement.PLAT.en(0.5), IntonationProche.ajuste(emptyList(), emptyList()).en(0.5), 0.0)
    }

    @Test fun `le contour de l original passe a la voix de synthese`() {
        // Heard: a man rising from 100 to 140 Hz in 1.5 s. Said: a flat voice at 200 Hz.
        val orig = voisee(16_000, 1.5) { t -> 100.0 + 40.0 * t / 1.5 }
        val a = IntonationProche.analyse(orig, 16_000, listOf(IntonationProche.Phrase(0.0, 1.5, "bonjour à tous les amis")))
        assertEquals(118.0, a.profil.f0Hz, 118 * 0.04)
        assertTrue("montée vue : ${a.mouvements[0]}", a.mouvements[0].en(1.0) - a.mouvements[0].en(0.0) > 4.0)
        val synth = voisee(22_050, 1.2) { 200.0 }
        val y = IntonationProche.applique(listOf(synth), 22_050, a)
        assertEquals(synth.size, y.size)
        val c = HauteurVoix.suit(y, 22_050)
        val debut = mediane((5 until 30).map { c.f0[it] }.filter { it > 0 })
        val fin = mediane((c.n - 30 until c.n - 5).map { c.f0[it] }.filter { it > 0 })
        // Brought down near the speaker (the mean bounded to 8 semitones: 200 Hz → ≥ 126 Hz), and rising.
        val moyen = HauteurVoix.profil(c).f0Hz
        assertTrue("moyenne $moyen", moyen in 115.0..135.0)
        val monteeDt = HauteurVoix.demiTons(fin) - HauteurVoix.demiTons(debut)
        // The heard rise is 5.8 semitones; 70 % of it, give or take the fit.
        assertTrue("montée $monteeDt demi-tons ($debut → $fin Hz)", monteeDt in 2.5..6.0)
    }

    @Test fun `profondeur nulle garde le contour de la synthese`() {
        val orig = voisee(16_000, 1.5) { t -> 100.0 + 40.0 * t / 1.5 }
        val a = IntonationProche.analyse(orig, 16_000, listOf(IntonationProche.Phrase(0.0, 1.5, "bonjour")))
        val synth = voisee(22_050, 1.2) { 120.0 }
        val c = HauteurVoix.suit(IntonationProche.applique(listOf(synth), 22_050, a, d = 0.0), 22_050)
        val debut = mediane((5 until 30).map { c.f0[it] }.filter { it > 0 })
        val fin = mediane((c.n - 30 until c.n - 5).map { c.f0[it] }.filter { it > 0 })
        assertTrue("$debut → $fin", abs(HauteurVoix.demiTons(fin) - HauteurVoix.demiTons(debut)) < 1.0)
    }

    @Test fun `phrases jointes avec les pauses entendues`() {
        val p = listOf(IntonationProche.Phrase(0.0, 1.0, "un"), IntonationProche.Phrase(1.4, 2.0, "deux"), IntonationProche.Phrase(5.0, 6.0, "trois"))
        val a = IntonationProche.analyse(FloatArray(16_000 * 6), 16_000, p)
        assertEquals(listOf(0.4, 0.6), a.pauses.map { Math.round(it * 10) / 10.0 })
        val y = IntonationProche.joint(listOf(FloatArray(100), FloatArray(100), FloatArray(100)), 1000, a.pauses)
        assertEquals(300 + 400 + 600, y.size)
    }

    @Test fun `syllabes et debit`() {
        assertEquals(3, IntonationProche.syllabes("bonjour à"))
        assertEquals(5, IntonationProche.syllabes("F4IOZ"))
        assertEquals(4, IntonationProche.syllabes("Здравствуйте друг"))
        val p = listOf(IntonationProche.Phrase(0.0, 2.0, "dzień dobry wszystkim kolegom"))
        val a = IntonationProche.analyse(FloatArray(32_000), 16_000, p)
        assertEquals(IntonationProche.syllabes("dzień dobry wszystkim kolegom") / 2.0, a.debit, 1e-9)
        // A fast Pole said in French by a voice doing 6 syllables a second at 1: shorter than 1.
        val vite = a.copy(debit = 8.5)
        assertTrue(IntonationProche.longueur(vite, "pl", "fr", 6.0) < 1.0)
        val lent = a.copy(debit = 3.0)
        assertTrue(IntonationProche.longueur(lent, "pl", "fr", 6.0) > 1.0)
        assertEquals(1.0, IntonationProche.longueur(a.copy(debit = 0.0), "pl", "fr", 6.0), 0.0)
        assertTrue(IntonationProche.longueur(a.copy(debit = 30.0), "pl", "fr", 6.0) >= 0.8)
    }
}
