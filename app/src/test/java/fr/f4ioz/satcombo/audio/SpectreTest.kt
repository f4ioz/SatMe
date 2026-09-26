/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The audio monitor's spectrum analyser.
 *
 * A 1500 Hz tone must light the 1500 Hz bar, silence must leave all bars at
 * zero, and the peak must reflect the input. All three answer the operator's
 * question — "is audio arriving, and how loud" — and a scale or window error
 * breaks all of them.
 */
class SpectreTest {

    private fun sinus(a: AnalyseurSpectre, freq: Double, amplitude: Double,
                      trames: Int = 4) {
        val n = a.taille * trames
        val pcm = ShortArray(n) {
            (amplitude * 32767.0 * sin(2.0 * PI * freq * it / a.rate)).toInt().toShort()
        }
        a.pousser(pcm, n)
    }

    @Test
    fun une_tonalite_allume_la_bande_qui_lui_correspond() {
        val a = AnalyseurSpectre()
        sinus(a, 1_500.0, 0.5)
        val b = a.bandeLaPlusForte()
        assertTrue("aucune bande allumée", b >= 0)
        val centre = a.centreHz(b)
        assertTrue("bande la plus forte à $centre Hz au lieu de 1500",
            kotlin.math.abs(centre - 1_500.0) < 150.0)
    }

    @Test
    fun deux_tonalites_eloignees_ne_se_confondent_pas() {
        val basse = AnalyseurSpectre().also { sinus(it, 800.0, 0.5) }
        val haute = AnalyseurSpectre().also { sinus(it, 2_300.0, 0.5) }
        assertTrue("les deux tonalités tombent dans la même bande",
            basse.bandeLaPlusForte() < haute.bandeLaPlusForte())
    }

    @Test
    fun le_silence_laisse_toutes_les_bandes_a_zero() {
        val a = AnalyseurSpectre()
        a.pousser(ShortArray(a.taille * 2), a.taille * 2)
        assertTrue("une bande s'allume sur du silence : ${a.bandes.toList()}",
            a.bandes.all { it == 0f })
        assertEquals(0f, a.crete, 1e-6f)
    }

    @Test
    fun la_crete_suit_le_niveau_qui_entre() {
        val fort = AnalyseurSpectre().also { sinus(it, 1_000.0, 0.9) }
        val faible = AnalyseurSpectre().also { sinus(it, 1_000.0, 0.1) }
        assertTrue("crête forte ${fort.crete}", fort.crete > 0.8f)
        assertTrue("crête faible ${faible.crete}", faible.crete < 0.2f)
        assertTrue("la crête ne dépasse jamais un", fort.crete <= 1f)
    }

    @Test
    fun une_saturation_se_voit_a_la_crete() {
        // A signal pinned to the rails: the monitor must show this in magenta
        // to tell the operator to turn the radio volume down.
        val a = AnalyseurSpectre()
        val n = a.taille * 2
        val pcm = ShortArray(n) { if ((it / 8) % 2 == 0) 32_767 else -32_768 }
        a.pousser(pcm, n)
        assertTrue("saturation non détectée : ${a.crete}", a.crete >= 0.95f)
    }

    @Test
    fun les_trames_se_comptent_et_le_reste_est_garde() {
        // The service delivers blocks that do not match the FFT size: the
        // remainder must carry over, or part of the audio is never analysed.
        val a = AnalyseurSpectre()
        val bloc = ShortArray(300)
        repeat(10) { a.pousser(bloc, bloc.size) }
        // 3000 samples: two 1024-point FFTs, 952 samples kept for the next block.
        assertEquals(2L, a.trames)
        assertTrue(a.trames * a.taille <= 3_000)
    }

    @Test
    fun le_reset_remet_tout_a_plat() {
        val a = AnalyseurSpectre()
        sinus(a, 1_500.0, 0.7)
        a.reset()
        assertEquals(0L, a.trames)
        assertEquals(0f, a.crete, 1e-6f)
        assertTrue(a.bandes.all { it == 0f })
        assertEquals(-1, a.bandeLaPlusForte())
    }

    @Test
    fun les_bandes_restent_ordonnees_a_seize_kilohertz() {
        // Bluetooth audio captures at 16 kHz: band edges must stay strictly
        // increasing, or an empty band breaks the computation.
        val a = AnalyseurSpectre(rate = 16_000)
        var precedent = -1.0
        for (i in 0 until a.nbBandes) {
            val c = a.centreHz(i)
            assertTrue("bande $i à $c Hz après $precedent", c > precedent)
            precedent = c
        }
        assertTrue("dernière bande à $precedent Hz", precedent <= 3_600.0)
        sinus(a, 1_200.0, 0.5)
        assertTrue(kotlin.math.abs(a.centreHz(a.bandeLaPlusForte()) - 1_200.0) < 200.0)
    }

    @Test
    fun une_taille_qui_nest_pas_une_puissance_de_deux_est_refusee() {
        var leve = false
        try { AnalyseurSpectre(taille = 1000) } catch (e: IllegalArgumentException) { leve = true }
        assertTrue("taille 1000 acceptée", leve)
    }
}
